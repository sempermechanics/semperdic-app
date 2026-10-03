# Releasing Semper

How a build goes from **`main`** to testers. Written for maintainers; nothing
here is needed for day-to-day contributions. Secrets/vars layout:
[ENVIRONMENTS.md](ENVIRONMENTS.md).

## Alpha (private testers)

Private alpha uses the same **`beta`** Release channel — there is no separate
`alpha` input on [`release.yml`](../../.github/workflows/release.yml).

1. Tag and run Release on **`main`** with channel **`beta`**
   (e.g. `v1.0-beta.1`) → private GitHub Release + signed APK.
2. Sideload onto tester devices (Pixel OK). Testers need access to this private
   repo’s Releases (or you pass the APK out-of-band).
3. Smoke sign-in, one analysis, backup/sync, and open results. Do not publish to
   Play or the public website for this ring.

Check the R8 mapping reached Crashlytics (the release build uploads it); the workflow artifact is a 90-day fallback.

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
   release (full matrix on push to `main`).
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
5. **Smoke the release build on a device** — clean install, sign in,
   run one analysis, confirm the session syncs and each share target works.
6. **Distribute publicly (manual)** — this workflow only builds a signed APK and
   a **private** GitHub Release. Customer distribution is separate:
   - Upload the APK (GitHub Release on
     [sempermechanics/website](https://github.com/sempermechanics/website) preferred) + update
     `downloads/manifest.json`, push so GitHub Pages redeploys — see that repo’s
     `RELEASING.md` — and/or publish on Play Store.
   - Announce on https://sempermechanics.com/ / Discussions on
     `sempermechanics/website` (not this private repo).

## CI-based release (workflow_dispatch)

The [`release.yml`](../../.github/workflows/release.yml) workflow runs
`verify-legal` / `verify-android` / `verify-backend` in parallel, then
`build-release` → `publish`. Dispatch from **`main` only**.

### Verify jobs — the gate

Green CI used to be a checklist item in this file, which meant a red suite could
still be signed and published. The same checks now run as three jobs so legal,
JVM unit tests, and backend pytest overlap instead of stacking:

- `verify-legal` — `python scripts/render_legal_pages.py --check`: the published
  Privacy Policy and Terms still match `docs/legal/`.
- `verify-android` — CI tier 1's checks: `:app:testDebugUnitTest spotlessCheck
  :app:detekt :app:lintDebug`. No engine/OpenCV submodules: the JVM suite does
  not `loadLibrary`.
- `verify-backend` — the shared `backend-gate` action (hashed lock, `pip-audit`,
  ruff over `app/ tests/ scripts/ ../scripts/`, pytest at the 75 % floor), the
  same one CI and Deploy run.

`build-release` declares `needs: [verify-legal, verify-android, verify-backend]`,
so none of the signing steps run if any of the above fails.

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
5. **Verifies `assetlinks.json` lists the release certificate.** It extracts the
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
6. Uploads two artifacts: the APK, and **`release-mapping-<version>`**, the R8
   mapping file (90-day retention, `if-no-files-found: error`).

**The R8 mapping is not attached to the GitHub Release, deliberately.** It is the
deobfuscation key — publishing it would undo the obfuscation for everyone — but
without it a field stack trace from that build is unreadable, and it cannot be
regenerated afterwards. The build passes `-PuploadCrashlyticsMapping=true`, so
the Crashlytics Gradle plugin uploads the mapping to Firebase and field crashes
stay readable there for the life of the project (TD-40). The 90-day workflow
artifact is only the fallback for a manual `retrace`. Local builds never upload.

### `publish`

Creates the **GitHub Release** with the APK attached, gated on the `release`
environment.

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

### Backend staging / production

Use [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml): choose
`staging` or `production`, supply GCP project/region. Updates deploy with
`no_traffic`, tag a candidate, smoke `/readyz` with an ID token, then promote.
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

### Local gate before triggering release

```bash
./gradlew ciReleaseGate
```

This mirrors CI **tiers 1 and 5** locally (quality gates + unit tests + R8 +
release assemble). It does **not** run the backend suite, the emulator tier, or
the two always-on gates. Run those separately:

```bash
python scripts/render_legal_pages.py --check                     # legal-pages
cd backend && pytest tests/ -q --cov=app --cov-fail-under=75     # tier 4
./gradlew :app:connectedDebugAndroidTest -PabiFilters=x86_64     # tier 3
```

Engine host and sanitizer suites are not part of this repo's gate — they run in
`sempermechanics/semper-dic-engine` against the commit this repo pins.

## After the release

- Verify a fresh install from the distributed artifact (not a dev build).
- **Upgrade smoke (manual):** install the previous Release APK → create a local
  analysis (and optionally enqueue upload/restore) → install the new APK
  *without uninstalling* → open Home, reopen the session, confirm queued
  WorkManager unique work still resolves. Prefs migrate via `DicSettings.migrate`
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
