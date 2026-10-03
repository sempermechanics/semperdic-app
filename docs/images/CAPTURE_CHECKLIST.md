# Screenshot recapture checklist

Most of the folder was recaptured on a debug build (Pixel 5 emulator,
`SEMPER_DEV_AUTH_BYPASS` on, local-only sessions) against the UI current as of
**2026-08-19**. A second, smaller pass on **2026-09-09** (headless Pixel 8
emulator) refreshed the three shots the camera removal and the speckle readout
made wrong. What's left below needs a signed-in account against a real
backend, which the recapture pass didn't have credentials for. Device: any
phone-class emulator or device at the project's debug build; light theme
unless noted. Keep the 460×1022 crop so the manual's `width="300"` doesn't
distort.

> **Theme mismatch, open:** the 2026-09-09/10 shots (`home.png`,
> `step2-parameters.png`, `speckle-warning.png`, `speckle-span-warning.png`)
> came off a dark-themed
> emulator, while the 2026-08-19 folder is light. They are scaled to 460×1022
> so they lay out correctly, but the manual now alternates light and dark
> mid-flow. Recapture all three in light theme on the next pass.

## Done

| File | Used in §. | State captured |
|---|---|---|
| `home.png` | §1 | *(2026-09-09, dark)* Empty state with the new plain **+** button, replacing the shot that still showed the camera-and-video FAB glyph. Still missing: session rows at all, and with them the **Synced** / **Pending** / **Only in cloud** badges — see below |
| `new-analysis-source.png` | §4 | Images tab open, gallery grid populated, a video tile showing its badge |
| `step1-frames.png` | §4 | Reference + 3 deformed frames loaded, order badges visible |
| `frame-order-menu.png` | §4 | The sort menu open over the loaded strip |
| `step2-parameters.png` | §4 | *(2026-09-09, dark)* Advanced parameters expanded, **Compute** button visible, and the muted speckle readout under the subset slider ("Speckle measures about 4.3 px across…") |
| `speckle-warning.png` | §4 (new) | *(2026-09-09, dark)* Step 1 with the over-resolved speckle chip — a 12.8 px pattern against the 9 px ceiling |
| `speckle-span-warning.png` | §4 (new) | *(2026-09-10, dark)* Step 2 with the subset-span chip under the slider — a 7.1 px pattern against a 15 px subset, asking for 23 |
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

Everything below needs `SEMPER_DEV_AUTH_BYPASS=false` plus `SEMPER_API_BASE_URL`
pointed at a live backend, and a signed-in, approved account with at least one
cloud-backed analysis. Don't fake these states — recapture once that account is
available.

| File | Used in §. | State to be in |
|---|---|---|
| `home.png` | §1 | A few sessions with mixed sync badges (**Synced** / **Pending** / **Only in cloud**) so the badge language is visible in one shot. The camera-glyph problem is fixed as of 2026-09-09, but that replacement is an empty-state shot, so the list itself is still undocumented |
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
