# ADR-011: The viewer draws each frame on its own photo

**Status:** Accepted, built
**Date:** 2026-09-28
**Deciders:** app owner

## Context

The result viewer decoded the reference photo once
(`ResultViewerActivity.decodeReferenceForDisplay`) and drew every frame's
heatmap on it, at the points' reference positions (x, y). This is the
Lagrangian view and had been the design since the viewer overhaul (`0ced55e3`).

For the lab tests it reads as a bug. A student scrubbing a tensile or bending
run sees the same photo under every frame: the bar never stretches and the
beam never bends, though the map says they did. The report already used each
frame's own photo on its page (`ViewerReportFactory.buildReportData`); the
screen did not.

Putting frame N's photo behind the old map is not enough. The map would sit
where the points *were*, off the specimen by (u, v), which is tens of pixels
at a bending beam's midspan.

## Decision

On screen, each frame is drawn in the **deformed configuration**, on that
frame's own photo:

- `VisualizationEngine.generateDeformedHeatmap` / `…Indices` draws each grid
  cell as the quad through its corners' displaced positions (x + u, y + v),
  filled by inverse-bilinear interpolation. With no displacement it is the
  reference render, within one colour step. The colour range is the same.
- `ResultViewerActivity` looks up each frame's photo with `deformedImagePathAt`,
  off the main thread in `readFrameDat`, and decodes it at display size under
  the map (`showFrameBase`). A frame whose photo is not on disk, or does not
  decode, falls back to the reference and the reference-position map, so the
  photo and the map always match.
- Tap-to-probe looks up the nearest **moved** point
  (`ViewerInspectController.displacedPositions` into the unchanged
  `PointSpatialIndex.build`), and the crosshair sits there. The readout still
  gives the point's reference (x, y), which is how the CSV names it.
- The rest-fit box also covers where the points moved to.
- A sweep shows its one deformed photo under every node.

`generateHeatmapIndices`, which feeds the summary GIF and the report, is
unchanged; the GIF bytes stay pinned.

## Options considered

### A: Keep the reference under every frame

**Cons:** the complaint stands. The screen disagrees with the report.

### B: Frame photo behind the unchanged map

**Cons:** the map is offset from the specimen by the displacement. That is
wrong, and it looks more wrong than A.

### C: Deformed configuration on the frame photo, everywhere on screen (chosen)

| Dimension | Assessment |
|-----------|------------|
| Complexity | Medium: a second rasterizer, a per-frame photo decode, a displaced probe lookup |
| Cost | One display-size decode per settled frame, off the main thread |
| Risk | The GIF and report still draw on the reference (TD-139) |

### D: A viewer toggle, reference by default

**Cons:** the student has to find a setting to see the specimen deform.
Rejected by the app owner.

## Trade-offs

- Scrubbing decodes a photo per settled frame (debounced like the `.dat`
  load). For a moment after a step, the new map can sit on the previous
  frame's photo.
- Coordinates in the probe readout are reference positions. The crosshair is
  not.

## Consequences

- The summary GIF, the PNG export (`ShareCenter.kt:388` in material_testing, `:360` here) and the report
  heatmap (`ReportBuilder.kt:278`) still draw on the reference. TD-139.
- Restored sessions need the deformed originals to show their frames this
  way. They already restore them (`docs/perf/backup-restore-split.md`).

## Action items

- [x] Deformed render, per-frame photo, displaced probe, fit box, tests
      (`DeformedHeatmapTest`).
- [ ] TD-139: decide whether the GIF and exports follow.
