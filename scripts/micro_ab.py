#!/usr/bin/env python3
"""Compare two builds' hot-path microbenchmarks on one device, interleaved (TD-199).

A fixed-nanosecond gate cannot hold on CI: the emulator is not the Pixel 6 the
real-device references were taken on (``benchmark/gates.json``), and its speed
moves from runner to runner. So the CI benchmark job builds the PR's base (A)
and the PR (B), runs ``HotPathMicroBenchmark`` from each on the same emulator in
A B B A order, so drift hits both equally, and judges B by its difference from A.
A hot path that gets slower fails the job; how fast the emulator is does not
matter.

Run mode uninstalls the app and its test package, installs one build's app and
androidTest APKs, runs the benchmark class with ``am instrument`` and pulls each
round's ``*-benchmarkData.json`` into ``OUT/<n>-<A|B>/``. Method tracing is off
(``profiling.mode none``): it costs seconds a test and no number comes from it.

Analyse mode (``--analyse OUT``) reads rounds already pulled. For each benchmark
it pools every ``timeNs`` run of A and of B, prints both medians, the difference
and both allocation counts, and exits 1 if B's median is more than
``microAbMargin`` (``benchmark/gates.json``) over A's. A benchmark only one build
has (added or removed by the PR) is reported, not gated. ``--advisory`` reports
the same and exits 0: CI passes it for a PR labelled ``perf-accepted``, a
slowdown its author and reviewer have agreed to.

Usage:
  python scripts/micro_ab.py --a base-apks/ --b pr-apks/ --out micro-ab [--serial S] [--rounds 1] [--package ID]
  python scripts/micro_ab.py --analyse micro-ab [--advisory]
Each APK folder holds app-debug.apk and app-debug-androidTest.apk.
"""

from __future__ import annotations

import argparse
import json
import math
import re
import statistics
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FALLBACK_APP = "com.sempermechanics.semper"
BENCHMARK_CLASS = "com.sempermechanics.semper.benchmark.HotPathMicroBenchmark"
APKS = ("app-debug.apk", "app-debug-androidTest.apk")
# A debug build on an emulator: every warning androidx.benchmark raises there.
SUPPRESS = "EMULATOR,DEBUGGABLE,LOW-BATTERY,UNLOCKED,ACTIVITY-MISSING,NOT-AOT-COMPILED"
METRIC = "timeNs"
GATES = ROOT / "benchmark" / "gates.json"
# androidx.benchmark prefixes a test's name with the warnings it ran under:
# DEBUGGABLE_EMULATOR_ACTIVITY-MISSING_NOT-AOT-COMPILED_gifEncode_150frames.
_WARNING_PREFIX = re.compile(r"^(?:[A-Z][A-Z0-9-]*_)+")


def default_app_id(build_file: Path = ROOT / "app" / "build.gradle.kts") -> str:
    """The ``applicationId`` in ``app/build.gradle.kts`` (material_testing has its own)."""
    try:
        ids = re.findall(r'^\s*applicationId\s*=\s*"([^"]+)"', build_file.read_text(encoding="utf-8"), re.M)
    except OSError:
        ids = []
    return ids[0] if len(ids) == 1 else FALLBACK_APP


def abba(rounds: int) -> list[str]:
    """A B B A, repeated: each build goes first as often as it goes second."""
    return [build for _ in range(rounds) for build in ("A", "B", "B", "A")]


def bench_name(bench: dict) -> str:
    return _WARNING_PREFIX.sub("", str(bench.get("name", "?")))


# --- analysis ---------------------------------------------------------------


def read_round(round_dir: Path) -> dict[str, dict[str, list[float]]]:
    """Each benchmark's runs in one round: ``{test: {metric: [values]}}``."""
    found: dict[str, dict[str, list[float]]] = {}
    for data_file in sorted(round_dir.rglob("*benchmarkData.json")):
        data = json.loads(data_file.read_text(encoding="utf-8"))
        for bench in data.get("benchmarks", []):
            metrics = found.setdefault(bench_name(bench), {})
            for metric, values in bench.get("metrics", {}).items():
                metrics.setdefault(metric, []).extend(float(v) for v in values.get("runs", []))
    return found


def _median(values: list[float]) -> float:
    return statistics.median(values) if values else float("nan")


def analyse(out: Path, margin: float, advisory: bool = False) -> int:
    rounds = sorted(
        (d for d in out.iterdir() if d.is_dir() and re.fullmatch(r"\d+-[AB]", d.name)),
        key=lambda d: int(d.name.split("-")[0]),
    )
    if not rounds:
        print(f"{out}: no rounds (expected folders like 1-A, 2-B)")
        return 2
    pooled: dict[str, dict[str, dict[str, list[float]]]] = {}
    for round_dir in rounds:
        build = round_dir.name[-1]
        for test, metrics in sorted(read_round(round_dir).items()):
            by_build = pooled.setdefault(test, {"A": {}, "B": {}})
            for metric, values in metrics.items():
                by_build[build].setdefault(metric, []).extend(values)
            times = metrics.get(METRIC, [])
            print(f"ROUND {round_dir.name} {test}: median {_median(times) / 1e6:.3f} ms of {len(times)}")
    failures = 0
    for test, by_build in sorted(pooled.items()):
        a, b = by_build["A"].get(METRIC, []), by_build["B"].get(METRIC, [])
        if not a or not b:
            print(f"AB {test}: only in {'B (new)' if b else 'A (removed)'}, not compared")
            continue
        median_a, median_b = _median(a), _median(b)
        change = median_b / median_a - 1
        # Only more than the margin fails. 115 / 100 - 1 is 0.15000000000000013
        # in floating point, so a B exactly at the limit needs the tolerance.
        over = change > margin and not math.isclose(change, margin, abs_tol=1e-9)
        allocs = (
            f"; allocations A {_median(by_build['A'].get('allocationCount', [])):.0f}"
            f", B {_median(by_build['B'].get('allocationCount', [])):.0f}"
        )
        line = (
            f"{test} {METRIC}: A {median_a / 1e6:.3f} ms (n={len(a)}), B {median_b / 1e6:.3f} ms (n={len(b)}), "
            f"B {change:+.1%} (limit +{margin:.0%}){allocs}"
        )
        if over:
            failures += 1
            print(f"::{'warning' if advisory else 'error'} title=Microbenchmark A/B::{line}")
        else:
            print(f"AB ok {line}")
    print(f"{failures} A/B comparison(s) over the limit" + (" (advisory: perf-accepted)" if advisory else ""))
    return 1 if failures and not advisory else 0


# --- running on a device ------------------------------------------------------


def adb(prefix: list[str], *args: str, check: bool = True) -> str:
    result = subprocess.run(prefix + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace")
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
    return result.stdout


def run_round(prefix: list[str], number: int, build: str, apk_dir: Path, package: str, out: Path) -> None:
    round_dir = out / f"{number}-{build}"
    round_dir.mkdir(parents=True, exist_ok=True)
    # Uninstall first: a build signed with another debug key (CI's smoke run
    # installs through Gradle) refuses to install over this one, and every round
    # starts from the same clean install.
    for installed in (f"{package}.test", package):
        adb(prefix, "uninstall", installed, check=False)
    for apk in APKS:
        adb(prefix, "install", "-r", "-t", str(apk_dir / apk))
    media = [f"/sdcard/Android/media/{package}", f"/sdcard/Android/media/{package}.test"]
    adb(prefix, "shell", " ".join(f"rm -rf {m}/*;" for m in media), check=False)
    print(f"round {number}: build {build}", flush=True)
    output = adb(
        prefix, "shell",
        f"am instrument -w -e class {BENCHMARK_CLASS} "
        f"-e androidx.benchmark.suppressErrors {SUPPRESS} "
        f"-e androidx.benchmark.profiling.mode none "
        f"{package}.test/androidx.test.runner.AndroidJUnitRunner",
        check=False,
    )
    (round_dir / "instrument.txt").write_text(output, encoding="utf-8")
    for m in media:
        adb(prefix, "pull", f"{m}/.", str(round_dir), check=False)
    # `am instrument` exits 0 when a test fails: its summary line is the verdict.
    if not re.search(r"^OK \(", output, re.M):
        raise RuntimeError(f"round {number} ({build}) did not pass; see {round_dir / 'instrument.txt'}")


def run(args: argparse.Namespace) -> None:
    prefix = ["adb"] + (["-s", args.serial] if args.serial else [])
    for build_dir in (args.a, args.b):
        missing = [apk for apk in APKS if not (build_dir / apk).is_file()]
        if missing:
            raise RuntimeError(f"{build_dir} is missing {', '.join(missing)}")
    args.out.mkdir(parents=True, exist_ok=True)
    for number, build in enumerate(abba(args.rounds), start=1):
        run_round(prefix, number, build, args.a if build == "A" else args.b, args.package, args.out)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--analyse", type=Path, metavar="OUT", help="analyse rounds already pulled into OUT")
    parser.add_argument("--a", type=Path, help="reference build: folder with both APKs")
    parser.add_argument("--b", type=Path, help="candidate build: folder with both APKs")
    parser.add_argument("--out", type=Path, help="where to pull each round")
    parser.add_argument("--serial", help="adb serial, if more than one device is attached")
    parser.add_argument("--package", default=default_app_id(), help="app id (default: %(default)s)")
    parser.add_argument("--rounds", type=int, default=1, help="ABBA blocks to run (default 1: A B B A)")
    parser.add_argument("--advisory", action="store_true", help="report a slowdown, but exit 0")
    parser.add_argument("--gates", type=Path, default=GATES, help="gates JSON with microAbMargin")
    args = parser.parse_args(argv[1:])
    margin = float(json.loads(args.gates.read_text(encoding="utf-8"))["microAbMargin"])
    if args.analyse:
        return analyse(args.analyse, margin, args.advisory)
    missing = [flag for flag in ("a", "b", "out") if getattr(args, flag) is None]
    if missing:
        parser.error("run mode needs " + ", ".join(f"--{flag}" for flag in missing))
    try:
        run(args)
    except RuntimeError as error:
        print(f"::error title=Microbenchmark A/B::{error}")
        return 2
    return analyse(args.out, margin, args.advisory)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
