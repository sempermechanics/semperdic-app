# Alpha usage metering

How to measure cloud API usage during private alpha testing on a real device
(Pixel / sideloaded beta APK). DIC **analysis does not hit the API** — cloud
cost is identity, backup/sync/restore, and (for restore) Cloud Run egress.

Labeled access logs require a backend revision that emits `opClass` (see
`backend/app/observability.py`). Deploy staging/production **before** metering
runs that expect those fields.

Related: [RELEASING.md](RELEASING.md) (alpha APK),
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §14–15 (free
tiers / Drive ceilings), [ALPHA_USAGE_REPORT.md](ALPHA_USAGE_REPORT.md) (filled
scenario table + **PLC daily/monthly cost model**).

## Operation classes (`opClass`)

| opClass | Typical routes |
|---|---|
| `health` | `/healthz`, `/readyz` |
| `login` | `GET /v1/me`, `POST /v1/devices/register` |
| `config` | `GET /v1/config` |
| `attest` | `POST /v1/challenge` (shared by every signed call — do not fold into login/backup) |
| `backup` | `POST /v1/sessions`, `GET …/uploads`, `POST …/complete`, `DELETE /v1/sessions/{id}`, provision task |
| `sync` | `GET /v1/sessions` |
| `restore` | `GET …/files`, `GET /v1/files/{id}/content` |
| `admin` | `/v1/admin/*` |
| `account` | export / erase account |
| `other` | unmatched |

Session create also logs PII-safe ints when present: `fileCount`, `frameCount`
(from the client `metrics` map). Those appear on the same `http_access` line and
in `audit_logs` `SESSION_CREATE.detail`.

## Expected call recipes (current client)

Sources: `DicUploadWorker`, `CloudSync`, `CloudRestore`, `IndicApi`.

### Login (cold, after Firebase Auth)

1. `GET /v1/me` → `login`
2. `GET /v1/config` → `config`
3. `POST /v1/challenge` + `POST /v1/devices/register` → `attest` + `login`

Firebase Auth itself is **not** Cloud Run.

### Home pull-to-sync (throttled ~5 min)

1. Often `GET /v1/config` when quota unknown → `config`
2. Paginated `GET /v1/sessions` → `sync` (each page; deep `verify=true` adds Drive probes server-side)
3. Attested paths also burn `attest` challenges

### Backup after one analysis (2-file Session.zip model)

1. `attest` + `POST /v1/sessions` → `backup` (access log may include `frameCount` / `fileCount`)
2. Poll `GET …/uploads` while `PROVISIONING` → `backup` (+ `attest` each)
3. Device **PUT bytes to Drive** (not Cloud Run)
4. Two × (`attest` + `POST …/complete`) → `backup`
5. Server task `POST /v1/tasks/provision-session` → `backup` (Cloud Tasks → Cloud Run)

Single vs sweep differ on-device; cloud mainly sees metrics (`frameCount`, `isSweep`).

### Restore one session

1. `GET /v1/sessions` (list) → `sync` if list not cached
2. `GET …/files` → `restore`
3. One or more `GET /v1/files/{id}/content` → `restore` (**bytes proxied through Cloud Run**)

### Delete one cloud backup

1. `attest` + `DELETE /v1/sessions/{id}` → `backup`

## Alpha device matrix

Hard gate: **do not start** until the tester confirms they are logged in on Home.

| # | Scenario | App actions | Meter |
|---|---|---|---|
| 1 | Warm Home sync | Pull-to-sync | `sync`, `config`, `attest` |
| 2 | Single (small) | Few frames → Compute → wait upload badge clear | `backup` + counts |
| 3 | Sweep (small) | Small ranges → lattice → View → upload | `backup` + metrics |
| 4 | Restore | Restore one cloud-only / freed session | `restore` (+ egress) |
| 5 | Delete | Delete one cloud-backed row | `DELETE` session |
| 6 | Heavy | `AAA5083_H111 - PLC band` full import → single analysis → upload | `backup`, frames/files, Drive size |

Keep light sets small so the heavy PLC run dominates image-related cost.

## How to meter one scenario

1. Note **UTC start**, tester **uid**, scenario name.
2. Run the scenario; wait until UI settles (upload complete / restore done).
3. Note **UTC end**.
4. Query Cloud Logging (project that hosts Cloud Run), e.g.:

```text
resource.type="cloud_run_revision"
jsonPayload.event="http_access"
jsonPayload.uid="USER_UID"
timestamp>="START_RFC3339"
timestamp<"END_RFC3339"
```

Group / count by `jsonPayload.opClass` and `jsonPayload.routeTemplate`.

gcloud example:

```bash
gcloud logging read \
  'resource.type="cloud_run_revision" AND jsonPayload.event="http_access" AND jsonPayload.uid="USER_UID"' \
  --project=PROJECT_ID \
  --format='csv(timestamp,jsonPayload.opClass,jsonPayload.routeTemplate,jsonPayload.status,jsonPayload.fileCount,jsonPayload.frameCount)' \
  --freshness=2h \
  --limit=500
```

5. Cross-check Firestore `audit_logs` for the same window (`SESSION_CREATE`,
   `UPLOAD_COMPLETE` / complete actions, `FILE_DOWNLOAD`, session delete).
6. Separate **Drive upload bytes** (Drive admin / API metrics; device→Drive) from
   **Cloud Run restore egress** (`GET …/content`).

## Interpreting results

- High `attest` with low `backup` usually means polling / signed GETs, not more uploads.
- `frameCount` on session create tracks analysis size; Cloud Run CPU for backup
  create is mostly metadata — byte cost is Drive until restore.
- Restore cost scales with bundle size through Cloud Run; prefer measuring one
  restore of the heavy PLC session if you need restore-vs-frames data.

## Human-driven checklist (cloud agent cannot see laptop USB)

When the Pixel is attached only to a laptop:

1. Install the private GitHub Release **beta** APK.
2. Copy `AAA5083_H111 - PLC band` onto the phone (or ensure Files picker sees it).
3. Sign in; message the agent **“logged in”**.
4. For each matrix row: message **“start: \<scenario\>”**, perform actions, then
   **“done: \<scenario\>”** with approximate local time or UTC.
5. Agent runs Logging / audit queries and fills the usage report table.
