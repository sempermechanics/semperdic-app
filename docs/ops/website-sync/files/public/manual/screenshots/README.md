# Manual screenshots

Phone stills published with the Manual. Sources for these exports live in this
repo at `docs/source/images/` (PNG captures + SVG diagrams). Convert to WebP
here for the Astro pages (quality ~82; frames are 460×1022).

| File | Source PNG | Used on |
|---|---|---|
| `home.webp` | `home.png` | Getting started, Exports |
| `step1-frames.webp` | `step1-frames.png` | Analysis · Step 1 |
| `roi-editor.webp` | `roi-editor.png` | Analysis · ROI |
| `result-viewer.webp` | `result-viewer.png` | Sweeps & results · Reading |
| `result-lattice.webp` | `result-lattice.png` | Sweeps & results · Lattice |

Extra WebPs in this folder (`home-selection`, `step2-parameters`, `viewer-tools`,
etc.) match `docs/source/images/` for reuse; Manual pages do not reference them
yet.

Keep filenames stable. After replacing a file, bump `FIGURE_CACHE` in
`src/components/Figure.astro`. `Figure` references use the `.webp` name and
pass intrinsic `width`/`height` to reserve layout space.

## Stills vs newer chrome

These captures predate some lattice/Home chrome documented in prose (pinned
**Save graph · View**, scrub slider, **Only in cloud**, determinate transfer
progress). Captions describe what the photo shows; the surrounding Manual copy
covers the newer controls. Replace `home.webp` / `result-lattice.webp` when
fresh device shots are available.
