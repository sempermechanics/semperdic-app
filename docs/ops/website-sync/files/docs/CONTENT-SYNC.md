# Content sync — website ↔ app

This site documents the Semper Android app. Behaviour facts are duplicated by
hand in Astro pages, so they drift. This page is the map: which website page
mirrors which part of the app docs, and the exact facts that must stay
identical. Check it whenever you touch app-behaviour copy.

**Local snapshot (in this repo):** `docs/source/` holds copies of the app
operating manual, workflows, legal drafts, and images so you can review without
private-repo access. Refresh that folder when the app tip advances (see
`docs/source/README.md`).

The mirrored sources of truth are **`docs/source/OPERATING_MANUAL.md`** (the
operating manual, "OM" below), backed by **`docs/source/WORKFLOWS.md`** for
per-screen behaviour. Where those disagree with shipped UI, verify against the
Android app **code**, not docs.

## Page map

| Website page | Mirrors (snapshot) |
|---|---|
| `src/pages/manual/getting-started.astro` | OM §1 What it does, §2 Getting in (incl. Forgot password App Link, email-link sign-in, crash-report opt-in), §3 Images (incl. video) |
| `src/pages/manual/analysis.astro` | OM §4 Running an analysis (incl. **Paste params** / Reset), §5 Parameters, §6 Region of interest |
| `src/pages/manual/sweeps-results.astro` | OM §7 Sweeps (lattice scroll, View / Save graph, Isolate/Highlight, scrub slider, pinch/pan, copy params), §8 Reading results |
| `src/pages/manual/exports-manage.astro` | OM §9 Exports, §10 Managing analyses (Only in cloud, storage tools, transfer progress, Your data exports) |
| `src/pages/manual/faq.astro` | Conceptual questions — no single OM section |
| `src/pages/support/troubleshooting.astro` | OM §11 Troubleshooting table + app `strings.xml` messages |
| `src/pages/learn/dic.astro` | OM §1 + Appendix B glossary |
| `src/pages/learn/glossary.astro` | OM Appendix B |
| `src/pages/learn/parameters.astro` | OM §5 + Appendix A |
| `src/pages/download.astro` | App `build.gradle.kts` (minSdk/ABI/version), OM §2 |
| `src/pages/privacy.astro` | OM §2, §10 + `docs/source/legal/*` + live Firebase Hosting `/privacy/` and `/terms/` |

## Facts that must match — and where they are enforced in code

Do **not** trust a stale app `README.md` for these — it has gone stale before
(it lists subset 15–101; the real range is 15–121). Verify against the file
named in the Android app tree when you have access; otherwise treat OM + this
table as the public contract.

| Fact | Value | Enforced in (app tree) |
|---|---|---|
| Subset size range | 15–121, odd | `app/src/main/res/layout/activity_static_analysis.xml` (`subsetSlider` valueFrom/To) |
| Step size range | 1–30 | same layout (`stepSlider`) |
| Strain window range | 5–101, odd | same layout (`strainWindowSlider`) |
| Step default | 5 | OM §5 / Appendix A |
| Strain window default | 15 | OM §5 / Appendix A |
| Kernel | 4×4 Bicubic / 6×6 Keys | OM §5 |
| Max frames | 10–150, default 50 | `DicSettings.kt` (`MIN_MAX_FRAMES`, `DEFAULT_MAX_FRAMES`, `MAX_MAX_FRAMES`) |
| VSG formula | `(strain window − 1) × step + 1` | OM §5 |
| Decorrelation rule | 2 consecutive frames < 50% convergence | OM §4; `strings.xml` `error_low_convergence` |
| CSV columns | `x_px,y_px,u_px,v_px,exx,eyy,exy,znssd` (sweeps add subset, step, window) | OM §9 |
| minSdk / OS floor | 24 → Android 7.0 | `app/build.gradle.kts` (`minSdk = 24`) |
| Release ABI | arm64-v8a only (debug adds x86_64) | `app/build.gradle.kts` (`abiFilters` default `listOf("arm64-v8a")`) |
| Package ID | `com.indicvision.semper` | `app/build.gradle.kts` (`applicationId`) |
| Support email | `support@indicvision.com` | `src/site.config.json` here; `strings.xml` in the app |
| Animation playback floor | Android 9+ | OM §8 |
| Full privacy / terms (canonical) | Firebase Hosting on `indicvision-dic-app-auth` | `docs/source/legal/*.md` → Hosting `{privacy,terms}/` |

### Exact on-screen messages (Troubleshooting page)

The Troubleshooting page quotes app strings **verbatim** so phone-find matches
what the user sees. Sources in the app `res/values/strings.xml`:
`error_low_convergence`, `limit_title`, `limit_body`, `failed_decode_raw`,
`failed_load_reference`, `error_network_retry`, `error_startup_failed`,
`error_verify_failed`, `video_read_failed`, `video_extract_insufficient`,
`session_data_gone_title` / `_body`. If a string changes in the app, update the
verbatim quote here to match.

Note: `error_low_convergence` contains the app-side typo "spackle pattern". It is
quoted verbatim in the message block (for searchability) but **our own prose must
spell it "speckle"** — do not propagate the typo.

## Do NOT document (built but unreachable)

Per `docs/source/WORKFLOWS.md` §11, these exist in code but are not reachable by
users. Keep them off the site so they don't creep back in:

- Circle / ellipse / freeform ROI — only **Rect** and **Square** are exposed.
- A convergence-view plot — never built; only line-cut plots exist.
- Replayable coach marks — they show once and cannot be replayed.
- A dedicated restore screen — the empty-state "Restore" just opens Settings.

**Live (do document):** Google sign-in, email/password, **email sign-in links**,
and **Forgot password** App Links — Digital Asset Links for
`com.indicvision.semper` are deployed on the auth Hosting site. Treat all of
those as supported paths.
