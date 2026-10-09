"""`scripts/render_legal_pages.py`: the hosted legal pages say what docs/legal/ says.

CI runs `--check`, which fails when a committed /privacy/ or /terms/ page no
longer matches its Markdown. These tests hold the renderer to the Markdown
subset the documents use, run `main()` against a small tree of their own (the
module's `ROOT` and `PAGES` are pointed at `tmp_path`), and run the real
`--check` on this checkout, as CI does.
"""
from __future__ import annotations

import importlib.util
import subprocess
import sys
from pathlib import Path

import pytest

_REPO = Path(__file__).resolve().parents[2]
_SCRIPT = _REPO / "scripts" / "render_legal_pages.py"

pytestmark = pytest.mark.skipif(not _SCRIPT.is_file(), reason="scripts/ not present (backend-only checkout)")


@pytest.fixture(scope="module")
def legal():
    spec = importlib.util.spec_from_file_location("render_legal_pages", _SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


# --- rendering ----------------------------------------------------------------


def test_headings_levels_one_to_four(legal):
    out = legal.render("# One\n## Two\n### Three\n#### Four")
    assert out.splitlines() == ["<h1>One</h1>", "<h2>Two</h2>", "<h3>Three</h3>", "<h4>Four</h4>"]


def test_five_hashes_or_no_space_is_not_a_heading(legal):
    assert legal.render("##### Five") == "<p>##### Five</p>"
    assert legal.render("#NoSpace") == "<p>#NoSpace</p>"


def test_paragraph_lines_join_until_a_blank_line(legal):
    out = legal.render("first line\nsecond line\n\nnext para")
    assert out.splitlines() == ["<p>first line second line</p>", "<p>next para</p>"]


def test_paragraph_ends_at_the_start_of_another_block(legal):
    out = legal.render("intro\n- item\n\ntext\n## Head")
    assert out.splitlines() == ["<p>intro</p>", "<ul>", "<li>item</li>", "</ul>", "<p>text</p>", "<h2>Head</h2>"]


def test_horizontal_rule_and_rule_ending_a_paragraph(legal):
    out = legal.render("para\n---\n\n-----")
    assert out.splitlines() == ["<p>para</p>", "<hr />", "<hr />"]


def test_bullet_list_with_wrapped_continuation_lines(legal):
    out = legal.render("- first\n  wraps here\n\tand a tab\n* second")
    assert out.splitlines() == ["<ul>", "<li>first wraps here and a tab</li>", "<li>second</li>", "</ul>"]


def test_numbered_list_directly_after_bullets_is_its_own_list(legal):
    out = legal.render("- a\n1. one\n22. two")
    assert out.splitlines() == ["<ul>", "<li>a</li>", "</ul>", "<ol>", "<li>one</li>", "<li>two</li>", "</ol>"]


def test_indented_marker_is_not_a_new_list(legal):
    # Anchored at column 0: an indented "- x" opening a block is paragraph text.
    assert legal.render("  - x") == "<p>- x</p>"


def test_table_with_header_divider_and_inline_cells(legal):
    out = legal.render("| A | B |\n|---|:-:|\n| 1 | **2** |\n| `c` | [l](https://e.x) |\nafter")
    assert out.splitlines() == [
        "<table>",
        "<thead><tr><th>A</th><th>B</th></tr></thead>",
        "<tbody>",
        "<tr><td>1</td><td><strong>2</strong></td></tr>",
        '<tr><td><code>c</code></td><td><a href="https://e.x">l</a></td></tr>',
        "</tbody></table>",
        "<p>after</p>",
    ]


def test_bold_code_and_html_escaping(legal):
    out = legal.render('a < b & "q" `x<y>` **bold** <script>')
    assert out == '<p>a &lt; b &amp; "q" <code>x&lt;y&gt;</code> <strong>bold</strong> &lt;script&gt;</p>'


def test_single_star_emphasis_is_left_literal(legal):
    # Only **bold** is supported; *x* stays as written rather than vanishing.
    assert legal.render("an *aside* here") == "<p>an *aside* here</p>"


@pytest.mark.parametrize(
    ("target", "href"),
    [
        ("https://example.com/a", "https://example.com/a"),
        ("http://example.com", "http://example.com"),
        ("mailto:legal@example.com", "mailto:legal@example.com"),
        ("/terms/", "/terms/"),
        ("#section", "#section"),
        ("PRIVACY_POLICY.md", "/privacy/"),
        ("../legal/TERMS_OF_SERVICE.md", "/terms/"),
    ],
)
def test_links_with_a_public_target_become_anchors(legal, target, href):
    assert legal.render(f"see [the page]({target})") == f'<p>see <a href="{href}">the page</a></p>'


def test_repo_relative_link_without_a_public_page_keeps_only_the_words(legal):
    assert legal.render("see [the runbook](../ops/RUNBOOK.md) now") == "<p>see the runbook now</p>"


def test_fenced_code_block_raises_rather_than_dropping_text(legal):
    with pytest.raises(ValueError, match="fenced code block"):
        legal.render("```\ncode\n```")


def test_ampersand_in_link_target_is_escaped_once(legal):
    out = legal.render("[x](https://example.com/?a=1&b=2)")
    assert out == '<p><a href="https://example.com/?a=1&amp;b=2">x</a></p>'


def test_stray_pipe_line_is_not_silently_dropped(legal):
    out = legal.render("para\n| not a table |\nnext")
    assert "not a table" in out


def test_fence_after_paragraph_text_also_raises(legal):
    with pytest.raises(ValueError, match="fenced code block"):
        legal.render("text\n```\ncode\n```")


def test_code_span_content_is_literal(legal):
    assert legal.render("`**x**`") == "<p><code>**x**</code></p>"


# --- main(): write mode and --check -------------------------------------------


_MD = "# Policy\n\nWe keep **nothing**.\n\n- one\n- two\n"


@pytest.fixture
def tree(legal, tmp_path, monkeypatch):
    """A repo-shaped tmp tree, with the module's ROOT/PAGES pointed at it."""
    (tmp_path / "docs/legal").mkdir(parents=True)
    (tmp_path / "docs/legal/PRIVACY_POLICY.md").write_text(_MD, encoding="utf-8")
    (tmp_path / "docs/legal/TERMS_OF_SERVICE.md").write_text("# Terms\n\nBe nice & fair.\n", encoding="utf-8")
    pages = {
        "privacy": (tmp_path / "docs/legal/PRIVACY_POLICY.md",
                    tmp_path / "public/privacy/index.html", "Semper — Privacy Policy"),
        "terms": (tmp_path / "docs/legal/TERMS_OF_SERVICE.md",
                  tmp_path / "public/terms/index.html", "Semper — Terms <of> Service"),
    }
    monkeypatch.setattr(legal, "ROOT", tmp_path)
    monkeypatch.setattr(legal, "PAGES", pages)
    return tmp_path


def _main(legal, monkeypatch, *args):
    monkeypatch.setattr(sys, "argv", ["render_legal_pages.py", *args])
    legal.main()


def test_build_wraps_the_body_in_the_shell(legal, tree):
    target, page = legal.build("terms")
    assert target == tree / "public/terms/index.html"
    assert page.startswith("<!DOCTYPE html>")
    assert "<title>Semper — Terms &lt;of&gt; Service</title>" in page
    assert "GENERATED FROM docs/legal/TERMS_OF_SERVICE.md" in page
    assert "<h1>Terms</h1>\n<p>Be nice &amp; fair.</p>\n<footer>" in page
    # The CSS braces in the shell survive str.format.
    assert "body { font-family:" in page


def test_write_mode_creates_pages_then_check_accepts_them(legal, tree, monkeypatch, capsys):
    _main(legal, monkeypatch)
    out = capsys.readouterr().out
    assert "wrote" in out and out.count("\n") == 2
    privacy = (tree / "public/privacy/index.html").read_text(encoding="utf-8")
    assert "<p>We keep <strong>nothing</strong>.</p>" in privacy
    _main(legal, monkeypatch, "--check")  # no SystemExit

    _main(legal, monkeypatch)
    assert capsys.readouterr().out.count("unchanged") == 2


def test_written_page_is_utf8_and_round_trips(legal, tree, monkeypatch):
    _main(legal, monkeypatch)
    raw = (tree / "public/privacy/index.html").read_bytes()
    assert "Semper — Privacy Policy".encode("utf-8") in raw
    # Text mode on Windows would write CRLF; --check reads back in text mode,
    # so either way the round trip compares equal. Pin that it does.
    expected = legal.build("privacy")[1]
    assert raw.decode("utf-8").replace("\r\n", "\n") == expected


def test_check_fails_naming_the_drifted_page(legal, tree, monkeypatch, capsys):
    _main(legal, monkeypatch)
    capsys.readouterr()
    (tree / "docs/legal/TERMS_OF_SERVICE.md").write_text("# Terms\n\nChanged.\n", encoding="utf-8")
    with pytest.raises(SystemExit) as exit_info:
        _main(legal, monkeypatch, "--check")
    assert exit_info.value.code == 1
    err = capsys.readouterr().err
    assert "no longer match docs/legal/" in err
    assert "public/terms/index.html" in err
    assert "public/privacy/index.html" not in err
    assert "Regenerate with: python scripts/render_legal_pages.py" in err


def test_check_fails_on_hand_edited_html_and_does_not_rewrite(legal, tree, monkeypatch, capsys):
    _main(legal, monkeypatch)
    page = tree / "public/privacy/index.html"
    edited = page.read_text(encoding="utf-8").replace("nothing", "everything")
    page.write_text(edited, encoding="utf-8")
    with pytest.raises(SystemExit) as exit_info:
        _main(legal, monkeypatch, "--check")
    assert exit_info.value.code == 1
    assert page.read_text(encoding="utf-8") == edited


def test_check_fails_when_a_page_is_missing(legal, tree, monkeypatch, capsys):
    with pytest.raises(SystemExit) as exit_info:
        _main(legal, monkeypatch, "--check")
    assert exit_info.value.code == 1
    err = capsys.readouterr().err
    assert "public/privacy/index.html" in err and "public/terms/index.html" in err
    assert not (tree / "public").exists()


def test_this_checkout_is_in_sync_as_ci_checks():
    result = subprocess.run(
        [sys.executable, str(_SCRIPT), "--check"],
        capture_output=True, text=True, encoding="utf-8", cwd=_REPO,
    )
    assert result.returncode == 0, result.stderr
