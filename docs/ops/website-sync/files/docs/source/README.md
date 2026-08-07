# App source snapshot

Copies of Semper app documentation that this public site mirrors. Keep them in
this repo so contributors do **not** need access to the private Android app
repository to review or refresh Manual / Privacy / Troubleshooting copy.

| Path | Mirrors |
|---|---|
| `OPERATING_MANUAL.md` | App operating manual (OM) |
| `WORKFLOWS.md` | App per-screen workflows |
| `images/` | OM device captures and diagrams (PNG / SVG) |
| `legal/` | App privacy / terms / cookie consent drafts |

Published Manual figures on the site are WebP exports under
`public/manual/screenshots/` (see that folder’s README). Diagrams used by Astro
pages also live under `public/manual/*.svg`.

When the app tip advances, refresh these files from the app tree, re-export any
changed phone stills to WebP, bump `FIGURE_CACHE` in `src/components/Figure.astro`,
and update `docs/CONTENT-SYNC.md` if page mapping or facts changed.
