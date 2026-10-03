#!/usr/bin/env python3
"""Compare two app builds' startup on one phone, interleaved (TD-135).

A fixed-millisecond gate (``benchmark/gates.json``) cannot tell a regression
from a warm phone: on a Pixel 6 the same build's cold start moved 30-40 % in six
minutes. When a startup gate trips, run the reference build (A) and the
candidate (B) back to back in ABBA order, so drift hits both equally, and judge
B by its difference from A instead.

Run mode installs each ``app-benchmark.apk`` over the installed app (same debug
key, so the phone's data is kept), runs the startup tests with ``am instrument``
(never Gradle's connected task, which uninstalls the app and deletes its data),
and pulls each round's results into ``OUT/<n>-<A|B>/``. The APK installed before
the run is pulled first and reinstalled at the end, whatever happens. It stops,
without touching the phone further, if the phone is locked or another
instrumentation is running.

Analyse mode (``--analyse OUT``) reads rounds already pulled. For each test it
pools every run of A and of B, prints both medians, the difference and the
phone's state per round, and exits 1 if B's median is more than ``abMargin``
(``benchmark/gates.json``) over A's.

Usage:
  python scripts/startup_ab.py --a ref/app-benchmark.apk --b new/app-benchmark.apk \\
      --bench benchmark-benchmark.apk --out ab-run [--serial S] [--rounds 2] [--package ID]
  python scripts/startup_ab.py --analyse ab-run
"""

from __future__ import annotations

import argparse
import json
import re
import statistics
import subprocess
import sys
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
FALLBACK_APP = "com.sempermechanics.semper"
BENCH = "com.sempermechanics.semper.benchmark"
RUNNER = f"{BENCH}/androidx.test.runner.AndroidJUnitRunner"
MEDIA = f"/sdcard/Android/media/{BENCH}"
DEFAULT_TESTS = [
    f"{BENCH}.StartupBenchmark#coldStartup",
    f"{BENCH}.ScreenBenchmark#settingsColdStartup",
    f"{BENCH}.ScreenBenchmark#analysisWizardColdStartup",
]
METRIC = "timeToInitialDisplayMs"
WAKE_EVERY_S = 10
GATES = ROOT / "benchmark" / "gates.json"


def default_app_id(build_file: Path = ROOT / "app" / "build.gradle.kts") -> str:
    """The ``applicationId`` in ``app/build.gradle.kts``, the one ``:benchmark`` targets.

    Read from the build, not written here, so this script runs unchanged in
    material_testing, which has its own app id.
    """
    try:
        ids = re.findall(r'^\s*applicationId\s*=\s*"([^"]+)"', build_file.read_text(encoding="utf-8"), re.M)
    except OSError:
        ids = []
    return ids[0] if len(ids) == 1 else FALLBACK_APP


def abba(rounds: int) -> list[str]:
    """A B B A, repeated: each build goes first as often as it goes second."""
    return [build for _ in range(rounds) for build in ("A", "B", "B", "A")]


# --- analysis ---------------------------------------------------------------


def read_round(round_dir: Path) -> tuple[dict[str, list[float]], dict]:
    """The runs of each test in one round, and that round's device state."""
    runs: dict[str, list[float]] = {}
    for data_file in round_dir.glob("*benchmarkData.json"):
        data = json.loads(data_file.read_text(encoding="utf-8"))
        for bench in data.get("benchmarks", []):
            name = f"{bench.get('className', '?').rsplit('.', 1)[-1]}.{bench.get('name', '?')}"
            values = bench.get("metrics", {}).get(METRIC, {}).get("runs", [])
            runs.setdefault(name, []).extend(float(v) for v in values)
    state: dict = {}
    for state_file in round_dir.glob("*deviceState.json"):
        state.update(json.loads(state_file.read_text(encoding="utf-8")).get("tests", {}))
    return runs, state


def describe_state(test_state: dict | None) -> str:
    if not test_state:
        return "state not recorded"
    parts = []
    for phase in ("start", "end"):
        snap = test_state.get(phase) or {}
        parts.append(
            f"{phase} thermal {snap.get('thermalStatus', '?')}, "
            f"{snap.get('batteryTempC', '?')} C, {snap.get('plugged', '?')}"
        )
    return "; ".join(parts)


def analyse(out: Path, ab_margin: float) -> int:
    rounds = sorted(
        (d for d in out.iterdir() if d.is_dir() and d.name.split("-")[-1] in ("A", "B")),
        key=lambda d: int(d.name.split("-")[0]),
    )
    if not rounds:
        print(f"{out}: no rounds (expected folders like 1-A, 2-B)")
        return 2
    pooled: dict[str, dict[str, list[float]]] = {}
    for round_dir in rounds:
        build = round_dir.name.split("-")[-1]
        runs, state = read_round(round_dir)
        for test, values in sorted(runs.items()):
            pooled.setdefault(test, {"A": [], "B": []})[build].extend(values)
            median = statistics.median(values) if values else float("nan")
            print(f"ROUND {round_dir.name} {test}: median {median:.1f} ms of {len(values)} ({describe_state(state.get(test))})")
    failures = 0
    for test, by_build in sorted(pooled.items()):
        a, b = by_build["A"], by_build["B"]
        if not a or not b:
            print(f"AB {test}: needs runs of both builds (A {len(a)}, B {len(b)})")
            failures += 1
            continue
        median_a, median_b = statistics.median(a), statistics.median(b)
        change = median_b / median_a - 1
        verdict = "FAIL" if change > ab_margin else "ok"
        line = (
            f"{test} {METRIC}: A {median_a:.1f} ms (n={len(a)}), B {median_b:.1f} ms (n={len(b)}), "
            f"B {change:+.1%} (limit +{ab_margin:.0%})"
        )
        if verdict == "FAIL":
            failures += 1
            print(f"::error title=Startup A/B::{line}")
        else:
            print(f"AB ok {line}")
    print(f"{failures} A/B comparison(s) failed")
    return 1 if failures else 0


# --- running on a phone -------------------------------------------------------


class Adb:
    def __init__(self, serial: str | None):
        self.prefix = ["adb"] + (["-s", serial] if serial else [])

    def run(self, *args: str, check: bool = True) -> str:
        result = subprocess.run(self.prefix + list(args), capture_output=True, text=True, encoding="utf-8", errors="replace")
        if check and result.returncode != 0:
            raise RuntimeError(f"adb {' '.join(args)} failed: {result.stderr.strip() or result.stdout.strip()}")
        return result.stdout

    def shell(self, command: str, check: bool = True) -> str:
        return self.run("shell", command, check=check)


def refuse_if_unsafe(adb: Adb) -> None:
    """Stop before touching anything if the phone is locked or already busy."""
    if "deviceLocked=1" in adb.shell("dumpsys trust", check=False):
        raise RuntimeError("the phone is locked: unlock it by hand, then run again")
    if "instrument" in adb.shell("ps -A", check=False):
        raise RuntimeError("another instrumentation is running on the phone")


def backup_app(adb: Adb, out: Path, package: str) -> list[Path]:
    """Pull the installed app's APK(s) so the run can put them back."""
    paths = [line.split(":", 1)[1].strip() for line in adb.shell(f"pm path {package}", check=False).splitlines() if ":" in line]
    backup_dir = out / "backup"
    backup_dir.mkdir(parents=True, exist_ok=True)
    saved = []
    for index, device_path in enumerate(paths):
        local = backup_dir / f"{index}-{Path(device_path).name}"
        adb.run("pull", device_path, str(local))
        saved.append(local)
    return saved


def restore_app(adb: Adb, saved: list[Path]) -> None:
    if not saved:
        print("no app was installed before the run; leaving the last build installed")
        return
    verb = "install" if len(saved) == 1 else "install-multiple"
    adb.run(verb, "-r", "-t", *map(str, saved))
    print(f"restored the app installed before the run ({len(saved)} APK file(s))")


def keep_awake(adb: Adb, stop: threading.Event) -> None:
    """The phone's screen timeout can end a run: wake it every few seconds."""
    while not stop.wait(WAKE_EVERY_S):
        adb.shell("input keyevent KEYCODE_WAKEUP", check=False)


def run_round(adb: Adb, number: int, build: str, apk: Path, tests: list[str], out: Path) -> None:
    round_dir = out / f"{number}-{build}"
    round_dir.mkdir(parents=True, exist_ok=True)
    adb.run("install", "-r", "-t", str(apk))
    adb.shell(f"rm -rf {MEDIA}/*", check=False)
    print(f"round {number}: build {build}")
    output = adb.shell(
        f"am instrument -w -e class {','.join(tests)} "
        f"-e androidx.benchmark.suppressErrors EMULATOR,LOW-BATTERY,UNLOCKED {RUNNER}",
        check=False,
    )
    (round_dir / "instrument.txt").write_text(output, encoding="utf-8")
    adb.run("pull", f"{MEDIA}/.", str(round_dir), check=False)
    if "OK (" not in output:
        raise RuntimeError(f"round {number} ({build}) did not pass; see {round_dir / 'instrument.txt'}")


def run(args: argparse.Namespace) -> None:
    adb = Adb(args.serial)
    refuse_if_unsafe(adb)
    out: Path = args.out
    out.mkdir(parents=True, exist_ok=True)
    saved = backup_app(adb, out, args.package)
    stop = threading.Event()
    waker = threading.Thread(target=keep_awake, args=(adb, stop), daemon=True)
    try:
        adb.run("install", "-r", "-t", str(args.bench))
        waker.start()
        for number, build in enumerate(abba(args.rounds), start=1):
            run_round(adb, number, build, args.a if build == "A" else args.b, args.tests, out)
    finally:
        stop.set()
        restore_app(adb, saved)
        if not args.keep_bench:
            adb.run("uninstall", BENCH, check=False)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--analyse", type=Path, metavar="OUT", help="analyse rounds already pulled into OUT")
    parser.add_argument("--a", type=Path, help="reference build: app-benchmark.apk")
    parser.add_argument("--b", type=Path, help="candidate build: app-benchmark.apk")
    parser.add_argument("--bench", type=Path, help="benchmark-benchmark.apk (the :benchmark module)")
    parser.add_argument("--out", type=Path, help="where to pull each round")
    parser.add_argument("--serial", help="adb serial, if more than one device is attached")
    parser.add_argument(
        "--package",
        default=default_app_id(),
        help="app id to back up and restore (default: app/build.gradle.kts's applicationId, %(default)s)",
    )
    parser.add_argument("--rounds", type=int, default=2, help="ABBA blocks to run (default 2: A B B A A B B A)")
    parser.add_argument("--tests", nargs="+", default=DEFAULT_TESTS, help="tests as Class#method")
    parser.add_argument("--keep-bench", action="store_true", help="leave the benchmark APK installed")
    parser.add_argument("--gates", type=Path, default=GATES, help="gates JSON with abMargin")
    args = parser.parse_args(argv[1:])
    ab_margin = float(json.loads(args.gates.read_text(encoding="utf-8")).get("abMargin", 0.10))
    if args.analyse:
        return analyse(args.analyse, ab_margin)
    missing = [flag for flag in ("a", "b", "bench", "out") if getattr(args, flag) is None]
    if missing:
        parser.error("run mode needs " + ", ".join(f"--{flag}" for flag in missing))
    try:
        run(args)
    except RuntimeError as error:
        print(f"::error title=Startup A/B::{error}")
        return 2
    return analyse(args.out, ab_margin)


if __name__ == "__main__":
    sys.exit(main(sys.argv))
