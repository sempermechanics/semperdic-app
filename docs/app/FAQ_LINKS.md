# Troubleshooting links from the app

Actionable errors and warning chips that can open the public Troubleshooting
page (`https://sempermechanics.com/support/troubleshooting/`). Every hop asks
first (leave-the-app confirm via `FaqRedirect.confirm`). Success toasts, coach
marks, and quota / sign-in / delete confirms do **not** link here.

The hosted page only has **section** fragment ids. Individual symptoms have no
anchors; if a section moves, update the matching `url_faq_*` string.

**Canonical text:** [FAQ.md](FAQ.md) in this repo — detailed per-topic copy for
publishers; live links use the section ids below.

| From | Trigger | String / surface | FAQ URL resource | Troubleshooting section |
|------|---------|------------------|------------------|-------------------------|
| Wizard step 1 | Lossy-format accuracy chip | `lossy_format_warning_fmt` + chip FAQ | `url_faq_jpeg` | `#loading` |
| Wizard step 1 | Low speckle / SSSIG chip | `texture_low_fmt` + chip FAQ | `url_faq_speckle` | `#loading` |
| Wizard step 2 | Frame-size mismatch chip | `frames_size_mismatch_fmt` + chip FAQ | `url_faq_frame_size` | `#loading` |
| Wizard step 2 | ROI smaller than subset | `roi_too_small` snackbar **Why?** | `url_faq_roi_too_small` | `#setup` |
| Wizard sweep | Subset range above ROI | sweep plan chip | `url_faq_sweep_subset_range` | `#reading` |
| Wizard sweep | Empty plan (no combinations) | sweep plan chip | `url_faq_sweep_empty_plan` | `#reading` |
| Wizard (run) | Engine failure dialog | `EngineFailure.reasonRes` + **Why?**; after dismiss, ⓘ beside `tvStaticResult` | `url_faq_engine_features` / `_roi` / `_init` / `_convergence` | `#during-a-run` |
| Wizard (run) | Engine VSG failure | same as above | `url_faq_engine_vsg` | `#reading` |
| Wizard import | Reference decode / load failed | `failed_load_reference` / `failed_decode_raw` snackbar **Why?** | `url_faq_import_reference` | `#loading` |
| Wizard import | Deformed batch load failed | `error_loading_images` snackbar **Why?** | `url_faq_import_deformed` | `#loading` |
| Wizard video | Meta read failed | `video_read_failed` snackbar **Why?** | `url_faq_video_read` | `#loading` |
| Wizard video | Extract produced too few frames / error | `video_extract_insufficient` / `video_read_error` snackbar **Why?** | `url_faq_video_extract` | `#loading` |
| Result viewer | No `.dat` batch on open | `no_batch_data` snackbar **Why?** | `url_faq_no_batch_data` | `#reading` |
| Result viewer | OOM while loading a frame | `viewer_frame_oom` snackbar **Why?** | `url_faq_viewer_oom` | `#reading` |
| Result viewer | Custom scale min ≥ max | `invalid_scale_inputs` snackbar **Why?** | `url_faq_custom_scale` | `#reading` |
| Result viewer | Strain field floor caption | `capture_noise_floor_readout` / `CaptureNoiseFloor.warning()` | `url_faq_noise_floor` | `#setup` |
| Capture test shot | Floor **pass** dialog | Large value + `capture_noise_floor_body`; **ⓘ** (does not dismiss) | `url_faq_noise_floor` | `#setup` |
| Capture test shot | Floor **fail** / drift / unsettled | `capture_noise_erroneous_*` / unsettled / drift; **Why?** (does not dismiss) | `url_faq_noise_floor` | `#setup` |
| Capture test shot | Speckle-fail dialog | **Why?** | `url_faq_speckle` | `#loading` |
| Capture test shot | Burst would not correlate | `capture_noise_uncorrelated_*`; **Why?** beside **Change resolution** / **Record anyway** | `url_faq_speckle` | `#loading` |
| Capture test shot | Speckle outside the 3-9 px band | `capture_speckle_under_*` / `capture_speckle_over_*`; **Why?** | `url_faq_speckle` | `#loading` |
| Capture test shot | HAL refused settings | `capture_isp_warn_more` snackbar | `url_faq_imaging_pipeline` | `#setup` |
| Capture test shot | Burst frames smoothed | `capture_denoise_warn` snackbar | `url_faq_imaging_pipeline` | `#setup` |
| Lattice | Hollow node tap | short reason dialog **Why?** | same `url_faq_engine_*` as the node code | `#during-a-run` or `#reading` for VSG |
| Lattice | All combinations failed (summary line) | tap `vsg_lattice_all_failed` | `url_faq_engine_vsg` | `#reading` |

---

## Related in-app copy (2026-08-28)

Measurement floor dialog strings (`capture_noise_floor_*`):

- **Pass:** label `capture_noise_floor_label`, body `capture_noise_floor_body`, button `capture_noise_continue`
- **Fail:** title `capture_noise_erroneous_title`, body `capture_noise_erroneous_body` (floor value in layout, not repeated in body)
- **Viewer:** `capture_noise_floor_readout` under strain colour bar

Deep dive in repo (not linked from app):
[NOISE_FLOOR_STRAIN_ACCURACY.md](NOISE_FLOOR_STRAIN_ACCURACY.md) (canonical lighting /
setup report).
