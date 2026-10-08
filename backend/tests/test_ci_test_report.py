"""`scripts/ci_test_report.py`: what CI prints for a device or unit test run.

The script is the only place a failing connected test's name reaches the job
log, and it gates the Pixel 6 benchmarks. A parsing slip there turns a red run
into an unexplained one, or a regression into a pass, so its readers are held
here on small JUnit and benchmark files.
"""
from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "ci_test_report.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def report():
    spec = importlib.util.spec_from_file_location("ci_test_report", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def _write(path: Path, text: str) -> Path:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))
    return path


_JUNIT = """<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.FooTest" tests="5" failures="2" errors="1">
  <testcase classname="com.example.FooTest" name="passes" time="0.1"/>
  <testcase classname="com.example.FooTest" name="breaks" time="0.1">
    <failure message="expected:&lt;1&gt; but was:&lt;2&gt;&#10;second line">java.lang.AssertionError: expected:&lt;1&gt; but was:&lt;2&gt;
\tat com.example.FooTest.breaks(FooTest.kt:12)</failure>
  </testcase>
  <testcase classname="com.example.FooTest" name="throws" time="0.1">
    <error message="boom">java.lang.IllegalStateException: boom</error>
  </testcase>
  <testcase classname="com.example.FooTest" name="assumes" time="0.1">
    <failure message="got: false">org.junit.AssumptionViolatedException: got: false</failure>
  </testcase>
  <testcase classname="com.example.FooTest" name="assumesInMessage" time="0.1">
    <error message="org.junit.AssumptionViolatedException: no device"/>
  </testcase>
  <testcase classname="com.example.FooTest" name="silent" time="0.1">
    <failure/>
  </testcase>
</testsuite>
"""


def test_failures_and_errors_are_annotated_and_assumptions_are_skips(report, tmp_path, capsys):
    _write(tmp_path / "debug" / "TEST-com.example.FooTest.xml", _JUNIT)
    assert report.report_failures(tmp_path) == 3
    out = capsys.readouterr().out
    assert "::error title=Failed test::com.example.FooTest.breaks: expected:<1> but was:<2>\n" in out
    assert "::group::com.example.FooTest.breaks\njava.lang.AssertionError" in out
    assert "(FooTest.kt:12)" in out
    assert "::error title=Failed test::com.example.FooTest.throws: boom" in out
    # A failure with neither message nor text is still named, by its kind.
    assert "::error title=Failed test::com.example.FooTest.silent: failure" in out
    assert "skipped: com.example.FooTest.assumes" in out
    assert "skipped: com.example.FooTest.assumesInMessage" in out
    assert "passes" not in out


def test_a_long_trace_is_cut_to_the_first_lines(report, tmp_path, capsys):
    trace = "\n".join(f"\tat frame{i}" for i in range(100))
    _write(tmp_path / "TEST-long.xml",
           f'<testsuite><testcase classname="C" name="n"><failure message="m">{trace}</failure></testcase></testsuite>')
    assert report.report_failures(tmp_path) == 1
    out = capsys.readouterr().out
    assert f"frame{report.MESSAGE_LINES - 1}\n" in out
    assert f"frame{report.MESSAGE_LINES}\n" not in out


def test_unreadable_xml_is_a_warning_not_a_crash(report, tmp_path, capsys):
    _write(tmp_path / "TEST-broken.xml", "<testsuite><testcase")
    _write(tmp_path / "TEST-ok.xml", _JUNIT)
    assert report.report_failures(tmp_path) == 3
    out = capsys.readouterr().out
    assert "::warning::" in out and "TEST-broken.xml: unreadable test report" in out


def test_only_test_report_files_are_read(report, tmp_path, capsys):
    _write(tmp_path / "results.xml", _JUNIT)
    assert report.report_failures(tmp_path) == 0
    assert capsys.readouterr().out == ""


def _bench(device="oriole", **extra):
    return {
        "context": {"build": {"device": device}},
        "benchmarks": [
            {
                "className": "com.example.ScreenBenchmark",
                "name": "settingsColdStartup",
                "metrics": {"timeToInitialDisplayMs": {"minimum": 700, "median": 738.25, "maximum": 800}},
                "sampledMetrics": {},
            },
            {
                "className": "com.example.ViewerScrubBenchmark",
                "name": "scrub150Frames",
                "metrics": {"memoryHeapSizeMaxKb": {"minimum": 1, "median": "n/a", "maximum": 3}},
                "sampledMetrics": {"frameDurationCpuMs": {"P50": 4.0, "P90": 8.7, "P99": 12.25}},
            },
        ],
        **extra,
    }


def test_benchmark_results_print_one_line_per_metric(report, tmp_path, capsys):
    _write(tmp_path / "a" / "x-benchmarkData.json", json.dumps(_bench()))
    measured = {}
    assert report.report_benchmarks(tmp_path, measured) == 2
    out = capsys.readouterr().out.splitlines()
    assert "BENCH ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs: min 700.0 median 738.2 max 800.0" in out
    assert "BENCH ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs: P50 4.0 P90 8.7 P99 12.2" in out
    # A non-numeric median is printed as it is and not gated.
    assert "BENCH ViewerScrubBenchmark.scrub150Frames memoryHeapSizeMaxKb: min 1.0 median n/a max 3.0" in out
    assert measured == {"oriole": {
        "ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs": 738.25,
        "ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P50": 4.0,
        "ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P90": 8.7,
        "ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P99": 12.25,
    }}


def test_unreadable_benchmark_data_is_a_warning(report, tmp_path, capsys):
    _write(tmp_path / "bad-benchmarkData.json", "{not json")
    assert report.report_benchmarks(tmp_path, {}) == 0
    assert "bad-benchmarkData.json: unreadable benchmark data" in capsys.readouterr().out


_GATES = {
    "margin": 0.30,
    "state": {"maxThermalStatus": 0, "requirePlugged": True},
    "devices": {"oriole": {"label": "Pixel 6", "reference": {
        "ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs": 500,
        "ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P90": 8.0,
        "ViewerScrubBenchmark.scrub10Frames memoryHeapSizeMaxKb": 100,
    }}},
}

_COOL = {"thermalStatus": 0, "plugged": "ac"}


def test_gates_breach_pass_and_unmeasured(report, capsys):
    measured = {"oriole": {
        "ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs": 738.25,  # > 500 * 1.3
        "ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P90": 8.7,  # <= 10.4
    }, "generic_x86_64": {"ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs": 9999}}
    assert report.check_gates(_GATES, measured) == (1, 0)
    out = capsys.readouterr().out
    assert ("::error title=Benchmark gate::Pixel 6 ScreenBenchmark.settingsColdStartup "
            "timeToInitialDisplayMs: 738.2 > 650.0 (500.0 + 30%)") in out
    assert "GATE ok Pixel 6 ViewerScrubBenchmark.scrub150Frames frameDurationCpuMs P90: 8.7 <= 10.4 " \
           "(device state not recorded)" in out
    assert "GATE Pixel 6 ViewerScrubBenchmark.scrub10Frames memoryHeapSizeMaxKb: not measured" in out
    assert "GATE generic_x86_64: no reference, report only" in out


def test_a_result_off_the_reference_state_is_not_gated(report, capsys):
    key = "ScreenBenchmark.settingsColdStartup timeToInitialDisplayMs"
    measured = {"oriole": {key: 9999.0}}
    hot = {"oriole": {"ScreenBenchmark.settingsColdStartup": {
        "start": {"thermalStatus": 2, "plugged": "ac"}, "end": {"thermalStatus": 0, "plugged": "none"}}}}
    assert report.check_gates(_GATES, measured, hot) == (0, 1)
    out = capsys.readouterr().out
    assert "GATE not gated Pixel 6" in out
    assert "thermal status 2 at start; on battery at end" in out

    cool = {"oriole": {"ScreenBenchmark.settingsColdStartup": {"start": _COOL, "end": _COOL}}}
    assert report.check_gates(_GATES, measured, cool) == (1, 0)
    assert "(device state not recorded)" not in capsys.readouterr().out


def test_state_problems_names_a_missing_phase(report):
    assert report.state_problems({"start": _COOL}, _GATES["state"]) == ["no end state"]
    assert report.state_problems({"start": _COOL, "end": _COOL}, _GATES["state"]) == []
    unplugged = {"thermalStatus": 0, "plugged": "none"}
    assert report.state_problems({"start": unplugged, "end": unplugged}, {}) == []


def test_device_states_are_merged_and_unreadable_ones_warned(report, tmp_path, capsys):
    _write(tmp_path / "a-deviceState.json", json.dumps({"device": "oriole", "tests": {"A.a": {"start": _COOL}}}))
    _write(tmp_path / "b" / "b-deviceState.json", json.dumps({"device": "oriole", "tests": {"B.b": {}}}))
    _write(tmp_path / "c-deviceState.json", "nope")
    states = {}
    report.read_device_states(tmp_path, states)
    assert states == {"oriole": {"A.a": {"start": _COOL}, "B.b": {}}}
    assert "c-deviceState.json: unreadable device state" in capsys.readouterr().out


def test_main_exits_0_without_gates_even_with_failures(report, tmp_path, capsys):
    _write(tmp_path / "TEST-x.xml", _JUNIT)
    _write(tmp_path / "x-benchmarkData.json", json.dumps(_bench()))
    missing = tmp_path / "nowhere"
    assert report.main(["ci_test_report.py", str(tmp_path), str(missing)]) == 0
    out = capsys.readouterr().out
    assert f"{missing}: not found" in out
    assert out.rstrip().endswith("3 failing test case(s), 2 benchmark result(s)")


def test_main_with_gates_exits_1_on_a_breach_and_0_otherwise(report, tmp_path, capsys):
    gates = _write(tmp_path / "gates.json", json.dumps(_GATES))
    results = tmp_path / "results"
    _write(results / "x-benchmarkData.json", json.dumps(_bench()))
    assert report.main(["ci_test_report.py", "--gates", str(gates), str(results)]) == 1
    assert "1 benchmark gate(s) exceeded, 0 not gated (phone state)" in capsys.readouterr().out

    _write(results / "x-benchmarkData.json", json.dumps(_bench(device="generic_x86_64")))
    assert report.main(["ci_test_report.py", "--gates", str(gates), str(results)]) == 0
    assert "0 benchmark gate(s) exceeded" in capsys.readouterr().out
