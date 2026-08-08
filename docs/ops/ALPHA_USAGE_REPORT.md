# Alpha usage report (fill during device matrix)

Date (UTC): 2026-08-08  
API project: *(fill from deploy)*  
Backend revision (must include `opClass`): *(confirm before counting)*  
App version / tag: **v1.0-beta.7** (#28 URI map, #30 restore timeout/Range-resume, #31 complete Session.zip)  
Tester uid: *(from Firebase / logs)*  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Notable paths | Frames / files | Notes (Drive vs Cloud Run) |
|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync | 2026-08-08T04:35:21Z | 2026-08-08T04:35:45Z | *(pending query)* | | — | Done (pre–beta.6) |
| 2 | Single (small) | 2026-08-08T05:52:38Z | 2026-08-08T05:53:44Z | *(pending query)* | | | Done (`dine:2`→`done:2`). ~66s window — confirm badge **synced** on device |
| 3 | Sweep (small) | 2026-08-08T05:54:45Z | 2026-08-08T05:56:06Z | *(pending query)* | | | Synced but **incomplete zip** (csv/reports/processed missing). Fix [#31](https://github.com/semperdic/semperdic-app/pull/31); re-upload after beta |
| 4 | Restore | 2026-08-08T06:46:02Z | | | | | **In progress** (retry on beta.7). Prior attempt `05:56:47Z` empty HTTP 500 on `accd2126…` |
| 5 | Delete backup |  |  |  |  | — |  |
| 6 | Heavy PLC band |  |  |  |  |  | `AAA5083_H111 - PLC band` |

## Blockers / gate

- [ ] Backend with `opClass` deployed
- [x] Beta APK installed (target **v1.0-beta.7**)
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
- End: *(await `done:4`)*
- Prefer a **small** COMPLETED cloud session (not the incomplete sweep zip from #3)

Cloud agent has no `gcloud` credentials in this environment — opClass counts need a local/laptop query or secrets wired later.

## Phase 1 status (automated)

- Metering docs: https://github.com/semperdic/semperdic-app/pull/29
- Branch: `cursor/alpha-release-usage-metering-819b`
- Upload URI fix: #28 → **v1.0-beta.6+**
- Restore timeout / Range-resume: #30 → merged; Cloud Run deploy success `06:26Z`; **v1.0-beta.7**
- Incomplete Session.zip: #31 → **v1.0-beta.7**
- Device phase: matrix **#4 Restore** retry started `2026-08-08T06:46:02Z` (`start:4`)
- Cloud agent has no ADB to the laptop Pixel 3; use human-driven checklist in ALPHA_USAGE_METERING.md.
