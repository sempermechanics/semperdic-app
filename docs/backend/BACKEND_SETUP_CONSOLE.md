# Backend setup — web console path (no terminal)

The same provisioning and deploy as [BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md),
done entirely in the browser. Pick this path if you would rather not install
`gcloud`; pick the CLI runbook if you want it scripted and repeatable.

**Requires:** the `backend/` folder pushed to a GitHub repo (GitHub Desktop or
your IDE is fine) — Cloud Run builds from GitHub, so no local Docker or
`gcloud` is needed.

Steps marked with a number are console clicks; each ends with a **Check**.
When you are done here, continue with **Part C** of the CLI runbook to point
the app at your deployment.

---

## 1. Select / create the project
1. Open <https://console.cloud.google.com>.
2. Top bar → **project picker** → **New Project** (or pick an existing one).
   Name it e.g. `semper-prod`. Note the **Project ID**.

## 2. Enable the APIs
1. Left menu (☰) → **APIs & Services → Enabled APIs & services**.
2. Click **+ Enable APIs and services**. Search for and **Enable** each of these
   (one at a time):
   - Cloud Run Admin API
   - Cloud Firestore API
   - Google Drive API
   - IAM Service Account Credentials API
   - Artifact Registry API
   - Cloud Build API
   - Cloud Tasks API *(only if you do step 8a — async provisioning)*

## 3. Create the Firestore database
1. ☰ → **Firestore**.
2. **Create database** → **Native mode** → choose a location (e.g.
   `asia-south1`; remember it — Cloud Run should use the same region).
3. **Create**.

### 3a. Enable the TTL policy on auth challenges
Every `/v1/challenge` writes a `challenges/{nonce}` document. Consumed nonces are
deleted immediately; abandoned ones only disappear if Firestore is told to treat
`expireAt` as a TTL field, so without this they accumulate forever.

1. ☰ → **Firestore → Time-to-live (TTL)**.
2. **Create policy** → collection group `challenges`, timestamp field
   `expireAt` → **Create**.
3. Wait until the policy shows **Active** (a few minutes).
4. Repeat for the licence hold: collection group `deleted_licenses`, field
   `purgeAt`; and collection group `deleted_seats`, field `purgeAt`. A deleted
   licence (and its seats) waits there 30 days for a possible restore; without
   these two policies it is never purged. Restore still refuses one past
   `purgeAt`, so a missing policy only keeps data longer than promised.

All three policies are also declared as `fieldOverrides` (`"ttl": true`) in
[`backend/firestore.indexes.json`](../../backend/firestore.indexes.json), so
`scripts/deploy-firestore.sh indexes` creates any that are missing and leaves
live ones alone. The Firebase CLI treats a live TTL policy the file omits as
an override to delete: without `--force` a non-interactive deploy stops, and
with it the policy is gone. Never pass `--force`. Each override also restates
the field's single-field indexes (ascending, descending, array-contains, the
database default), because an override with empty `indexes` turns single-field
indexing off. Add a new TTL policy to the file as well as here.

### 3b. Create the composite indexes
Four composite indexes back the duplicate-session lookup
(`repo/sessions.py`) and the staff licence list (`repo/license_admin.list_licenses`).
A missing one is not caught at deploy time — it fails at runtime with
`FAILED_PRECONDITION`, so create them before the first real client.

The reliable route is one command, even in a console-first setup (Git Bash,
Firebase CLI on Node >= 20; it stages the file beside a `firebase.json` of its
own, because the CLI refuses files outside its project directory):

```bash
PROJECT=<project-id> ./scripts/deploy-firestore.sh indexes
```

To do it by hand instead, ☰ → **Firestore → Indexes → Composite → Create index**,
and reproduce each entry from
[`backend/firestore.indexes.json`](../../backend/firestore.indexes.json):

| Collection | Fields |
|---|---|
| `sessions` | `uid` ↑, `localSessionId` ↑, `status` ↑ |
| `licenses` | `mode` ↑, `createdAt` ↓ |
| `licenses` | `mode` ↑, `status` ↑, `createdAt` ↓ |
| `licenses` | `status` ↑, `createdAt` ↓ |

↑ ascending, ↓ descending; all collection scope. Equality filters ordered by
`__name__` (`sessions(uid)`, `users(access_status)`, `files(sessionId)`) need no
composite: the automatic single-field indexes serve them, and the index API
refuses a `field + __name__` entry as unnecessary.

## 4. Create the runtime service account
1. ☰ → **IAM & Admin → Service Accounts**.
2. **+ Create service account**. Name: `indic-api`. **Create and continue**.
3. On "Grant this service account access": add role **Cloud Datastore User**,
   then **+ Add another role** → **Logs Writer**. **Continue → Done**.
4. Copy its email — `indic-api@<project-id>.iam.gserviceaccount.com`.

## 5. Let the SA impersonate itself (the keyless-Drive grant)
1. Still in **Service Accounts**, click the **indic-api** account.
2. Open the **Permissions** tab → **Grant access**.
3. **New principals:** paste the **same** `indic-api@…` email.
4. **Role:** **Service Account Token Creator**. **Save**.

> This is what lets Cloud Run mint a Drive-scoped token with no JSON key.

## 6. Add the SA to the Shared Drive
1. Open <https://drive.google.com> → left menu → **Shared drives** →
   `Semper-Research-Storage` (create it if needed: **New**).
2. **Manage members** (top-right people icon) → paste the `indic-api@…` email →
   role **Manager** → **Send/Share**. (Manager — not Content manager — or
   account/analysis deletion cannot erase the files; see A5.)
   - If Drive refuses because it's outside your org, a **Workspace admin** must
     allow it once (Admin console → Apps → Google Workspace → Drive and Docs →
     Sharing → allow adding members outside the organization).
3. Get the **Shared Drive ID**: open the Shared Drive; the browser URL is
   `https://drive.google.com/drive/folders/<THIS_IS_THE_ID>`. Copy it.

## 7. Note your Firebase project id
Sign-in runs on Firebase Authentication, and the backend accepts ID tokens
issued by that project. Open the [Firebase console](https://console.firebase.google.com)
→ your project → **Project settings**, and copy the **Project ID**. If Firebase
Auth lives in the same project as this backend, you can skip the
`FIREBASE_PROJECT_ID` variable below entirely. Setup details:
[AUTH_SETUP.md](AUTH_SETUP.md).

## 8. Deploy to Cloud Run

**Canonical path:** run
[`deploy-backend.yml`](../../.github/workflows/deploy-backend.yml) from Actions
(`staging` or `production`) after WIF secrets and vars are set — see
[ENVIRONMENTS.md](../ops/ENVIRONMENTS.md) and
[BACKEND_SETUP_GCP.md](BACKEND_SETUP_GCP.md). That workflow builds from
`backend/`, smokes a tagged candidate, then shifts traffic. Production should
use `--no-allow-unauthenticated` at the Cloud Run layer (API Gateway in front).

**Alternate (console / Cloud Build continuous deploy)** — useful for a first
manual create or labs, not the pilot CD path:

1. ☰ → **Cloud Run** → **Create service**.
2. Select **Continuously deploy from a repository (source or function)** →
   **Set up with Cloud Build**.
3. **Repository provider: GitHub** → authorize / install the Google Cloud Build
   app for your repo → pick the **repository** and **branch** (e.g. `main`).
4. **Build configuration:**
   - **Build type: Dockerfile**
   - **Source location / Dockerfile path:** `/backend/Dockerfile`
   - If asked for a **build context directory**, set it to `/backend`.
   - **Save**.
5. **Service settings:**
   - **Region:** the same as Firestore (e.g. `asia-south1`).
   - **Authentication:** for a console smoke test you may **Allow unauthenticated
     invocations**; production pilot uses gateway + authenticated Cloud Run.
6. Expand **Container(s), Volumes, Networking, Security**:
   - **Security tab → Service account:** select **indic-api**.
   - **Container → Variables & Secrets → + Add variable**, add each:

     | Name | Value |
     |---|---|
     | `SERVICE_ACCOUNT_EMAIL` | `indic-api@<project-id>.iam.gserviceaccount.com` |
     | `SHARED_DRIVE_ID` | the ID from step 6 |
     | `GOOGLE_CLOUD_PROJECT` | your Project ID |
     | `FIREBASE_PROJECT_ID` | the Firebase project id from step 7 — omit if it is the same as above |
     | `AUTO_APPROVE_HD` | your domain, e.g. `yourdomain.com` — verified emails there are approved on first sign-in |
     | `ADMIN_EMAILS` | admin addresses, space-separated (commas and `;` also parse) |
     | `SUPPORT_EMAIL` | where "a new user is waiting for approval" mail goes — defaults to `support@sempermechanics.com` |
     | `NOTIFY_FROM` | verified Resend sender, e.g. `Semper <noreply@yourdomain.com>` — leave unset to disable notification mail |

     Optional, all with working defaults — add only the ones you need:

     | Name | Default | What it does |
     |---|---|---|
     | `DEMO_MAX_ANALYSES` | `25` | Cloud analyses for an unlicensed user (overridable per user by an admin) |
     | `LICENSED_MAX_SESSIONS_PER_USER` | `999` | Cloud analyses for a licensed user |
     | `MAX_FILES_PER_SESSION` | `600` | Files in one analysis |
     | `MAX_FRAMES_PER_ANALYSIS` | `500` | Deformed-frame ceiling |
     | `ROOT_FOLDER_ID` | the Shared Drive | A folder inside the drive to root everything under |
     | `TASKS_PROVISION_WORKERS` | `8` | Fan-out when the provisioning task opens resumable sessions |

   - **Container → Variables & Secrets → + Reference a secret** for the API key
     (it must not be a plain variable): name `RESEND_API_KEY`, secret
     `resend-api-key`, version `latest`, exposed as an environment variable.
     Create the secret first in **Security → Secret Manager**, and grant
     **indic-api** the *Secret Manager Secret Accessor* role on it. Skip this and
     the backend just sends no notification mail — nothing breaks.

   - (Resources) CPU 1, Memory 512 MiB, Min instances 0, Max 10.
7. **Create.** Wait for the build+deploy to finish; copy the service **URL**
   (looks like `https://semper-api-xxxx.a.run.app`).

## 8a. Cloud Tasks queue for session provisioning (recommended)

`POST /v1/sessions` has to open one Drive resumable session per file. At the
600-file ceiling that is roughly 1200 sequential round-trips, which does not fit
in a 60-second request — so provisioning runs as a background task and the
request just reserves the session.

Skip this and the service provisions **inline** instead. That is correct and is
how local dev and the tests run, but a large analysis will time out.

1. ☰ → **Cloud Tasks → Create queue**. Name `semper-provision`, region the same as
   Cloud Run. Set **Max attempts** 5 and **Max concurrent dispatches** 20.
2. ☰ → **Cloud Run → semper-api → Permissions → Add principal**: the
   `indic-api@…` service account, role **Cloud Run Invoker**. Cloud Tasks calls
   back in with an OIDC token for this identity.
3. ☰ → **IAM & Admin → IAM → Grant access**: the same service account, role
   **Cloud Tasks Enqueuer**.
4. Back in Cloud Run → **Edit & deploy new revision → Variables & Secrets**, add:

   | Name | Value |
   |---|---|
   | `TASKS_QUEUE` | `semper-provision` |
   | `TASKS_LOCATION` | your region |
   | `TASKS_TARGET_BASE_URL` | the **Cloud Run** service URL, not the gateway |
   | `TASKS_INVOKER_SA` | `indic-api@<project-id>.iam.gserviceaccount.com` |

`TASKS_TARGET_BASE_URL` must be the Cloud Run URL exactly: the callback is not
part of the public API, and the same string is the OIDC audience the service
checks the token against.

## 8b. Upload targets are always attested

`GET /v1/sessions/{sid}/uploads` returns Drive upload capability URLs and always
requires a device signature; there is nothing to set. The
`REQUIRE_ATTESTED_UPLOADS` flag that used to open an ID-token-only window was
retired on 2026-09-26 (TD-45) and is no longer read. If a service still carries
it, remove it after the next promote (`--remove-env-vars REQUIRE_ATTESTED_UPLOADS`).

## 9. Verify in the browser
1. Visit `https://<your-url>/healthz` → you should see
   `{"ok":true}`.
2. Visit `https://<your-url>/docs` → the interactive API page loads. (Calls will
   return 401 until dev mode is on — next step.)

## 10. Prove Drive + Firestore work (browser only, no curl)
Temporarily enable dev mode so you can call the API without a signed request:
1. **Cloud Run** → click **semper-api** → **Edit & deploy new revision**.
2. **Variables & Secrets** → add `DEV_INSECURE_AUTH` = `1`,
   `INSECURE_AUTH_I_ACCEPT_THE_RISK` = `1`, and `AUTO_APPROVE` = `1` →
   **Deploy**. The second variable is required on a deployed service: without
   it the container refuses to start, so nobody bypasses auth by accident.
3. Open `https://<your-url>/docs` → expand **POST /v1/sessions** → **Try it out**
   → paste this body → **Execute**:
   ```json
   {
     "specimen": "smoke-test",
     "files": [
       { "name": "note.txt", "role": "metadata", "bytes": 9,
         "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa" }
     ]
   }
   ```
   You get **200** with a `sessionId`.

   **If you set up the Cloud Tasks queue (8a), `uploads` comes back empty** and
   the status is `PROVISIONING` — that is correct, and is what the app sees. The
   targets are opened by a background task a second or two later. Expand
   **GET /v1/sessions/{sessionId}/uploads**, put your `sessionId` in and
   **Execute**; repeat until it reports `UPLOADING` with one `uploadUrl` per
   file. Without the queue, the `uploadUrl` is in the create response directly.

   A session stuck on `PROVISIONING` means the task never arrived — check the
   queue's backlog and that `TASKS_TARGET_BASE_URL` matches the Cloud Run URL
   exactly. `PROVISION_FAILED` means the task ran and Drive rejected it; the
   Cloud Run logs will say why.
4. **Check Drive:** in `Semper-Research-Storage` a tree now exists —
   `Research Storage/user/dev-user/session/<sessionId>/` with `raw`, `processed`,
   `reports`, `metadata` subfolders. ✅ keyless Drive access works.
5. **Check Firestore:** ☰ → **Firestore → Data** → collection `sessions` has your
   `<sessionId>` (status `UPLOADING`), and `files` has a matching doc. ✅ Firestore
   works.

> Completing a file later needs its **`md5`** as well as the `driveFileId` and
> `bytes`. Drive reports an `md5Checksum` for every blob we store, and omitting
> the field is treated as `checksum_mismatch` — a `422`, with the file left
> `PENDING`.

> The actual file-bytes upload (resumable `PUT` straight to `uploadUrl`) can't be
> driven from a plain browser — it needs the app (Part C) or an HTTP client like
> Postman. Steps 3–5 already prove the two things that were risky: **keyless Drive
> writes** and **Firestore writes**.

## 11. Turn dev mode OFF (important)
Cloud Run → **semper-api** → **Edit & deploy new revision** → **Variables &
Secrets** → delete `DEV_INSECURE_AUTH`, `INSECURE_AUTH_I_ACCEPT_THE_RISK` and
`AUTO_APPROVE` → **Deploy**.
Confirm `https://<your-url>/v1/me` now returns **401**.

---

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `/v1/sessions` 500, Drive `404` on folder create | SA not a member of the Shared Drive, or wrong `SHARED_DRIVE_ID`. |
| Deletion reports success but files remain in Drive | SA is **Content manager**, not **Manager**. `files.delete` needs organizer rights; Drive answers 404 rather than 403. Promote the SA to Manager. |
| Drive `403 storageQuotaExceeded` | Writing to the SA's personal 15 GB, not the Shared Drive — `driveId`/membership wrong. Must be a Workspace **Shared** Drive. |
| `403 PERMISSION_DENIED` minting Drive token | SA missing `serviceAccountTokenCreator` **on itself** (step 5), or `iamcredentials` API not enabled (step 2). |
| `iam.serviceAccounts.getAccessToken` denied locally | Your user lacks `tokenCreator` on the SA — see the local-run block. |
| Firestore `NOT_FOUND` / `PermissionDenied` | Firestore DB not created (step 3) or `datastore.user` not granted (step 4). |
| `401 invalid_token` from the app | Token audience is not the project in `FIREBASE_PROJECT_ID` (defaults to `GOOGLE_CLOUD_PROJECT`), or the token expired. Check the deployed env vars. |
| `403 not_approved` | User is `PENDING`; set `access_status: APPROVED` in Firestore, or deploy with `AUTO_APPROVE=1` during pilot. |
| `409 device_conflict` on register | User already has an active device — needs admin rebind (revoke old device in Firestore). |
