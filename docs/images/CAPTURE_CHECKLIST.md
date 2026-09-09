# Screenshot recapture checklist

Most of the folder was recaptured on a debug build (Pixel 5 emulator,
`INDIC_DEV_AUTH_BYPASS` on, local-only sessions) against the UI current as of
**2026-08-19**. What's left below needs a signed-in account against a real
backend, which the recapture pass didn't have credentials for. Device: any
phone-class emulator or device at the project's debug build; light theme
unless noted. Keep the 460×1022 crop so the manual's `width="300"` doesn't
distort.

## Done

| File | Used in §. | State captured |
|---|---|---|
| `home.png` | §1 | Home with two local sessions, both showing an **upload pending** badge (cloud backup is on but the bypass build has no backend to actually sync to). Still missing: **Synced** / **Only in cloud** badges — see below |
| `new-analysis-source.png` | §4 | Images tab open, gallery grid populated, a video tile showing its badge |
| `step1-frames.png` | §4 | Reference + 3 deformed frames loaded, order badges visible |
| `frame-order-menu.png` | §4 | The sort menu open over the loaded strip |
| `step2-parameters.png` | §4 | Advanced parameters expanded, **Compute** button visible |
| `running.png` | §4 | **# converged** / **convergence** tiles showing |
| `roi-editor.png` | §6 | Draw + Crop mode, one rectangle drawn, HUD showing `W × H at (x, y)` |
| `step3-sweep.png` | §7 | Ranges populated, planned lattice visible, **Compute** button |
| `result-lattice.png` | §7 | Summary line above the lattice, hollow (skipped) nodes, **All / Node** pill visible |
| `result-viewer.png` | §8 | Exx strain field with full chrome: back · title · ⓘ · Home · share along the top, field pills + scale + scrub along the bottom |
| `settings-used.png` | §8 | ⓘ details sheet on a sweep result, line-cut section showing. Still missing: a run that stopped early, to show the "Stopped early / Frames solved" rows |
| `home-selection.png` | §10 | Two rows selected, bar reads "2 selected" |
| `settings.png` | §10 | Settings scrolled to **Analyses data management**, expanded. Still missing: a row with all three actions (Download / Restore / Delete) and the transfer banner — see below |
| `media-picker-files-saf.png` | §4 (new) | The **Files** tab handing off to the system file browser |
| `media-picker-permission-empty.png` | §4 (new) | The Images tab's empty state with **Allow access**, before media permission is granted |

`viewer-tools.png` was deleted — confirmed unreferenced anywhere in
`OPERATING_MANUAL.md` before removal.

## Still needs a real backend + account

Everything below needs `INDIC_DEV_AUTH_BYPASS=false` plus `INDIC_API_BASE_URL`
pointed at a live backend, and a signed-in, approved account with at least one
cloud-backed analysis. Don't fake these states — recapture once that account is
available.

| File | Used in §. | State to be in |
|---|---|---|
| `home.png` | §1 | A few sessions with mixed sync badges (**Synced** / **Pending** / **Only in cloud**) so the badge language is visible in one shot |
| `delete-dialog.png` | §10 | The delete choice dialog, on a row with both local and cloud copies, so it reads **Delete device** / **Delete cloud** |
| `settings.png` | §10 | **Analyses data management** with a row showing all three actions — Download / Restore / Delete — and, if a transfer is running, the top transfer banner (§4.0 of WORKFLOWS.md) |

## New screens with no screenshot yet

- The Settings **Download** flow: the SAF save-location picker, and a row mid-download. *(cloud-dependent, see above)*
- The non-modal **transfer banner** itself (Settings and the viewer), ideally
  mid-transfer with the ‹ › paging visible on two concurrent jobs. *(cloud-dependent, see above)*
- The viewer's **Send to** sheet for a slow export (opens before generation —
  distinct from the single-photo path, which still builds first). Attempted
  this pass but not captured: the viewer's custom chrome icons (ⓘ / Home /
  share) registered inconsistently under `adb shell input tap` on the Pixel 5
  emulator used here — taps sometimes landed on the underlying image as a
  tap-to-probe instead of the icon beneath the cursor. Worth a retry on a
  different AVD or with a physical device.

## Record for analysis — no screenshot yet (added 2026-09-09)

The whole Record-for-analysis flow has never had a screenshot in this folder,
and `fix/capture-dic-good-practice-gates` adds six screens to it. None of these
need a backend or an account — a phone with a camera and a speckled specimen is
the whole rig — but they do need a *real* one: the sizes offered, the frame
cost and the measured speckle diameter all come from the device in front of the
user, so an emulator produces a plausible-looking screenshot of numbers that
mean nothing.

| Proposed file | Would be used in | State to be in |
|---|---|---|
| `capture-setup-no-rate.png` | WORKFLOWS §3b (3b.2b2/3b.2b3) | The refusal at a resolution nothing can shoot at 1 fps: the one-line message naming what binds, **Continue** visibly dimmed |
| `capture-speckle-under.png` | FAQ `#speckle-contrast`, WORKFLOWS 3b.6f3 | Under-resolved dialog: measured diameter, the 3–9 px band, the size that reaches 5 px, the mm line, **Change resolution** / **Record anyway** / **Why?** |
| `capture-speckle-over.png` | same | Oversampled dialog on the largest offered size — the case where a *lower* resolution is recommended and the fps comes back |
| `capture-speckle-unreachable.png` | WORKFLOWS 3b.6f3c | A pattern too fine for any offered size: no promise made, the closest size named, **Change resolution** still present |
| `capture-burst-uncorrelated.png` | FAQ, WORKFLOWS 3b.6 | The INSUFFICIENT dialog — frames saved, correlation failed — rather than the old "No photo was saved" |
| `capture-noise-floor-heatmap.png` | OPERATING_MANUAL, WORKFLOWS 3b.6f6 | The noise-floor dialog with the sigma heat map composited over the burst frame, legend showing both ends in px and µε |

The mm line in the two speckle dialogs reads **not available** on most phones.
Capture it in that state as well as, not instead of, a phone that reports a
focus distance — it is the common case and it must not read as an error.
