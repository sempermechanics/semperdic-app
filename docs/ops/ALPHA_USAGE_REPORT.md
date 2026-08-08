# Alpha usage report (fill during device matrix)

Date (UTC): 2026-08-08  
API project: *(fill from deploy)*  
Backend revision (must include `opClass`): *(confirm before counting)*  
App version / tag: **v1.0-beta.6** (includes #28 upload URI role/name map)  
Tester uid: *(from Firebase / logs)*  
Device: Pixel 3  

## Scenario results

| # | Scenario | Start UTC | End UTC | opClass counts | Notable paths | Frames / files | Notes (Drive vs Cloud Run) |
|---|---|---|---|---|---|---|---|
| 1 | Warm Home sync | 2026-08-08T04:35:21Z | 2026-08-08T04:35:45Z | *(pending query)* | | — | Done (pre–beta.6) |
| 2 | Single (small) | 2026-08-08T05:52:38Z | | | | | **In progress** — require beta.6; watch for synced badge, no Drive 400 |
| 3 | Sweep (small) |  |  |  |  |  |  |
| 4 | Restore |  |  |  |  |  | Cloud Run egress |
| 5 | Delete backup |  |  |  |  | — |  |
| 6 | Heavy PLC band |  |  |  |  |  | `AAA5083_H111 - PLC band` |

## Blockers / gate

- [ ] Backend with `opClass` deployed
- [x] Beta APK installed (target **v1.0-beta.6**)
- [x] Tester confirmed **logged in**
- [ ] PLC-band images on device

## Raw query notes

Paste `gcloud logging read` / `scripts/meter_alpha_usage.sh` snippets here.

### Matrix #2 window

- Start: `2026-08-08T05:52:38Z`
- End: *(await `done:2`)*

```bash
# After done:2 — set USER_UID / PROJECT_ID, then filter the window above.
PROJECT_ID=... USER_UID=... FRESHNESS=3h ./scripts/meter_alpha_usage.sh
```

## Phase 1 status (automated)

- Metering docs PR: https://github.com/semperdic/semperdic-app/pull/20
- Branch: `cursor/alpha-release-usage-metering-819b`
- Upload URI fix: https://github.com/semperdic/semperdic-app/pull/28 → **merged**; release **v1.0-beta.6**
- Device phase: matrix **#2 Single** started 2026-08-08T05:52:38Z (tester: logged in + `start:2`)
- Cloud agent has no ADB to the laptop Pixel 3; use human-driven checklist in ALPHA_USAGE_METERING.md.
