# On-device characterization: cloud, compute, space

Component-level cost of the four core operations — **analysis, screen refresh, backup,
restore** — measured against the real native engine, real backend
(`https://semper-gw-86wx7pp1.an.gateway.dev`), and real Google Drive/Firestore, driven
headlessly via [`SyntheticWorkloadDriver`](../../app/src/androidTest/java/com/sempermechanics/semper/perf/SyntheticWorkloadDriver.kt)
and captured by a local `characterize.sh` harness (never committed — the driver above
is the reproducible half).

**Device:** Pixel 6 (`oriole`), arm64-v8a, wireless-debugging (adb-over-Wi-Fi).
**Build:** debug-cloud (`SEMPER_DEV_AUTH_BYPASS=false`, prod `SEMPER_API_BASE_URL`) —
native `.so` is `-O3` in every variant, so compute numbers are representative; the
Kotlin layer is debug (unoptimized), so Kotlin-side component times are an upper bound,
not a release number.
**Backend:** `https://semper-gw-86wx7pp1.an.gateway.dev` (Cloud Run + Firestore + Drive,
prod project).
**Repeats:** 2 for light scale, 1 for large scale (large scale's per-frame cost made
higher repeat counts impractical in-session — see the LARGE scale note below).

## Scales

| | frames | step | subset | strainWin | use6x6 | image |
|---|--:|--:|--:|--:|:--:|---|
| **LIGHT** | 1 | 6 | 21 | 15 | no | 320×320 |
| **LARGE** | 20 (see note) | 2 | 81 | 15 | yes | 640×640 |

**LARGE frame count note:** the app's real ceiling is 150 frames; this run uses 20.
Measured per-frame wall time across two independent 20-frame runs (analysis/large's
last 2 frames + backup/large's last 13 frames — the rest scrolled out of the logcat
ring buffer on the longer run): **34.0–51.6s/frame, mean ≈ 37s/frame**, with no clear
trend by frame position (frame 18 was the fastest in one run and the slowest in the
other) — the spread looks like ordinary background-app CPU contention on a
general-purpose phone, not thermal throttling or an engine regression. 150 frames at
the observed mean would be **~92 minutes for `analysis` alone**, and
`screen`/`backup`/`restore` each build their own fresh session first. The per-frame
compute numbers below are real and extrapolate roughly linearly to 150 frames (native
engine solves each frame independently — no amortization from a longer sequence);
anything that does *not* scale linearly (cloud transfer, zip build) is called out
explicitly.

**A real bug found and fixed by this exercise:** the driver's original LIGHT-scale
`step=20` grid was too sparse for the AKAZE-seeded points to land near enough to any
analysis-grid vertex for RGDIC to propagate from — it converged on **zero points**,
regardless of `subset` (`step=20/subset=21` and `step=20/subset=31` both failed;
`step=6` is the sparsest configuration that converges reliably against this synthetic
speckle). This is a real, load-bearing characteristic of the engine's seeding/mesh
relationship, not a driver-only artifact — noted here because it shapes what a genuinely
"light" workload can look like.

## Component tables

Times are t_ms from the `SWD|` markers; single representative run shown where repeats
agreed within measurement noise (session-record I/O varies ±50-100ms run to run — GC/IO
jitter on a live device, not a component regression).

### Analysis

| Component | LIGHT (1 frame) | LARGE (per frame, frame 0 cold + steady-state) |
|---|--:|--:|
| image/reference encode+decode | 29 ms | 142–178 ms (640×640 PNG) |
| `initializeReference` (native) | 4 ms | 22–25 ms |
| AKAZE + RANSAC seeding | 17–41 ms | 145 ms (frame 0) |
| Hessian pre-pass (OpenMP) | 17–39 ms | 2,290 ms (frame 0) |
| Delaunay mesh | 0.4 ms | 12 ms (frame 0) |
| ICGN + RGDIC propagation (implicit — no finer seam) | ~65 ms | **~32,350 ms (frame 0) / ~34,000–39,700 ms steady-state** |
| VSG strain | 0.3 ms | 45 ms (frame 0) |
| **native solve wall time** | **~100–130 ms** | **34.0–51.6s/frame, mean ≈ 37s (20-frame sample, see note above)** |
| `.dat` write (I/O) | 0.4 ms | not separately measured at this scale (bundled into the per-frame total) |
| record persist (once, end of run) | 22–89 ms | 93–111 ms (all 20 frames) |
| **points converged / grid** | 1,764 / 2,809 (62.8%, 91.1% accept rate) | 65,025 / 102,400 (63.5%, 95.5% accept rate, every frame) |
| **total `analysis` op (20 frames)** | — | **890 s (~14.8 min)** |
| session on-disk footprint (20 frames) | — | 49,194,564 bytes (~46.9 MB): dat=41,616,000, raw_deformed=7,233,203, reference=345,361 |

**Where the cost lives:** at LIGHT scale, the engine stages (AKAZE/Hessian/Delaunay) and
image I/O are comparable order-of-magnitude — no single stage dominates. At LARGE scale,
**ICGN/RGDIC propagation is >90% of wall time** (frame 0: 32.3s of 34.8s) — the
6-parameter shape function (`use6x6`) over 65K converged points is the entire story;
AKAZE/Hessian/Delaunay (the "setup" stages) stay under 2.5s combined even at this scale,
and stayed roughly flat across the run (only the propagation stage is folded into the
34–51.6s per-frame spread above, since only frame 0 got the full stage breakdown before
the log line scrolled out of the ring buffer on later frames).

### Screen refresh

Adds to a completed analysis:

| Component | LIGHT (1 frame, 1,764 pts) | LARGE (20 frames, 65,025 pts/frame) |
|---|--:|--:|
| `.dat` decode | 0.14–0.18 ms | 3.35 ms |
| field stats (percentile extrema) | 1.6–7.0 ms | 120.39 ms |
| heatmap generate (indices + jet raster) | **173–180 ms** | 120.71 ms |
| whole-batch summary pre-pass (`valueRanges` over all frames) | not applicable (1 frame) | 994.99 ms (all 20 frames) |
| **total `screen` op (on top of a fresh 20-frame analysis)** | — | **697 s (~11.6 min)**, of which ~1.24s is the four viewer components above — the analysis rebuild dominates |

**Where the cost lives:** heatmap generation dominates screen refresh at LIGHT scale by
~25-100x over decode/stats. At LARGE scale the relationship flips: field stats and
heatmap generation become comparable (both ~120ms), because field-stats cost scales
with point count (65K vs 1.7K, a ~37x increase driving a ~20-75x time increase) while
heatmap render cost is more resolution-bound and grows more slowly. The whole-batch
summary pre-pass (once per session open, not per-frame-toggle) is the single largest
screen-refresh component at LARGE scale — ~1s to scan value ranges across 20 frames.
None of this competes with the ~690s of underlying analysis compute needed to have a
session to view in the first place — screen-refresh cost is negligible next to
producing the data it displays.

### Backup

Adds to a completed analysis:

| Component | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| build `Session.zip` (restore-essential: raw + dat) | 11–41 ms, 230,325 bytes | 1,146 ms, 49,199,096 bytes |
| full upload worker (create-session → per-object Drive transfer → complete, **including real report/heatmap generation for Extras.zip**) | **13.2–15.6 s** | **118.8 s (~2.0 min)** |
| peak PSS during backup (device-wide, `dumpsys meminfo`) | not captured (op too fast for the 500ms poll interval) | **496,145 KB (~485 MB)** |
| session on-disk footprint after backup (session dir + staged zip, before cleanup) | 460,298 bytes | 98,393,660 bytes (~93.8 MB) |

The upload total is dominated by fixed round-trip costs (session create, per-object
Drive resumable-upload handshakes, complete calls, device-side report/PDF/heatmap
generation) at LIGHT scale — 13-15s for ~1.3MB is far slower than the connection's raw
throughput. At LARGE scale (~50MB uploaded in ~119s ≈ 420 KB/s effective) the fixed
overhead is a much smaller fraction of the total; the wall time is dominated by actual
transfer + the larger Session.zip build (1.1s) + real 20-frame report/heatmap
generation for Extras.zip.

### Restore

| Component | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| list manifest | 391–424 ms | 468 ms |
| full restore worker (metadata + bundle download, unpack, CRC verify, index upsert) | 5.6–6.1 s | 333.0 s (~5.6 min) |
| peak PSS during restore | not captured (op too fast) | 258,408 KB (~252 MB) |
| **bytes downloaded vs whole backup** | **231,660 of 1,293,753 (17.9%) — an ~82% saving** | **49,202,702 of 195,060,345 (25.2%) — a ~75% saving** |
| restored session on-disk footprint | 231,307–231,308 bytes | 49,198,170 bytes (byte-equal to the original 49,194,564 + metadata) |

**This is the split feature's core payoff, measured on real cloud, real production
code paths, at both scales:** LIGHT skips ~82% of its own backup on restore; LARGE
skips ~75%. Both are well above the ~26% figure from the existing 50-frame/typical-size
reference point — this synthetic workload's imagery (640×640 LARGE, 320×320 LIGHT,
dense speckle) produces derived deliverables (PDF report + several heatmap PNGs per
frame) that are proportionally larger than a typical real session's, so treat the
82%/75% figures as characterizing *this* workload's derived-artifact ratio, not a
universal constant — the ~26% reference point (real report/heatmap sizes on a real
50-frame batch) is the one to use for realistic session-size planning. What's confirmed
at both scales: the split reliably saves a large, non-trivial fraction of restore
bandwidth, and the saving does **not** shrink as frame count grows 20x (LIGHT 82% →
LARGE 75%, not a collapse toward 0%) — restore-essential (raw+dat) and derived
(csv+reports+processed) both scale with frame count in this workload, so the ratio
between them stays in the same broad band.

### Space (cross-cutting)

| Artifact | LIGHT (1 frame) | LARGE (20 frames) |
|---|--:|--:|
| `reference.png` | 87,114 (320×320) | 345,361 (640×640) |
| `raw_deformed/` | 86,411 (1 frame) | 7,233,203 (20 frames, ~362 KB/frame) |
| `.dat` files | 56,448 (1,764 pts × 32B) | 41,616,000 (20 × 65,025 pts × 32B, exact) |
| **local session total** | 229,973 | 49,194,564 (~46.9 MB) |
| `Session.zip` (staged for upload) | 230,325 | 49,199,096 |
| `Extras.zip` (derived, from manifest total − Session.zip − metadata) | 1,062,093 (~1.04 MB) | 145,857,643 (~139 MB) |
| session dir during backup (session + staged zip) | 460,298 | 98,393,660 (~93.8 MB) |
| restored session total | 231,307 (= local total + `metadata.json`, byte-equivalent) | 49,198,170 (byte-equivalent) |

## Cloud components vs free-tier limits

Endpoint/Firestore-write counts below are derived from the backend source
(`backend/app/routers/`, `firestore_repo.py`) rather than live-measured per-call —
**approximate, order-of-magnitude**, not exact. GCP always-free quotas as commonly
published (verify current values at gcp-quota-review time — these change):
Cloud Run ~2M requests/mo + ~1 GiB egress/mo (NA egress only), Firestore 50K reads /
20K writes / 20K deletes per day + 1 GiB stored, Drive 15 GB/account.

**⚠️ Confirmed via `gcloud run services list`: production (`indic-api` at the time; renamed `semper-api` in #146, 2026-09-23) runs in
`asia-south1`.** GCP's always-free Cloud Run egress tier is **North America only** — so
the "~1 GiB/mo free egress" figure above **does not apply to this deployment at all**.
Every byte this section counts against that free allowance is actually **billed
egress** (Cloud Run's `asia-south1` egress-to-internet rate, ~$0.12/GiB at the time of
writing — verify current pricing). The "#restores/mo before exhaustion" framing below
is retained because it still correctly identifies the *binding resource* (egress, by a
wide margin over every other quota), but every occurrence should be read as "before
this much billed cost accrues," not "before a free allotment runs out." This changes
the restore-egress finding from a capacity ceiling to a **recurring cost driver** — the
backup/restore split's 75-82% saving is a proportional cost reduction, not just a
capacity extension.

### Backup

Uploads go **device → Drive directly** (bytes bypass Cloud Run):

| Resource | per backup (3 objects: metadata + Session.zip + Extras.zip) |
|---|--:|
| Cloud Run requests | ~5 (`POST /v1/sessions` + 3×`POST /v1/files/{id}/complete` + 1 provision task) |
| Firestore writes | ~10-12 (session doc create+update, 3 file docs create, 3 file docs complete-update, ±session status update) |
| Drive storage | full backup size — measured LIGHT (1 frame): 1,293,753 bytes (~1.23 MB); LARGE (20 frames): 195,060,345 bytes (~186 MB) → **~9.75 MB/frame** at this workload's image size/density |

At ~5 requests/backup, Cloud Run's 2M req/mo free tier supports **~400,000 backups/mo**
before request quota binds — not the limiting factor. At ~11 writes/backup, Firestore's
20K writes/day supports **~1,800 backups/day** — this is the tighter cloud-request
constraint for backup. **Drive's 15 GB/account is the real binding constraint for
backup at scale**: at the measured ~9.75 MB/frame, a 15 GB account holds roughly
**~1,540 frames total** across all sessions — for a single 150-frame session at this
workload's density (~1.46 GB), that's **~10 such sessions per account** before Drive
storage exhausts. (This workload's 640×640/dense-speckle images run larger than a
typical real session; treat this as a stress-case lower bound, not a typical-case
number — the existing 50-frame/386MB reference point, ~7.7 MB/frame, is closer to real
usage and implies ~1,950 frames / ~13 typical 150-frame sessions per account.)

### Restore

Downloads **proxy through Cloud Run** (`GET /v1/files/{id}/content` → streamed) — so
restore spends **Cloud Run egress**, the scarcest quota:

**Measured directly from production Cloud Run access logs** (`gcloud logging read`,
filtered by session id and by request URL/method — not estimated from source), for one
LIGHT and one LARGE restore, each isolated to its exact time window:

| Resource | LIGHT (1 frame) | LARGE (20 frames) | Source |
|---|--:|--:|---|
| `GET /v1/sessions/{sid}/files` (list manifest) | 2 | 2 | access log, per restore |
| `POST /v1/challenge` (one per download window) | 2 | ~48 | access log, per window |
| `GET /v1/files/{id}/content` (download) | 2 | ~48 | access log, per window |
| **Total Cloud Run requests** | **6** | **~98** | sum of the above |
| Cloud Run egress (response bytes, `/content` only) | 231,660 B | 49,227,140 B (~46.9 MB) | access log `responseSize`, summed |

The LARGE figure is the average of two independent real restores of the same session
(96 total `/content` requests logged for that session id across both, 98,454,280 bytes
— divided by 2), and matches the driver's own logged "downloaded 49,202,702 bytes"
almost exactly, cross-validating both measurement paths. **This confirms the original
"~2 requests" estimate in this report was wrong by more than an order of magnitude for
LARGE restores** — the driving cause is the 1 MiB fixed download window (finding 1
below): each window costs its own attestation challenge, so a 47 MB restore is not 2
requests, it's essentially `⌈payload / 1 MiB⌉ × 2 + 2`.

At 20 frames → ~98 requests, a 150-frame restore extrapolates to roughly **~706
requests** (⌈150-frame Session.zip / 1 MiB⌉ × 2 + 2, using this workload's measured
**~2.46 MB/frame Session.zip density** — the restore-only payload, not the ~9.75
MB/frame *total backup* density quoted in the Backup section above, which includes the
never-downloaded Extras.zip). Cloud Run's ~2M req/mo free tier still comfortably
absorbs that volume — request *count* was never the binding resource. **Firestore is
the one this finding newly implicates**: each challenge is a nonce write + delete
(`issue_nonce`/`consume_nonce`), so ~48 windows is ~96 Firestore writes for a single
LARGE restore, on top of ~48 unconditional `lastSeenAt` updates on the *production*
backend measured here (Phase 1.4's throttle is written but **not yet deployed** — see
below) — **on the order of 150 Firestore writes for one 20-frame restore**, before
counting reads. At Firestore's 20K writes/day free tier, that is a **materially
tighter** ceiling than request count ever suggested, and scales the same way egress
does: linearly with `⌈payload / window size⌉`.

**Cloud Run egress is billed, not free** (see the region note above) — at ~46.9 MB per
20-frame restore, a 150-frame restore costs roughly ~352 MB egress (linear
extrapolation at this workload's density), and the same fixed-window inefficiency that
inflates Firestore writes does **not** inflate egress further — egress is the payload
size regardless of window count. So the split's 75-82% saving is a direct, proportional
cut to a real per-restore cost, while the request/Firestore-write finding is a
*separate* problem the split does not address: Phase 1.1's adaptive window (already
committed, not yet deployed to the device fleet) is what fixes *that* — it converges
toward 16 MiB windows on a fast link, which would cut the ~48-window LARGE case to ~3-4
windows, i.e. **~8-10 requests instead of ~98**, and a proportional ~16-20x cut to the
Firestore-write count above.

**Binding constraints, from this measurement:** Cloud Run egress is the dominant
*billed cost* for restore at any session size (region confirmed non-free); Firestore
writes from the per-window challenge pattern are the dominant *quota-exhaustion risk*
until Phase 1.1 ships to devices. Both point at the same fix — download in fewer,
larger windows — which is already implemented and gated behind a device rebuild.

## Summary: where the cost lives, by operation

| Operation | LIGHT total | LARGE total (20 frames) | Dominant component |
|---|--:|--:|---|
| analysis | ~130 ms | 890 s (~14.8 min) | ICGN/RGDIC propagation (>90% of native solve time) |
| screen (on a fresh session) | ~140 ms on top of analysis | 697 s (~11.6 min), ~1.2s of which is viewer work | Same as analysis — screen-refresh itself is negligible |
| backup (on a fresh session) | ~13.3 s on top of analysis | ~120 s on top of analysis | Fixed round-trip overhead at LIGHT; real transfer + report/heatmap generation at LARGE |
| restore (from a fresh backup) | ~5.9 s on top of backup | ~333 s (~5.6 min) on top of backup | Cloud Run egress-bound download (post-split) |

**Bottom line:** at any scale, the native DIC solve (specifically ICGN/RGDIC
propagation under a 6-parameter shape function) so thoroughly dominates every other
component that screen-refresh and zip-build costs are noise by comparison. The
backup/restore split's ~75-82% egress saving is the single largest lever available on
the *cloud* side; it does not touch the compute side, where the only lever is workload
parameters (`step`/`subset`/`use6x6`/frame count) — the same knobs this report found
have a hard floor below which the engine simply fails to converge (see the LIGHT-scale
seeding note above).

## Before → after: measured impact of Phase 1/2/4

Real captures, both targets, both before Phase 1/2/4 landed (app `3428584`, engine
`f954cab`, backend `indic-api-00063-lmk`) and after (app HEAD, engine
`eb36c02`→`a2c2106`). Provenance for every "before" build was verified empirically —
pulled the installed APK, dex-grepped for Phase 1/4 markers (absent), cross-checked
install timestamps against commit timestamps and the live Cloud Run revision — not
inferred from the working tree, after that inference was caught being wrong once
mid-session (`perf-runs/before-*/PROVENANCE.txt`).

**Status: partial.** The account's cloud session quota (25/25 stored analyses) was
exhausted by the volume of testing this exercise itself generated, blocking `restore/large`
entirely on both targets and most cloud ops on the emulator's after-run. Numbers below are
real measurements, not estimates; anything not yet captured is marked `pending`, not filled
with a guess.

### Time — Pixel 6 (arm64/NEON)

| Metric | Before | After | Δ |
|---|--:|--:|--:|
| analysis, LIGHT | 4 s | 4 s | ~none |
| analysis, LARGE (20 fr) | 696 s | 1,108 s | **+59%** — see note below |
| screen, LIGHT | 4 s | 5 s | ~none |
| screen, LARGE | 889 s | 1,144 s | **+29%** — same note |
| backup, LIGHT | 18 s | 16 s | ~none |
| backup, LARGE | 1,211 s | 1,265 s | within before's own run-to-run spread (882–1,299 s) |
| restore, LIGHT | 25 s | 22 s | slightly faster |
| restore, LARGE | 1,353 s (median of 2 successful reps) | 1,249 s (mean of 2 successful reps: 1,434/1,064 — rep 3 quota-failed) | ~8% faster, but see the spread note below |

### Time — Emulator (x86_64/SSE)

| Metric | Before | After | Δ |
|---|--:|--:|--:|
| analysis, LIGHT | 2 s | 2 s | ~none |
| analysis, LARGE (20 fr) | 221 s | 245 s | **+11%** |
| screen, LIGHT | 2 s | 2 s | ~none |
| screen, LARGE | 223 s | 233 s | **+4.5%** |
| backup, LIGHT | 19 s | *pending* — all 3 reps quota-failed across two retry attempts | — |
| backup, LARGE | 313 s (median of 3) | 400 s (1 successful rep of 3 — reps 1–2 quota-failed) | not directly comparable (n=1) |
| restore, LIGHT | 26 s (median of 3) | 25 s (median of 3) | ~none |
| restore, LARGE | 526 s (median of 2 successful reps) | 416 s (median of 3, all succeeded) | ~21% faster |

**On the LARGE analysis/screen slowdown.** Both targets got *slower* on the pure-compute
path, not faster, which is worth stating plainly rather than glossing over. Three
candidate causes, weighed against the evidence:

1. **Phase 4.2 added real work to the analysis loop** — a sigma-clamped percentile
   pass (accepted-point extraction + two quickselects) per field per frame, where
   before there was none. Ruled out as the primary cause by magnitude: ~5 fields ×
   20 frames × ~65k points is on the order of 6.5M comparisons total, low tens of
   milliseconds even unoptimized — nowhere near the ~400 s (Pixel 6) or ~24 s
   (emulator) observed deltas.
2. **A Phase 2 engine regression.** Considered and set aside: every Phase 2 change was
   dead-code/redundant-work removal (a mutex protecting values nobody reads, a
   rescan already computed elsewhere, a proven-safe lock removal) — none plausibly
   *adds* work, and the golden-corpus/host-suite verification for Phase 3 (built on
   top of the same Phase 2 base) shows no timing anomaly on the host.
3. **Thermal/session-order confound.** Most consistent with the evidence: both
   captures ran for *hours* of continuous LARGE-scale native compute today (before,
   then after, on the same physical devices, same session), and the Pixel 6 — a
   phone SoC under sustained multi-threaded OpenMP load — shows a far larger
   slowdown (+59%/+29%) than the emulator (+11%/+4.5%), which runs on a
   presumably better-cooled desktop CPU. A late-session, heat-soaked "after" run
   following an early-session, cool "before" run is exactly the pattern thermal
   throttling produces.

None of these is proven from today's data alone — that needs a **clean, isolated
re-measurement**: idle device, back-to-back before/after with no hours-long gap between
them, ideally with thermal state logged. Until then, treat the LARGE-scale compute
deltas above as *not yet attributable*, not as a confirmed regression or a confirmed
non-issue.

**On Pixel 6 `restore/large`'s wide after-spread (1,434s vs 1,064s, a ~35% swing between
the two successful reps).** Restore is network-bound, not compute-bound, so this reflects
real Wi-Fi/Cloud-Run variance on the day, not the code — the same class of variance the
*before* capture's own backup/large numbers showed (882–1,299 s). Not evidence either way
on Phase 1.1's adaptive-window change; a controlled network-quality comparison would be
needed to isolate that, which this capture doesn't provide.

**On the emulator's `restore/large` improvement (526s → 416s, ~21% faster, 3 clean reps
each side).** More suggestive than the Pixel 6 number since both sides have a full 3-rep
sample here, but still not attributed to a specific change — the emulator's network is
host-proxied (per this report's own standing caveat), so this could reflect Phase 1.1's
adaptive download window, host machine load differing between when before/after ran today,
or both. Directionally consistent with Phase 1.1's intent (fewer, larger download windows
on a fast link), not proof of it in isolation.

### Space — both targets agree exactly (as they should)

Byte counts are ISA-independent, so hardware and emulator matching is itself a
correctness check, not just a convenience. Every value below is **identical** across
both targets and both before/after — confirming the `.dat` codec's encode side is still
correctly disabled (no size drift from Phase 1.3) and nothing else silently changed
on-disk or in-transit payload sizes:

| Metric | Value (all 4 captures) |
|---|--:|
| Local session total, LARGE | 49,194,564 B |
| `.dat` total | 41,616,000 B |
| `raw_deformed/` total | 7,233,203 B |
| `reference.png` | 345,361 B |
| `Session.zip` | 49,199,096 B |
| Restored session footprint, LARGE | 49,198,165–49,198,177 B *(all 4 captures that completed restore/large — before and after, both targets — agree to within a few bytes of session-metadata JSON)* |

Phase 4.2's new `field_ranges.bin` sidecar (~1 KB for a 20-frame LARGE session) doesn't
show up in the totals above — `duSummary`'s session-footprint accounting sums named
categories (`dat`/`reference`/`raw_deformed`) rather than every file on disk, so a small
untracked sidecar is invisible to it by design, not evidence it's missing.

### What's still pending

- ~~**Pixel 6 `restore/large`**~~ — resolved: quota freed up mid-session, rep 1/2
  succeeded (rep 3 quota-failed again), numbers folded into the table above.
- ~~**Emulator cloud ops, after-run**~~ — resolved after two retries: `restore/light`
  and `restore/large` both completed cleanly (3/3 reps); `backup/light` never got a
  rep through (3 quota-fails across two attempts) and `backup/large` only got 1 of 3,
  so those two rows stay a single sample rather than a median.
- **Cloud request/Firestore-write counts, before vs after** — Phase 1.4's backend
  changes (throttled `lastSeenAt`, deduped audit log, batched file writes) aren't live
  in production yet (still on the pre-1.4 revision, `indic-api-00063-lmk`), so a GCP-metrics
  comparison would currently show the *app-side* Phase 1.1 savings (fewer, larger
  download windows) but not the *backend-side* Phase 1.4 savings — deploying is a
  separate, already-scoped decision.
- **A clean thermal-isolated re-measurement** of LARGE analysis/screen, per the note above.

## Caveats

- Single device (Pixel 6, `oriole`), single account, single backend project. No
  cross-device or cross-account variance data.
- Kotlin-layer component times are from a **debug** build (unoptimized) — an upper
  bound, not a release number. The native `.so` is `-O3` in every build variant, so
  compute-heavy numbers (the vast majority of total time here) are representative.
- Repeats: 2 for LIGHT, 1 for LARGE (LARGE's per-op runtime, up to ~15 min per run, made
  more repeats impractical in-session). LIGHT repeats agreed within normal run-to-run
  jitter (record-persist I/O ±50-100ms); LARGE per-frame solve time varied 34-51.6s
  across two independent 20-frame runs with no clear pattern by frame position —
  consistent with ordinary background-app CPU contention on a general-purpose phone,
  not a driver or engine issue, but not confirmed as such (single-device, no isolation
  from other apps).
- GCP always-free quota figures are as commonly published at the time of this report
  (2026-08-14) — verify current values before using them for capacity planning, as
  Google revises free-tier terms periodically.
- Cloud Run request counts for **restore** are now live-measured from production
  access logs (`gcloud logging read`), exact for the two sessions checked. **Backup**
  request/Firestore-write counts are still derived from reading the backend endpoint
  structure (`backend/app/routers/`, `firestore_repo.py`), not live-measured —
  order-of-magnitude, not exact.
- The restore measurement reflects the **currently deployed production backend**
  (`indic-api`, last deployed 2026-08-12; the service is `semper-api` since #146), which predates every change from this
  report's Phase 1/1.4 work — none of it is live yet. The numbers here are a genuine
  "before" baseline, not a mix of old and new behavior.
- This workload's synthetic imagery (640×640 LARGE / 320×320 LIGHT, dense seeded
  speckle) produces derived deliverables (PDF reports, heatmap PNGs) that are larger
  relative to raw+dat than a typical real session — the 75-82% restore-saving figures
  measured here should be read as this workload's characteristic, not a universal
  constant; the existing 50-frame/386MB reference point (~26% saved) is closer to
  real-world session composition.
