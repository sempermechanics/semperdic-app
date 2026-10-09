#!/usr/bin/env python3
"""Render the canonical legal Markdown into the pages Hosting serves.

The published /privacy/ and /terms/ pages were placeholders that said, in the
body, "operators should keep this page in sync with docs/legal/… before launch"
— while the app linked to them as the real policy. Generating them removes the
"keep in sync" step: the Markdown is the only source, and CI fails if the
committed HTML does not match it (--check).

Deliberately dependency-free. Bringing in a Markdown library for three static
documents means a supply-chain dependency on the release path of the one thing
that must say exactly what we mean; this handles the subset those documents
actually use (headings, paragraphs, lists, tables, links, bold/code, rules) and
raises on anything it does not understand rather than silently dropping it.
"""
from __future__ import annotations

import argparse
import html
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PAGES = {
    "privacy": (ROOT / "docs/legal/PRIVACY_POLICY.md",
                ROOT / "firebase-hosting/public/privacy/index.html",
                "Semper — Privacy Policy"),
    "terms": (ROOT / "docs/legal/TERMS_OF_SERVICE.md",
              ROOT / "firebase-hosting/public/terms/index.html",
              "Semper — Terms of Service"),
}

SHELL = """<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>{title}</title>
  <style>
    body {{ font-family: Georgia, "Times New Roman", serif; max-width: 44rem; margin: 2rem auto; padding: 0 1rem; line-height: 1.6; color: #1a1a1a; }}
    h1 {{ font-size: 1.75rem; }}
    h2 {{ font-size: 1.3rem; margin-top: 2rem; }}
    h3 {{ font-size: 1.08rem; margin-top: 1.5rem; }}
    a {{ color: #0b3d2e; }}
    code {{ background: #f2f2f2; padding: .1rem .3rem; border-radius: 3px; font-size: .92em; }}
    hr {{ border: 0; border-top: 1px solid #ddd; margin: 2rem 0; }}
    table {{ border-collapse: collapse; width: 100%; margin: 1rem 0; display: block; overflow-x: auto; }}
    th, td {{ border: 1px solid #ddd; padding: .5rem .6rem; text-align: left; vertical-align: top; }}
    th {{ background: #f7f7f7; }}
    footer {{ margin-top: 3rem; font-size: .9rem; color: #555; }}
  </style>
</head>
<body>
<!-- GENERATED FROM {source} — DO NOT EDIT.
     Regenerate with: python scripts/render_legal_pages.py -->
{body}
<footer><a href="/privacy/">Privacy Policy</a> &middot; <a href="/terms/">Terms of Service</a></footer>
</body>
</html>
"""

_LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")
_BOLD = re.compile(r"\*\*([^*]+)\*\*")
_CODE = re.compile(r"`([^`]+)`")
# Anchored at column 0: an indented line is a continuation of the item above,
# not a new one. These documents wrap their bullets across lines.
_BULLET = re.compile(r"^[-*]\s+")
_NUMBERED = re.compile(r"^\d+\.\s+")
_BLOCK_START = re.compile(r"^(#{1,4}\s|[-*]\s|\d+\.\s|\||```)")


def _emit_list(lines: list[str], i: int, marker: re.Pattern, tag: str, out: list[str]) -> int:
    """Emit one list, folding wrapped continuation lines into their item."""
    out.append(f"<{tag}>")
    items: list[str] = []
    while i < len(lines):
        line = lines[i]
        if marker.match(line):
            items.append(marker.sub("", line).strip())
            i += 1
        elif line.strip() and line.startswith((" ", "\t")) and items:
            # Indented continuation of the previous bullet.
            items[-1] += " " + line.strip()
            i += 1
        else:
            break
    out.extend(f"<li>{_inline(item)}</li>" for item in items)
    out.append(f"</{tag}>")
    return i


# Repo-relative links resolve when reading the Markdown in the repository, but
# would 404 on the hosted page. Map the ones that have a public equivalent and
# flatten the rest to plain text rather than publishing a dead link in a policy.
_PUBLIC_LINKS = {
    "PRIVACY_POLICY.md": "/privacy/",
    "TERMS_OF_SERVICE.md": "/terms/",
}


def _href(target: str) -> str | None:
    if target.startswith(("http://", "https://", "mailto:", "/", "#")):
        return target
    return _PUBLIC_LINKS.get(target.rsplit("/", 1)[-1])


def _link(match: re.Match) -> str:
    label, target = match.group(1), match.group(2)
    # The text arrives escaped by _inline; unescape the target so the href
    # is escaped once (`&` as `&amp;`, not `&amp;amp;`).
    href = _href(html.unescape(target))
    if href is None:
        return label  # internal repo path: keep the words, drop the dead link
    return f'<a href="{html.escape(href, quote=True)}">{label}</a>'


def _inline(text: str) -> str:
    """Escape first, then re-introduce only the markup we recognise."""
    out = html.escape(text, quote=False)
    # A code span is literal: set it aside so bold and links do not reach in.
    spans: list[str] = []

    def stash(m: re.Match) -> str:
        spans.append(m.group(1))
        return f"\x00{len(spans) - 1}\x00"

    out = _CODE.sub(stash, out)
    out = _BOLD.sub(lambda m: f"<strong>{m.group(1)}</strong>", out)
    out = _LINK.sub(_link, out)
    return re.sub(r"\x00(\d+)\x00", lambda m: f"<code>{spans[int(m.group(1))]}</code>", out)


def _is_table_divider(line: str) -> bool:
    stripped = line.strip()
    return bool(stripped.startswith("|")) and set(stripped) <= set("|-: ")


def _cells(line: str) -> list[str]:
    return [c.strip() for c in line.strip().strip("|").split("|")]


def render(markdown: str) -> str:
    lines = markdown.splitlines()
    out: list[str] = []
    i = 0
    while i < len(lines):
        line = lines[i]
        stripped = line.strip()

        if not stripped:
            i += 1
            continue

        if stripped.startswith("---") and set(stripped) == {"-"}:
            out.append("<hr />")
            i += 1
            continue

        heading = re.match(r"^(#{1,4})\s+(.*)$", stripped)
        if heading:
            level = len(heading.group(1))
            out.append(f"<h{level}>{_inline(heading.group(2))}</h{level}>")
            i += 1
            continue

        # Table: a header row followed by a |---|---| divider.
        if stripped.startswith("|") and i + 1 < len(lines) and _is_table_divider(lines[i + 1]):
            header = _cells(stripped)
            out.append("<table>")
            out.append("<thead><tr>" + "".join(f"<th>{_inline(c)}</th>" for c in header) + "</tr></thead>")
            out.append("<tbody>")
            i += 2
            while i < len(lines) and lines[i].strip().startswith("|"):
                out.append("<tr>" + "".join(f"<td>{_inline(c)}</td>" for c in _cells(lines[i])) + "</tr>")
                i += 1
            out.append("</tbody></table>")
            continue

        for marker, tag in ((_BULLET, "ul"), (_NUMBERED, "ol")):
            if marker.match(line):
                i = _emit_list(lines, i, marker, tag, out)
                break
        else:
            if stripped.startswith("```"):
                raise ValueError(
                    "fenced code block in a legal document — the renderer does "
                    "not handle it, and silently dropping legal text is not "
                    "acceptable"
                )
            # Paragraph: consume until a blank line or the start of another block.
            # Its first line is taken whatever it starts with: everything this
            # renderer knows was tried above, so a line that only looks like a
            # block start (a `|` with no divider under it) is text, and skipping
            # it would drop legal wording.
            para = []
            while i < len(lines) and lines[i].strip() and (not para or not _BLOCK_START.match(lines[i])) and not (
                lines[i].strip().startswith("---") and set(lines[i].strip()) == {"-"}
            ):
                para.append(lines[i].strip())
                i += 1
            out.append(f"<p>{_inline(' '.join(para))}</p>")
        continue

    return "\n".join(out)


def build(name: str) -> tuple[Path, str]:
    source, target, title = PAGES[name]
    body = render(source.read_text(encoding="utf-8"))
    rel = source.relative_to(ROOT).as_posix()
    return target, SHELL.format(title=html.escape(title), source=rel, body=body)


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check", action="store_true",
        help="fail if the committed pages differ from the Markdown (for CI)",
    )
    args = parser.parse_args()

    stale = []
    for name in PAGES:
        target, rendered = build(name)
        current = target.read_text(encoding="utf-8") if target.exists() else None
        if args.check:
            if current != rendered:
                stale.append(target.relative_to(ROOT).as_posix())
            continue
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(rendered, encoding="utf-8")
        print(f"{'unchanged' if current == rendered else 'wrote'} {target.relative_to(ROOT)}")

    if stale:
        print(
            "These published pages no longer match docs/legal/:\n  "
            + "\n  ".join(stale)
            + "\nRegenerate with: python scripts/render_legal_pages.py",
            file=sys.stderr,
        )
        raise SystemExit(1)


if __name__ == "__main__":
    main()
