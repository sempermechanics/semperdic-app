# Semper FAQ

Canonical text for the public manual at
`https://semperdic.github.io/website/manual/faq/`. Each **Why?** or **ⓘ** in the
app opens one of these sections (after a leave-app confirm).

**Publish:** copy or render this file into [semperdic/website](https://github.com/semperdic/website)
on release. Anchor IDs must match `url_faq_*` in `app/src/main/res/values/strings.xml`.
App ↔ FAQ map: [FAQ_LINKS.md](FAQ_LINKS.md).

**Last updated:** 2026-08-28 (measurement-floor dialog, lighting study, strain stats).

---

## Table of contents

| Section | Anchor | Opened from |
|---------|--------|-------------|
| Lossy formats | [jpeg-warning](#jpeg-warning) | Wizard step 1 chip |
| Speckle contrast | [speckle-contrast](#speckle-contrast) | Wizard chip; capture speckle fail **Why?**; speckle-size dialogs; uncorrelated burst **Why?** |
| Measurement floor | [noise-floor](#noise-floor) | Capture floor dialog **ⓘ** / **Why?**; result viewer caption |
| Lighting & accuracy | [lighting-and-accuracy](#lighting-and-accuracy) | Linked from measurement floor; reports |
| Strain field stats | [strain-field-stats](#strain-field-stats) | Result viewer; reports |
| Imaging pipeline | [imaging-pipeline](#imaging-pipeline) | Capture ISP / denoise snackbars |
| Frame size mismatch | [frame-size-mismatch](#frame-size-mismatch) | Wizard step 2 chip |
| ROI too small | [roi-too-small](#roi-too-small) | Wizard / capture ROI snackbar **Why?** |
| Sweep subset range | [sweep-subset-range](#sweep-subset-range) | Sweep plan chip |
| Sweep empty plan | [sweep-empty-plan](#sweep-empty-plan) | Sweep plan chip |
| Engine: decorrelation | [engine-features](#engine-features) | Engine failure **Why?**; lattice node |
| Engine: ROI | [engine-roi](#engine-roi) | Engine failure **Why?** |
| Engine: decode / init | [engine-init](#engine-init) | Engine failure **Why?** |
| Engine: low convergence | [engine-convergence](#engine-convergence) | Engine failure **Why?** |
| Engine: strain window | [engine-vsg](#engine-vsg) | Engine failure **Why?**; lattice all-failed |
| Import reference | [import-reference](#import-reference) | Wizard import snackbar **Why?** |
| Import deformed batch | [import-deformed](#import-deformed) | Wizard import snackbar **Why?** |
| Video read | [video-read](#video-read) | Wizard video snackbar **Why?** |
| Video extract | [video-extract](#video-extract) | Wizard video snackbar **Why?** |
| No batch data | [no-batch-data](#no-batch-data) | Result viewer snackbar **Why?** |
| Viewer out of memory | [viewer-oom](#viewer-oom) | Result viewer snackbar **Why?** |
| Custom colour scale | [custom-scale](#custom-scale) | Result viewer snackbar **Why?** |

---

## jpeg-warning {#jpeg-warning}

**When you see it:** Wizard step 1 — a chip warns that a frame or reference is not
lossless (JPEG, HEIC, etc.).

**Why it matters:** Lossy compression adds blocking artefacts and softens speckle
edges. Displacement can still run, but strain noise and failed subsets rise.

**What to do:** Re-export or re-capture as **PNG** or **TIFF** when accuracy matters.
For phone capture, use the in-app recorder (PNG stills) instead of pulling JPEGs
from the gallery.

---

## speckle-contrast {#speckle-contrast}

**When you see it:** Low SSSIG chip in the wizard, or **Speckle contrast is too
low** after the capture test shot (**Why?** opens this section).

**Why it matters:** DIC tracks small windows of random pattern. If gradients inside
a subset are weak, correlation fails or wanders.

**What to do:**

- Paint a finer, high-contrast speckle (black on white or white on black).
- Improve **lighting** — even, diffuse light; avoid glare and deep shadows in the ROI.
- Focus sharply on the speckled surface.
- Draw the contrast ROI on the busiest part of the pattern.

See [lighting-and-accuracy](#lighting-and-accuracy) for how much light changes
measured strain noise.

### How big a speckle should be

Contrast is only half of it. A speckle can be perfectly black on perfectly white
and still be untrackable, because what DIC needs is a *feature size* the sensor
can resolve. The iDICs *Good Practices Guide* puts a single speckle at:

| | Speckle diameter | What happens outside it |
|---|---|---|
| **Minimum** | **3 px** | Below this the pattern aliases: neighbouring frames no longer share a feature to match, and correlation fails outright |
| **Recommended** | **5 px** | Enough detail to interpolate to sub-pixel without wasting frame area |
| **Maximum** | **9 px** | Above this the pattern is oversampled — more pixels are being spent per speckle with no gain in correlation, and the frame rate is paying for it |

This is measured in **pixels of the recording**, not of the test shot, so the same
specimen moves in and out of the band as the capture resolution changes. That is
why the app measures the speckle on the test shot and then re-checks it against
the resolution you actually chose.

**When the app says the speckle is too small,** the fix is either a bigger
recording (the dialog names the long edge that puts it at 5 px) or a coarser
pattern. **When it says too large,** a *smaller* recording is the better answer:
it costs less per frame, so the same run becomes available at a higher frame
rate, and the correlation is no worse.

### Speckle size in millimetres

Pixels are a property of the camera; the pattern you paint is in millimetres. The
app converts the band when it can work out the image scale, from the sensor size,
the focal length and the subject distance the camera reports:

```
mm per pixel = (sensor long edge mm / image long edge px) × (distance − focal) / focal
```

Most phones do not report a calibrated subject distance, so this often reads
**not available** — that is the normal case, not an error. The pixel figures are
measured directly and are unaffected.

---

## speckle-uncorrelated {#speckle-uncorrelated}

**When you see it:** **The pattern could not be tracked** after the static burst.

The photographs were taken and processed — nothing failed about the capture. What
failed is that the burst frames would not correlate with *each other*, on a scene
that was not moving. At the recording size, the pattern is not resolved.

**What to do:** the primary action is **Change resolution**, which returns to the
setup screen with the recommended size already selected. **Record anyway** is
still offered — you may know something the check does not — but a retry at the
same size will fail the same way, which is why one is not offered.

See [speckle-contrast](#speckle-contrast) for the size band and how to hit it.

---

## noise-floor {#noise-floor}

**When you see it:** After a good test-shot ROI, the app captures a short **static
burst** (2–5 stills) and shows a **measurement floor** dialog:

| Floor | Dialog |
|-------|--------|
| **At or below ~1 mε** | Large **measurement floor** value (e.g. **402 µε**), plain-language body, **Continue**, **ⓘ** for this FAQ |
| **Above ~1 mε** | Red **Results will be unreliable**, same large floor value (e.g. **1.0 mε**), **Record anyway** (primary), **Retry test shot**, **Why?** (does not dismiss the dialog) |

Other outcomes: **Readings will not settle** (motion or flicker) and **The image
is drifting** (steady shift) — retry after stabilising the rig; **Why?** still
links here.

### What the number means

The specimen is **not loaded yet**. Every displacement in the burst is treated as
**error**, not real strain. The app correlates burst frames against the first burst
frame over your contrast ROI, takes the **median** displacement scatter σ across
pairs, and converts it to strain at a **15 px gauge**:

```
floor (microstrain) = √2 × σ_px / 15 × 10⁶
```

**Display:** values below **1 mε** show as **µε** (e.g. 402 µε); at or above 1 mε
as **mε** (e.g. 1.0 mε). Same number, different unit for readability.

**Gate:** **1 mε** is the warning line. Above it, strain smaller than the floor is
mostly noise — the app warns strongly but still lets you **Record anyway** and
stamps the floor on the **PDF**, **CSV**, and session.

### The scatter map

Under the number, when the burst produced enough frames, is a **heat map of the
scatter drawn over the frame it was measured on**. Each cell is the spread of
that point's position across the burst — the same quantity the floor above is a
summary of, before it was summarised.

It is there because one number cannot say *where* a setup is weak. A glare patch,
a soft corner and a thin band of speckle all reduce to the same slightly-worse
floor, and all three have different fixes. The map is what turns the verdict from
a grade into somewhere to look: a bright corner means refocus, a bright patch
means move the lamp, bright everywhere means the pattern.

The legend gives both ends of the colour scale twice — in **pixels**, which is
what was measured, and in **microstrain** at the same 15 px gauge the floor
itself is quoted at, so the colours and the number above them read in one unit.

The map is absent, rather than empty, when the burst could not produce one:
fewer than three usable frames, or too few points that solved in all of them.

### What the dialog is telling you to do

- **Finer strain may not show** below the floor — that is the smallest change the
  setup can reliably resolve on this burst.
- **Add light** or **measure over a larger area** (larger subset / ROI) to lower
  the floor. See [lighting-and-accuracy](#lighting-and-accuracy).

### How the floor is measured (not the heatmap)

The floor comes from a **coarse probe grid** over the ROI during the burst — not
from the strain heatmap after loading. It is a **displacement-noise** estimate,
one number for the whole setup.

### Burst vs exported PNGs

The dialog measures a **pre-recording burst** (frames are then deleted). Gallery
`reference.png` and `frame_*.png` come from the **recording session** later. The
formula is the same; the **images are not**. The value stamped on your **CSV/PDF**
is authoritative for what the run was captured at. Offline re-analysis of exported
PNGs may read higher or lower — especially in bright light — without indicating a
bug. Dim runs often agree closely; bright/medium can diverge when burst frames
were tighter in time than later recording stills.

### Result viewer

On strain fields, a caption under the colour bar repeats the floor when it was
within limits, or the report warning when you recorded past it.

---

## lighting-and-accuracy {#lighting-and-accuracy}

**Context:** Internal noisetest (2026-08-28) — static speckle, fixed specimen and
tripod, **only lighting varied** (bright / medium / dim). True strain = **0**; all
reported Exx values are measurement error.

### Main finding

**More light → better accuracy** when subset size is held fixed. Dim light is
always worst; a larger subset **partially** compensates but does not beat bright
at the same patch size.

Example — robust strain scatter (typical error, mε) at **65 px subset**, same ROI:

| Lighting | Typical σ |
|----------|-----------|
| Bright | **0.07–0.12** |
| Medium | **0.10–0.13** |
| Dim | **0.23–0.37** |

At **15 px subset**, dim scatter can be **2–3×** bright (e.g. 1.6 vs 0.5 mε).

### Why dim sometimes looked “best” in the app

The app **raises subset size in dim light** (e.g. 65 px vs 15–21 px in bright).
Larger patches average noise down. Comparing app-default runs **confuses lighting
with subset**. Always compare at the **same subset** to isolate lighting.

### Image quality vs strain

| Lighting | Effect on images | Effect on strain |
|----------|------------------|------------------|
| **Dim** | Dark ROI, low sharpness, weak SSSIG | Highest scatter; needs large subset |
| **Medium** | Good luma and SSSIG | Middle — but **tripod drift** in some runs dominated outliers |
| **Bright** | Strong ROI luma, good contrast | Lowest scatter at fixed subset |

### Practical rules

1. **Prefer bright or medium light** on the speckle — dim costs accuracy even when
   the floor dialog passes.
2. **Hold the rig steady** — session drift (e.g. 0.6 px vertical over ten frames)
   can spike max strain to hundreds of mε while mean/median stay near zero.
3. **Draw the same contrast ROI** when comparing lighting sessions (lab scripts use
   the intersection of per-session ROIs).
4. **Do not trust heatmap max alone** — see [strain-field-stats](#strain-field-stats).

Device-to-device differences were **secondary** to lighting and burst stability in
this study.

Full protocol, fixed-subset tables, and setup checklist:
[NOISE_FLOOR_STRAIN_ACCURACY.md](NOISE_FLOOR_STRAIN_ACCURACY.md).

---

## strain-field-stats {#strain-field-stats}

**When you see it:** Result viewer heatmap, CSV `# field_stats`, PDF field summary.

On a **static** specimen (zero true strain), the app still reports mean, median,
min, max, and scatter. They answer different questions.

| Stat | What it is | How to read it on a static test |
|------|------------|-----------------------------------|
| **Mean / median** | Average / middle Exx in the field | Stay **near 0 mε** — looks fine even when the run is noisy |
| **Min / max** | Single worst points in the field | **Spike** (±100–800 mε) from outliers, drift, or bad subsets — **misleading alone** |
| **Robust σ (MAD)** | Typical point-to-point scatter | **Best single accuracy read** — tracks lighting and subset |
| **Measurement floor** | Capture-time displacement noise | Smallest strain the **setup** can trust; compare robust σ **to** the floor |

### Patterns from the lighting study

- **Mean and median** barely moved across bright / medium / dim — do not use them
  alone to judge quality.
- **Max** swung with drift and small subsets; **min/max tightened** with larger
  subset and stable burst.
- **Robust σ** ranked lighting cleanly: bright < medium < dim at fixed subset.

### What to trust

1. Compare **robust σ to the measurement floor** on static or near-static checks.
2. Treat **max** as “worst pixel this frame,” not “accuracy.”
3. If mean ≈ 0 but max is huge, look for **tripod drift** or **too small a subset**
   before blaming the specimen.

CSV exports max/min/mean per frame; median on points is computed in reports. The
heatmap colour scale uses percentiles (p02–p98); CSV extrema are raw.

---

## imaging-pipeline {#imaging-pipeline}

**When you see it:** After the floor gate — one snackbar naming the costliest
imaging setting the phone **refused to lock**, or a **frames were smoothed**
warning when neighbour correlation on the burst difference image is high.

**Why it matters:** DIC needs pixel-level stability frame to frame. OIS, EIS, ZSL
merge, spatial denoise, sharpening, tone mapping, AWB drift, and scene modes can
shift or blur speckle between stills even when the app asked for a manual pipeline.

**What to do:**

- Prefer a **manual / pro** camera path when available; disable beauty / scene modes.
- Retry on a phone that honours more keys, or accept the warning and use a **larger
  subset** and **more light**.
- Smoothed frames: the measured noise variance can look **better than reality** —
  treat the floor as optimistic and read [strain-field-stats](#strain-field-stats).

Each refusal string in the app names one effect (e.g. lens stabiliser will not
switch off). **Why?** on the snackbar opens this section.

---

## frame-size-mismatch {#frame-size-mismatch}

**When you see it:** Wizard step 2 — deformed frames differ in pixel size from the
reference.

**Why it matters:** Subset positions are in reference pixels; a size change breaks
the grid unless frames are rescaled (not automatic).

**What to do:** Re-export all frames at the same resolution, or re-capture with
fixed resolution settings.

---

## roi-too-small {#roi-too-small}

**When you see it:** ROI width or height is smaller than the subset diameter.

**What to do:** Enlarge the ROI on the speckle, or reduce subset size in step 3.

---

## sweep-subset-range {#sweep-subset-range}

**When you see it:** Sweep plan chip — subset range extends above what the ROI can
fit.

**What to do:** Widen the ROI, lower the maximum subset in the sweep, or reduce step
so fewer grid points are required.

---

## sweep-empty-plan {#sweep-empty-plan}

**When you see it:** No valid subset × strain-window combinations for this ROI and
ranges.

**What to do:** Enlarge the ROI or narrow subset / VSG ranges until at least one
combination fits.

---

## engine-features {#engine-features}

**When you see it:** Engine failure — decorrelation / AKAZE could not match the pair.

**What to do:** Check focus and speckle; ensure reference and deformed frames are
the same scene; improve [speckle contrast](#speckle-contrast).

---

## engine-roi {#engine-roi}

**When you see it:** Engine failure — ROI held no valid points.

**What to do:** Enlarge ROI; confirm it lies on speckle; check subset fits inside ROI
([roi-too-small](#roi-too-small)).

---

## engine-init {#engine-init}

**When you see it:** Engine failure — decode or engine start failed.

**What to do:** Re-import images; confirm files are readable PNG/TIFF; free memory on
low-RAM devices.

---

## engine-convergence {#engine-convergence}

**When you see it:** Engine failure — too few subsets converged.

**What to do:** Improve speckle and lighting; try a larger subset or smaller step;
check for motion blur between frames.

---

## engine-vsg {#engine-vsg}

**When you see it:** Engine failure or hollow lattice nodes — strain window too large
for ROI/step, or no points survived VSG filtering.

**What to do:** Reduce strain window (VSG), enlarge ROI, or coarsen step. In sweeps,
tap a hollow node for its one-line reason; **Why?** opens this section.

---

## import-reference {#import-reference}

**When you see it:** Reference image failed to load or decode.

**What to do:** Try PNG/TIFF; avoid corrupted or unsupported RAW without conversion;
check storage permission.

---

## import-deformed {#import-deformed}

**When you see it:** One or more deformed frames failed to load.

**What to do:** Same as reference; ensure batch paths are stable and formats match.

---

## video-read {#video-read}

**When you see it:** Video metadata could not be read.

**What to do:** Re-copy the file; try a shorter clip; confirm the container is
supported on this device.

---

## video-extract {#video-extract}

**When you see it:** Too few frames extracted from the selected segment.

**What to do:** Widen the segment, lower sampling interval, or raise max frames in
Settings.

---

## no-batch-data {#no-batch-data}

**When you see it:** Result viewer opened without a `.dat` batch for this session.

**What to do:** Re-run analysis from the wizard, or open a session that completed
successfully.

---

## viewer-oom {#viewer-oom}

**When you see it:** Not enough memory to decode a full-field frame.

**What to do:** Close other apps; open a smaller analysis; reduce ROI or resolution
at capture.

---

## custom-scale {#custom-scale}

**When you see it:** Custom colour-scale min ≥ max.

**What to do:** Set min below max, or reset to auto scale.
