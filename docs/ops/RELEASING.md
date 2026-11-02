# Releasing Semper

How a build goes from **`main`** to testers. Written for maintainers; nothing
here is needed for day-to-day contributions. Secrets/vars layout:
[ENVIRONMENTS.md](ENVIRONMENTS.md).

## Alpha (private testers)

Private alpha uses the same **`beta`** Release channel — there is no separate
`alpha` input on [`release.yml`](../../.github/workflows/release.yml).

1. Tag and run Release on **`main`** with channel **`beta`**
   (e.g. `v1.0-beta.1`) → GitHub pre-release + signed APK.
2. Sideload onto tester devices (Pixel OK). The repository reports **public**
   (GitHub REST API, checked 2026-10-18), so anyone with the link can download
   from its Releases; treat a release as published, not private.
3. Smoke sign-in, one analysis, backup/sync, and open results. Do not publish to
   Play or the public website for this ring.

Check the R8 mapping reached Crashlytics (the release build uploads it); the workflow artifact is a 14-day fallback, and readable by any signed-in GitHub user while it is kept (the repository is public).

## Versioning

- A CI release sets the version from the workflow inputs: `versionName` comes
  from the `version` input (leading `v` stripped) and `versionCode` is 1000 plus
  the workflow run number (`RELEASE_CODE_BASE` in `release.yml`), passed to
  Gradle as `-PversionName` / `-PversionCode`.
  You do **not** hand-edit `app/build.gradle.kts` for a CI release.
- The values in [`app/build.gradle.kts`](../../app/build.gradle.kts) (`1` / `1.0`)
  are only the fallback for local builds that pass no `-P` overrides.
- Beta builds are tagged `v<versionName>-beta.<n>` (e.g. `v1.0-beta.1`);
  stable releases are tagged `v<versionName>`.
- `versionCode` increases for every release (the run number is monotonic).
  It is monotonic only while `release.yml` keeps its identity: renaming or
  re-creating the workflow restarts `run_number` at 1, so that change must
  raise `RELEASE_CODE_BASE` past the last release's versionCode (1036 for
  `v1.2-beta.4`).
- The `version` input must read `v<major>.<minor>[.<patch>][-beta.<n>]`, and a
  tag of that name that already exists must point at the commit being built
  (`verify-tag`, [`scripts/check_release_tag.py`](../../scripts/check_release_tag.py));
  a new tag is created at that commit by `publish` (`target_commitish`).
- `main`'s CI also builds a `release-apk` signed with the release key, at
  **versionCode 1**, so it can never install over a release. Until 2026-10-01 it
  used CI's run number (671 by then, against the release workflow's 35), so a
  tester who installed it was refused every later release until they
  uninstalled, which deletes analyses that are not in the cloud. The 1000 base
  puts releases above every number CI ever used, so those phones update again.
  Do not hand CI artifacts to anyone; cut a beta instead.

## Release checklist

1. **Green working tree** — all changes committed, branch merged to **`main`**
   through a reviewed PR.
2. **Green CI** — `CI OK` must be green on the `main` commit you intend to
   release from a **full-matrix** run. A push to `main` runs the emulator and
   release tiers only when its diff touches them, so dispatch **Actions → CI →
   Run workflow** on `main` with `full_ci` ticked (or use that commit's weekly
   run, if it is the head). The Release workflow enforces it: its `verify-ci`
   job (`scripts/check_release_ci.py`) fails unless the latest check run of every
   CI job on the commit passed, not skipped (secret scan, legal and console
   pages, console accessibility, console Lighthouse, engine pin, tiers 1, 3, 4
   and 5, Firestore rules, the sanitized engine host tests, the Android dependency scan,
   semgrep, `CI OK`).
3. **Tag and push** the commit you intend to release:

   ```bash
   git tag v1.0-beta.1
   git push origin main --tags
   ```

4. **Run the Release workflow** — go to Actions → Release → Run workflow.
   Select branch **`main`**. Provide the version tag, changelog, and channel
   (beta/stable); the version drives `versionName`/`versionCode` automatically
   (see Versioning). Jobs refuse to run on other refs. The workflow uses the
   `release` environment (and repo-level secrets/vars on Free orgs).
   When the release ships with a backend deploy, dispatch `deploy-backend.yml`
   with `release_train` set to the release tag (`v1.2-beta.5`), so the service and
   the revision carry the label `release-train=v1-2-beta-5` (lowercased, dots to
   dashes). Left empty, the deploy takes the newest `v*` tag reachable from the
   deployed commit. Name the backend commit and revision in the release notes
   ([CI.md](CI.md), Backend deploy).
5. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
   Then run the solver on the shipping ABI, which no CI job does (Tier 3 runs it
   on x86_64 only, Tier 5 only builds arm64): on one arm64 phone or arm64 AVD,
   with `ANDROID_SERIAL` set to it,
   `./gradlew :app:connectedDebugAndroidTest -PabiFilters=arm64-v8a "-Pandroid.testInstrumentationRunnerArguments.class=com.sempermechanics.semper.pipeline.EnginePipelineSmokeTest"`.
   Record the device, Android version, engine pin and result in the release's
   [CHANGELOG.md](CHANGELOG.md) entry; a failure blocks the release.
6. **Distribute publicly (manual)** — this workflow builds a signed APK and a
   GitHub Release in this repository, a pre-release on the `beta` channel. The
   repository is public, so that Release is downloadable by anyone, and it is
   where a sideloaded build's **Update** button goes: below the minimum version,
   a build not installed by Play opens
   `https://github.com/sempermechanics/semperdic-app/releases` (the list, since
   `/latest` skips pre-releases; `ui/limit/UpdateSource.kt:19`), and a Play
   install opens its Play listing. Attach the APK to every release. Customer
   distribution is separate:
   - Upload the APK (GitHub Release on
     [sempermechanics/website](https://github.com/sempermechanics/website) preferred) + update
     `downloads/manifest.json`, push so GitHub Pages redeploys — see that repo’s
     `RELEASING.md` — and/or publish on Play Store.
   - Announce on https://sempermechanics.com/ / Discussions on
     `sempermechanics/website`, not this repository.
   - For Play, upload the **`release-aab`** artifact (or the `.aab` attached to the
     GitHub Release), not the APK; see "Staged rollout on Google Play" below.

**Release notes rule.** Every release's notes name what changed for the user and
every customer-visible backend change it depends on. The first release built
after 2026-10-18 must say that **Android 11 (API 30) is now the minimum**: phones
on Android 7–10 cannot install or update it, and keep the version they have,
which the backend may later gate (`MIN_SUPPORTED_VERSION_CODE`, or the `_SEMPER_CLASSROOM` pair for Semper Classroom).

## Staged rollout on Google Play

Readiness items 1.1, 6.3 and Part D5
([READINESS_FRAMEWORK_2026-10.md](READINESS_FRAMEWORK_2026-10.md)). Nothing here
has been rehearsed: there is no Play listing or closed track yet (TD-229, TD-231).
Play does not allow a staged rollout for an app's first production release, and a
staged percentage never rises by itself.

| Stage | Users | Minimum soak before the next stage |
|---|---|---|
| Internal / closed track | testers | until every halt signal below has data |
| 1 | 5 % | 24 h, and at least 7 days of the closed track behind it for a release that changes licensing |
| 2 | 20 % | 24 h |
| 3 | 50 % | 48 h |
| 4 | 100 % | — |

**Halt criteria.** Halt the rollout (Play Console → Release → Halt) when any of
these holds for the new version. "The new version" is always the pair
(appId, appVersion): Semper Classroom numbers its builds from its own counter, so
a bare `appVersion` can match both apps. The numbers are starting values, to be
tuned after the first rollouts:

- **Crash-free users** below **99.5 %** (Crashlytics, the release's version) once at
  least 200 users run it, or a new crash cluster in licensing, upload or restore.
- **User-perceived ANR rate** above **0.47 %**, Play's bad-behaviour threshold
  (Android vitals).
- **Activation errors:** the share of `semper_activation` lines with
  `outcome="refused"` on the new (appId, appVersion) more than 5 points above the
  previous version's 7-day share, with at least 20 attempts (`ops/monitoring/metrics/semper_activation.yaml`).
  The same for `semper_checkout`, leaving out `no_floating_seat`, which is a full pool.
  Both shares are charted per build on the `semper-licensing` dashboard.
- **Device-lock drops:** `semper_devlock_drop` with `stage="mismatch"` on the new
  (appId, appVersion): at least **10** in 24 h, and that 24 h count divided by the
  stage's percentage more than **2** times the previous version's 7-day daily mean
  divided by the rest (at 5 %: new ÷ 5 against previous ÷ 95). The
  `devlock-mismatch` alert catches one licence looping sooner.
- **Refusals:** the `auth-denied-spike` alert, or any `semper_app_check_failed`
  line (`app_check_missing` or `app_check_invalid`) from the new (appId, appVersion).
  The `AUTH_DENIED` metric carries only `stage`, so it cannot say which build was
  refused; the `app-check-refused` alert fires on it for any build.
- **Backend:** the `http-5xx-ratio` or `slo-burn-fast` alert while the rollout is
  under way.

Halting stops new installs only; users who already updated stay on the new build.
That is why the levers below, not Play, are the rollback.

## Rollback levers

Play cannot recall a build. In order of speed.

**Which builds obey which lever.** The client half of the levers (the version
gate `VersionGate`, `KillSwitch`, `FeatureLocks`, and `X-App-Version` on every
call) arrived on 2026-10-18 (4979354a, 55282bb8, wired in 5a14cce2), none of them in `v1.2-beta.4`
(versionCode 1036, 33dce87d), the latest release. Every build released so far
ignores `MIN_SUPPORTED_VERSION_CODE` / `RECOMMENDED_VERSION_CODE` and the
`share` switch. For that installed fleet the only levers are the ones the
backend enforces itself: `KILL_SWITCHES` on the guarded routes (503
`feature_paused`), `APP_CHECK_MODE`, and the backend revision. The version gate
starts to work for builds released after `v1.2-beta.4`. Whether the backend
should also refuse device-signed upload and restore below the minimum, by
`X-App-Version`, is an owner decision (TE6-006, not taken).

| Lever | Effect | How |
|---|---|---|
| `KILL_SWITCHES` | Switches off `backup`, `share`, `restore` or `upload` without a release. The backend refuses the guarded routes itself (503 `feature_paused`, `Retry-After: 300`; `backend/app/deps.py:301-336`), so it holds for every build, old ones included, which retry the 503; current builds also show the feature as paused. `share` has no route, so only builds that read `/v1/config` obey it | Lever variable (scope below), space-separated, then `deploy-backend.yml` ([ENVIRONMENTS.md](ENVIRONMENTS.md)); watch `event="feature_paused"` in the logs to see what it holds back |
| `MIN_SUPPORTED_VERSION_CODE` | **Builds after `v1.2-beta.4` only** (above). Semper builds below it keep their analyses, viewer and exports, and cannot start an analysis, upload or restore until updated. `RECOMMENDED_VERSION_CODE` only nudges. Semper Classroom has its own pair, `MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM` / `RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM`, because its versionCodes come from its own release counter (`settings.release_gate(app)`, `backend/app/config.py:308-335`); an unset one falls back to its pre-rename `_MATERIAL_TESTING` name ([ADR-030](../adr/ADR-030-semper-classroom-app-identity.md)), and the pair gates Material Testing builds too | Lever variable (scope below), redeploy. Raise the recommended one a release ahead. A local debug build at versionCode 1 is never gated |
| `ENTITLEMENT_OFFLINE_GRACE_DAYS` | How long newly issued entitlement tokens hold offline; the fail-open / fail-closed lever for licensing. Tokens already issued keep theirs | Lever variable (scope below), redeploy ([ADR-023](../adr/ADR-023-signed-offline-entitlement.md)) |
| `APP_CHECK_MODE` | `enforce` back to `monitor` or `off` | [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md) |
| Backend revision | Traffic back to the previous revision | `gcloud run services update-traffic semper-api --to-revisions=<previous>=100 --region asia-south1 --project indicvision-dic-app`, then read **After a revision rollback** below; the image is kept as `rollback-prev` ([BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) A7). Firestore is never rolled back by a deploy |
| Hotfix | A new build with a higher `versionCode` | The release workflow's run number makes every release higher than the last (Versioning above); roll it out with the same stages, faster soak if the fix is narrow |

**Lever variable scope.** Set a lever on the GitHub environment of the service
it is for: `gh variable set NAME --env production` (or `--env staging` for a
drill), not on the repository. `deploy-backend.yml` runs under `environment:`,
whose variables win over the repository's, so a drill on staging never reaches
production, and a repository value is ignored wherever the environment sets the
same name. Delete the environment value when the lever is released
(`gh variable delete NAME --env production`). On 2026-10-24 (`gh variable list`)
neither environment set any lever, and `APP_CHECK_MODE` was a repository variable,
which an environment value overrides. [INCIDENT_RESPONSE.md](INCIDENT_RESPONSE.md)
uses the same words.

**A lever deploy is a full deploy.** `deploy-backend.yml` has no lever-only
path: its `deploy` job needs `test` (`.github/workflows/deploy-backend.yml:77-89`),
which runs the backend gate, whose `pip-audit --strict` has no override
(`.github/actions/backend-gate/action.yml:37-38`); and it builds the commit it is
dispatched on and promotes it `--to-latest` (`deploy-backend.yml:465`). So a new advisory in
`backend/requirements.lock` blocks every lever flip until the lock is regenerated
and merged (on 2026-10-28 msgpack 1.2.1 did, readiness TE6-101), and a flip also
ships every backend commit on `main` that is not yet live. When either is true,
or the flip cannot wait, use the emergency lever below.

**Emergency lever (no code change).** The same three steps as an env change
after a revision rollback (below, step 2): put the lever on the **serving**
revision's image with no traffic, then move traffic to the new revision by name.

```bash
GC="--region asia-south1 --project indicvision-dic-app"
SERVING=$(gcloud run services describe semper-api $GC --format='value(status.traffic[0].revisionName)')
IMAGE=$(gcloud run revisions describe "$SERVING" $GC --format='value(spec.containers[0].image)')
gcloud run services update semper-api $GC --image "$IMAGE" \
  --update-env-vars 'KILL_SWITCHES=backup restore upload' --no-traffic
gcloud run services update-traffic semper-api $GC --to-revisions=<new>=100
```

Check first that `status.traffic` has one entry at 100 % (a split names more than
one revision). Quote the value whole (it is space-separated; gcloud splits
pairs on commas only); the version gate is `MIN_SUPPORTED_VERSION_CODE=<code>`.
Then **set the lever variable** (scope above), or the next deploy
undoes it. The service stays pinned by name until the next `deploy-backend.yml`
run, which returns it to `--to-latest`. Who: someone with `run.services.update`
on the project, under the incident's owner; the production approval of the
workflow path is skipped, so record the command and the reason in the incident.

**How long a flip takes, and how to see it hold.** Through `deploy-backend.yml`:
about 4.5–5 min of pipeline (the staging drill runs 39752109438 and 39755073181:
`test` 1.5 min, `deploy` 2.4 min, `gateway` 0.4 min), plus the product owner's
approval on `production` ([ENVIRONMENTS.md](ENVIRONMENTS.md)). The emergency
lever: about a minute for the new revision. Either way, installs that read
`/v1/config` see it on their next read, up to 5 min later
(`RECONCILE_MIN_INTERVAL_MS`, below); the backend refuses the `KILL_SWITCHES`
routes from the first request the new revision serves. Verify with
`curl -s <api>/v1/config` (its `killSwitches` list, `backend/app/routers/account.py:178`)
and `event="feature_paused"` in the logs.

**After a revision rollback.** `update-traffic --to-revisions=<previous>=100`
pins traffic to that revision by name, and every lever above then has a trap:

1. **Revert first.** Do not dispatch `deploy-backend.yml` for a lever until the
   bad commit is reverted on `main` (PR, `CI OK`): the workflow builds the commit
   it is dispatched on and promotes it `--to-latest`, which re-ships the code you
   rolled back.
2. **An env change that cannot wait for the revert** goes on the serving image
   with no traffic, then traffic moves to it by name:

   ```bash
   GC="--region asia-south1 --project indicvision-dic-app"
   IMAGE=$(gcloud run revisions describe <previous> $GC --format='value(spec.containers[0].image)')
   gcloud run services update semper-api $GC --image "$IMAGE" --update-env-vars K=V --no-traffic
   gcloud run services update-traffic semper-api $GC --to-revisions=<new>=100
   ```

   `--image` matters: a new revision is built from the service template, which a
   traffic move leaves on the bad revision (its image and its env), so compare the
   two revisions' env too. Without `--no-traffic` and the move, a plain
   `services update` on a pinned service makes a revision that serves 0 %.
3. **Set the lever variable as well** (scope above), or the next deploy undoes the
   change. Once `main` is fixed, a normal deploy returns the service to
   `--to-latest`.

**Drill on staging, 2026-10-22** (TD-220), with a debug build at versionCode 100
pointed at `semper-api-staging`, signed in as a QC test account on an Android 14
and an Android 12 emulator; each step is a `deploy-backend.yml` run from `main`
with `gateway_mode=dry-run`:

1. `KILL_SWITCHES="backup share restore"` (run 39752109438). The viewer's Share
   is greyed and a tap says "Sharing is temporarily unavailable"; Settings hides
   Cloud backup and Analyses data management (`FeatureLocks.settings`), so
   neither backup nor restore can be asked for; the app sends no
   `POST /v1/sessions`. A client that ignores the config (the CI round trip,
   `StagingRoundTripDeviceTest`) is refused by the backend itself: 503
   `{"detail":"feature_paused"}` on the signed create.
2. `KILL_SWITCHES` unset, `MIN_SUPPORTED_VERSION_CODE=200` (run 39755073181).
   **New analysis** opens "Update to keep analysing" (`UpdateRequiredActivity`)
   on both phones; **Open my analyses** returns to Home, where analyses still
   open.
3. Both unset (run 39755853906): the revision's env is back to `0` and empty,
   and after **Refresh licence** New analysis opens the import sheet again.
4. `RECOMMENDED_VERSION_CODE=200` (run 39762144670). After **Refresh licence**,
   the next Home opens with the Snackbar "A newer version of the app is
   available." on both phones; analysing, backup and restore are unchanged.
   It shows once: the key `100<200` is kept (`update_nudge`,
   `data/policy/AppPolicy.kt:44-51`), so further launches stay quiet until the
   recommended version or the build changes. Set back to `0` in run
   39762952329.

A running install sees a change on its next config read: launches within five
minutes of the last reconcile reuse the cached `/v1/config`
(`data/cloud/CloudSync.kt:412`, `RECONCILE_MIN_INTERVAL_MS`), and **Refresh
licence** fetches it at once. Lifting a lever reaches phones the same way, so
allow that long before calling a rollback done. The Semper Classroom pair and
`ENTITLEMENT_OFFLINE_GRACE_DAYS` were not moved (TD-220).

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow runs
`verify-legal` / `verify-android` / `verify-backend` / `verify-tag` / `verify-ci` in parallel,
then `build-release` → `publish`. Dispatch from **`main` only**.

### Verify jobs — the gate

Green CI used to be a checklist item in this file, which meant a red suite could
still be signed and published. The same checks now run as three jobs so legal,
JVM unit tests, and backend pytest overlap instead of stacking:

- `verify-legal` — `python scripts/render_legal_pages.py --check`: the published
  Privacy Policy and Terms still match `docs/legal/`.
- `verify-android` — CI tier 1's checks: `:app:testDebugUnitTest spotlessCheck
  :app:detekt :app:lintDebug`, the androidTest and `:benchmark` compiles, and
  the Kover floor (`koverVerify`). `compileReleaseKotlin` is left to
  `build-release`, whose R8 build compiles it. No engine/OpenCV submodules: the
  JVM suite does not `loadLibrary`.
- `verify-backend` — the shared `backend-gate` action (hashed lock, `pip-audit`,
  ruff over `app/ tests/ scripts/ ../scripts/`, pytest at the 93 % floor), the
  same one CI and Deploy run.
- `verify-ci` — what the three above do not re-run: reads the commit's check
  runs (`gh api …/commits/<sha>/check-runs`, `checks: read`) and
  [`scripts/check_release_ci.py`](../../scripts/check_release_ci.py) requires the
  latest run of each CI job to have **passed**: the secret scan, legal pages,
  console pages, console accessibility, console Lighthouse, the engine pin, tiers 1, 3, 4 and 5, Firestore rules, the
  sanitized engine host tests, the Android dependency scan (OSV-Scanner),
  semgrep and `CI OK`. A path-filtered push run skips tiers 3
  and 5 (and the engine host tests with them), so it is not enough: run CI with `full_ci` first
  (checklist step 2).

- `verify-tag` — the `version` input's format, and that its tag, if it exists,
  points at the commit being built (`scripts/check_release_tag.py`; full
  history for the tags). `publish` then creates a missing tag at that commit
  (`target_commitish: github.sha`), not at whatever `main` is by then.

`build-release` declares `needs: [verify-legal, verify-android, verify-backend,
verify-tag, verify-ci]`, so none of the signing steps run if any of the above
fails.

### `build-release` — sign and check

1. Builds a **signed release APK** using repository secrets (keystore, alias,
   passwords) — Environment-scoped when available, otherwise **repo-level**
   secrets on Free private orgs. The `signingConfigs.release` block in
   `app/build.gradle.kts` reads the `SIGNING_*` env vars the workflow sets; if
   the keystore is absent the variant stays **unsigned** rather than silently
   debug-signed. `assembleRelease` still runs R8 minify; the mapping upload
   fails the job if that file is missing.
2. Requires variable **`SEMPER_API_BASE_URL`** (HTTPS API Gateway or Cloud Run
   URL) and builds with `-PrequireCloudApi=true`. A missing, empty or non-HTTPS
   URL fails the job — cloud sync must not ship silently disabled, and ID tokens
   must not go out in cleartext.
3. Verifies the arm64-v8a `.so` is packaged.
4. **Verifies the signature** with `apksigner verify` — the release fails here
   if the APK is not validly signed with the release key. Newer Android
   build-tools print `V2 Signer: certificate SHA-256 digest:` (and similar);
   older ones used `Signer #1 certificate SHA-256 digest:`. The workflow accepts
   both.
5. **Inspects the APK's hardening** with
   [`scripts/check_release_apk.py`](../../scripts/check_release_apk.py) (aapt2
   from the newest build-tools, plus a scan of every `classes*.dex`). It fails
   the release when the APK is debuggable or `testOnly`, ships a component or
   class from the debug source set (`app/src/debug`, e.g.
   `DebugViewerSeedActivity`; found by name in the dex or, renamed, on the left
   of R8's mapping), contains `DebugAppCheckProviderFactory` (dex or mapping) or a
   firebase-appcheck-debug version file, allows cleartext (manifest or network
   security config), has a `minSdkVersion` other than `minSdk` in
   `app/build.gradle.kts`, has a `targetSdkVersion` other than `targetSdk` there
   or below Play's floor of 36 (`target-sdk`), ships a `lib/<abi>/*.so` with a
   LOAD segment aligned below 16 KB or stored uncompressed off a 16 KB zip
   offset, or ships no `.so` at all (`native-16kb`, Play's 16 KB page-size
   rule), loses a consent default (`<application>` meta-data
   `firebase_crashlytics_collection_enabled`, `firebase_analytics_collection_enabled`
   and `google_analytics_adid_collection_enabled` must be present and `false`;
   no `AD_ID` or `ACCESS_ADSERVICES_*` permission, TD-232), or R8's
   `mapping.txt` is missing, empty, or renames no `com.sempermechanics.semper`
   class (an identity mapping, as `-dontobfuscate` writes; `--mapping` is
   required). It
   **fails** (`--require-pins`, TE1-001) when no `sha256/…` pin is compiled in,
   i.e. `SEMPER_API_CERT_PINS` was empty ([Certificate pins](#certificate-pins-semper_api_cert_pins)
   below); CI tier 5 only warns. It **warns** when no
   entitlement key (`kid:<P-256 SPKI>`) is compiled in, i.e.
   `SEMPER_ENTITLEMENT_PUBKEYS` was empty: the build then ignores signed tokens
   and trusts its cached licence with no offline bound (ADR-023, TD-215). The
   workflow turns that warning into a failure (`--require-entitlement-pins`)
   as soon as the `SEMPER_ENTITLEMENT_PUBKEYS` variable is set, or when
   `SEMPER_REQUIRE_ENTITLEMENT_PINS` is `true`. **Once TD-215 lands** (the
   signing key is live and the pubkeys variable set), also set
   `SEMPER_REQUIRE_ENTITLEMENT_PINS=true` on the `release` environment, so a
   cleared pubkeys variable cannot ship a key-less build quietly. Both are unset
   today, so no release fails on it. **Signing-key continuity** (`signing-key`):
   the signer's certificate must be the release certificate, SHA-256
   `FF:72:A8:B0:8F:02:A0:5D:65:B5:3D:91:25:0B:EF:7E:3B:0F:F7:53:8A:F2:B0:16:8E:E5:2D:5F:36:B1:5C:9A`,
   which signed every Semper release so far (release runs 31565590316 to
   39064532280; `v1.2-beta.4`, versionCode 1036). A build signed with any other
   key (a different `KEYSTORE_BASE64`) would not install over them
   (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`), and the only way past that is an
   uninstall, which deletes analyses not in the cloud. `assetlinks.json` lists a
   second Semper certificate, so step 7 alone does not catch it. The value is the
   workflow's default for this repository; the variable
   `SEMPER_RELEASE_CERT_SHA256` overrides it, which is how a deliberate rotation
   (with an APK Signature Scheme v3 lineage, or Play App Signing's upload key)
   is made. A fork gets no default and only a warning until it sets the variable.
   CI tier 5 runs the same check on its own release build, without the
   entitlement flag or a signer. Locally:
   `python3 scripts/check_release_apk.py app-release.apk --mapping mapping.txt`.
6. **Verifies the bundle's signature** (`jarsigner -verify` on the `.aab`).
7. **Verifies `assetlinks.json` lists the release certificate.** It extracts the
   SHA-256 digest from the signed APK, reformats it to the colon-separated
   uppercase form Digital Asset Links uses, and greps
   `firebase-hosting/public/.well-known/assetlinks.json` for it. If the
   fingerprint is absent the release fails with instructions.

   This check exists because the failure it prevents is invisible. App Links only
   verify when the *release* signing certificate is in the hosted file; when it is
   not, the email sign-in and password-reset links stop opening the app and fall
   back to a browser disambiguation dialog — phishable, and indistinguishable from
   an app bug. Rotating the signing key or moving to Play App Signing means adding
   the new fingerprint here and redeploying Hosting.
8. Uploads three artifacts: the APK, the **`release-aab`** Android App Bundle
   (built in the same Gradle run as the APK, `:app:bundleRelease`, so both
   share one R8 mapping; for a Play Console upload by hand: nothing here uploads
   to Play), and **`release-mapping-<version>`**, the R8 mapping file (14-day
   retention, `if-no-files-found: error`).

**The R8 mapping is not attached to the GitHub Release, deliberately.** It is the
deobfuscation key — publishing it would undo the obfuscation for everyone — but
without it a field stack trace from that build is unreadable, and it cannot be
regenerated afterwards. The build passes `-PuploadCrashlyticsMapping=true`, so
the Crashlytics Gradle plugin uploads the mapping to Firebase and field crashes
stay readable there for the life of the project (TD-40). The workflow artifact
is only the fallback for a manual `retrace`, and it is **not private**: on a public
repository any signed-in GitHub user can download a workflow artifact while it is
kept. So it is kept 14 days, long enough to check Crashlytics has the mapping
(CI's Tier 5 `release-mapping`, from an unshipped build, 3 days). A copy needed
longer (a pentest under NDA, [PENTEST_SCOPE.md](PENTEST_SCOPE.md)) is downloaded
in that window and kept outside GitHub. Local builds never upload.

### `publish`

Creates the **GitHub Release** with the APK and the AAB attached, gated on the
`release` environment.

### Required secrets (repo or `release` environment)

| Secret | Description |
|--------|-------------|
| `KEYSTORE_BASE64` | Base64-encoded release keystore |
| `KEY_ALIAS` | Signing key alias |
| `KEY_PASSWORD` | Key password |
| `STORE_PASSWORD` | Keystore password |

### Required variables (repo or `release` environment)

| Variable | Description |
|----------|-------------|
| `SEMPER_API_BASE_URL` | HTTPS base URL of the API Gateway (preferred) or Cloud Run service |
| `SEMPER_API_CERT_PINS` | Required: the four GTS root pins, comma-separated ([Certificate pins](#certificate-pins-semper_api_cert_pins)), passed to Gradle as env. Public, so a variable; a secret of the same name also works. Empty fails the release (`--require-pins`) |
| `SEMPER_ENTITLEMENT_PUBKEYS` | Public key(s) the app verifies signed entitlements with, passed to Gradle as env. Public material, so a variable, not a secret. Set, it also makes the APK inspection require an entitlement key in the build (step 5) |
| `SEMPER_REQUIRE_ENTITLEMENT_PINS` | Optional, `true` once TD-215 is live: the APK inspection fails a release with no entitlement key compiled in, whatever `SEMPER_ENTITLEMENT_PUBKEYS` holds |
| `SEMPER_RELEASE_CERT_SHA256` | Optional here (the workflow defaults to the release certificate of every release so far, step 5); set only to rotate the signing key deliberately. A fork must set it to its own release certificate |

### Certificate pins (`SEMPER_API_CERT_PINS`)

The release gate (`check_release_apk.py --require-pins`) fails a release APK
that compiles no pin (TE1-001); CI tier 5 only warns. By the Admin decision of
2026-10-28 the pins are the SPKI hashes of the four Google Trust Services
roots: one serves the API today, the other three are backups.

| Root | Pin | Cert SHA-256 | Valid to |
|---|---|---|---|
| GTS Root R1 | `sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=` | d947432a…57a7f4cf | 2036-06-22 |
| GTS Root R2 | `sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=` | 8d25cd97…841c7ea8 | 2036-06-22 |
| GTS Root R3 | `sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=` | 34d8a73e…010a6f48 | 2036-06-22 |
| GTS Root R4 | `sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=` | 349dfa40…031b3c7d | 2036-06-22 |

Provenance: the DER files `https://i.pki.goog/r1.crt` … `r4.crt` (listed at
pki.goog/repository). Each pin is
`openssl x509 -pubkey -noout | openssl pkey -pubin -outform der | openssl dgst -sha256 -binary | base64`,
cross-checked with Python `cryptography`, on 2026-10-28. That day the gateway
(`semper-gw-86wx7pp1.an.gateway.dev`) served `*.gateway.dev` → WE2 → GTS Root
R4, the last cross-signed by GlobalSign Root CA until 2028-01-28. The
cross-sign has the same key, so the R4 pin matches either way: OkHttp checks
the pins against the chain Android verified, trust anchor included.

The value to set:

```text
sha256/hxqRlPTu1bMS/0DITB1SSu0vd4u/8l8TjPgfaAp63Gc=,sha256/Vfd95BwDeSQo+NUYxVEEIlvkOlWY2SalKK1lPhzOx78=,sha256/QXnt2YHvdHR3tJYmQIr0Paosp6t/nggsEGD4QJZ3Q0g=,sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=
```

Set it as an environment variable (the `SEMPER_API_CERT_PINS` repository or
`release`-environment variable, which `release.yml` reads; a secret of that
name also works) or in `local.properties` for a local build, not `-P`: the
build reads only the environment and `local.properties`, so a `-P` value is
ignored and the gate fails. Check a build with `check_release_apk.py
--require-pins` (`cert-pins PASS 4 sha256/ pin(s) compiled in`).

Rotation: the pins outlive leaf and intermediate rotation (WE2 and its
successors), not a change of root. The GTS roots expire in 2036. Adding or
removing a root, or moving the API off Google Trust Services (a custom domain
with another CA), needs a release carrying the new pin set that users have
installed **before** the server chain changes: an installed app keeps its
compiled pins, and a chain they do not match fails every API call with
`SSLPeerUnverifiedException`. Re-check the live chain with
`openssl s_client -showcerts` before any change.

### Backend staging / production

Use [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml): choose
`staging` or `production`, supply GCP project/region. Updates deploy with
`no_traffic`, tag a candidate, smoke `/readyz` and `/v1/config` (the app's own
401) with an ID token, then promote, then check the live service the same way
and roll traffic back to the previous revision if that fails.
First-time Cloud Run creates omit `no_traffic` (Cloud Run rejects it on create).
See [CI.md](CI.md) § Backend deploy and [ENVIRONMENTS.md](ENVIRONMENTS.md).

#### Bumping backend dependencies

The image installs from `backend/requirements.lock` with `--require-hashes`, so a
version bump is two files, in this order:

```bash
cd backend
# 1. Edit requirements.txt (the direct dependency you actually want to move).
# 2. Regenerate the hashed lock from it on Python 3.12 (CI and Cloud Run runtime).
#    Do not compile the lock on 3.13 — the header and markers must match runtime.
#    Compile on Linux: uvicorn[standard] pulls uvloop there; a Windows lock omits
#    it and `--require-hashes` then fails in CI.
pip install pip-tools
pip-compile --generate-hashes --output-file requirements.lock requirements.txt
```

CI tier 4 then proves the lock resolves under `--require-hashes` on Python 3.12
*and* that every direct dependency's **version** matches `requirements.txt`, so a
stale or hand-edited lock fails in CI rather than in the Cloud Build step of a
deploy. Never edit `requirements.lock` by hand — the hashes will not match and
the image will fail to build. Never bump txt without regenerating the lock.

**If you have no Linux Python 3.12 to hand, do not improvise.** Compiling on
Windows or on 3.13 produces a lock that installs fine locally and then fails
`--require-hashes` in CI: `uvicorn[standard]` pulls `uvloop` on Linux and swaps
in `colorama` on Windows, and the marker set differs between 3.12 and 3.13. Run
the [`Backend lock`](../../.github/workflows/backend-lock.yml) workflow instead —
Actions → Backend lock → Run workflow, and give it the branch. It compiles in
the documented environment and pushes the lock to that branch.

**Dependabot cannot do this step.** It bumps `requirements.txt` and has no way to
produce a hashed lock, so every backend Dependabot PR arrives with the two files
out of step and tier 4 red. Run `Backend lock` against the Dependabot branch to
put the lock on the PR. Its push is made with the workflow's `GITHUB_TOKEN`,
and GitHub starts no new workflow run for a push made with that token, so the
PR's checks stay red on the old commit: re-run CI from the PR (Checks → Re-run
all jobs) or push any follow-up commit yourself. A human push also stops
Dependabot rebasing that PR. A `Backend lock` check also runs on any PR touching either
file: when the committed lock is stale it fails and attaches the regenerated file
as the `requirements-lock` artifact, so the fix is a download rather than a
toolchain install.

**A transitive pin with an advisory.** pip-audit (the backend gate) fails on a
package `requirements.txt` does not name, and re-running the lock keeps its pin.
Dispatch `Backend lock` on a PR branch with the optional `upgrade_packages` input,
space-separated package names (e.g. `msgpack`): each becomes `pip-compile
--upgrade-package <name>`, and a value that is not a package name is refused
(`.github/workflows/backend-lock.yml`, "Compile the lock"). Re-run the PR's checks
afterwards, as above. It was added for msgpack 1.2.1 → 1.2.4 (readiness TE5-101).

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI **tiers 1 and 5** locally (quality gates + unit tests + R8 +
release assemble). It does **not** run the backend suite, the emulator tier, or
the two always-on gates. Run those separately:

```bash
python scripts/render_legal_pages.py --check                     # legal-pages
cd backend && pytest tests/ -q --cov=app --cov-fail-under=93     # tier 4
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64     # tier 3
```

Engine host and sanitizer suites run in `sempermechanics/semper-dic-engine`; this
repo's gate checks the pin is a tag (`engine-pin`) and reruns the host suite under
ASan+UBSan at the pin (`engine-host-sanitized`, in the full-matrix CI of step 2).
TSan and the C SDK smoke run only in the engine repo.

## After the release

- Verify a fresh install from the distributed artifact (not a dev build).
- **Upgrade smoke (manual):** install the previous Release APK → create a local
  analysis (and optionally enqueue upload/restore) → install the new APK
  *without uninstalling* → open Home, reopen the session, confirm queued
  WorkManager unique work still resolves. Prefs migrate via `AppSettings.migrate`
  on cold start.
- Watch Firestore (sessions collection) and Cloud Logging for the first synced sessions.
- Paste the GitHub Release changelog into Play / website **What’s new** when
  publishing that channel.
- Open a milestone for the next version and triage incoming beta feedback
  (Discussions on `sempermechanics/website`, support mail, in-app Send feedback)
  into issues against it.
- **Follow up:** when a reported item ships, reply on the thread or mail and
  close/check the milestone item so reporters know it landed.

## History rewrite / re-clone

If git history is rewritten (for example to purge the old prebuilt OpenCV SDK
under `app/src/main/cpp/opencv/` from every commit), collaborators must
**re-clone** or hard-reset to the rewritten tip. Force-pushed branches invalidate
existing local clones' merge bases. Coordinate with the team before rewriting;
document the rewrite in the PR / release notes. Do **not** force-push `main` for
routine fixes — prefer `git revert`.
