# Alpha usage report (fill during device matrix)

Date (UTC): 2026-08-08  
API project: `indicvision-dic-app` (Cloud Run / Firestore)  
Auth project: `indicvision-dic-app-auth`  
Backend revision (must include `opClass`): deploy of PR #20+  
App version / tag: `v1.0-beta.2`+ (upload-loop / SSO fixes as available)  
Tester uid: `HZmfMd2N8LfyyDTXwqql32apuwq2` (`damodar@indicvision.com`)  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Notable paths | Frames / files | Notes (Drive vs Cloud Run) |
|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync |  |  |  |  | — |  |
| 2 | Single (small) |  |  |  |  |  | Upload loop blocked early alpha; re-measure after prepare-retry fix |
| 3 | Sweep (small) |  |  |  |  |  |  |
| 4 | Restore |  |  |  |  |  | Cloud Run egress |
| 5 | Delete backup |  |  |  |  | — |  |
| 6 | Heavy PLC band |  |  |  |  |  | `AAA5083_H111 - PLC band` |

## Blockers / gate

- [x] Backend with `opClass` shipped
- [x] Beta APK installed; tester logged in
- [ ] Full matrix metered with `opClass` log CSV (upload prepare-loop delayed fills)
- [ ] PLC-band `Session.zip` byte size measured once (Drive file size)

## PLC usage & cloud cost model (engineering estimate)

Device matrix rows above are still sparse. Until one heavy PLC backup’s
`frameCount` / Drive `Session.zip` size is pasted into the table, costs use the
**call recipes** in [ALPHA_USAGE_METERING.md](ALPHA_USAGE_METERING.md) plus the
Always Free ceilings in [CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md) §14
and current Google Cloud Free Tier docs.

### Planner assumptions (per user)

| Parameter | Value | Why |
|---|---|---|
| PLC tests / month | **20–30** (model mid **25**) | User-stated |
| Lifetime PLC tests | **50–100** (model mid **75**) | User-stated |
| Analysis cloud cost | **$0** | DIC runs fully on-device |
| Backup shape | 2 Cloud files: `metadata.json` + `Session.zip` | Current client |
| Default cloud retention | **`maxSessions = 4`** | `MAX_SESSIONS_PER_USER` — old backups must be deleted to keep uploading |
| Session size bands | **S 0.3 GB / M 1.0 GB / L 2.0 GB** | PLC raw + `.dat` + reports; confirm with one Drive listing |
| Restores / month | **0–2** (model **1**) | Occasional phone wipe / free-space restore |
| Home sync | ~several times / day | Throttled ~5 min; small JSON |

**Important:** monthly *ingest* can be 20–30× session size, but **steady Drive
footprint stays ≈ 4 × session size** while the default quota is 4. Lifetime
50–100 tests only grow Drive if retention is raised or deletes are never done.

### What one PLC backup burns (Cloud Run)

| Step | opClass | Approx requests |
|---|---|---|
| `attest` + `POST /v1/sessions` | attest + backup | 2 |
| Poll `GET …/uploads` while provisioning | backup (+ attest) | ~6–12 |
| 2 × (`attest` + `POST …/complete`) | attest + backup | 4 |
| Cloud Tasks → `POST /v1/tasks/provision-session` | backup | 1 |
| **Total Cloud Run / backup** | | **~15–20** (use **19**) |

Bytes of the zip go **device → Google Drive**, not through Cloud Run.

### Daily usage (≈ 1 PLC test / day average)

25 tests ÷ 30 days ≈ **0.8–1 PLC backup / day**.

| Resource | Daily (1 PLC + login + sync) | Free / pool limit | Headroom |
|---|---|---|---|
| Cloud Run requests | ~40–60 | 2M / month | Trivial |
| Cloud Run memory-time | ≪ 1k GiB-s | 360k GiB-s / mo | Trivial |
| Cloud Run **egress** | ~0 (backup bypasses CR) | 1 GB / mo (NA) | OK unless restoring |
| Firestore writes | ~20–40 | 20k / **day** | ≪ 1% |
| Firestore reads | ~50–150 | 50k / **day** | ≪ 1% |
| Drive upload | **0.3–2 GB** | 750 GB / SA / day | OK |
| Drive API queries | tens–low hundreds | 12k / min | OK |
| Cloud Logging | ≪ 100 MB | 50 GiB / mo | OK |
| Firebase Auth | few token refreshes | generous free | OK |
| Cloud Tasks | 1 | 1M / mo class free | OK |

### Monthly usage (25 PLC tests)

| Resource | Monthly @ mid (25 × M 1 GB) | Free / pool | Fits free? |
|---|---|---|---|
| Cloud Run requests | ~1.2k (backup) + ~0.8k sync/login ≈ **~2k** | 2M | **Yes** |
| Cloud Run GiB-s / vCPU-s | ~100–300 | 360k / 180k | **Yes** |
| Firestore writes | ~0.4–1k | 20k/**day** (600k/mo ceiling) | **Yes** |
| Firestore storage | ≪ 100 MB metadata | 1 GiB | **Yes** |
| Drive **ingest** | **~7.5 / 25 / 50 GB** (S/M/L) | 5 TB Workspace pool | **Yes** |
| Drive **retained** (quota 4) | **~1.2 / 4 / 8 GB** | same pool | **Yes** |
| Drive retained if keep-all 100 L | up to **~200 GB** | 5 TB | Yes, but wastes pool |
| Cloud Run restore egress | **~0–2 GB** if 0–2 restores | 1 GB free NA | **May bill ~$0.12/GB** after 1 GB |
| API Gateway calls | ~same as CR ~2k | ~$3 / million | **~$0.01** if billed |
| Logging | ≪ 1 GiB | 50 GiB | **Yes** |

### Approx monthly cloud **bill** (GCP project)

| Scenario | GCP $ / user / month | Notes |
|---|---|---|
| **Backup-only, no restore** (20–30 PLC) | **≈ $0** | Inside Always Free; Drive uses paid Workspace 5 TB (already sunk) |
| **+ 1 restore of ~1 GB** | **≈ $0 – $0.12** | First 1 GB CR egress free; second GB ~list price |
| **+ 2 restores of ~2 GB** | **≈ $0.12 – $0.36** | Still noise |
| **10 users, backup-only** | **≈ $0** | Still inside free request/Firestore caps |
| **100 users, backup-only, 25 PLC ea** | **≈ $0–few $** | Watch Firestore daily writes & Drive 750 GB/day SA cap |

**Drive storage is not billed as GCS** — it counts against the **already-paid
Workspace Shared Drive (5 TB)**. Marginal GCP invoice for PLC backup traffic is
effectively **zero** at this cadence.

### Lifetime (50–100 PLC) — what actually grows

| Policy | Drive footprint (M = 1 GB/session) | GCP $ |
|---|---|---|
| Keep default **maxSessions=4**, delete as you go | **~4 GB** steady | ~$0 |
| Keep every session (raise quota / never delete) | **50–100 GB** | ~$0 GCP; burns Workspace pool |
| Occasional restores over years | egress cents when used | — |

### Where money *would* show up (avoid these)

1. **Frequent restores of large PLC zips** — bytes proxy through Cloud Run → egress + CPU.
2. **Raising retention to hundreds of GB × many users** — hits the **5 TB Drive pool**, not the GCP bill.
3. **Deep sync with Drive verify** on huge libraries — extra Drive API + Firestore reads.
4. **Migrating blobs to GCS later** — then storage ~$0.02/GB-mo + egress ~$0.12/GB (see architecture §15).

### Concrete takeaway

For **one power user doing 20–30 PLC backups/month** (lifetime 50–100), with
default 4-slot cloud retention and rare restore:

- **Daily:** well under every free-tier daily cap; ~0.3–2 GB to Drive per test day.
- **Monthly GCP cash cost: ≈ $0** (expect **under $1** even with a couple of restores).
- **Real scarce resource:** Workspace Shared Drive capacity and the **4-session
  quota**, not Cloud Run dollars.
- **To harden the estimate:** fill scenario #6 with `Session.zip` size (bytes) and
  one `gcloud logging` opClass CSV after a successful backup — then replace the
  S/M/L bands with a measured mid.

## Raw query notes

```bash
# After a successful PLC backup window:
./scripts/meter_alpha_usage.sh \
  --project indicvision-dic-app \
  --uid HZmfMd2N8LfyyDTXwqql32apuwq2 \
  --start START_RFC3339 --end END_RFC3339
```

Paste CSV + Drive `Session.zip` size here when measured.
