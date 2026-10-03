# Backend requests per app open

Opening the app on the Pixel 6 on 2026-09-24 sent **19 backend requests in about
one second**: 10 × `GET /v1/config`, 8 × `GET /v1/sessions` and 1 × `GET /v1/me`
(production access log, one uid). Each one also started an App Check attestation,
which Firebase answered with "Too many attempts". This file records each change
that lowers that number, one lever per PR, with its measurements before and after.

Evidence labels: **[Measured]** means run and read here; **[Estimated]** means derived
from a measured count and a documented cost; **[Unknown]** means not yet measured.

## Why so many

- Home calls `refresh()` once for each finished upload/restore job that WorkManager
  still keeps (`HomeActivity.observeUploadFailures` / `observeRestoreProgress`). A new
  activity has not seen any of them yet, so N kept jobs start N + 1 reconciles at once.
- `CloudSync.reconcile` throttles to one check every 5 minutes, but it reads the
  timestamp at the start and writes it only after `listSessions` succeeds. Calls that
  start together all pass the throttle, and each one fetches config first.

## Pass 1: one reconcile at a time (#191)

**Lever:** redundant work elimination. A `Mutex` in `CloudSync.reconcile` runs
the calls one at a time. The first one lists the cloud and writes the timestamp. Each
later call then finds the timestamp fresh and the config known, and returns `Skipped`
without touching the network. A pull-to-refresh (`deep`) still always goes through;
it just waits for any call already running. If the pull takes the lock first, a
resume queued behind it finds the check fresh and skips, so the pull's listing is
the only one.

**Instrument:** `CloudSyncConcurrencyTest` starts K `reconcile(deep = false)` calls
together against `FakeCloudApi`, whose `listSessions` and `getConfig` each take
50 ms. It counts the calls. JVM, Robolectric SDK 34, Windows 11; 3 runs before, 13 after.

| Simultaneous reconciles (K) | listings / config reads before | after |
|--:|--:|--:|
| 2 | 2 / 2 | 1 / 1 |
| 8 | 8 / 8 | 1 / 1 |
| 32 | 32 / 32 | 1 / 1 |

[Measured] The counts were identical in every run at each size (spread 0):
before = K / K, after = 1 / 1 at every K. The test stays in the suite as the
regression guard.

**Amdahl ceiling.** Of the 19 requests per open, the reconciles account for
8 listings + 8 config reads = 16 (p ≈ 0.84), so the lever can at best cut requests
by 1 / (1 − p) ≈ 6.3× [Estimated].

**Per app open on the device**, with 7 kept jobs as on 2026-09-24:

| | before [Measured, 1 open] | after [Estimated] |
|---|--:|--:|
| `GET /v1/sessions` | 8 | 1 |
| `GET /v1/config` | 10 | 3 |
| `GET /v1/me` | 1 | 1 |
| **total** | **19** | **5** (3.8× fewer), only when the burst happens |
| Firestore reads for listings and config (S sessions; listing ≈ S + 4, config ≤ 3) | ≤ 8S + 62 | ≤ S + 13 |

**Device, 2026-09-25 (Pixel 6, sideloaded debug build, the reconcile throttle reset
before every open so each open is a first open).** Counted per open from the
production Cloud Run access log and the API Gateway log for the phone's uid, over
the opens whose requests reached the server:

| Requests per open (server log) | opens | median | `GET /v1/sessions` | `GET /v1/config` | `GET /v1/me` |
|---|---|--:|--:|--:|--:|
| before (main build of 2026-09-23) | 12 12 10 12 | 12 | 4–5 | 5–6 | 1 |
| after (this PR) | 4 4 4 4 4 4 4 | 4 | 1 | 2 | 1 |

[Measured] 3× fewer requests per open; the shift (8) is far outside the spread
(≤ 2). Every "after" open logged 3 × "Reconcile skipped": the calls queued behind
the first one found the check fresh. **Gate met** (1 listing per open).

A first attempt counted "No App Check token" lines in logcat instead (this build
cannot attest, so each request logs one). That count stayed near 10 per open on
both builds and is not a request count: after about five quick restarts the phone
stopped reaching the server at all (no gateway entry), and each failed attempt is
retried and logged again. [Note 2026-09-25, found in Pass 2] The phone had gone to
sleep: with a 30 s screen timeout and Battery Saver on, the locked phone was dozing,
and Android blocked the app's network (`dumpsys netpolicy`:
`blocked=BATTERY_SAVER|DOZE|APP_BACKGROUND|DATA_SAVER`, process state `TPSL`).
The app logged `UnknownHostException` while the shell could still reach the
gateway. `launch_counts.py` now sends a wake key every 10 s and records whether
the phone was awake at the end of each open.

**Cost:** one private `Mutex`; no new dependency. The only behaviour change is
that concurrent reconciles now wait for each other instead of overlapping. A
waiting pull-to-refresh can take up to one extra listing's latency.

## Pass 2: one config fetch per app open

**Bottleneck.** After Pass 1 an app open made 4 requests, 2 of them
`GET /v1/config`. `StatusRecheck` (`AuthRepository.resolveStatus`, which fetches
`/v1/me` and `/v1/config` together) and Home's reconcile each fetched config. In
the production access log for the phone's uid on 2026-09-25, the two fetches reach
the server 20–100 ms apart on every open. Each takes 30–90 ms on the server, so
the second one starts before the first has answered.

The plan was to skip the reconcile's fetch when config had been fetched in the
last 60 s. That can never trigger when the two overlap, so it was not built.

**Lever:** request coalescing. `SemperApi.getConfig` runs through a
`SingleFlight` (`data/net/SingleFlight.kt`): a call that arrives while one is
running waits for the same answer.
- Nothing is cached; the next call after one finishes fetches again.
- A failure reaches every caller that joined, and the next call retries.
- The shared call runs in its own scope, so a caller that gives up cancels only
  its own wait.
- The status check and the reconcile stay independent: no lock spans the two.

**Amdahl ceiling.** Config is 2 of the 4 requests per open (p = 0.5). Collapsing
it to 1 saves 1 of 4, 25 % [Estimated]. That clears the 5 % bar for adding a
concurrency primitive.

**Instrument (JVM).** `ConfigSingleFlightTest` starts K callers at once against a
fetch that takes 50 ms. JVM, Windows 11.

| Overlapping callers (K) | fetches without it | with it |
|--:|--:|--:|
| 2 | 2 | 1 |
| 8 | 8 | 1 |
| 32 | 32 | 1 |

[Measured] Identical in every run (spread 0).

**Device, 2026-09-25.** Pixel 6 on the production gateway, debug build, 10 opens
each with 25 s per open. The reconcile throttle was reset before every open.
Counts are per open from the Cloud Run access log.

| Requests per open (server log) | opens | median | `GET /v1/config` | `GET /v1/me` | `GET /v1/sessions` |
|---|---|--:|--:|--:|--:|
| before (#191 build) | 4 4 4 4 4 4 4 4 4 (1 open sent none) | 4 | 2 | 1 | 1 |
| after (this PR) | 3 3 3 3 3 3 3 3 3 3 | 3 | 1 | 1 | 1 |

[Measured] Spread 0 on both runs, shift 1. **Gate met** (1 config fetch per
open). Since #191's baseline of 12, requests per open are down 4×.

The after run kept the screen awake with the wake key and checked that it held on
all 10 opens. The before run did not; its one silent open is most likely the
phone sleeping. The phone's own log showed no requests for that open either.

**Cost:** one small class and 12 lines in `SemperApi`; no new dependency. A fetch
started with one token answers any caller that joins within its ~100 ms flight.
Every caller at launch holds the same signed-in user's token.

## Pass 3: App Check, measured and left alone

**Premise.** The plan was to stop retrying App Check for 60 s after a failure.
It assumed each request paid its own failed attestation, as in the burst of
2026-09-24: 19 requests per open, each asking Firebase, answered with "Too many
attempts". Passes 1 and 2 cut that to 3 requests per open.

**Instrument.** A temporary, uncommitted debug line in `AppCheckHeader.intercept`
timed each token wait. A scratch script then ran 10 opens on the Pixel 6 and
counted `requestIntegrityToken` calls in logcat. The build was a sideloaded debug
build; Play Integrity refuses every build until the Play account exists (see
[AUTH_SETUP.md](../backend/AUTH_SETUP.md) §3.2).

| Per app open, 10 opens | median | IQR |
|---|--:|--:|
| Play Integrity attestations | 1 | 0.25 |
| Token wait, `/v1/me` and `/v1/config` (they share the one attestation) | 1767 ms | 475 ms |
| Token wait, `/v1/sessions` (answered from the SDK's own backoff) | 2–8 ms | — |
| Cold start (`am start -W` TotalTime) | 726 ms | 128 ms |

[Measured] The Firebase SDK already backs off after a failed attestation, so
only the first request per process waits. A backoff in the app could save at
most the 2–8 ms of the later calls, under 1 % of the wait. That is below the 5 %
floor, so it was not built.

**Second lever: start the attestation in `SemperApp.onCreate`.** The signed-in
user's attestation would start at process start instead of at the first API
call. The SDK shares a fetch in flight, so the status check would wait only for
the rest of it. Built and measured:

| 10 opens each | before | after |
|---|--:|--:|
| Token wait for `/v1/me` and `/v1/config`, median (IQR) | 1767 ms (475) | 1702 ms (694) |
| Cold start, median (IQR) | 726 ms (128) | 700 ms (36) |

[Measured] A 65 ms shift, well inside the spread: reverted. The status check
already starts from Splash, a few hundred ms into the process. The SDK then has
to fetch a challenge over the network before it calls Play Integrity. In both
builds that call is made within ~40 ms of Home appearing. The wait left over is
the Play Integrity handshake itself.

**What would remove it.** A build that attests gets a token the SDK caches for
its lifetime, so later opens don't wait at all. That needs the Play account
(AUTH_SETUP §3.2, step 3). Until then every build pays about 1.3–1.8 s before
its first status and session calls on each cold open. Home is already on
screen by then: this delays the cloud data, not the app.

## Pass 4: an inline session create stops reading back what it wrote

Pass 4 is about Firestore cost per request, not the number of requests.

**Instrument.** `backend/tests/test_read_budget.py` counts reads and writes as
Firestore bills them against the store double:
- a document get is 1 read;
- a query is 1 read per document returned, at least 1;
- a count is 1 read.

It uses real device auth: the account lookup, the licence re-checks and the nonce
claim all count.
It freezes the cost of each route the app calls as `BUDGET`, so a change that adds a
read fails the suite. Python 3.13, Windows 11; the counts are deterministic
(3 runs, spread 0).

| Route (S = 20 sessions stored, N = 3 files) | reads | writes |
|---|--:|--:|
| `GET /v1/config`, the account's first ever | 4 | 3 |
| `GET /v1/config` | 1 | 0 |
| `GET /v1/me` | 1 | 0 |
| `GET /v1/sessions` | S + 2 = 22 (S + 3 = 23 since #224) | 0 |
| `POST /v1/sessions` | 14 → **9** (10 since #224) | 11 |
| `POST /v1/files/{id}/complete`, each | 6 | 4 |
| `DELETE /v1/sessions/{id}` | 7 | 5 |

**Bottleneck.** Up to 8 files (a bundle upload is 3), a create provisions its
Drive targets inside the request. It then read back what it had just written:
- the session doc, once in `provision_session` and once in the route;
- every file doc, through `list_pending_uploads`, to build the reply.

**Amdahl ceiling.** One upload costs one create plus N completes: 14 + 3 × 6 =
32 reads. The 1 + 1 + N re-reads are 5 of them, p ≈ 0.16, so at most 1.2× fewer
reads per upload [Estimated]. The plan's gate was at least 3 fewer reads per
create.

**Lever:** redundant work elimination.
- `repo.create_session` returns the doc it wrote, and the route passes it to
  `provision_session`.
- `provision_session` returns the targets it opened. They are built by
  `repo.upload_target`, the same function the `/uploads` listing uses, and come
  back in the listing's order.
- The queued (Cloud Tasks) path still reads everything; its reply to Cloud Tasks
  leaves the upload URLs out.

| Files per create (N) | reads before (8 + 2N) | after (6 + N) |
|--:|--:|--:|
| 1 | 10 | 7 |
| 3 | 14 | 9 |
| 8 (largest inline manifest) | 24 | 14 |

[Measured] against the store double; `test_create_reads_grow_once_per_file` keeps
it. Per upload of 3 files: 32 → 27 reads (16 % fewer). **Gate met** (5 ≥ 3).

**Later, deliberately (#224):** the quota count (`count_user_sessions`, used by
both the create check and `quota.used` on the listing) now subtracts a second
count of `PROVISION_FAILED` sessions, which store nothing and were charged as
stored analyses. That is one more read on each of the two routes: create is
7 + N, the listing S + 3. The status write that ends an inline create stays a
plain write; only the queued path pays a read to keep a `COMPLETED` session
from being moved back (TD-120).

**Correctness.** `test_inline_create_hands_back_what_the_uploads_listing_would`
was written first and passed on the old code. It checks that the reply equals
`list_pending_uploads` for the same session: status, targets, fields and order.
The whole backend suite passes (539 passed, 23 skipped; coverage 89.4 %).

**Not taken:**
- Passing the route's `user` doc through as well would save 1 more read, about 3 %
  of an upload, below the 5 % floor. It would also make the Drive folder pointers
  depend on what the auth layer hands the route: under `DEV_INSECURE_AUTH` that is
  a synthetic user, and `test_folder_ids_are_stored_then_reused` failed.
- `iter_unprovisioned_files` (N reads) stays. It is what makes a retried
  provisioning resume instead of opening a second upload URL.
- The re-reads inside the `complete` transaction and the second licence check in
  `verified_device` are there for correctness and security.

**Latency.** Production `POST /v1/sessions`, 200s over the last 30 days, all
N = 3 (9 requests, 2026-09-23/24): median 3158 ms, IQR 2704–3653 ms, range
1180–6054 ms [Measured, `latencyMs` in the Cloud Run `http_access` log]. The
Drive calls dominate that. The three Firestore round trips this removes (two gets
and one query) are about 10–30 ms [Estimated], under 1 % and far inside the spread,
so the latency gate is only "no rise".

**After the deploy** (revision `semper-api-36096112375-1`, 2026-09-25). There were no
real uploads yet, so the Pixel 6 made 10 on the main debug build (`883425d9`): copies
of one 1-frame session, 3 files each, one at a time about 35 s apart.

| `POST /v1/sessions`, N = 3 | n | median | IQR | range |
|---|---|---|---|---|
| Before (2026-09-23/24) | 9 | 3158 ms | 2704–3653 ms | 1180–6054 ms |
| After (2026-09-25 08:40–08:46 UTC) | 10 | 2487 ms | 2399–2598 ms | 2328–3129 ms |

[Measured, `latencyMs` in the `http_access` log; all 200.] **Gate met: no rise.**
Do not credit the 671 ms drop to Pass 4. It is about 20 times the 10–30 ms that
three Firestore round trips could save. The after-runs were back to back against one
warm instance with one payload; the baseline was spread over two days and three
revisions. It is the Drive calls that vary.
