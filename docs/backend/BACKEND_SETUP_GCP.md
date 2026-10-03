# Semper GCP Backend — Setup & Run Runbook

Get the **keyless GCP backend** (`backend/`) deployed and proven end-to-end,
then point the app at it.

**Nobody needs this to work on Semper.** The app builds and runs fully offline
without a backend; do this only if you are deploying the cloud side yourself.

- Prefer clicking to typing? → [BACKEND_SETUP_CONSOLE.md](BACKEND_SETUP_CONSOLE.md)
  covers Parts A–B in the browser, then send you back here for Part C.
- Want to know *why* it is shaped this way? → [CLOUD_ARCHITECTURE_GCP.md](CLOUD_ARCHITECTURE_GCP.md)
- Sign-in trouble specifically? → [AUTH_SETUP.md](AUTH_SETUP.md)

| Part | What you get | Needs |
|---|---|---|
| **A** | GCP project provisioned (APIs, Firestore, service account, IAM, Drive) | `gcloud` |
| **B** | Backend deployed to Cloud Run and smoke-tested with `curl` | `gcloud`, `curl`, python |
| **C** | The Android app talking to your deployment | Android Studio |

> **Read this first — what works today.**
> - ✅ The **backend** (`backend/app/*.py`) is complete: auth, device binding,
>   Firestore, keyless Drive resumable uploads. You can deploy and smoke-test it
>   with `curl` right now (Parts A + B).
> - ✅ The **Android client is implemented** — `data/net/` (SemperApi, TokenStore,
>   TokenProvider), `DeviceKeyManager`, `AuthRepository`, and
>   `DicUploadWorker` (resumable PUT straight to Drive). Part C is the
>   operational path to point the app at your deployment and test it.
> - Cloud sync stays off entirely until `SEMPER_API_BASE_URL` is set (C1), so
>   the app builds and runs fully offline without any of this.
>
> Steps marked 🖐️ happen in a web console (can't be scripted). Do them in order;
> each ends with a **Check**.

---

## 0. Prerequisites

- **Google Cloud SDK** (`gcloud`) installed and `gcloud init` done.
- A **GCP project** you can create resources in (company creates it if needed).
- The **Shared Drive** `Semper-Research-Storage` (or any Shared Drive you own)
  and its **`SHARED_DRIVE_ID`**.
- A **Firebase project** with sign-in providers enabled and its
  `google-services.json` in `app/` — see [AUTH_SETUP.md](AUTH_SETUP.md). Note
  its **project id**: the backend accepts ID tokens issued by it.
- `curl` + `python` (3.12) for the smoke test.

```bash
export PROJECT=semper-prod          # your GCP project id (placeholder — replace)
export REGION=asia-south1          # pick one near you; Firestore must match
export API_SA=indic-api@$PROJECT.iam.gserviceaccount.com
gcloud config set project $PROJECT
```
> **Note:** `$PROJECT` is the Cloud Run / Firestore / Drive project. Firebase Auth
> may live in a different project (currently `indicvision-dic-app-auth` — see
> [AUTH_SETUP.md](AUTH_SETUP.md)); set `FIREBASE_PROJECT_ID` when they differ.
> **PowerShell:** replace `export X=Y` with `$env:X="Y"`, and `$VAR` with `$env:VAR`.

---

## Part A — Provision GCP (one time)

### A1. Enable APIs
```bash
gcloud services enable \
  run.googleapis.com \
  firestore.googleapis.com \
  drive.googleapis.com \
  iamcredentials.googleapis.com \
  artifactregistry.googleapis.com \
  cloudbuild.googleapis.com \
  cloudtasks.googleapis.com
```
`cloudtasks` is only needed if you follow A6 (async provisioning); enabling it
here saves a second round trip later.

**Check:** `gcloud services list --enabled | grep -E 'run|firestore|drive|iamcredentials|artifactregistry|cloudbuild|cloudtasks'`
lists all seven.

### A2. Create the Firestore database (Native mode)
```bash
gcloud firestore databases create --location=$REGION --type=firestore-native
```
**Check:** `gcloud firestore databases list` shows one `(default)` database.

### A2a. Enable the TTL policy on auth challenges
Each `/v1/challenge` writes a `challenges/{nonce}` doc with an `expireAt`
timestamp. Consumed nonces are deleted immediately, but *abandoned* ones only
disappear if Firestore is told `expireAt` is a TTL field — otherwise they
accumulate forever. Set the policy once:
```bash
gcloud firestore fields ttls update expireAt \
  --collection-group=challenges --enable-ttl
```
**Check:** `gcloud firestore fields ttls list --collection-group=challenges`
shows `expireAt` in state `ACTIVE` (may take a few minutes to apply).

### A2b. Deploy the composite indexes

`backend/firestore.indexes.json` declares four composite indexes: the
duplicate-session lookup's (`sessions`: `uid`, `localSessionId`, `status`) and
three for the staff licence list (`licenses`: `mode` + `createdAt` DESC,
`mode` + `status` + `createdAt` DESC, `status` + `createdAt` DESC). Every other query the backend and the consoles run is a single-field equality or
`array-contains`, optionally ordered by `__name__`, and Firestore serves those
from its automatic single-field indexes — do not add `field + __name__` entries
to the file; the index API refuses them ("this index is not necessary") and
aborts the whole deploy. A missing composite does not fail at deploy time — it
fails at runtime with `FAILED_PRECONDITION`, so deploy before the first real
client.

The file has no `firebase.json` of its own, and the CLI refuses files outside
its project directory, so the script stages it in a scratch directory (it
deploys the deny-all `firestore.rules` the same way, with `rules`):

```bash
PROJECT=$PROJECT ./scripts/deploy-firestore.sh indexes
```

**Check:** `gcloud firestore indexes composite list --project $PROJECT` shows
all four in state `READY` (building can take a few minutes on a populated
database).

### A3. Create the runtime service account
```bash
gcloud iam service-accounts create indic-api \
  --display-name="Semper Cloud Run runtime"
```
**Check:** `gcloud iam service-accounts list | grep indic-api`.

### A4. Grant IAM roles (least privilege + keyless Drive)
```bash
# Firestore read/write
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/datastore.user"

# THE KEYLESS-DRIVE GRANT: SA may impersonate ITSELF to add the Drive scope
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="serviceAccount:$API_SA" --role="roles/iam.serviceAccountTokenCreator"

# Log writer
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/logging.logWriter"
```
**Check:** `gcloud iam service-accounts get-iam-policy $API_SA` lists the SA itself as `serviceAccountTokenCreator`.

### A5. 🖐️ Add the SA to the Shared Drive
In **Google Drive → `Semper-Research-Storage` → Manage members**, add
`indic-api@semper-prod.iam.gserviceaccount.com` as **Manager**.
  > Use **Manager**, not Content manager. Content manager can upload but
  > **cannot permanently delete**: `files.delete` needs *organizer* rights on
  > the parent, so GDPR erasure silently fails without it.
- If Workspace blocks adding a service-account principal, a Workspace **admin**
  must one-time-allow it (Admin console → Apps → Google Workspace → Drive and
  Docs → Sharing → permit members outside the org / allow-list).
- **No JSON key and no domain-wide delegation are involved.**

**Check:** the SA appears as **Manager** on the Shared Drive.

### A6. Cloud Tasks queue for session provisioning

`POST /v1/sessions` opens one Drive resumable session per file. At the 600-file
ceiling that is ~1200 sequential round-trips, which does not fit in a 60 s
request — so provisioning runs as a Cloud Task and the request just reserves the
session. The client polls `GET /v1/sessions/{sid}/uploads`, which it already
does for resume.

Without a queue the service provisions **inline** instead: correct, but slow,
and large analyses will time out. Small deployments can skip this.

```bash
gcloud services enable cloudtasks.googleapis.com --project $PROJECT

gcloud tasks queues create semper-provision \
  --location=$REGION --project=$PROJECT \
  --max-attempts=5 --max-concurrent-dispatches=20

# Cloud Tasks delivers with an OIDC token for this SA. Reusing $API_SA keeps it
# to one identity; the service only accepts tokens whose email matches
# TASKS_INVOKER_SA, so this is the identity /v1/tasks/* trusts.
gcloud run services add-iam-policy-binding semper-api \
  --member="serviceAccount:$API_SA" --role="roles/run.invoker" \
  --region=$REGION --project=$PROJECT

# The API SA enqueues its own tasks.
gcloud projects add-iam-policy-binding $PROJECT \
  --member="serviceAccount:$API_SA" --role="roles/cloudtasks.enqueuer"

# A task that carries an OIDC token for $API_SA can only be created by a
# caller allowed to act as $API_SA — here, itself. Scoped to that one SA, not
# the project. Without it every enqueue fails with 403
# iam.serviceAccounts.actAs and the service quietly provisions inline.
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="serviceAccount:$API_SA" --role="roles/iam.serviceAccountUser" \
  --project=$PROJECT
```

Then set these on the service (GitHub Environment or **repo-level** `vars` for
the deploy workflow — Free private orgs often use repo-level — or
`--set-env-vars` by hand):

| Variable | Value |
|---|---|
| `TASKS_QUEUE` | `semper-provision` |
| `TASKS_LOCATION` | `$REGION` |
| `TASKS_TARGET_BASE_URL` | the Cloud Run service URL (not the gateway) |
| `TASKS_INVOKER_SA` | `$API_SA` |

`TASKS_TARGET_BASE_URL` is the **Cloud Run** URL on purpose: the callback is not
part of the public API and is absent from `gateway/openapi.yaml`. It is also the
OIDC audience, so it must match exactly.

Manifests of up to `INLINE_PROVISION_MAX_FILES` (default 8 — a bundle upload is
three) are still provisioned inside the request: the task hop plus the
client's first poll cost more than the work.

**Check:** create a session with more than eight files; the response is
`{"status": "PROVISIONING", "uploads": []}` and, within a second or two,
`GET /v1/sessions/{sid}/uploads` reports `UPLOADING` with one target per file.
`gcloud tasks queues describe semper-provision --location=$REGION` should show no
backlog.

### A7. Storage hygiene

Every source deploy pushes an image of about 80 MB to Artifact Registry and a source
tarball to a bucket. Nothing removes them by default. Registry storage above 0.5 GB is
billed, so the registry grows without bound. Once the first deploy has created both, add
the two rules below. Run them as a project owner: the deploy SA cannot change a repository.

```bash
# Registry: delete versions more than 15 days old, except images tagged `serving` or
# `latest`, any tagged `rollback…` and each package's five newest versions.
gcloud artifacts repositories set-cleanup-policies cloud-run-source-deploy \
  --location=$REGION --project=$PROJECT \
  --policy=backend/deploy/ar-cleanup-policy.json --dry-run

# Source tarballs: only Cloud Build reads them, during the build.
gcloud storage buckets update gs://run-sources-$PROJECT-$REGION \
  --lifecycle-file=backend/deploy/run-sources-lifecycle.json
```

`--dry-run` stores the policy but deletes nothing. Before switching it on, list what it
would remove:

```bash
gcloud artifacts docker images list \
  $REGION-docker.pkg.dev/$PROJECT/cloud-run-source-deploy \
  --include-tags --sort-by=~CREATE_TIME
```

Every version older than 15 days goes, unless it is tagged `serving` or `latest`, has a tag
starting `rollback`, or is one of its package's five newest. Then re-run the first command with
`--no-dry-run` in place of `--dry-run`.

**Changing the rules later.** `--dry-run` is a setting on the repository, not on the one
call: re-running the first command as written switches an enforcing repository back to
dry run, and cleanup stops without any error. Once the policy is enforcing, apply an edited
`ar-cleanup-policy.json` with `--no-dry-run`:

```bash
gcloud artifacts repositories set-cleanup-policies cloud-run-source-deploy \
  --location=$REGION --project=$PROJECT \
  --policy=backend/deploy/ar-cleanup-policy.json --no-dry-run
```

`list-cleanup-policies` (see **Check**) should still print "Dry run is disabled".

Artifact Registry does not record when an image was last pulled, so "unused" here means
"uploaded more than 15 days ago". The keep rules are there because Cloud Run needs a
revision's image each time it starts an instance, and at `--min-instances 0` that happens
after every idle spell.

**Which tags the deploy moves.** A source deploy tags each image it builds `latest` before
the smoke, so `latest` is the newest build, not necessarily the one serving. After the
promote, `deploy-backend.yml` tags by digest: `serving` on the image now taking traffic,
and `rollback-prev` on the image of the revision it replaced. So each package keeps the
serving image and one rollback target, and a failed smoke leaves `serving` on the image
still serving. Moving a tag that already exists needs `artifactregistry.tags.delete`,
which `roles/artifactregistry.writer` lacks, so the deploy SA also holds
`roles/artifactregistry.repoAdmin` on this repository alone
([CLOUD_ARCHITECTURE_GCP.md](CLOUD_ARCHITECTURE_GCP.md) has the grant). If the step fails
(an error annotation on the run; the deploy stands), re-pin by hand:

```bash
IMG=$(gcloud run revisions describe <revision> --region=$REGION --format='value(status.imageDigest)')
gcloud artifacts docker tags add "$IMG" "${IMG%@*}:serving"   # or :rollback-prev
```

**Hand-pinned `rollback-<sha>` tags** keep one known-good release beyond `rollback-prev`.
Each is kept until someone deletes it with `gcloud artifacts docker tags delete`, so
whoever adds one owns it: name it after its commit, record it in
[CHANGELOG.md](../ops/CHANGELOG.md), and delete it once a Firestore data change (a field
that release would not write, or would misread) means it should no longer serve.
`rollback-prev` needs none of this, since each deploy moves it. No other earlier revision
is kept: once its image is more than 15 days old and outside its package's five newest, it
is deleted, and routing traffic back to that revision gives it no image to start from. The Firestore backup bucket is not covered: it
has its own retention ([FIRESTORE_DATA_PROTECTION.md](FIRESTORE_DATA_PROTECTION.md)).

**Check:** `gcloud artifacts repositories list-cleanup-policies cloud-run-source-deploy
--location=$REGION` shows four policies. `gcloud storage buckets describe
gs://run-sources-$PROJECT-$REGION --format='value(lifecycle_config)'` shows the 15-day rule.

---

## Part B — Deploy & smoke-test the backend

### B1. Deploy to Cloud Run from source
```bash
gcloud run deploy semper-api \
  --source backend \
  --region $REGION \
  --service-account "$API_SA" \
  --no-allow-unauthenticated \
  --min-instances 0 --max-instances 10 \
  --concurrency 40 --cpu 1 --memory 512Mi --timeout 300 \
  --set-env-vars "SERVICE_ACCOUNT_EMAIL=$API_SA,SHARED_DRIVE_ID=$SHARED_DRIVE_ID,GOOGLE_CLOUD_PROJECT=$PROJECT,FIREBASE_PROJECT_ID=$FIREBASE_PROJECT_ID,AUTO_APPROVE_HD=yourdomain.com,ADMIN_EMAILS=you@yourdomain.com" \
  --set-env-vars "SUPPORT_EMAIL=support@sempermechanics.com,NOTIFY_FROM=Semper <noreply@yourdomain.com>" \
  --set-secrets "RESEND_API_KEY=resend-api-key:latest"
```
> `FIREBASE_PROJECT_ID` can be omitted when Firebase Auth lives in the same
> project as the backend — it defaults to `GOOGLE_CLOUD_PROJECT`. If you do set
> it, export it alongside the other shell variables in §0 first
> (`export FIREBASE_PROJECT_ID=indicvision-dic-app-auth`). See
> [AUTH_SETUP.md](AUTH_SETUP.md) §3 for what `AUTO_APPROVE_HD` and
> `ADMIN_EMAILS` do.

#### Optional environment variables

Everything above is required (or near enough). These are the rest of what
[`config.py`](../../backend/app/config.py) reads, all with working defaults:

| Variable | Default | What it does |
|---|---|---|
| `DEMO_MAX_ANALYSES` | `25` | How many analyses an **unlicensed** user may keep in the cloud. **Not** overridable per user: `resolve_user_config` applies it to every demo account and ignores a `maxSessions` override (`backend/app/repo/user_config.py`, the `else` branch of `resolve_user_config`). Lifting one demo account's cap means attaching a licence (decided 2026-09-23, TD-28) |
| `LICENSED_MAX_SESSIONS_PER_USER` | `999` | The same ceiling for a **licensed** user. The first positive value wins, in this order: the per-user `maxSessions` override, then the licence's `maxAnalyses` (mirrored onto the user as `licenseMaxAnalyses`), then this variable — so an override of 999 beats a key's cap of 100, and a key's cap of 2000 beats this default. It is **not** "whichever is tighter". Whatever wins is then floored at `DEMO_MAX_ANALYSES` (`repo/user_config.py`, `resolve_user_config`): a licence never gives fewer analyses than demo, and a licence `maxAnalyses` below it is refused at mint and PATCH (`models.py`, `_analysis_cap`) |
| `ADMIN_WEB_MFA_ENABLED` | `1` | Whether browser dashboards may act via MFA at all. `0` restores attestation-only admin — every state change then needs the phone |
| `ADMIN_WEB_REAUTH_SECONDS` | `900` | How old a console sign-in may be and still authorise an ordinary state change. Sudo mode, not a session length |
| `ADMIN_WEB_REVOKE_REAUTH_SECONDS` | `120` | Tighter window for whole-licence revoke; the operator page forces password/Google re-auth plus TOTP before that call |
| `MAX_FILES_PER_SESSION` | `600` | Upper bound on files in one analysis |
| `MAX_FRAMES_PER_ANALYSIS` | `500` | Deformed-frame ceiling the app enforces |
| `ROOT_FOLDER_ID` | `SHARED_DRIVE_ID` | A folder inside the Shared Drive to root everything under, instead of the drive root |
| `TASKS_QUEUE` · `TASKS_LOCATION` · `TASKS_TARGET_BASE_URL` · `TASKS_INVOKER_SA` | unset / `asia-south1` / unset / `SERVICE_ACCOUNT_EMAIL` | Async provisioning — see A6. Leave `TASKS_QUEUE` empty to provision inline |
| `TASKS_PROVISION_WORKERS` | `8` | Fan-out when the provisioning task opens resumable sessions |
| `INLINE_PROVISION_MAX_FILES` | `8` | Manifests this small provision inside `POST /v1/sessions` instead of through the queue. `0` sends everything through Cloud Tasks |
| `CLIENT_NONCE_WINDOW_SECONDS` | `120` | How far a device-minted `t1.` nonce's timestamp may be from server time ([CLOUD_ARCHITECTURE_GCP.md §3](CLOUD_ARCHITECTURE_GCP.md)). `0` refuses client nonces, so every signed call fetches a challenge |
| `APP_CHECK_MODE` | `off` | `off` / `monitor` / `enforce`. Whether a caller sending `X-Device-Id` must also carry a valid Firebase App Check token. Roll out through `monitor` — see [AUTH_SETUP.md §3.2](AUTH_SETUP.md). A value outside the three fails startup. **Never `enforce` while a build without App Check is still installed** — every request from it would 403 |
| `LICENSE_GRACE_DAYS_DEFAULT` | `14` | Grace applied at mint time when the request names none. A licence already stored without `graceDays` reads as zero, so changing this never reinstates an expired account |
| `LICENSE_LEASE_HOURS` · `LICENSE_LEASE_HEARTBEAT_MINUTES` | `8` · `30` | Floating-seat lease length and how often the app renews it. A crashed client parks a seat for at most the lease |
| `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` | `30` | Wait between self-service device changes on one licence. `0` disables the wait. Staff-initiated changes ignore it |
| `DEVICE_RELEASE_HOLD_HOURS` | `24` | How long the phone a device-lock clear released is refused at `POST /v1/devices/register`, so its upload worker cannot take the account back before the new phone signs in. Registering any other device ends it; `0` disables it. Not pinned by `deploy-backend.yml` |
| `CONSOLE_ORIGINS` | `https://app.sempermechanics.com https://indicvision-dic-app-auth.firebaseapp.com` | Browser origins the API answers CORS for. The dashboards live on Firebase Hosting, never on the gateway host, so every console call is cross-origin and preflighted; an origin missing here renders a page whose every button silently does nothing. Exact origins, **space-separated** (the deploy action splits its `env_vars` on commas, so a comma-separated value is truncated), no wildcard. The phone sends no `Origin` and is unaffected. The gateway must also carry `allowCors` (step 3 below) or the preflight never reaches Cloud Run |

> **`MAX_SESSIONS_PER_USER` and `PRO_MAX_SESSIONS_PER_USER` are no longer
> read.** The first was the single cloud cap for every user, defaulting to 4;
> `mode` now selects between `DEMO_MAX_ANALYSES` and
> `LICENSED_MAX_SESSIONS_PER_USER` instead (the second, its pre-rename name,
> stopped seeding the licensed default on 2026-09-26, TD-45). An unlicensed user gets
> `DEMO_MAX_ANALYSES` — six times the old ceiling at the default — so **set that
> variable deliberately before deploying** rather than inheriting it.
>
> [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) pins
> `DEMO_MAX_ANALYSES`, `LICENSED_MAX_SESSIONS_PER_USER`, `ADMIN_WEB_MFA_ENABLED`,
> `APP_CHECK_MODE`, `SELF_DEVICE_CHANGE_COOLDOWN_DAYS` and `CONSOLE_ORIGINS` from repository
> variables of the same name, with the defaults above when a variable is unset.
> `deploy-cloudrun` *merges* env into the live revision, so a retired variable
> stays on the service until removed by hand; the workflow's "Describe live
> env" step warns while `MAX_SESSIONS_PER_USER` or `REQUIRE_ATTESTED_UPLOADS`
> (retired 2026-09-26, below) is still present. Remove them **after** promote
> (the previous revision keeps its env for rollback):
>
> ```bash
> gcloud run services update semper-api --region $REGION \
>   --remove-env-vars MAX_SESSIONS_PER_USER,REQUIRE_ATTESTED_UPLOADS
> ```
>
> The deploy workflow promotes with `--to-latest`, so the revision this
> creates serves at once. A service last promoted before that change is still
> pinned to its revision by name and the new one serves **0 %**: check with
> `gcloud run services describe semper-api --region $REGION
> --format='value(status.traffic)'` and, if so, run `gcloud run services
> update-traffic semper-api --region $REGION --to-latest`. The promote also
> removes every `cand-*` traffic tag, so none accumulate.

**Upload targets are always attested.** `GET /v1/sessions/{sid}/uploads` returns
Drive upload capability URLs and requires a device signature unconditionally.
The `REQUIRE_ATTESTED_UPLOADS` flag that held an ID-token-only window open was
retired on 2026-09-26 (TD-45; CLOUD_ARCHITECTURE_GCP.md §20.5 shim 2): the
backend no longer reads it and [`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml)
no longer pins it. Keep the GitHub var at `1` until no redeploy of a commit
older than the retirement is plausible — that older workflow still passes it,
and an empty value there would re-open the window on the old code.

> `NOTIFY_FROM` / `RESEND_API_KEY` drive the "a new user is waiting for
> approval" mail to `SUPPORT_EMAIL` (see B1a). Leave both unset and the backend
> simply sends nothing — no error, no failed sign-ins.

### B1a. Access-request notification mail (optional)

When a new account is created `PENDING`, the backend mails `SUPPORT_EMAIL` with
the account, the uid, and how to approve it. Without this, a pending user is
only discovered by opening **Settings → Access requests** in the app.

Sending goes through [Resend](https://resend.com). Verify your sending domain
there first — an unverified `NOTIFY_FROM` is rejected and only shows up as a
warning in the logs.

`RESEND_API_KEY` is the one secret this service holds, so it goes in Secret
Manager rather than `--set-env-vars`:

```bash
printf '%s' 're_your_key_here' | gcloud secrets create resend-api-key --data-file=-
```

```bash
gcloud secrets add-iam-policy-binding resend-api-key --member "serviceAccount:$API_SA" --role roles/secretmanager.secretAccessor
```

Then deploy with the `--set-secrets` flag shown in B1. Verify by signing in with
a fresh non-admin, non-domain account: mail should land in support@ within
seconds, and `gcloud run services logs read semper-api --region $REGION` should
carry no `access-request mail` warning.

> `--no-allow-unauthenticated` keeps Cloud Run private: only the service
> accounts in C0 invoke it, and the API Gateway is the public door. **Auth is
> still enforced in the app layer** (Firebase ID token + device signature).
> Never deploy with `--allow-unauthenticated` — it binds `allUsers`.
>
> `--min-instances 0` scales to zero, so the service costs nothing while idle. The first
> sign-in or upload after about 15 minutes idle waits for a cold start (measured
> p50 3.9 s, p95 6.0 s). `1` keeps one instance warm for roughly ₹800–1,150 a month, more
> than the whole pilot budget; set the `MIN_INSTANCES` variable only if that latency becomes
> a complaint ([perf/backend-cost.md](../perf/backend-cost.md)).

Grab the URL:
```bash
export URL=$(gcloud run services describe semper-api --region $REGION --format='value(status.url)')
echo $URL
```
**Check:** `curl -s $URL/readyz` → `{"ok":true}`. (`/healthz` on the
`*.run.app` URL answers a Google-frontend 404 in this project without
reaching the container — the deploy workflow uses `/readyz`; the route
exists and `test_health.py` covers it, but do not use it as the smoke check.)
`/readyz` is **not** published at the API Gateway: it costs a Firestore read
and a Drive call per hit and names the failing dependency, so it is reachable
only on the private `*.run.app` URL with an invoker token (the deploy smoke).

### B2. Redeploy in **insecure dev mode** to smoke-test Drive + Firestore

This bypasses the ID-token + device-signature checks (see `DEV_INSECURE_AUTH`
in [config.py](../../backend/app/config.py)) so you can prove the storage path with
plain curl, before any Android work. **Never leave this on.**
```bash
gcloud run services update semper-api --region $REGION \
  --update-env-vars "DEV_INSECURE_AUTH=1,INSECURE_AUTH_I_ACCEPT_THE_RISK=1,AUTO_APPROVE=1"
```

**B2a. Create a session** (declare one tiny file):
```bash
SHA=$(printf 'hello dic' | sha256sum | cut -d' ' -f1)
RESP=$(curl -s -X POST "$URL/v1/sessions" -H "content-type: application/json" -d "{
  \"specimen\":\"smoke-test\",
  \"files\":[{\"name\":\"note.txt\",\"role\":\"metadata\",\"bytes\":9,\"sha256\":\"$SHA\"}]
}")
echo "$RESP"
SID=$(echo "$RESP" | python -c "import sys,json;print(json.load(sys.stdin)['sessionId'])")
```

If you configured a Cloud Tasks queue in A6, this response is
`{"sessionId": "...", "status": "PROVISIONING", "uploads": []}` — the upload
targets are opened by a background task, exactly as the app sees it. Poll the
resume endpoint until they arrive:

```bash
for i in $(seq 1 20); do
  UPRESP=$(curl -s "$URL/v1/sessions/$SID/uploads")
  echo "$UPRESP" | grep -q '"uploadUrl"' && break
  sleep 1
done
echo "$UPRESP"
```

Without a queue, `POST /v1/sessions` returns the targets inline and `$UPRESP`
above is simply the first poll. Either way, pull the target out of it:

```bash
UP=$(echo "$UPRESP"  | python -c "import sys,json;print(json.load(sys.stdin)['uploads'][0]['uploadUrl'])")
FID=$(echo "$UPRESP" | python -c "import sys,json;print(json.load(sys.stdin)['uploads'][0]['fileId'])")
```

If the loop times out, check `sessions/$SID.status` in Firestore: `PROVISION_FAILED`
means the task ran and Drive rejected it (look at the Cloud Run logs), while a
stuck `PROVISIONING` means the task never arrived (check the queue's backlog and
that `TASKS_TARGET_BASE_URL` matches the Cloud Run URL exactly — it is also the
OIDC audience).

**B2b. Upload the 9 bytes directly to Drive** (single-shot; bytes never touch Cloud Run):
```bash
DRIVE=$(printf 'hello dic' | curl -s -X PUT "$UP" \
  -H "content-type: application/octet-stream" \
  -H "Content-Range: bytes 0-8/9" --data-binary @-)
echo "$DRIVE"
DRIVE_ID=$(echo "$DRIVE" | python -c "import sys,json;print(json.load(sys.stdin)['id'])")
MD5=$(echo "$DRIVE" | python -c "import sys,json;print(json.load(sys.stdin).get('md5Checksum',''))")
```

**B2c. Complete the file:**
```bash
curl -s -X POST "$URL/v1/files/$FID/complete" -H "content-type: application/json" \
  -d "{\"sessionId\":\"$SID\",\"driveFileId\":\"$DRIVE_ID\",\"md5\":\"$MD5\",\"bytes\":9}"
```
> The `md5` is not optional. Whenever Drive reports an `md5Checksum` — which it
> does for every binary blob we store — omitting it is treated as
> `checksum_mismatch` and you get a `422` with the file left `PENDING`.

**Check (the payoff):**
- `Semper-Research-Storage/Research Storage/user/dev-user/session/$SID/metadata/note.txt` exists in Drive.
- Firestore → `sessions/$SID` shows `status: COMPLETED`; `files/$FID` shows `driveFileId`.
- Cloud Run logs (`gcloud run services logs read semper-api --region $REGION`) show the requests, no errors.

### B3. Turn dev mode OFF
```bash
gcloud run services update semper-api --region $REGION \
  --remove-env-vars "DEV_INSECURE_AUTH,INSECURE_AUTH_I_ACCEPT_THE_RISK,AUTO_APPROVE"
```
**Check:** `curl -s $URL/v1/me` (no token) → `401 missing_bearer`.

### (Optional) Run locally instead of Cloud Run
```bash
cd backend
python -m venv .venv && source .venv/Scripts/activate   # Git Bash on Windows
pip install -r requirements.txt
gcloud auth application-default login                    # user ADC (no key)
# let your user mint Drive tokens for the SA:
gcloud iam service-accounts add-iam-policy-binding $API_SA \
  --member="user:$(gcloud config get-value account)" --role="roles/iam.serviceAccountTokenCreator"
export GOOGLE_CLOUD_PROJECT=$PROJECT FIREBASE_PROJECT_ID=$FIREBASE_PROJECT \
       SERVICE_ACCOUNT_EMAIL=$API_SA SHARED_DRIVE_ID=$SHARED_DRIVE_ID \
       DEV_INSECURE_AUTH=1 AUTO_APPROVE=1
uvicorn app.main:app --reload --port 8080
```
Then use `URL=http://localhost:8080` in the B2 steps.

---

## Part C — Connect & test the Android app

> **The client code is implemented.** `data/net/` (SemperApi, TokenStore,
> TokenProvider, ApiDtos), the EC-P256 `DeviceKeyManager` (challenge-response),
> `AuthRepository` (Google, email/password, or email-link sign-in) and `DicUploadWorker` (resumable PUT
> direct to Drive) are all in the app. This part is the **operational** steps
> to point the app at your live backend and test it.

### C0. Make the service reachable by the app (**do this first**)

The app authenticates users with a **Firebase ID token**, which is *not* a Cloud
Run **invoker** token. So a **private** Cloud Run service is unreachable by the
app — every call gets Google's HTML `403 Forbidden`. It needs a public front
door.

**Never bind `allUsers`.** Cloud Run stays private; only service accounts (and
the owner, for manual smokes) hold `roles/run.invoker`. In production those are:

| Principal | Why it invokes |
|-----------|----------------|
| `indic-gw@` (gateway SA) | The API Gateway forwards every client call |
| `indic-api@` (runtime SA) | Cloud Tasks delivers `/v1/tasks/provision-session` with its OIDC token |
| `indic-deployer@` | The deploy workflow smokes the tagged candidate at `/readyz` |
| the owner account | Manual smokes; remove when not needed |

Every one of them still needs a Firebase ID token for `/v1/*` — invoker only
gets a request to the container. The public front door is the API Gateway
below; `allUsers` would also expose `/readyz` and the Tasks callback's URL to
anyone.

> **IAP is not an option if you need external (non-domain) users:** IAP
> authorizes via IAM, which the same DRS policy restricts — so IAP can only admit
> your own Workspace domain. For external collaborators, use API Gateway.

**API Gateway (no policy change, supports external users):**
A managed **public** endpoint whose reachability is *not* granted via `allUsers`
IAM, so DRS doesn't block it. The gateway invokes Cloud Run using **its own
service account** (an org-internal principal DRS permits); Cloud Run stays
private. Your FastAPI app-layer auth (Firebase ID token + approval + device
signature) runs unchanged — the client token arrives as
`X-Forwarded-Authorization` (already handled in `deps.py`).

```bash
# Reuse the same $PROJECT you set in Part A — do not reassign it here. Set
# $REGION to wherever you deployed Cloud Run.
REGION=asia-south1
RUN_URL=$(gcloud run services describe semper-api --region $REGION --format='value(status.url)')

# 1. APIs
gcloud services enable apigateway.googleapis.com servicemanagement.googleapis.com \
  servicecontrol.googleapis.com

# 2. Gateway service account, granted invoker on the private Cloud Run service
gcloud iam service-accounts create indic-gw
GW_SA=indic-gw@$PROJECT.iam.gserviceaccount.com
gcloud run services add-iam-policy-binding semper-api --region $REGION \
  --member="serviceAccount:$GW_SA" --role="roles/run.invoker"

# 3. Create the API first: its managed service name is a placeholder input.
#    API Gateway is not offered in asia-south1; the gateways live in
#    asia-northeast1 (GW_REGION) in front of Cloud Run in asia-south1.
GW_REGION=asia-northeast1
gcloud api-gateway apis create semper-api
MANAGED_SERVICE=$(gcloud api-gateway apis describe semper-api \
  --format='value(managedService)')

#    Spec is committed at backend/gateway/openapi.yaml (covers all current
#    routes) with three placeholders. Substitute ALL into a generated copy —
#    the generated file is gitignored because it carries the live hostname.
#    __MANAGED_SERVICE__ goes into x-google-endpoints / allowCors, which lets
#    the browser dashboards' CORS preflights through. It must be the managed
#    service name, not the *.gateway.dev hostname (ESPv2 ignores allowCors on
#    a mismatch and answers OPTIONS with 405).
sed -e "s|__CLOUD_RUN_URL__|$RUN_URL|g" \
    -e "s|__FIREBASE_PROJECT_ID__|$FIREBASE_PROJECT_ID|g" \
    -e "s|__MANAGED_SERVICE__|$MANAGED_SERVICE|g" \
  backend/gateway/openapi.yaml > backend/gateway/openapi.generated.yaml

# Fail loudly rather than shipping a spec with a placeholder still in it.
# Skip comment lines: the header comment of openapi.yaml names the placeholders.
grep -v '^[[:space:]]*#' backend/gateway/openapi.generated.yaml | grep -qE '__[A-Z_]+__' && \
  echo "unsubstituted placeholder remains" && exit 1

# 4. Create the config (with backend-auth SA) and the gateway
gcloud api-gateway api-configs create v1 --api=semper-api \
  --openapi-spec=backend/gateway/openapi.generated.yaml \
  --backend-auth-service-account=$GW_SA
gcloud api-gateway gateways create semper-gw --api=semper-api \
  --api-config=v1 --location=$GW_REGION

# 5. The public gateway URL → this is what the app talks to (C1)
gcloud api-gateway gateways describe semper-gw --location $GW_REGION \
  --format='value(defaultHostname)'
```
Leave Cloud Run **ingress at its default** (`all`) — the gateway calls the
`run.app` URL and only its SA has `run.invoker`, so direct calls still 403; only
the gateway gets through. In **C1**, set `SEMPER_API_BASE_URL` to
`https://<gateway defaultHostname>` (not the `run.app` URL).

#### Redeploying the gateway after a route change

Every dispatch of `deploy-backend.yml` runs a `gateway` job after the Cloud
Run promote ([ADR-006](../adr/ADR-006-gateway-deploy-job.md)): `semper-gw` for
production, `semper-gw-staging` for staging. The
order matters: a config that names a route the backend does not serve yet
would 5xx, and the gateway 404s any route the config does not name.
`test_gateway_parity.py` proves only that the committed spec matches the routers.

Dispatch with `gateway_mode: dry-run` first. It renders the spec and puts its
diff against the live config in the job summary. Re-dispatch with `apply` to
create the config, switch, verify and roll back on failure. The deploy SA needs
`roles/apigateway.admin` on the project and `roles/iam.serviceAccountUser` on
`indic-gw@…`. Until those are granted, or when CI is unavailable, the block
below is the manual fallback. It runs the same steps; for staging, use
`semper-api-staging` (both the service and the API) and `semper-gw-staging`.

API configs are immutable: create a new one and point the gateway at it.

```bash
set -euo pipefail
PROJECT=indicvision-dic-app REGION=asia-south1
# The Firebase Auth project, which is NOT the GCP project here (see §A above).
# An empty value would pass the placeholder guard and produce the issuer
# `https://securetoken.google.com/`, rejecting every token.
FIREBASE_PROJECT_ID=indicvision-dic-app-auth
RUN_URL=$(gcloud run services describe semper-api --region $REGION --format='value(status.url)')
GW_SA=indic-gw@$PROJECT.iam.gserviceaccount.com
GW_REGION=asia-northeast1
MANAGED_SERVICE=$(gcloud api-gateway apis describe semper-api --format='value(managedService)')
for v in RUN_URL FIREBASE_PROJECT_ID MANAGED_SERVICE; do
  [ -n "${!v}" ] || { echo "$v is empty"; exit 1; }
done

sed -e "s|__CLOUD_RUN_URL__|$RUN_URL|g" \
    -e "s|__FIREBASE_PROJECT_ID__|$FIREBASE_PROJECT_ID|g" \
    -e "s|__MANAGED_SERVICE__|$MANAGED_SERVICE|g" \
  backend/gateway/openapi.yaml > backend/gateway/openapi.generated.yaml
if grep -v '^[[:space:]]*#' backend/gateway/openapi.generated.yaml | grep -qE '__[A-Z_]+__'; then
  echo "unsubstituted placeholder remains"; exit 1
fi

# Remember the config currently live — this is the rollback target.
PREV_CFG=$(gcloud api-gateway gateways describe semper-gw --location $GW_REGION \
  --format='value(apiConfig)' | sed 's|.*/||')
echo "rollback: $PREV_CFG"

# New config, named to the minute so a second deploy the same day cannot
# collide; then switch the gateway (takes a few minutes).
NEW_CFG=v$(date -u +%Y%m%d%H%M)
gcloud api-gateway api-configs create $NEW_CFG --api=semper-api \
  --openapi-spec=backend/gateway/openapi.generated.yaml \
  --backend-auth-service-account=$GW_SA
gcloud api-gateway gateways update semper-gw --api=semper-api \
  --api-config=$NEW_CFG --location=$GW_REGION
gcloud api-gateway gateways describe semper-gw --location $GW_REGION \
  --format='value(apiConfig,state)'
```

Earlier versions of this block never set `FIREBASE_PROJECT_ID`, named configs by
day only, and ended the guard with `grep … && exit 1` — which, as a script's
last line, exits 1 exactly when **no** placeholder remains. All three are fixed
above, and the `gateway` job in `deploy-backend.yml` runs the same steps
([ADR-006](../adr/ADR-006-gateway-deploy-job.md)).

Verify from outside: an unauthenticated `GET https://<gateway>/v1/config` must
answer **401** (route known, token missing), not 404 (route missing from the
config). Then the dashboards' preflight:
`curl -si -X OPTIONS https://<gateway>/v1/me -H 'Origin: https://app.sempermechanics.com' -H 'Access-Control-Request-Method: GET' -H 'Access-Control-Request-Headers: authorization'`
must answer **200** with `access-control-allow-origin: https://app.sempermechanics.com`
— a 401/403/405 here means the config lacks `x-google-endpoints … allowCors` or
names the wrong service (it must be the API's managed service name, not the
`*.gateway.dev` host), and
a 200 without the header means the Cloud Run revision lacks that origin in
`CONSOLE_ORIGINS`. Roll back with
`gcloud api-gateway gateways update semper-gw --api=semper-api --api-config=$PREV_CFG --location=$GW_REGION`;
old configs stay listed under `api-configs list --api=semper-api` and can be
deleted once nothing points at them.

### C1. Point the app at the backend

In `local.properties`:
```properties
SEMPER_API_BASE_URL=https://semper-gw-xxxx.an.gateway.dev
```
Blank `SEMPER_API_BASE_URL` = offline-only (cloud disabled). Rebuild after editing.

Release builds go further. The release workflow passes `-PrequireCloudApi=true`,
and `app/build.gradle.kts` then **fails the build** if `SEMPER_API_BASE_URL` is
blank or does not start with `https://` — offline-only must not ship by accident,
and cleartext would put ID tokens and device signatures on the wire in plain
text. Set the variable from the environment or `local.properties` when building a
release locally.

### C2. Make sure Google sign-in works on-device

In the **Firebase console** → Project settings → your Android app, confirm the
**SHA-1** of the keystore you are building with is registered (debug keystore
for a debug build), and that `app/google-services.json` is current. Firebase
creates the matching Android OAuth client for you. Full steps in
[AUTH_SETUP.md](AUTH_SETUP.md).

### C3. Configure the access model

```bash
gcloud run services update semper-api --region asia-south1 \
  --update-env-vars AUTO_APPROVE_HD=indicvision.com \
  --remove-env-vars DEV_INSECURE_AUTH,INSECURE_AUTH_I_ACCEPT_THE_RISK,AUTO_APPROVE
```
- `DEV_INSECURE_AUTH` **off** → real device auth (after this, curl smoke tests no
  longer work; test via the app).
- `AUTO_APPROVE_HD=indicvision.com` → `@indicvision.com` accounts are **APPROVED**
  on first sign-in.
- Any account may sign in regardless — there is no domain gate on
  authentication, deliberately. Non-domain accounts land **PENDING** and use
  the in-app **Request access** button (emails `support@sempermechanics.com`), then
  an admin approves them individually.
- `AUTO_APPROVE` (blanket approve-everyone) **removed**.

Designate admins with `ADMIN_EMAILS` (space-separated; `,` and `;` also
parse) — they're always
approved and can call the admin API:
```bash
gcloud run services update semper-api --region asia-south1 \
  --update-env-vars ADMIN_EMAILS=support@sempermechanics.com
```

**Approve / revoke via the admin API** (ID-token + admin role; no device
signature — works from an in-app admin screen, or from curl while
`DEV_INSECURE_AUTH=1`):
```
GET  /v1/admin/users?status=PENDING     # who's waiting
POST /v1/admin/users/{uid}/approve      # → APPROVED
POST /v1/admin/users/{uid}/revoke       # → SUSPENDED
POST /v1/admin/device-releases          # {"email": …}: free a Demo account's phone
```
Or, without the API, set `access_status: "APPROVED"` on the `users/{uid}` doc in
Firestore directly.

### C4. Build, install, sign in

- Build a **debug** APK and install on a **Google-Play** emulator or a real
  device (plain AOSP images can't do Google Sign-In).
  - The debug auth-skip now only applies when **no** backend is configured. With
    `SEMPER_API_BASE_URL` set (C1), even a debug build runs the **real** sign-in
    flow — so device testing exercises the full path.
- Sign in with a Google account:
  - **`@indicvision.com`** → auto-APPROVED → straight into the app.
  - **Any other account** → **PENDING** screen → **Request access** (emails
    support) → an admin approves them → they tap **Check now** and get in.
- First APPROVED sign-in registers the device public key
  (`/v1/devices/register`) — one user ⇄ one device.

### C5. Run an analysis and verify the upload

- Ensure **Save to cloud** is enabled in settings, run an analysis.
- `DicUploadWorker` fires (network-gated). Verify in Drive:
  ```
  session/<sid>/
  ├── raw/       Reference.png, Deformed_*.png
  ├── csv/       Data_<frame>.csv
  ├── reports/   Master_Report_*.pdf
  └── metadata/  metadata_<frame>.json   (device/time/engine info)
  ```
- Firestore: `sessions/<sid>` → `COMPLETED`; `files/*` have `driveFileId`.
- Backend logs for a run: `gcloud run services logs read semper-api --region asia-south1 --limit 50`.

### C6. Approving outside collaborators

Non-`indicvision.com` users land PENDING. Approve them from the **in-app admin
screen**: sign in with an `ADMIN_EMAILS` account → **Home ▸ settings ▸ Pending
access requests** → **Approve**/**Deny** each. (The button only appears for admin
accounts.) The approved user taps **Check now** and is let in.

### C7. (post-pilot) tighten

- Add Cloud Armor / rate-limiting on the public endpoint.
- Consider minting a backend session JWT via IAM `signJwt` if per-request ID
  token verification becomes a latency concern (see
  [CLOUD_ARCHITECTURE_GCP.md](CLOUD_ARCHITECTURE_GCP.md) §2).

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/v1/sessions` 500, Drive `404` on folder create | SA not a member of the Shared Drive, or wrong `SHARED_DRIVE_ID`. |
| Deletion reports success but files remain in Drive | SA is **Content manager**, not **Manager**. `files.delete` needs organizer rights; Drive answers 404 rather than 403. Promote the SA to Manager. |
| Drive `403 storageQuotaExceeded` | Writing to the SA's personal 15 GB, not the Shared Drive — `driveId`/membership wrong. Must be a Workspace **Shared** Drive. |
| `403 PERMISSION_DENIED` minting Drive token | SA missing `serviceAccountTokenCreator` **on itself** (A4), or `iamcredentials` API not enabled (A1). |
| `iam.serviceAccounts.getAccessToken` denied locally | Your user lacks `tokenCreator` on the SA — see the local-run block. |
| Firestore `NOT_FOUND` / `PermissionDenied` | Firestore DB not created (A2) or `datastore.user` not granted (A4). |
| `401 invalid_token` from the app | Token audience is not the project in `FIREBASE_PROJECT_ID` (defaults to `GOOGLE_CLOUD_PROJECT`), or the token expired. Check the deployed env vars. |
| `403 not_approved` | User is `PENDING`; set `access_status: APPROVED` in Firestore, or deploy with `AUTO_APPROVE=1` during pilot. |
| `409 device_conflict` on register | User already has an active device — needs admin rebind (revoke old device in Firestore). |
