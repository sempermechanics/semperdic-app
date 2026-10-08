"""`scripts/startup_ab.py`: the interleaved A/B startup comparison (TD-135).

Analysis is held on small macrobenchmark JSON files laid out as pulled rounds
(`1-A/`, `2-B/`, ...). Run mode is driven through a fake `adb` (and, for the
`Adb` wrapper itself, a fake `subprocess.run`), so nothing here touches a
device.
"""
from __future__ import annotations

import argparse
import importlib.util
import json
import subprocess
import threading
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "startup_ab.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def ab():
    spec = importlib.util.spec_from_file_location("startup_ab", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write_json(path: Path, data) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data), encoding="utf-8")


def _bench(cls: str, name: str, runs: list[float], metric: str = "timeToInitialDisplayMs") -> dict:
    return {"className": f"com.sempermechanics.semper.benchmark.{cls}", "name": name,
            "metrics": {metric: {"runs": runs}}}


def _round(out: Path, label: str, *benches: dict, state: dict | None = None) -> Path:
    round_dir = out / label
    _write_json(round_dir / "com.x-benchmarkData.json", {"benchmarks": list(benches)})
    if state is not None:
        _write_json(round_dir / "com.x-deviceState.json", {"tests": state})
    return round_dir


# --- small helpers --------------------------------------------------------------


def test_abba_order(ab):
    assert ab.abba(1) == ["A", "B", "B", "A"]
    assert ab.abba(2) == ["A", "B", "B", "A", "A", "B", "B", "A"]
    assert ab.abba(0) == []


def test_default_app_id_reads_the_single_application_id(ab, tmp_path):
    build = tmp_path / "build.gradle.kts"
    build.write_text('android {\n    defaultConfig {\n        applicationId = "org.example.app"\n', encoding="utf-8")
    assert ab.default_app_id(build) == "org.example.app"


def test_default_app_id_falls_back_when_missing_or_ambiguous(ab, tmp_path):
    assert ab.default_app_id(tmp_path / "absent.kts") == ab.FALLBACK_APP
    build = tmp_path / "build.gradle.kts"
    build.write_text('applicationId = "a.one"\napplicationId = "a.two"\n', encoding="utf-8")
    assert ab.default_app_id(build) == ab.FALLBACK_APP
    build.write_text('// applicationId = "commented.out"\n', encoding="utf-8")
    assert ab.default_app_id(build) == ab.FALLBACK_APP


def test_default_app_id_of_this_checkout(ab):
    assert ab.default_app_id() == "com.sempermechanics.semper"


def test_describe_state(ab):
    assert ab.describe_state(None) == "state not recorded"
    assert ab.describe_state({}) == "state not recorded"
    state = {"start": {"thermalStatus": 0, "batteryTempC": 31.5, "plugged": "AC"},
             "end": {"thermalStatus": 2}}
    assert ab.describe_state(state) == "start thermal 0, 31.5 C, AC; end thermal 2, ? C, ?"


# --- reading a round --------------------------------------------------------------


def test_read_round_pools_files_and_keeps_only_the_startup_metric(ab, tmp_path):
    round_dir = tmp_path / "1-A"
    _write_json(round_dir / "a-benchmarkData.json", {"benchmarks": [
        _bench("StartupBenchmark", "coldStartup", [300, 310]),
        _bench("ScreenBenchmark", "settingsScroll", [8.0], metric="frameDurationCpuMs"),
        {"name": "noClass", "metrics": {}},
    ]})
    _write_json(round_dir / "b-benchmarkData.json", {"benchmarks": [
        _bench("StartupBenchmark", "coldStartup", [320]),
    ]})
    _write_json(round_dir / "a-deviceState.json", {"tests": {"StartupBenchmark.coldStartup": {"start": {}}}})
    _write_json(round_dir / "b-deviceState.json", {"tests": {"Other.t": {"end": {}}}})
    (round_dir / "instrument.txt").write_text("OK (3 tests)", encoding="utf-8")

    runs, state = ab.read_round(round_dir)
    assert sorted(runs["StartupBenchmark.coldStartup"]) == [300.0, 310.0, 320.0]
    assert runs["ScreenBenchmark.settingsScroll"] == []  # other metric: no startup runs
    assert runs["?.noClass"] == []
    assert set(state) == {"StartupBenchmark.coldStartup", "Other.t"}


def test_read_round_of_an_empty_folder(ab, tmp_path):
    assert ab.read_round(tmp_path) == ({}, {})


# --- analyse --------------------------------------------------------------------


def test_analyse_passes_within_the_margin(ab, tmp_path, capsys):
    cold = ("StartupBenchmark", "coldStartup")
    _round(tmp_path, "1-A", _bench(*cold, [100, 102, 98]),
           state={"StartupBenchmark.coldStartup": {"start": {"thermalStatus": 0}, "end": {}}})
    _round(tmp_path, "2-B", _bench(*cold, [104, 106]))
    _round(tmp_path, "3-B", _bench(*cold, [105]))
    _round(tmp_path, "4-A", _bench(*cold, [101]))
    (tmp_path / "backup").mkdir()  # run mode's APK backup is not a round

    assert ab.analyse(tmp_path, 0.10) == 0
    out = capsys.readouterr().out
    assert "ROUND 1-A StartupBenchmark.coldStartup: median 100.0 ms of 3 (start thermal 0, ? C, ?; " in out
    assert "ROUND 2-B StartupBenchmark.coldStartup: median 105.0 ms of 2 (state not recorded)" in out
    # A pooled: 98 100 101 102 -> 100.5; B pooled: 104 105 106 -> 105.
    assert ("AB ok StartupBenchmark.coldStartup timeToInitialDisplayMs: A 100.5 ms (n=4), "
            "B 105.0 ms (n=3), B +4.5% (limit +10%)") in out
    assert out.rstrip().endswith("0 A/B comparison(s) failed")


def test_analyse_fails_a_regression_with_a_ci_annotation(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("StartupBenchmark", "coldStartup", [100]),
           _bench("ScreenBenchmark", "settingsColdStartup", [200]))
    _round(tmp_path, "2-B", _bench("StartupBenchmark", "coldStartup", [125]),
           _bench("ScreenBenchmark", "settingsColdStartup", [190]))

    assert ab.analyse(tmp_path, 0.10) == 1
    out = capsys.readouterr().out
    assert ("::error title=Startup A/B::StartupBenchmark.coldStartup timeToInitialDisplayMs: "
            "A 100.0 ms (n=1), B 125.0 ms (n=1), B +25.0% (limit +10%)") in out
    assert "AB ok ScreenBenchmark.settingsColdStartup" in out and "B -5.0%" in out
    assert "1 A/B comparison(s) failed" in out


def test_analyse_margin_is_the_callers(ab, tmp_path):
    _round(tmp_path, "1-A", _bench("StartupBenchmark", "coldStartup", [100]))
    _round(tmp_path, "2-B", _bench("StartupBenchmark", "coldStartup", [125]))
    assert ab.analyse(tmp_path, 0.30) == 0


def test_analyse_needs_runs_of_both_builds(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("StartupBenchmark", "coldStartup", [100]))
    _round(tmp_path, "2-B", _bench("StartupBenchmark", "coldStartup", []))
    assert ab.analyse(tmp_path, 0.10) == 1
    out = capsys.readouterr().out
    assert "ROUND 2-B StartupBenchmark.coldStartup: median nan ms of 0" in out
    assert "AB StartupBenchmark.coldStartup: needs runs of both builds (A 1, B 0)" in out


def test_analyse_orders_rounds_numerically(ab, tmp_path, capsys):
    for number in (1, 2, 10, 9):
        _round(tmp_path, f"{number}-{'A' if number % 2 else 'B'}",
               _bench("StartupBenchmark", "coldStartup", [100]))
    ab.analyse(tmp_path, 0.10)
    order = [line.split()[1] for line in capsys.readouterr().out.splitlines() if line.startswith("ROUND")]
    assert order == ["1-A", "2-B", "9-A", "10-B"]


def test_analyse_without_rounds_exits_2(ab, tmp_path, capsys):
    (tmp_path / "backup").mkdir()
    assert ab.analyse(tmp_path, 0.10) == 2
    assert "no rounds" in capsys.readouterr().out


def test_analyse_exactly_at_the_margin_passes(ab, tmp_path):
    _round(tmp_path, "1-A", _bench("StartupBenchmark", "coldStartup", [100]))
    _round(tmp_path, "2-B", _bench("StartupBenchmark", "coldStartup", [110]))
    assert ab.analyse(tmp_path, 0.10) == 0


# --- main -----------------------------------------------------------------------


def test_main_analyse_uses_the_gates_margin(ab, tmp_path, capsys):
    out = tmp_path / "ab"
    _round(out, "1-A", _bench("StartupBenchmark", "coldStartup", [100]))
    _round(out, "2-B", _bench("StartupBenchmark", "coldStartup", [125]))
    gates = tmp_path / "gates.json"
    _write_json(gates, {"abMargin": 0.5})
    assert ab.main(["startup_ab.py", "--analyse", str(out), "--gates", str(gates)]) == 0
    assert "(limit +50%)" in capsys.readouterr().out
    _write_json(gates, {})  # abMargin absent: 10 %
    assert ab.main(["startup_ab.py", "--analyse", str(out), "--gates", str(gates)]) == 1


def test_main_analyse_with_the_repo_gates(ab, tmp_path, capsys):
    _round(tmp_path, "1-A", _bench("StartupBenchmark", "coldStartup", [100]))
    _round(tmp_path, "2-B", _bench("StartupBenchmark", "coldStartup", [100]))
    assert ab.main(["startup_ab.py", "--analyse", str(tmp_path)]) == 0
    assert "(limit +10%)" in capsys.readouterr().out


def test_main_run_mode_requires_every_path(ab, capsys):
    with pytest.raises(SystemExit) as exit_info:
        ab.main(["startup_ab.py", "--a", "a.apk"])
    assert exit_info.value.code == 2
    assert "run mode needs --b, --bench, --out" in capsys.readouterr().err


# --- running on a phone, faked --------------------------------------------------


class FakeAdb:
    """Records every call; answers shell commands from a table of substrings."""

    def __init__(self, answers: dict[str, str] | None = None, fail_on: str | None = None):
        self.calls: list[tuple] = []
        self.answers = answers or {}
        self.fail_on = fail_on

    def run(self, *args: str, check: bool = True) -> str:
        self.calls.append(args)
        joined = " ".join(args)
        if self.fail_on and self.fail_on in joined:
            raise RuntimeError(f"adb {joined} failed: boom")
        if args[0] == "pull" and "Android/media" in args[1]:
            # The round's results land in the folder it was pulled to.
            dest = Path(args[2])
            build = dest.name.split("-")[-1]
            value = 100.0 if build == "A" else 104.0
            _write_json(dest / "x-benchmarkData.json",
                        {"benchmarks": [_bench("StartupBenchmark", "coldStartup", [value])]})
        if args[0] == "pull":
            Path(args[2]).parent.mkdir(parents=True, exist_ok=True)
        for key, answer in self.answers.items():
            if key in joined:
                return answer
        return ""

    def shell(self, command: str, check: bool = True) -> str:
        return self.run("shell", command, check=check)


def test_adb_wrapper_builds_the_command_and_raises_on_failure(ab, monkeypatch):
    seen = []

    def fake_run(cmd, **kwargs):
        seen.append(cmd)
        code = 1 if cmd[-1] == "bad" else 0
        return subprocess.CompletedProcess(cmd, code, stdout="out\n", stderr="err text" if code else "")

    monkeypatch.setattr(ab.subprocess, "run", fake_run)
    adb = ab.Adb("SERIAL1")
    assert adb.shell("echo hi") == "out\n"
    assert seen[-1] == ["adb", "-s", "SERIAL1", "shell", "echo hi"]
    assert ab.Adb(None).run("devices") == "out\n"
    assert seen[-1] == ["adb", "devices"]
    with pytest.raises(RuntimeError, match="adb shell bad failed: err text"):
        adb.shell("bad")
    assert adb.shell("bad", check=False) == "out\n"


@pytest.mark.parametrize(
    ("answers", "message"),
    [
        ({"dumpsys trust": "deviceLocked=1\n"}, "locked"),
        ({"ps -A": "u0 123 com.x instrument\n"}, "another instrumentation"),
    ],
)
def test_refuse_if_unsafe(ab, answers, message):
    with pytest.raises(RuntimeError, match=message):
        ab.refuse_if_unsafe(FakeAdb(answers))


def test_refuse_if_unsafe_lets_an_idle_unlocked_phone_through(ab):
    adb = FakeAdb({"dumpsys trust": "deviceLocked=0\n", "ps -A": "system_server\n"})
    ab.refuse_if_unsafe(adb)
    assert [c[1] for c in adb.calls] == ["dumpsys trust", "ps -A"]


def test_backup_and_restore_split_apks(ab, tmp_path):
    adb = FakeAdb({"pm path": "package:/data/app/x/base.apk\npackage:/data/app/x/split_a.apk\nnoise\n"})
    saved = ab.backup_app(adb, tmp_path, "org.example")
    assert saved == [tmp_path / "backup" / "0-base.apk", tmp_path / "backup" / "1-split_a.apk"]
    assert ("pull", "/data/app/x/base.apk", str(saved[0])) in adb.calls
    ab.restore_app(adb, saved)
    assert adb.calls[-1] == ("install-multiple", "-r", "-t", *map(str, saved))
    ab.restore_app(adb, saved[:1])
    assert adb.calls[-1] == ("install", "-r", "-t", str(saved[0]))


def test_restore_with_nothing_backed_up_installs_nothing(ab, capsys):
    adb = FakeAdb()
    ab.restore_app(adb, [])
    assert adb.calls == []
    assert "no app was installed before the run" in capsys.readouterr().out


def test_keep_awake_wakes_until_stopped(ab, monkeypatch):
    monkeypatch.setattr(ab, "WAKE_EVERY_S", 0)
    stop = threading.Event()

    class Waking(FakeAdb):
        def shell(self, command, check=True):
            super().shell(command, check)
            if len(self.calls) == 3:
                stop.set()
            return ""

    adb = Waking()
    ab.keep_awake(adb, stop)
    assert adb.calls == [("shell", "input keyevent KEYCODE_WAKEUP")] * 3


def test_run_round_saves_output_and_raises_when_tests_fail(ab, tmp_path):
    adb = FakeAdb({"am instrument": "OK (3 tests)\n"})
    ab.run_round(adb, 1, "A", Path("a.apk"), ["T#a", "T#b"], tmp_path)
    assert (tmp_path / "1-A" / "instrument.txt").read_text(encoding="utf-8") == "OK (3 tests)\n"
    instrument = next(c[1] for c in adb.calls if c[0] == "shell" and "am instrument" in c[1])
    assert instrument.startswith("am instrument -w -e class T#a,T#b ")
    assert instrument.endswith(ab.RUNNER)
    assert adb.calls[0] == ("install", "-r", "-t", "a.apk")

    failing = FakeAdb({"am instrument": "FAILURES!!!\n"})
    with pytest.raises(RuntimeError, match="round 2 \\(B\\) did not pass"):
        ab.run_round(failing, 2, "B", Path("b.apk"), ["T#a"], tmp_path)
    assert (tmp_path / "2-B" / "instrument.txt").read_text(encoding="utf-8") == "FAILURES!!!\n"


def _args(tmp_path: Path, **overrides) -> argparse.Namespace:
    values = dict(serial=None, out=tmp_path / "ab", package="org.example", a=Path("a.apk"), b=Path("b.apk"),
                  bench=Path("bench.apk"), rounds=1, tests=["T#a"], keep_bench=False)
    values.update(overrides)
    return argparse.Namespace(**values)


def _installs(adb: FakeAdb) -> list[str]:
    return [c[-1] for c in adb.calls if c[0] == "install"]


def test_run_installs_abba_then_restores_and_uninstalls_the_bench(ab, tmp_path, monkeypatch):
    adb = FakeAdb({"pm path": "package:/data/app/x/base.apk\n", "am instrument": "OK (1 test)\n"})
    monkeypatch.setattr(ab, "Adb", lambda serial: adb)
    ab.run(_args(tmp_path))
    backup = str(tmp_path / "ab" / "backup" / "0-base.apk")
    assert _installs(adb) == ["bench.apk", "a.apk", "b.apk", "b.apk", "a.apk", backup]
    assert adb.calls[-1] == ("uninstall", ab.BENCH)
    assert sorted(p.name for p in (tmp_path / "ab").iterdir()) == ["1-A", "2-B", "3-B", "4-A", "backup"]


def test_run_restores_the_app_even_when_a_round_fails(ab, tmp_path, monkeypatch):
    adb = FakeAdb({"pm path": "package:/data/app/x/base.apk\n", "am instrument": "FAILURES!!!\n"})
    monkeypatch.setattr(ab, "Adb", lambda serial: adb)
    with pytest.raises(RuntimeError, match="round 1"):
        ab.run(_args(tmp_path, keep_bench=True))
    assert _installs(adb)[-1] == str(tmp_path / "ab" / "backup" / "0-base.apk")
    assert not any(c[0] == "uninstall" for c in adb.calls)


def test_run_touches_nothing_on_a_locked_phone(ab, tmp_path, monkeypatch):
    adb = FakeAdb({"dumpsys trust": "deviceLocked=1"})
    monkeypatch.setattr(ab, "Adb", lambda serial: adb)
    with pytest.raises(RuntimeError, match="locked"):
        ab.run(_args(tmp_path))
    assert [c[0] for c in adb.calls] == ["shell"]
    assert not (tmp_path / "ab").exists()


def test_main_run_mode_end_to_end_then_analyses(ab, tmp_path, monkeypatch, capsys):
    adb = FakeAdb({"am instrument": "OK (1 test)\n"})
    monkeypatch.setattr(ab, "Adb", lambda serial: adb)
    out = tmp_path / "ab"
    code = ab.main(["startup_ab.py", "--a", "a.apk", "--b", "b.apk", "--bench", "bench.apk",
                    "--out", str(out), "--rounds", "1", "--serial", "S"])
    assert code == 0
    printed = capsys.readouterr().out
    assert "no app was installed before the run" in printed
    assert "AB ok StartupBenchmark.coldStartup" in printed and "B +4.0%" in printed


def test_main_run_mode_reports_a_device_error_as_exit_2(ab, tmp_path, monkeypatch, capsys):
    adb = FakeAdb(fail_on="bench.apk")
    monkeypatch.setattr(ab, "Adb", lambda serial: adb)
    code = ab.main(["startup_ab.py", "--a", "a.apk", "--b", "b.apk", "--bench", "bench.apk",
                    "--out", str(tmp_path / "ab")])
    assert code == 2
    assert "::error title=Startup A/B::adb install -r -t bench.apk failed: boom" in capsys.readouterr().out
