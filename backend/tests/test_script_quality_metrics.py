"""`scripts/quality_metrics.py`: the before/after code-quality numbers.

The script reads `sys.argv` at import, so the parsers are loaded with a dummy
argv, and the report itself comes from running the real CLI on a small
app/src tree in `tmp_path` whose every number is worked out by hand below.
"""
from __future__ import annotations

import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest

_SCRIPT = Path(__file__).resolve().parents[2] / "scripts" / "quality_metrics.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")

_PKG = Path("app/src/main/java/com/sempermechanics/semper")


@pytest.fixture(scope="module")
def qm():
    saved = sys.argv
    sys.argv = ["quality_metrics.py", "unused-root", "unused-out"]
    try:
        spec = importlib.util.spec_from_file_location("quality_metrics", _SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    finally:
        sys.argv = saved
    return module


def _write(path: Path, text: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(text.encode("utf-8"))


# --- parsers --------------------------------------------------------------------


def test_strip_strings_blanks_string_and_char_literals(qm):
    assert qm.strip_strings('f("a, (b", \'(\', "esc\\"q)")') == "f(\"\", '', \"\")"


def test_functions_block_expression_and_generic_extension(qm):
    text = (
        "class C {\n"                                    # 1
        "    fun block(a: Int) {\n"                      # 2
        "        if (a > 0) {\n"                         # 3
        "            println(\"}\")\n"                   # 4  brace in a string
        "        }\n"                                    # 5
        "    }\n"                                        # 6
        "    private fun expr(): Int = 1\n"              # 7
        "\n"                                             # 8
        "    override suspend fun <T> List<T>.ext(\n"    # 9
        "        x: T,\n"                                # 10
        "        y: Map<String, Int>,\n"                 # 11
        "        z: String = \"a,b\",\n"                 # 12
        "    ): Int {\n"                                 # 13
        "        return 1\n"                             # 14
        "    }\n"                                        # 15
        "    fun none() {}\n"                            # 16
        "}\n"
    )
    assert qm.functions("C.kt", text) == [
        ("block", 2, 5, 1),
        ("expr", 7, 1, 0),
        ("ext", 9, 7, 3),  # trailing comma is not a fourth parameter
        ("none", 16, 1, 0),
    ]


def test_functions_skip_block_comments(qm):
    text = "/**\n * fun hidden() {\n */\nfun shown() {\n}\n/* fun inline() {} */\n"
    assert qm.functions("C.kt", text) == [("shown", 4, 2, 0)]


def test_lambda_typed_parameters_are_all_counted(qm):
    assert qm.functions("C.kt", "fun f(a: () -> Unit, b: Int, c: Int) {}\n") == [("f", 1, 1, 3)]


def test_abstract_function_is_one_line(qm):
    text = "interface I {\n    fun a()\n    fun b() {\n        x()\n    }\n}\n"
    assert qm.functions("I.kt", text) == [("a", 2, 1, 0), ("b", 3, 3, 0)]


def test_functions_of_never_raises(qm, monkeypatch):
    def boom(path, text):
        raise IndexError("parser slip")

    monkeypatch.setattr(qm, "functions", boom)
    assert qm.funs_of("x.kt", "fun a() {}") == []


def test_norm_code_lines_drops_noise_and_collapses_spaces(qm):
    text = (
        "package a.b\n"
        "import c.D\n"
        "\n"
        "// line comment\n"
        "/* one-line block */\n"
        "/**\n"
        " * kdoc\n"
        "   still comment\n"
        " */\n"
        "@Suppress(\"X\")\n"
        "class   K  {\n"
        "    val   a  =  1\n"
        "}\n"
        "),\n"
        "})\n"
    )
    assert qm.norm_code_lines(text) == ["class K {", "val a = 1"]


# --- the report -----------------------------------------------------------------


_ALPHA = """@file:Suppress("LongMethod", "MagicNumber")

package com.sempermechanics.semper

import kotlin.math.max

// a comment
class Alpha {
    lateinit var name: String

    @Suppress("ReturnCount", "UNCHECKED_CAST")
    fun pick(a: Int, b: Int): Int {
        if (a > b) return a
        return b
    }

    fun one() = 1
}
"""  # 18 lines; code lines: class, lateinit, fun pick, if, return b, fun one = 6

_BETA = """package com.sempermechanics.semper.ui

class Beta {
    fun show(context: Context) {
        Toast.makeText(context, "x, y", Toast.LENGTH_SHORT).show()
        val v = findViewById<View>(R.id.a)!!
        try {
            work()
        } catch (e: Exception) {
            Timber.e(e)
        }
    }
}"""  # 13 lines, no final newline; code lines: 8 (class..Timber, minus braces-only)


def _shared(n: int) -> str:
    # 10 lines; code: "fun sharedN() {" + six identical vals = 7
    body = "".join(f"    val {c} = {i}\n" for i, c in enumerate("abcdef", 1))
    return f"package com.sempermechanics.semper.ui\n\nfun shared{n}() {{\n{body}}}\n"


def _numbered(prefix: str, count: int) -> str:
    return "".join(f"val {prefix}{i} = {i}\n" for i in range(count))


_TEST = """package com.sempermechanics.semper

@Config(sdk = [34])
class FooTest {
    @Test
    fun a() { Thread.sleep(5) }

    @Test
    fun b() {}
}
"""  # 10 newlines


@pytest.fixture
def report(tmp_path):
    root = tmp_path / "repo"
    main = root / _PKG
    _write(main / "Alpha.kt", _ALPHA)
    _write(main / "ui/Beta.kt", _BETA)
    _write(main / "ui/Shared1.kt", _shared(1))
    _write(main / "ui/Shared2.kt", _shared(2))
    _write(main / "data/Big.kt", _numbered("big", 501))
    _write(main / "data/Medium.kt", _numbered("med", 350))
    _write(main / "data/VisualizationEngine.kt", _numbered("vis", 600))
    _write(main / "data/notes.md", "not kotlin\n" * 900)
    _write(main / "build.gradle.kts", "x\n" * 900)
    _write(root / "app/src/test/java/FooTest.kt", _TEST)
    _write(root / "app/src/main/res/layout/a.xml", "<a>\n</a>\n")
    _write(root / "app/src/main/res/layout/b.xml", "<b/>\n")
    _write(root / "app/src/main/res/layout/c.txt", "x\n")
    out = tmp_path / "metrics"
    result = subprocess.run(
        [sys.executable, str(_SCRIPT), str(root), str(out)],
        capture_output=True, text=True, encoding="utf-8",
    )
    assert result.returncode == 0, result.stderr
    data = json.loads(out.with_suffix(".json").read_text(encoding="utf-8"))
    md = out.with_suffix(".md").read_text(encoding="utf-8")
    return data, md, result.stdout


def test_file_and_line_counts(report):
    main = report[0]["main"]
    assert main["kotlin_files"] == 7  # .md and .kts are not counted
    # 18 + 13 (unterminated last line still counts) + 10 + 10 + 501 + 350 + 600
    assert main["kotlin_lines"] == 1502
    assert main["mean_file_lines"] == round(1502 / 7, 1)
    assert main["files_over_300"] == 3
    assert main["files_over_500"] == 2
    assert main["files_over_500_non_invariant"] == 1  # VisualizationEngine.kt is exempt
    assert main["largest_file"] == ["data/VisualizationEngine.kt", 600]
    assert main["top25_files"][:3] == [["data/VisualizationEngine.kt", 600], ["data/Big.kt", 501],
                                       ["data/Medium.kt", 350]]
    assert len(main["top25_files"]) == 7


def test_packages(report):
    main = report[0]["main"]
    assert main["packages"] == {
        "(root)": {"files": 1, "lines": 18},
        "data": {"files": 3, "lines": 1451},
        "ui": {"files": 3, "lines": 33},
    }
    assert main["max_files_in_package"] == 3


def test_suppressions_split_file_level_from_inline(report):
    main = report[0]["main"]
    assert main["files_with_@file:Suppress"] == 1
    assert main["suppress_file_level"]["LongMethod"] == 1
    assert main["suppress_file_level"]["MagicNumber"] == 1
    assert main["suppress_file_level"]["ReturnCount"] == 0
    assert main["suppress_inline"]["ReturnCount"] == 1
    assert main["suppress_inline"]["LongMethod"] == 0
    assert main["suppress_total"]["MagicNumber"] == 1
    # Only the tracked detekt rules are reported.
    assert "UNCHECKED_CAST" not in main["suppress_inline"]


def test_pattern_counts(report):
    counts = report[0]["main"]["counts"]
    assert counts["findViewById"] == 1
    assert counts["lateinit var"] == 1
    assert counts["Toast.makeText"] == 1
    assert counts["generic catch (Exception/Throwable)"] == 1
    assert counts["!! (non-null assertions)"] == 1
    assert counts["Timber.e/w"] == 1
    assert counts["Dispatchers.IO"] == 0


def test_functions_and_duplication(report):
    main = report[0]["main"]
    # pick, one, show, shared1, shared2
    assert main["functions"] == 5
    assert main["functions_over_60_lines"] == 0
    assert main["functions_with_7plus_params"] == 0
    longest = main["top20_longest_functions"]
    assert longest[0] == ["show", "ui/Beta.kt", 4, 9, 1]
    assert ["pick", "Alpha.kt", 12, 4, 2] in longest
    # code lines: Alpha 6 + Beta 8 + Shared 7 + 7 + Big 501 + Medium 350 + Vis 600
    assert main["code_lines_normalised"] == 1479
    # The six identical vals of Shared1/Shared2 are one block, covering 12 lines.
    assert main["duplicated_6line_blocks"] == 1
    assert main["duplicated_lines"] == 12
    assert main["duplication_pct"] == round(100.0 * 12 / 1479, 2)


def test_tests_and_layouts(report):
    data = report[0]
    assert data["tests"]["test"] == {"files": 1, "lines": 10, "@Test": 2, "Thread.sleep": 1, "@Config(sdk": 1}
    assert data["tests"]["androidTest"] == {"files": 0, "lines": 0, "@Test": 0, "Thread.sleep": 0,
                                            "@Config(sdk": 0}
    assert data["main"]["layout_xml_files"] == 2
    assert data["main"]["layout_xml_lines"] == 3


def test_markdown_table_and_stdout(report):
    _, md, stdout = report
    assert md.startswith("# Quality metrics\n")
    assert "| kotlin_files | 7 |" in md
    assert "| @Suppress ReturnCount (file / inline) | 0 / 1 |" in md
    assert "| test: files / lines / @Test / Thread.sleep / @Config(sdk | 1 / 10 / 2 / 1 / 1 |" in md
    assert "| data/VisualizationEngine.kt | 600 |" in md
    assert "| show | ui/Beta.kt:4 | 9 | 1 |" in md
    assert "| data | 3 | 1451 |" in md
    assert "| kotlin_files | 7 |" in stdout


def test_empty_file_has_zero_lines(tmp_path):
    root = tmp_path / "repo"
    _write(root / _PKG / "Empty.kt", "")
    _write(root / _PKG / "One.kt", "val a = 1\n")
    out = tmp_path / "m"
    subprocess.run([sys.executable, str(_SCRIPT), str(root), str(out)], check=True, capture_output=True)
    main = json.loads(out.with_suffix(".json").read_text(encoding="utf-8"))["main"]
    assert main["kotlin_lines"] == 1
