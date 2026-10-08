"""`scripts/micro_ab.py`: the interleaved A/B hot-path microbenchmark gate (TD-199).

Analysis is held on small microbenchmark JSON files laid out as pulled rounds
(`1-A/`, `2-B/`, ...). Run mode is driven through a fake `subprocess.run`, so
nothing here touches a device.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "micro_ab.py"
_GATES = _SCRIPT.parents[1] / "benchmark" / "gates.json"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_PREFIX = "DEBUGGABLE_EMULATOR_ACTIVITY-MISSING_NOT-AOT-COMPILED_"


@pytest.fixture(scope="module")
def ab():
    spec = importlib.util.spec_from_file_location("micro_ab", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def _bench(name: str, times_ms: list[float], allocs: int = 10) -> dict:
    """One benchmark as androidx.benchmark writes it: warning-prefixed name, ns runs."""
    return {
        "name": _PREFIX + name,
        "className": "com.sempermechanics.semper.benchmark.HotPathMicroBenchmark",
        "metrics": {
            "timeNs": {"runs": [t * 1e6 for t in times_ms]},
            "allocationCount": {"runs": [allocs] * len(times_ms)},
        },
    }


def _round(out: Path, label: str, *benches: dict) -> Path:
    round_dir = out / label / "com.sempermechanics.semper"
    _write_json(round_dir / "com.sempermechanics.semper-benchmarkData.json", {"benchmarks": list(benches)})
    return round_dir.parent


# --- small helpers --------------------------------------------------------------


def test_abba_order(ab):
    assert ab.abba(1) == ["A", "B", "B", "A"]
    assert ab.abba(2) == ["A", "B", "B", "A", "A", "B", "B", "A"]


@pytest.mark.parametrize(("name", "expected"), [
    (_PREFIX + "gifEncode_150frames", "gifEncode_150frames"),
    ("EMULATOR_valueRanges_oneFrame", "valueRanges_oneFrame"),
    ("decodeDatFile_oneFrame", "decodeDatFile_oneFrame"),
])
def test_bench_name_drops_the_warning_prefix(ab, name, expected):
    assert ab.bench_name({"name": name}) == expected


def test_default_app_id(ab, tmp_path):
    assert ab.default_app_id() == "com.sempermechanics.semper"
    assert ab.default_app_id(tmp_path / "absent.kts") == ab.FALLBACK_APP


def test_repo_gates_hold_the_micro_margin(ab):
    gates = json.loads(_GATES.read_text(encoding="utf-8"))
    assert 0 < gates["microAbMargin"] < gates["margin"]


# --- reading a round --------------------------------------------------------------


def test_read_round_pools_every_file_by_test_and_metric(ab, tmp_path):
    round_dir = _round(tmp_path, "1-A", _bench("gifEncode_10frames", [5.0, 6.0], allocs=30))
    _write_json(round_dir / "com.sempermechanics.semper.test" / "x-benchmarkData.json",
                {"benchmarks": [_bench("gifEncode_10frames", [7.0]), {"name": "noMetrics"}]})
    found = ab.read_round(round_dir)
    assert sorted(found["gifEncode_10frames"]["timeNs"]) == [5e6, 6e6, 7e6]
    assert found["gifEncode_10frames"]["allocationCount"] == [30, 30, 10]
    assert found["noMetrics"] == {}


# --- analyse --------------------------------------------------------------------


def test_analyse_passes_within_the_margin(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("valueRanges_oneFrame", [5.0, 5.2]))
    _round(tmp_path, "2-B", _bench("valueRanges_oneFrame", [5.3]))
    _round(tmp_path, "3-B", _bench("valueRanges_oneFrame", [5.5]))
    _round(tmp_path, "4-A", _bench("valueRanges_oneFrame", [5.1]))
    (tmp_path / "logs").mkdir()  # not a round

    assert ab.analyse(tmp_path, 0.15) == 0
    out = capsys.readouterr().out
    assert "ROUND 1-A valueRanges_oneFrame: median 5.100 ms of 2" in out
    assert ("AB ok valueRanges_oneFrame timeNs: A 5.100 ms (n=3), B 5.400 ms (n=2), "
            "B +5.9% (limit +15%); allocations A 10, B 10") in out
    assert out.rstrip().endswith("0 A/B comparison(s) over the limit")


def test_analyse_fails_a_slowdown_with_a_ci_annotation(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("gifEncode_150frames", [60.0]), _bench("decodeDatFile_oneFrame", [0.4]))
    _round(tmp_path, "2-B", _bench("gifEncode_150frames", [75.0], allocs=500), _bench("decodeDatFile_oneFrame", [0.38]))

    assert ab.analyse(tmp_path, 0.15) == 1
    out = capsys.readouterr().out
    assert ("::error title=Microbenchmark A/B::gifEncode_150frames timeNs: A 60.000 ms (n=1), "
            "B 75.000 ms (n=1), B +25.0% (limit +15%); allocations A 10, B 500") in out
    assert "AB ok decodeDatFile_oneFrame" in out and "B -5.0%" in out
    assert "1 A/B comparison(s) over the limit" in out


def test_analyse_advisory_warns_and_passes(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("gifEncode_150frames", [60.0]))
    _round(tmp_path, "2-B", _bench("gifEncode_150frames", [75.0]))
    assert ab.analyse(tmp_path, 0.15, advisory=True) == 0
    out = capsys.readouterr().out
    assert "::warning title=Microbenchmark A/B::gifEncode_150frames" in out
    assert "1 A/B comparison(s) over the limit (advisory: perf-accepted)" in out


def test_analyse_exactly_at_the_margin_passes(ab, tmp_path):
    _round(tmp_path, "1-A", _bench("valueRanges_oneFrame", [100.0]))
    _round(tmp_path, "2-B", _bench("valueRanges_oneFrame", [115.0]))
    assert ab.analyse(tmp_path, 0.15) == 0


def test_analyse_reports_a_benchmark_only_one_build_has(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("valueRanges_oneFrame", [5.0]), _bench("oldOne", [1.0]))
    _round(tmp_path, "2-B", _bench("valueRanges_oneFrame", [5.0]), _bench("newOne", [9.0]))
    assert ab.analyse(tmp_path, 0.15) == 0
    out = capsys.readouterr().out
    assert "AB newOne: only in B (new), not compared" in out
    assert "AB oldOne: only in A (removed), not compared" in out


def test_analyse_orders_rounds_numerically(ab, tmp_path, capsys):
    for label in ("1-A", "2-B", "10-B", "9-A"):
        _round(tmp_path, label, _bench("valueRanges_oneFrame", [5.0]))
    ab.analyse(tmp_path, 0.15)
    order = [line.split()[1] for line in capsys.readouterr().out.splitlines() if line.startswith("ROUND")]
    assert order == ["1-A", "2-B", "9-A", "10-B"]


def test_analyse_without_rounds_exits_2(ab, tmp_path, capsys):
    (tmp_path / "1-C").mkdir()
    assert ab.analyse(tmp_path, 0.15) == 2
    assert "no rounds" in capsys.readouterr().out


# --- main -----------------------------------------------------------------------


def test_main_analyse_uses_the_gates_margin(ab, tmp_path, capsys):
    out = tmp_path / "ab"
    _round(out, "1-A", _bench("valueRanges_oneFrame", [100.0]))
    _round(out, "2-B", _bench("valueRanges_oneFrame", [125.0]))
    gates = tmp_path / "gates.json"
    _write_json(gates, {"microAbMargin": 0.5})
    assert ab.main(["micro_ab.py", "--analyse", str(out), "--gates", str(gates)]) == 0
    assert "(limit +50%)" in capsys.readouterr().out
    _write_json(gates, {"microAbMargin": 0.1})
    assert ab.main(["micro_ab.py", "--analyse", str(out), "--gates", str(gates)]) == 1
    assert ab.main(["micro_ab.py", "--analyse", str(out), "--gates", str(gates), "--advisory"]) == 0


def test_main_run_mode_requires_every_path(ab, capsys):
    with pytest.raises(SystemExit) as exit_info:
        ab.main(["micro_ab.py", "--a", "base"])
    assert exit_info.value.code == 2
    assert "run mode needs --b, --out" in capsys.readouterr().err


# --- running on a device, faked -------------------------------------------------


class FakeDevice:
    """Stands in for `subprocess.run`: records adb calls and serves pulled results."""

    def __init__(self, instrument: str = "OK (10 tests)\n", times: dict[str, float] | None = None):
        self.calls: list[list[str]] = []
        self.instrument = instrument
        self.times = times or {"A": 100.0, "B": 104.0}
        self.installed = ""

    def __call__(self, cmd, **kwargs):
        self.calls.append(cmd)
        args = cmd[cmd.index("adb") + 1:]
        if args[:2] == ["-s", "S"]:
            args = args[2:]
        stdout = ""
        if args[0] == "install":
            self.installed = args[-1]
        elif args[0] == "shell" and "am instrument" in args[1]:
            stdout = self.instrument
        elif args[0] == "pull" and args[1].endswith(".test/."):
            # The test process writes into the app's media folder; the .test one is empty.
            Path(args[2]).mkdir(parents=True, exist_ok=True)
        elif args[0] == "pull":
            build = Path(self.installed).parent.name
            _write_json(Path(args[2]) / "x-benchmarkData.json",
                        {"benchmarks": [_bench("valueRanges_oneFrame", [self.times[build]])]})
        return subprocess.CompletedProcess(cmd, 0, stdout=stdout, stderr="")


def _apks(root: Path) -> tuple[Path, Path]:
    dirs = []
    for build in ("A", "B"):
        folder = root / build
        folder.mkdir(parents=True)
        for apk in ("app-debug.apk", "app-debug-androidTest.apk"):
            (folder / apk).write_bytes(b"apk")
        dirs.append(folder)
    return dirs[0], dirs[1]


def test_adb_raises_on_failure_unless_unchecked(ab, monkeypatch):
    monkeypatch.setattr(ab.subprocess, "run", lambda cmd, **k: subprocess.CompletedProcess(cmd, 1, "out", "err"))
    with pytest.raises(RuntimeError, match="adb shell x failed: err"):
        ab.adb(["adb"], "shell", "x")
    assert ab.adb(["adb"], "shell", "x", check=False) == "out"


def test_main_run_mode_installs_abba_and_gates_the_result(ab, tmp_path, monkeypatch, capsys):
    a, b = _apks(tmp_path / "apks")
    device = FakeDevice()
    monkeypatch.setattr(ab.subprocess, "run", device)
    out = tmp_path / "ab"
    code = ab.main(["micro_ab.py", "--a", str(a), "--b", str(b), "--out", str(out),
                    "--serial", "S", "--package", "org.example"])
    assert code == 0
    installs = [Path(c[-1]) for c in device.calls if "install" in c]
    assert [p.parent.name for p in installs] == ["A", "A", "B", "B", "B", "B", "A", "A"]
    assert [p.name for p in installs[:2]] == ["app-debug.apk", "app-debug-androidTest.apk"]
    instrument = next(c[-1] for c in device.calls if "am instrument" in c[-1])
    assert instrument == (
        "am instrument -w -e class com.sempermechanics.semper.benchmark.HotPathMicroBenchmark "
        "-e androidx.benchmark.suppressErrors " + ab.SUPPRESS + " "
        "-e androidx.benchmark.profiling.mode none "
        "org.example.test/androidx.test.runner.AndroidJUnitRunner"
    )
    assert all(c[:3] == ["adb", "-s", "S"] for c in device.calls)
    assert sorted(p.name for p in out.iterdir()) == ["1-A", "2-B", "3-B", "4-A"]
    assert (out / "2-B" / "instrument.txt").read_text(encoding="utf-8") == "OK (10 tests)\n"
    assert "B +4.0%" in capsys.readouterr().out


def test_main_run_mode_fails_a_slow_candidate(ab, tmp_path, monkeypatch):
    a, b = _apks(tmp_path / "apks")
    monkeypatch.setattr(ab.subprocess, "run", FakeDevice(times={"A": 100.0, "B": 130.0}))
    assert ab.main(["micro_ab.py", "--a", str(a), "--b", str(b), "--out", str(tmp_path / "ab")]) == 1


def test_main_run_mode_stops_on_a_failing_round(ab, tmp_path, monkeypatch, capsys):
    a, b = _apks(tmp_path / "apks")
    monkeypatch.setattr(ab.subprocess, "run", FakeDevice(instrument="FAILURES!!!\nTests run: 10,  Failures: 1\n"))
    out = tmp_path / "ab"
    assert ab.main(["micro_ab.py", "--a", str(a), "--b", str(b), "--out", str(out)]) == 2
    assert "::error title=Microbenchmark A/B::round 1 (A) did not pass" in capsys.readouterr().out
    assert sorted(p.name for p in out.iterdir()) == ["1-A"]


def test_main_run_mode_needs_both_apks_in_each_folder(ab, tmp_path, monkeypatch, capsys):
    a, b = _apks(tmp_path / "apks")
    (b / "app-debug-androidTest.apk").unlink()
    device = FakeDevice()
    monkeypatch.setattr(ab.subprocess, "run", device)
    assert ab.main(["micro_ab.py", "--a", str(a), "--b", str(b), "--out", str(tmp_path / "ab")]) == 2
    assert f"{b} is missing app-debug-androidTest.apk" in capsys.readouterr().out
    assert device.calls == []
