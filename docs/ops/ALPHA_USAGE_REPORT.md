# Alpha usage report (fill during device matrix)

Date (UTC): 2026-08-08  
API project: *(fill from deploy)*  
Backend revision (must include `opClass`): *(confirm before counting)*  
App version / tag: **v1.0-beta.13** (#36 STORED zip, #37 home download UX, #38 byte progress)
Tester uid: *(from Firebase / logs)*  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Notable paths | Frames / files | Notes (Drive vs Cloud Run) |
|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync | 2026-08-08T04:35:21Z | 2026-08-08T04:35:45Z | *(pending query)* | | — | Done (pre–beta.6) |
| 2 | Single (small) | 2026-08-08T05:52:38Z | 2026-08-08T05:53:44Z | *(pending query)* | | | Done (`dine:2`→`done:2`). ~66s window — confirm badge **synced** on device |
| 3 | Sweep (small) | 2026-08-08T05:54:45Z | 2026-08-08T05:56:06Z | *(pending query)* | | | Synced but **incomplete zip** (csv/reports/processed missing). Fix [#31](https://github.com/semperdic/semperdic-app/pull/31); re-upload after beta |
| 4 | Restore | 2026-08-08T09:43:18Z | | | | | `start:4` @ beta.13 — restore **re-uploaded** COMPLETED session; watch row % |
| 5 | Delete backup |  |  |  |  | — |  |
| 6 | Heavy PLC band |  |  |  |  |  | `AAA5083_H111 - PLC band` |

## Blockers / gate

- [ ] Backend with `opClass` deployed
- [x] Beta APK installed (target **v1.0-beta.13**)
- [x] Tester confirmed **logged in**
- [ ] PLC-band images on device

## Raw query notes

Paste `gcloud logging read` / `scripts/meter_alpha_usage.sh` snippets here.

### Matrix #2 window

- Start: `2026-08-08T05:52:38Z`
- End: `2026-08-08T05:53:44Z`

```bash
PROJECT_ID=... USER_UID=... FRESHNESS=3h ./scripts/meter_alpha_usage.sh
# Filter rows to [05:52:38Z, 05:53:44Z] for scenario #2.
```

### Matrix #3 window

- Start: `2026-08-08T05:54:45Z`
- End: `2026-08-08T05:56:06Z`

### Matrix #4 window

- Prior (blocked): `2026-08-08T05:56:47Z` — empty HTTP 500 on `accd2126407344f881bcc8753b492004`
- Retry start: `2026-08-08T06:46:02Z`
- Retry fail: local `2026-08-08 12:16:18` — empty HTTP 500 on `88967dd77a8247c09bf43a8ed0b1479a` (same `HTTP 500:` / DicRestoreWorker RETRY shape)
- Root cause: Deploy Backend success only updated **staging**; phone → **API Gateway → production** still on ~60s open-ended `/content` kill. Gateway api-config not refreshed.
- Fix: [#33](https://github.com/semperdic/semperdic-app/pull/33) 1 MiB Range chunks (in beta8).
- beta8 fail: `ZipException: invalid distance too far back` on `198866b594a94fb3b8542b1dd6a5e222` @ local `12:40:25` — truncated/mismatched chunk finalized as Session.zip. Fix [#34](https://github.com/semperdic/semperdic-app/pull/34).
- beta.9 fail: same ZipException @ local `13:10:22` (r8 `40fd61e0…`) — size/sha path passed; **object on Drive is corrupt**. Fix [#35](https://github.com/semperdic/semperdic-app/pull/35) (atomic zip + terminal failure). Re-upload or restore a different session.
- Retry `start:4`: `2026-08-08T09:43:18Z` on **v1.0-beta.13** (#36 STORED + round-trip, #37 row progress, #38 byte %). Prefer session re-uploaded after STORED beta.

Cloud agent has no `gcloud` credentials in this environment — opClass counts need a local/laptop query or secrets wired later.

## Phase 1 status (automated)

- Metering docs: https://github.com/semperdic/semperdic-app/pull/29
- Branch: `cursor/alpha-release-usage-metering-819b`
- Upload URI fix: #28 → **v1.0-beta.6+**
- Restore timeout / Range-resume: #30 → merged; Cloud Run deploy success `06:26Z`; **v1.0-beta.7**
- Incomplete Session.zip: #31 → **v1.0-beta.7**
- Chunked restore (survive 60s gateway): #33 → **v1.0-beta8**
- Corrupt zip / size-validate chunks: #34 → **v1.0-beta.9**
- Atomic Session.zip + terminal corrupt restore: https://github.com/semperdic/semperdic-app/pull/35
- Device phase: #4 in progress — `start:4` @ `09:43:18Z` on beta.13; await `done:4`
- Cloud agent has no ADB to the laptop Pixel 3; use human-driven checklist in ALPHA_USAGE_METERING.md.
