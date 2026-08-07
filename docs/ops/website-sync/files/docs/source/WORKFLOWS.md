# App workflows — the complete map

Every user-facing flow in Semper, as a tree plus a checkable test step per leaf.
Use it two ways:

- **As a map** — the trees answer "what screens exist and how do I reach them".
- **As a manual test script** — walk the tables top to bottom on a debug build
  and tick the boxes. A full pass should never land you on a screen this file
  doesn't name.

For the *automated* suite see [TESTING.md](TESTING.md); for how the code is laid
out see [ARCHITECTURE.md](ARCHITECTURE.md). This file covers what a human sees.

## How the app is put together

There is no `NavHost`, no Compose and no Fragments. Semper is **Activity-based**:
12 activities in `app/src/main/AndroidManifest.xml`, wired with plain
`startActivity` and `ActivityResultContracts`. Sub-flows are wizard pages inside
one Activity, `BottomSheetDialog`s, `MaterialAlertDialog`s and `PopupMenu`s. So a
"workflow" here is rarely a route — most of this tree lives inside four files.

| Activity | Flow |
|---|---|
| `SplashActivity` | 0. App launch |
| `AuthActivity` | 1. Login |
| `PendingApprovalActivity` | 2. Pending approval |
| `HomeActivity` | 3. Home |
| `SettingsActivity` · `AdminActivity` | 4. Settings |
| `StaticAnalysisActivity` | 5. Analysis (3-step wizard) |
| `RoiDrawActivity` | 6. ROI editor |
| `VsgLatticeActivity` | 7. Lattice |
| `ResultViewerActivity` · `SaveExportActivity` | 8. Result viewer |
| `SessionLimitActivity` | 9. Session limit |

## Legend

| Mark | Meaning |
|---|---|
| `[admin]` `[sweep]` `[single]` `[debug]` | only reachable in that condition |
| `→` | navigates to another flow |
| `[ ]` | tick when the step passes |

---

## 0. App launch

Decides where you land. No user input; the whole flow is a routing decision.

```
0. App launch — SplashActivity
   ├── session restore (400 ms delayed spinner)
   ├── no session ................................ → 1. Login
   ├── status PENDING ............................ → 2. Pending approval
   ├── status APPROVED ........................... → 3. Home
   ├── offline + cached approval ................. → 3. Home, "Offline mode" toast
   ├── quota already full ........................ → 9. Session limit
   └── [debug] dev bypass ........................ → 3. Home, cloud disabled
```

**Entry:** launcher icon. **Exit:** Login, Pending approval, Home or Session limit.

| # | Action | Expected |
|---|---|---|
| [ ] 0.1 | Cold start, signed out | Login screen; no spinner flash on a fast device |
| [ ] 0.2 | Cold start, signed in and approved | Home, no re-authentication prompt |
| [ ] 0.3 | Cold start, signed in but not yet approved | Pending approval screen |
| [ ] 0.4 | Cold start in airplane mode, previously approved | Home opens with an "Offline mode" toast |
| [ ] 0.5 | Cold start in airplane mode, never approved | Login with an explanatory message |
| [ ] 0.6 | Cold start with the analysis quota already full | Session limit screen |
| [ ] 0.7 | Slow network on launch | Spinner appears after ~400 ms, not instantly |

---

## 1. Login

**One screen**, not five. The sign-in / create-account distinction is a mode
toggle on the same layout, and forgot-password is a link that fires an email —
neither opens a separate screen.

```
1. Login — AuthActivity
   ├── Google SSO                        (button hidden when not configured)
   ├── Email / password sign in
   ├── Create account                    (same screen; sends a verification
   │                                      email silently, never enforced)
   ├── Email sign-in link (passwordless)
   │   ├── request the link
   │   ├── return via App Link deep link → /finishSignIn
   │   └── wrong-device error path
   ├── Forgot password (reset email; reports success even for unknown emails)
   │   └── return via App Link deep link → /finishReset
   │       └── set-new-password form, in-app (same Activity, reset mode)
   ├── Generate secure password          [register]
   └── validation: email format, password policy on register (8+, upper, lower,
       digit, special), confirm mismatch, routing-error banner from Splash
```

**Entry:** Splash, sign-out, or the sign-in deep link. **Exit:** Home or Pending
approval, depending on the backend's answer.

| # | Action | Expected |
|---|---|---|
| [ ] 1.1 | Tap **Sign in with Google** | Account chooser; on success you land in Home or Pending approval |
| [ ] 1.2 | Dismiss the Google chooser | Returns to Login silently — no error toast |
| [ ] 1.3 | Google sign-in on a device with no Google account | "No Google account available on this device." |
| [ ] 1.4 | Sign in with a valid email + password | Routes onward per account status |
| [ ] 1.5 | Sign in with a wrong password | Red snackbar, fields keep their contents |
| [ ] 1.6 | Enter a malformed email | Inline "invalid email" before any network call |
| [ ] 1.7 | Tap the mode toggle | Becomes "Create account"; the confirm-password field appears |
| [ ] 1.8 | Create an account with a 5-character password | Blocked: at least 8 characters |
| [ ] 1.8a | Try `abcdefgh`, `ABCDEFGH1!`, `Abcdefgh!`, `Abcdefg1` | Each blocked naming the rule it misses — lowercase, uppercase, digit, special character |
| [ ] 1.8b | Tap **Generate secure password** | Both password fields fill with a strong value, shown in clear so it can be saved, and it passes every rule |
| [ ] 1.8c | Sign in (not register) with an old short password | Still allowed — the rules bind new passwords, not existing accounts |
| [ ] 1.9 | Create an account with mismatched confirm | Blocked with "passwords do not match" |
| [ ] 1.10 | Create a valid new account | A verification email is sent, the session is dropped, and **the screen returns to Sign in** — email kept, both password fields cleared, no confirm box |
| [ ] 1.10a | Try to sign in before opening that link | Blocked with the same message; a fresh verification email is sent each time |
| [ ] 1.10b | Open the link, then sign in | Signs in and lands on Pending approval (new accounts aren't pre-approved) |
| [ ] 1.10c | Sign in with Google, or via an email sign-in link | No verification step — both arrive already verified |
| [ ] 1.11 | In register mode, look for the recovery links | Forgot-password / email-link links are hidden |
| [ ] 1.12 | Tap **Forgot password** with a registered email | Green snackbar confirming the email was sent |
| [ ] 1.13 | Tap **Forgot password** with an unregistered email | Same success message — enumeration is deliberately not leaked |
| [ ] 1.13a | Open the reset link from that email on this device | The **app** opens on a set-new-password form — you never land on a Firebase web page |
| [ ] 1.13b | Enter a new password there | Same policy as registration applies; on success you are signed in and routed onward |
| [ ] 1.13c | Open a reset link that has expired or was already used | The form reports the link is no longer valid and offers to request a fresh one |
| [ ] 1.14 | Tap **Email me a sign-in link** | Confirmation snackbar; the email arrives with a link |
| [ ] 1.15 | Open that link on the same device | App opens and completes sign-in (needs verified asset links — see §11) |
| [ ] 1.16 | Open that link on a different device | "Open the sign-in link on the device that requested it." |
| [ ] 1.17 | Open the link while Login is already in the foreground | Handled in place, no duplicate screen |
| [ ] 1.18 | Arrive here from Splash after a status failure | The routing error is shown as a red snackbar |

---

## 2. Pending approval

The allow-list gate. Firebase says who you are; the backend says whether you're
allowed in. A new account sits here until an admin approves it.

```
2. Pending approval — PendingApprovalActivity
   ├── Request access (mailto to support, prefilled with account/device/build)
   ├── Check status  (manual only — it does not poll)
   └── Log out
```

**Entry:** Splash or Login when status is `PENDING`. **Exit:** Home on approval,
Login on sign-out.

The backend mails support the moment the account is created `PENDING`, so an
admin learns about it without the user asking (see
[BACKEND_SETUP_GCP.md](../backend/BACKEND_SETUP_GCP.md) §B1a). **Request access**
below is the user's own nudge on top of that, not the only signal.

| # | Action | Expected |
|---|---|---|
| [ ] 2.0 | Land here for the first time on a new account | support@ receives an "Semper access request" mail naming the account and its user id, without anyone tapping anything |
| [ ] 2.0a | Sign out and back in on that same account | No second mail — it is sent once, when the account is created |
| [ ] 2.0b | Same on a backend with no `RESEND_API_KEY` set | No mail and no error: sign-in still ends on this screen normally |
| [ ] 2.1 | Read the screen | Your email and a truncated device ID are both shown |
| [ ] 2.2 | Tap **Request access** | Mail app opens, prefilled with account, device ID, app version and device model |
| [ ] 2.3 | Same, with no mail app installed | Toast instead of a crash |
| [ ] 2.4 | Tap **Check status** while still pending | "Account is still pending approval." and you stay put |
| [ ] 2.5 | Have an admin approve, then tap **Check status** | "Access granted" and you land on Home |
| [ ] 2.6 | Wait on this screen without touching it | Nothing happens — approval is *not* polled |
| [ ] 2.7 | Tap **Log out** → confirm | Login screen, back stack cleared |

---

## 3. Home

The session list and the only entry point to a new analysis.

```
3. Home — HomeActivity
   ├── Beta / data-use notice          (first run, non-dismissable, acked once)
   ├── Diagnostics opt-in prompt       (first run, after the notice; default off)
   ├── Coach mark on the FAB           (first run)
   ├── Session list
   │   ├── open a session ............ → 8. Result viewer, or 7. Lattice for sweeps
   │   ├── sync badge tap ............ retry backup / open Settings
   │   ├── "Only in cloud" row ....... download-then-open dialog + progress
   │   ├── live row progress ......... backup (prepare/upload) and restore/download
   │   └── "session data gone" dialog  (local frames deleted, no cloud copy either)
   ├── Selection mode (long-press)
   │   ├── select all
   │   ├── rename                      (only with exactly one selected)
   │   └── delete → "everywhere" / "on this device only"
   ├── Quota chip ...................... → 9. Session limit / 4. Settings
   ├── Pull-to-refresh                  (deep cloud reconcile, repairs blobs)
   ├── Empty state → "Restore"          (opens Settings)
   ├── Start new analysis (FAB)
   │   ├── quota gate .................. → 9. Session limit
   │   └── source chooser: Photos (system picker) / Files (SAF)
   ├── Settings (gear)
   └── Exit-app confirm on Back
```

**Entry:** Splash, Pending approval, the viewer's home button, sign-in.
**Exit:** Analysis, Settings, Result viewer, Lattice, Session limit.

| # | Action | Expected |
|---|---|---|
| [ ] 3.1 | First launch after install | Beta / data-use notice appears, cannot be dismissed by tapping outside; only "I understand" closes it |
| [ ] 3.2 | Relaunch, and again after signing out and back in | The notice does not reappear |
| [ ] 3.2a | Acknowledge the beta notice on a fresh install | A second dialog asks whether to send crash reports; **declining is the default outcome** and nothing is collected until you accept |
| [ ] 3.2b | Relaunch after answering it | It does not reappear; the choice is mirrored by the Settings toggle (§4, Your data) |
| [ ] 3.3 | First visit | A coach mark points at the **+** button; Skip and Got it both dismiss it |
| [ ] 3.4 | Look at a session row | Thumbnail, name, "date · N frames" (or "Parameter sweep"), headline value, sync badge |
| [ ] 3.5 | Tap a normal session | Result viewer opens on frame 1 |
| [ ] 3.6 | Tap a sweep session | **Lattice** opens, not the viewer |
| [ ] 3.7 | Tap a row whose local files were deleted but which has a cloud backup | It carries an **"Only in cloud"** badge; tapping offers to download it, shows determinate progress, then opens it |
| [ ] 3.7a | Tap a row with no local files *and* no cloud copy | "Session data gone" dialog — this is now the only case that reaches it |
| [ ] 3.7b | Watch a row during a backup | The badge and an inline progress bar track the prepare and upload phases |
| [ ] 3.7c | Watch a row during a restore or download | Same row progress, so you are not left guessing while it runs |
| [ ] 3.7d | Let a restore fail terminally while on Home | A snackbar names the reason here too, not only in Settings |
| [ ] 3.8 | Tap a "Pending" sync badge | Upload is retried / queued |
| [ ] 3.8a | Tap a "Failed" sync badge | A dialog names *why* the last backup failed (device conflict, too large, render ran out of memory) with a **Try again** action — not a silent re-queue |
| [ ] 3.8b | Let a background backup fail terminally while on Home | A snackbar surfaces the reason once (quota-full is excluded — it has its own screen) |
| [ ] 3.9 | Tap a badge with cloud backup switched off | Settings opens |
| [ ] 3.10 | Long-press a row | Selection bar with count, select-all, rename, delete, close |
| [ ] 3.11 | Select two rows | Rename disappears; delete still offered |
| [ ] 3.12 | Rename a single selection | Text dialog; the new name persists after leaving and returning |
| [ ] 3.13 | Delete one session | Choice of "Delete everywhere" / "Delete on device only" |
| [ ] 3.13a | Choose **on device only** | Snackbar: "Removed from this phone. Tap the row to download from the cloud." The row stays, now badged "Only in cloud" |
| [ ] 3.13b | Delete a row that is already cloud-only, on device only | No-op branch — there is nothing local left to remove |
| [ ] 3.14 | Delete several sessions | Same choice, with counts in the message |
| [ ] 3.15 | Press Back in selection mode | Selection clears; the app does not exit |
| [ ] 3.16 | Tap the quota chip below the cap | Settings (or the limit screen at the cap) |
| [ ] 3.17 | Reach the quota cap | The chip turns red |
| [ ] 3.18 | Pull to refresh | Cloud reconcile runs; a repair or failure is reported by toast |
| [ ] 3.19 | Open Home with no sessions | Empty state with a **Restore** button (it opens Settings) |
| [ ] 3.20 | Tap **+** below the quota | Source chooser sheet: Photos / Files |
| [ ] 3.21 | Tap **+** at the quota cap | Session limit screen instead of the chooser |
| [ ] 3.22 | Choose **Photos** | System photo picker, images *and* video |
| [ ] 3.23 | Choose **Files** | SAF browser — the only route to DNG/RAW |
| [ ] 3.24 | Press Back on Home | "Exit app?" confirmation |

---

## 4. Settings

One scrolling screen of seven collapsible sections, all collapsed on open, plus a
two-button footer.

```
4. Settings — SettingsActivity
   ├── Account
   │   ├── email, device ID (read-only, selectable)
   │   └── Pending access requests      [admin] → AdminActivity
   │       └── approve / deny a user
   ├── Cloud backup
   │   ├── "Save to cloud" toggle → offer to back up N local-only analyses
   │   ├── "Wi-Fi only uploads" toggle
   │   └── sync status line (up to date / pending)
   ├── Analyses data management         (local ⋈ cloud, merged)
   │   ├── open the analysis
   │   ├── Back up now / Retry backup
   │   ├── Restore from cloud           (background worker)
   │   └── Delete backup → cloud only / cloud + local / forever
   │       └── 5-second Undo snackbar before the delete really fires
   ├── Storage
   │   ├── analyses size + cache size   (measured, refreshed on expand)
   │   ├── Free up space                (drops local frames of backed-up analyses)
   │   ├── Clear cache
   │   └── Auto-free budget             (slider 0–64 GB, 0 = off; enforced at app start)
   ├── Your data
   │   ├── Send crash reports           (opt-in toggle; mirrors the first-run prompt)
   │   ├── Export my data               (master ZIP → 8.5a Send to)
   │   ├── Download my cloud account data  (server-side export of the account)
   │   └── Delete my account and data   (backend first; local wipe only on success)
   ├── Analysis preferences
   │   └── Max frames per analysis      (10–150, default 50) + info dialog
   ├── Help & support
   │   ├── Ask the community / Report a bug / Request a feature
   │   │     → https://semperdic.github.io/website/…
   │   ├── support@indicvision.com      (selectable, copyable)
   │   └── Email support                (mailto, prefilled with account/device/build)
   ├── About                            (version + Privacy Policy / Terms links)
   └── Sign out
```

**Entry:** the Home gear (also the empty-state Restore button, the quota chip and
any sync badge). **Exit:** Home, Admin, a result, or Login.

| # | Action | Expected |
|---|---|---|
| [ ] 4.1 | Open Settings | All seven sections are collapsed; chevrons rotate on tap |
| [ ] 4.2 | Expand **Account** | Your email and "Device ID · …" are shown; the device ID can be selected and copied |
| [ ] 4.3 | Expand **Account** as a non-admin | No "Pending access requests" button |
| [ ] 4.4 | Expand **Account** as an admin | The button appears and opens the admin list |
| [ ] 4.5 | Turn **Save to cloud** on with local-only analyses present | A dialog offers to back up N of them |
| [ ] 4.6 | Accept that offer | Uploads are queued; badges on Home move to "Pending" |
| [ ] 4.7 | Turn **Save to cloud** off | Subtitle changes; no new uploads are queued |
| [ ] 4.8 | Toggle **Wi-Fi only uploads** on, then queue an upload on mobile data | The upload waits for Wi-Fi |
| [ ] 4.9 | Read the sync status line | "Up to date" or a pending count, matching the badges on Home |
| [ ] 4.10 | Expand **Analyses data management** | Merged local + cloud list; each row shows a state line |
| [ ] 4.11 | Same, while signed out or with no backend | An explanatory line instead of an empty list |
| [ ] 4.12 | Tap a row that exists locally | That analysis opens (viewer or lattice) |
| [ ] 4.13 | Tap **Back up now** on a local-only row | Upload is queued; the row state changes |
| [ ] 4.14 | Tap **Restore** on a cloud-only row | Toast says the restore continues in the background; on success the analysis appears in the list without reopening Settings |
| [ ] 4.14a | Restore a backup that fails terminally (deleted server-side, or not this account) | A snackbar names the failure — the restore is no longer silent |
| [ ] 4.15 | Tap the bin on a row with a local copy | Choice: cloud backup only / local + cloud / cancel |
| [ ] 4.16 | Tap the bin on a cloud-only row | "Delete this backup forever?" naming the analysis |
| [ ] 4.17 | Confirm any backup delete, then tap **Undo** within 5 s | The row returns; nothing is deleted server-side |
| [ ] 4.18 | Confirm and wait past the undo window | The backup is really gone after a refresh |
| [ ] 4.18a | Expand **Storage** | Analyses and cache sizes are measured and shown, not left on "Measuring…" |
| [ ] 4.18b | Tap **Free up space** with backed-up analyses present | Their local frames are dropped; the rows become "Only in cloud" on Home and the analyses total falls |
| [ ] 4.18c | Tap it with nothing safely backed up | The subtitle says there is nothing to free and the button does not strand un-backed-up data |
| [ ] 4.18d | Tap **Clear cache** | The cache total drops; open analyses still work — only regenerable files go |
| [ ] 4.18e | Drag the **auto-free** slider off 0 | The label names the budget in GB; at 0 it reads "off" |
| [ ] 4.18f | Set a budget below current usage and restart the app | Space is reclaimed at start-up, oldest backed-up analyses first |
| [ ] 4.18g | Tap the ⓘ beside it | Explains that only cloud-backed analyses are ever dropped |
| [ ] 4.19 | Tap **Export my data** | A master ZIP is built with determinate progress, then handed to the **Send to** sheet (§8.5a) |
| [ ] 4.19a | Tap **Download my cloud account data** | The server-side export of the account is fetched with progress, then offered through the same sheet |
| [ ] 4.19b | Trigger either export with no network | It fails with a named reason, not a silent no-op |
| [ ] 4.19c | Toggle **Send crash reports** off, then force a crash on a debug build | Nothing is uploaded; turning it on again resumes collection without a restart |
| [ ] 4.20 | Tap **Delete my account and data** | Dialog listing exactly what goes: local analyses, cloud backups, profile and device |
| [ ] 4.20a | Confirm it | The **sign-in screen** opens to re-verify, with your email filled in and locked, and no "create account" toggle |
| [ ] 4.20b | Enter the wrong password there | "Incorrect password." and nothing is deleted |
| [ ] 4.20c | Confirm it, as a Google account | Same screen; **Sign in with Google** re-authenticates instead |
| [ ] 4.20d | Confirm it, as an email-link-only account | **Email me a sign-in link** works too — this account could not be deleted at all before |
| [ ] 4.20e | Press back on that screen | Returns to Settings; nothing is deleted |
| [ ] 4.21 | Confirm the delete with no network | Nothing local is touched; a failure toast is shown |
| [ ] 4.22 | Confirm the delete online | Everything is wiped, including the sign-in identity, and you land back on Login |
| [ ] 4.22a | Sign up again with the same email afterwards | It behaves as a brand-new account — the old identity is gone |
| [ ] 4.23 | Drag the **Max frames** slider | Value label tracks in steps of 10 between 10 and 150 |
| [ ] 4.24 | Tap the ⓘ next to it | Explains the cost of more frames |
| [ ] 4.25 | Set it to 20, then import 40 frames in an analysis | Only the first 20 are kept, with a "capped" toast |
| [ ] 4.26 | Expand **Help & support** | Community / bug / feature buttons and the support address are shown; address can be selected and copied |
| [ ] 4.27 | Tap **Email support** | Mail app opens to support@, subject "Semper support request", body carrying account, device ID, app version and device model |
| [ ] 4.28 | Same with no mail app installed | "No email app found…" toast naming the address; no crash |
| [ ] 4.29 | Tap **About** | "Semper v<name> (<code>)" plus **Privacy Policy** and **Terms of Service** buttons |
| [ ] 4.29a | Tap either legal button | The hosted page opens in a browser; both are reachable without an account |
| [ ] 4.30 | Tap **Sign out** → confirm | Login, back stack cleared |

### 4.1 Admin `[admin]`

```
4.1 Admin — AdminActivity
    ├── list of PENDING users (email + display name)
    ├── Approve
    └── Deny
```

| # | Action | Expected |
|---|---|---|
| [ ] 4.1.1 | Open the admin list | Every user awaiting approval is listed — each one should match a mail support already received (§2.0) |
| [ ] 4.1.2 | With no one waiting | "No pending requests." |
| [ ] 4.1.3 | Tap **Approve** | Toast, list reloads, that user can now get past Pending approval |
| [ ] 4.1.4 | Tap **Deny** | Toast, the row disappears |

---

## 5. Analysis

`StaticAnalysisActivity` is a three-page wizard in one Activity. Page 3 exists
only in sweep mode, so the toolbar reads "Step N of 2" or "of 3" depending on the
mode chosen on page 2.

**Entry:** the Home FAB, after picking a source. **Exit:** Result viewer,
Lattice, Session limit, or back to Home.

### 5.1 Step 1 — Load frames

```
5.1 Load frames
    ├── Reference image → Photos / Files      (RAW/DNG only via Files)
    ├── Deformed frames → Photos / Files      (multi-select, capped)
    ├── Video source
    │   ├── sampling sheet: fps slider, time-segment range, live estimate
    │   └── extraction progress
    ├── Frame order
    │   ├── Name ↑ / Name ↓ / Date oldest / Date newest / Manual
    │   └── drag thumbnails to reorder (Manual)
    ├── warnings: JPEG accuracy, low texture (SSSIG)
    └── blocking error: frame size mismatch
```

| # | Action | Expected |
|---|---|---|
| [ ] 5.1.1 | Tap the reference dropzone → **Photos** | Picker opens, images only |
| [ ] 5.1.2 | Pick a `.dng` or `.tif` via **Files** | Card shows the filename and `W × H`; no decode error |
| [ ] 5.1.3 | Tap **Change** on the reference card | Source chooser reopens; the new image replaces the old |
| [ ] 5.1.4 | Pick deformed frames via **Photos** (multi-select) | Card shows "N frames" and the first…last filenames |
| [ ] 5.1.5 | Pick more frames than *Max frames* | The first N are kept, with a "capped" toast |
| [ ] 5.1.6 | Load a reference only | **Next** is disabled with "add at least one deformed frame to continue" |
| [ ] 5.1.7 | Load deformed frames only | **Next** is disabled with the matching reference message |
| [ ] 5.1.8 | Include one frame of a different pixel size | A blocking message appears and **Run** stays disabled |
| [ ] 5.1.9 | Load JPEGs | A non-blocking accuracy warning chip appears |
| [ ] 5.1.10 | Load a poorly speckled reference | A low-texture warning names a suggested subset size |
| [ ] 5.1.11 | Open the sort menu → **Name A–Z** | Thumbnails reorder; the badge numbers renumber 1…N |
| [ ] 5.1.12 | Choose **Date oldest first** | Order follows capture date, not filename |
| [ ] 5.1.13 | Choose **Manual** | Hint toast about dragging; drag a thumbnail and it stays where dropped |
| [ ] 5.1.14 | Load a single deformed frame | The sort control is hidden |
| [ ] 5.1.15 | Press Back with inputs loaded | "Exit analysis?" confirmation |

#### 5.1a Video source

Reached only when the file picked on Home was a video.

| # | Action | Expected |
|---|---|---|
| [ ] 5.1a.1 | Pick a video on Home | Sampling sheet opens with resolution, source fps and duration |
| [ ] 5.1a.2 | Drag the **fps** slider | The estimated frame count updates live |
| [ ] 5.1a.3 | Drag the time-segment handles | Estimate updates; the button relabels to "Extract N frames" |
| [ ] 5.1a.4 | Choose settings that exceed *Max frames* | The estimate shows the cap being applied |
| [ ] 5.1a.5 | Tap **Extract** | Progress overlay; frame 0 becomes the reference, the rest deformed |
| [ ] 5.1a.6 | Look for the sort control afterwards | Hidden — video frames are already in time order |

### 5.2 Step 2 — Confirm settings

```
5.2 Confirm settings
    ├── inputs summary card
    ├── Region of interest ......... → 6. ROI editor (cancel = full image)
    ├── Analysis mode: Single setting / Parameter sweep
    ├── Line-cut preview + X/Y axis           [sweep]
    ├── Advanced parameters                   [single]
    │   ├── Paste params chip  (only when the lattice clipboard holds a set)
    │   ├── subset size      (slider + typed field + ⓘ)
    │   ├── step size        (slider + typed field + ⓘ)
    │   ├── strain window    (slider + typed field + ⓘ)
    │   ├── interpolator: Bicubic 4×4 / Keys 6×6
    │   └── Reset to recommended
    └── Run analysis
```

| # | Action | Expected |
|---|---|---|
| [ ] 5.2.1 | Arrive on step 2 | Summary card shows the reference thumbnail, name and frame count |
| [ ] 5.2.2 | Read the ROI line before editing | "Full image W × H" |
| [ ] 5.2.3 | Tap **Edit** → draw an ROI → save | The line becomes "W × H at (x, y)" |
| [ ] 5.2.4 | Tap **Edit** → cancel | Falls back to full image; any mask is cleared |
| [ ] 5.2.5 | Switch to **Parameter sweep** | Advanced parameters hide; the line-cut preview appears |
| [ ] 5.2.6 | Toggle the line-cut axis X ↔ Y | The preview redraws the cut line through the ROI centre |
| [ ] 5.2.7 | Tap the ⓘ next to the mode toggle | Explains single setting vs sweep |
| [ ] 5.2.8 | Switch back to **Single setting** | Advanced parameters return with their previous values |
| [ ] 5.2.9 | Drag the **subset size** slider | Only odd values between 15 and 121; the field mirrors it |
| [ ] 5.2.10 | Type an even subset size and press Done | Snapped to the nearest valid odd value |
| [ ] 5.2.11 | Type nonsense in a parameter field | Reverts to the previous value on commit |
| [ ] 5.2.12 | Drag **step size** | 1–30, field mirrors it |
| [ ] 5.2.13 | Drag **strain window** | Odd values 5–101, field mirrors it |
| [ ] 5.2.13a | Open step 2 having never copied params from a lattice | No **Paste params** chip — it only appears when the clipboard holds a set |
| [ ] 5.2.13b | Copy params from a sweep lattice (§7.3), then return here | The chip appears beside **Reset**; tapping it fills subset, step and strain window and scrolls them into view |
| [ ] 5.2.14 | Tap each ⓘ | Subset, step and strain window each explain themselves |
| [ ] 5.2.15 | Switch the interpolator to **Keys 6×6** | Selection sticks; the run uses it |
| [ ] 5.2.16 | Change several parameters, then tap **Reset** | Subset returns to the recommended value, step to 5, strain window to 15, interpolator to Bicubic |
| [ ] 5.2.17 | Load a well-speckled reference and watch the subset | It is pre-seeded from the SSSIG recommendation — until you touch it |
| [ ] 5.2.18 | Draw an ROI smaller than the subset and run | "ROI too small" toast; the run does not start |
| [ ] 5.2.19 | Edit a parameter field and tap **Run** without pressing Done | The typed value is committed and used |

### 5.3 Step 3 — Sweep setup `[sweep]`

```
5.3 Sweep setup
    ├── subset size range      (dual slider + min/max fields, 15–121)
    ├── strain window range    (dual slider + min/max fields, 5–101)
    ├── step denominator       ("subset ÷ n", 2–6)
    ├── frame to sweep         (dialog: radio list + number + live preview)
    ├── planned lattice preview
    ├── lattice samples: no. of subsets × no. of VSGs (1–8 each)
    ├── plan summary, or "empty plan" / "subset too big for this ROI"
    └── Run sweep
```

| # | Action | Expected |
|---|---|---|
| [ ] 5.3.1 | Arrive on step 3 | Ranges are pre-seeded around the recommended subset; the toolbar reads "Step 3 of 3" |
| [ ] 5.3.2 | Drag the subset range handles | Both ends stay odd; min never crosses max |
| [ ] 5.3.3 | Type a subset min above the max | Clamped so min ≤ max |
| [ ] 5.3.4 | Look for a "max VSG size" field | There is none — the ceiling comes from the **strain window range** below the subset range |
| [ ] 5.3.5 | Type a step denominator of 1, then 9 | Clamped into 2–6; the prefix reads "subset ÷ n" |
| [ ] 5.3.6 | Tap each ⓘ | Subset range, strain window range, step depth and samples each explain themselves |
| [ ] 5.3.6b | Read the strain window control | It has its own title and ⓘ and spans the row, like the subset range above it |
| [ ] 5.3.6d | Drag the strain window range | Two handles like the subset's; the min and max boxes track it and the plan count updates |
| [ ] 5.3.6e | Find the step size | On its own row below the range, not sharing one with it |
| [ ] 5.3.6c | Type a min above the max | Clamped rather than inverted; the sweep still plans |
| [ ] 5.3.6a | Open sweep setup for the first time | A coach mark points out the lattice preview graph |
| [ ] 5.3.7 | Tap **Pick frame** | Dialog with a radio list, a frame-number field and a live preview |
| [ ] 5.3.8 | Type a frame number in that dialog | The radio selection and preview follow |
| [ ] 5.3.9 | Scrub quickly through frames in the dialog | Preview keeps up; no stale image is left behind |
| [ ] 5.3.10 | With one deformed frame only | The frame picker is hidden |
| [ ] 5.3.11 | Look at the planned lattice | Grid of nodes, subset across, VSG up; taps do nothing (it's a preview) |
| [ ] 5.3.12 | Open the samples panel (gear) and set 4 × 4 | The plan summary reads 16 analyses and the lattice redraws |
| [ ] 5.3.13 | Set samples to 9 | Clamped to 8 |
| [ ] 5.3.14 | Set the subset min above what the ROI can hold | "Subset range starts above what this image and ROI can hold"; **Run sweep** is disabled |
| [ ] 5.3.15 | Set a strain window range that no subset can satisfy | "No combination fits this ceiling — raise Max strain window or lower the subset range"; **Run sweep** is disabled |
| [ ] 5.3.16 | Read a valid plan summary | "N analyses · subset a–b px · VSG c–d px" |

### 5.4 Running

```
5.4 Running
    ├── progress %, elapsed
    ├── compute tiles: Total points converged, convergence %   [compute/sweep only]
    ├── Cancel (confirm; cooperative — the engine stops mid-frame)
    └── Back is hard-blocked, screen kept on
```

The same overlay is reused for importing frames and extracting video, but the two
compute tiles are **hidden** there — they would only ever read zero. Import and
extraction show determinate progress instead.

| # | Action | Expected |
|---|---|---|
| [ ] 5.4.1 | Start a run | Overlay with title, percentage, status, elapsed seconds |
| [ ] 5.4.2 | Watch the two tiles during a solve | **Total points converged** and convergence % update as it goes |
| [ ] 5.4.2a | Watch the overlay while frames import or a video extracts | The two tiles are absent; progress is a determinate count of frames |
| [ ] 5.4.2b | Tap **Cancel** during an import | A confirm dialog ("Cancel this import?"); confirming leaves no half-imported frames behind |
| [ ] 5.4.3 | Leave the device untouched during a long run | The screen does not sleep |
| [ ] 5.4.4 | Press Back mid-run | Blocked, with a toast |
| [ ] 5.4.5 | Tap **Cancel** → "Keep running" | The run continues |
| [ ] 5.4.6 | Tap **Cancel** → confirm | Stops within a moment — not at the end of the frame — and returns to step 2, silently |
| [ ] 5.4.6a | Cancel a long frame (big ROI, small step) | Same: no multi-second wait on the progress overlay after confirming |
| [ ] 5.4.6b | Start a new run straight after cancelling one | It runs normally — the cancel does not carry over |
| [ ] 5.4.6c | Cancel a sweep at combination 3 of 16 | The **whole sweep** stops — it does not go on to combination 4 |
| [ ] 5.4.7 | Start a sweep | Status reads "Run i/N · subset · step · VSG" |
| [ ] 5.4.8 | Background the app mid-run | The run does not survive process death — no resume is offered |

### 5.5 Terminal states

| # | Action | Expected |
|---|---|---|
| [ ] 5.5.1 | Run on a featureless image pair | Engine failure dialog naming the feature-detection cause, **and the frame and image it failed on** |
| [ ] 5.5.1a | Run a batch where a later frame decorrelates | "Stopped early" — not "Analysis failed" — naming the frame and how many were kept |
| [ ] 5.5.1c | Acknowledge that dialog | The kept frames open in the viewer — the run does not leave you back on the settings page |
| [ ] 5.5.1d | Press Back on that dialog | Nothing dismisses it; the only way on is through to the results |
| [ ] 5.5.1e | Return to Home afterwards | The short analysis is listed with the frames it kept — not a phantom row from a run reported as failed |
| [ ] 5.5.1f | Read that Home row | "39 of 50 frames" and the reason, not a bare "39 frames" |
| [ ] 5.5.1g | Open it and tap ⓘ | Settings used lists **Stopped early** and **Frames solved** |
| [ ] 5.5.1h | Force-stop the app, reopen, look again | Both still say why — the reason is stored, not held in memory |
| [ ] 5.5.1i | Run with a featureless ROI that solves nothing | "No data produced" dialog explaining speckle/ROI, not a bare line of text |
| [ ] 5.5.1j | Trigger an OOM (huge ROI, step 1) | "Analysis stopped unexpectedly" dialog naming the error and what causes it |
| [ ] 5.5.1b | Sweep a decorrelated pair | Stops after two combinations under 50% rather than sweeping the rest |
| [ ] 5.5.2 | Run with an unusable ROI | Engine failure dialog naming the ROI cause |
| [ ] 5.5.3 | Finish a single-setting run | Result viewer opens on frame 1 |
| [ ] 5.5.4 | Finish a sweep with some combinations failing | "N of M skipped" toast, then the Lattice |
| [ ] 5.5.4a | Tap a hollow node | A dialog titled with that combination (S · St · W · VSG) and a one-line reason — decorrelated, subset too large, VSG failure |
| [ ] 5.5.4f | Reopen that sweep from Home, tap a hollow node | The same specific reason, not the generic "was skipped" — codes are stored on the record |
| [ ] 5.5.4g | Restore that sweep from the cloud, tap a hollow node | Same again; the reasons survive the round-trip |
| [ ] 5.5.4c | Tap a hollow node from a sweep with no recorded code | Still explains itself rather than doing nothing |
| [ ] 5.5.4d | Open the lattice for the first time | Coach marks point out the graph, then the strain plot's drag readout |
| [ ] 5.5.4e | Drag across the strain plot | Every curve's value at that position, each in its own curve's colour |
| [ ] 5.5.4b | Run a sweep where **every** combination fails | The Lattice opens — not the parameter screen — all nodes hollow, summary says all failed, and **View** and **Save graph** are both disabled |
| [ ] 5.5.5 | Finish a sweep cleanly | Lattice opens with every node filled |
| [ ] 5.5.6 | Hit the quota during a run | Session limit screen |
| [ ] 5.5.7 | Re-run with the same inputs after changing a parameter | The same Home row is updated, not duplicated |
| [ ] 5.5.8 | Change the inputs and run again | A new Home row is created |

---

## 6. ROI editor

Full-screen editor over the reference image. Two edit modes crossed with two
tools — the Crop/Erase toggle persists when you switch between Draw and Manual.

```
6. ROI editor — RoiDrawActivity
   ├── Draw mode
   │   ├── Rectangle / Square
   │   └── draw, move, corner-resize (min 50 px)
   ├── Manual mode
   │   └── X / Y / W / H fields + Apply
   ├── Crop ↔ Erase
   │   └── exclusion holes / mask; multiple, individually editable
   ├── Use full image
   ├── Reset
   ├── live HUD readout (W × H at (x, y))
   └── Save ROI / Cancel
```

**Entry:** the ROI card on analysis step 2. **Exit:** back to step 2, with the
ROI and mask, or with full-image defaults on cancel.

| # | Action | Expected |
|---|---|---|
| [ ] 6.1 | Open the editor | Reference image fills the canvas; HUD names the current mode |
| [ ] 6.2 | Drag a rectangle in **Draw** + **Crop** | Green ROI appears; HUD reports `W × H at (x, y)` live |
| [ ] 6.3 | Drag inside the rectangle | It moves as a whole, clamped to the image |
| [ ] 6.4 | Drag a corner handle | It resizes; the handle is forgiving to grab |
| [ ] 6.5 | Switch to **Square** and draw | Width and height stay equal |
| [ ] 6.6 | Make a very small drag | Ignored — a minimum size is enforced |
| [ ] 6.7 | Switch to **Erase** and drag inside the ROI | A hole is punched out of the correlated area |
| [ ] 6.8 | Add a second hole | Both are kept |
| [ ] 6.9 | Drag an existing hole in Erase mode | It moves and resizes independently |
| [ ] 6.10 | Erase without ever drawing a crop | Treated as "full image minus the holes" |
| [ ] 6.11 | Switch to **Manual** | X / Y / W / H fields prefill from the current selection |
| [ ] 6.12 | Type values and tap **Apply** | The ROI jumps to exactly those coordinates |
| [ ] 6.13 | Apply a zero or negative size | Rejected with an "invalid size" toast |
| [ ] 6.14 | Switch Manual → Draw → Manual | Crop/Erase state survives; the image does not jump |
| [ ] 6.15 | Tap **Reset** | The canvas clears back to nothing selected |
| [ ] 6.16 | Tap **Use full image** | Saves immediately and returns; step 2 reads "Full image" |
| [ ] 6.17 | Tap **Save ROI** | Returns; step 2 reads the custom size and origin |
| [ ] 6.18 | Tap **Cancel** | Returns with the ROI reset to full image |
| [ ] 6.19 | Rotate the device mid-edit | The ROI, holes and both toggles survive |
| [ ] 6.20 | Save an ROI with holes, then run | The masked regions are absent from the result heatmap |

---

## 7. Parameter sweep lattice `[sweep]`

The parameter-space map for a sweep, titled **Parameter sweep** on screen. It
sits **in front of** the result viewer — a sweep opens here, and Back from the
viewer returns here.

The screen is designed to be driven with one thumb: the column **scrolls**
(lattice, then controls, then plot) while the two action buttons stay pinned at
the bottom, and every node can be reached with the prev/next stepper without
aiming at a small target.

```
7. Parameter sweep — VsgLatticeActivity
   ├── coach marks                       (first visit: the lattice, then the plot)
   ├── result lattice: subset × VSG, solved (filled) vs skipped (hollow)
   │   ├── node fill colour matches that combination's curve in the plot
   │   ├── tap a node → focus it
   │   └── double-tap / long-press → open that combination in the viewer
   ├── stepper row: ‹ prev · parameter chip · next ›   (solved nodes only)
   ├── summary: total / solved / skipped / step denominator
   ├── strain plot section
   │   ├── component spinner: Exx / Eyy / Exy
   │   ├── Highlight ↔ Isolate           (Isolate is the default)
   │   ├── plot: pinch-zoom, two-finger pan, double-tap to reset
   │   ├── scrub slider under the plot   (two-way synced with the drag)
   │   └── readout: "x=… · y=… · subset … · step … · strain …"
   │       └── double-tap to copy the parameters
   └── Save graph · View                 (pinned bottom bar)
```

**Entry:** finishing a sweep, or tapping a sweep row on Home or in Settings.
**Exit:** Result viewer, or back to Home.

### 7.1 Lattice and stepper

| # | Action | Expected |
|---|---|---|
| [ ] 7.1.1 | Open a sweep | Lattice with subset across and VSG up; a legend explains filled vs hollow |
| [ ] 7.1.2 | Open a sweep that had failures | Skipped combinations are hollow red rings |
| [ ] 7.1.3 | Compare a filled node with the plot below | Its fill colour is the same as its curve's colour — the two views are colour-keyed |
| [ ] 7.1.4 | Read the summary line | "N combinations · S solved · K skipped · step subset÷D" |
| [ ] 7.1.5 | Tap a solved node | A selection ring appears; the stepper chip and the plot follow it |
| [ ] 7.1.6 | Tap a skipped node | A dialog explains why it was skipped; nothing is focused |
| [ ] 7.1.7 | Double-tap a solved node | The result viewer opens on that combination |
| [ ] 7.1.8 | Long-press a solved node | Same as double-tap |
| [ ] 7.1.9 | Tap **›** repeatedly | Focus walks the solved nodes in order; the chip names each one |
| [ ] 7.1.10 | Reach the first or last solved node | **‹** or **›** disables rather than wrapping |
| [ ] 7.1.11 | Open a sweep with no solved nodes at all | The stepper row is hidden and both bottom buttons are disabled |
| [ ] 7.1.12 | Scroll the screen down | Lattice, stepper and plot scroll together; the **Save graph · View** bar stays pinned |

### 7.2 Strain plot

| # | Action | Expected |
|---|---|---|
| [ ] 7.2.1 | Read the plot title | It names the cut axis — "Strain along X axis" or "…Y axis" |
| [ ] 7.2.2 | Change the strain component spinner | The plot redraws for Exx / Eyy / Exy |
| [ ] 7.2.3 | Look at the Highlight/Isolate toggle on open | **Isolate** is selected by default — one curve, not a thicket |
| [ ] 7.2.4 | Switch to **Highlight** | Every solved combination is drawn, with the focused one at full strength and the rest muted |
| [ ] 7.2.5 | Pinch to zoom on the plot | It zooms about the pinch centre |
| [ ] 7.2.6 | Drag with two fingers | The zoomed plot pans |
| [ ] 7.2.7 | Double-tap the plot | The viewport resets to fit |
| [ ] 7.2.8 | Zoom in, then step to another node | The zoom **survives** — the viewport is kept while the component is unchanged |
| [ ] 7.2.9 | Zoom in, then change the strain component | The viewport **resets**, because Exx/Eyy/Exy differ in magnitude and a stale zoom would clip |
| [ ] 7.2.10 | Drag one finger across the plot | A vertical guide follows it; a dot marks the selected curve and its value is drawn beside it |
| [ ] 7.2.11 | Watch the slider while dragging | It tracks the finger |
| [ ] 7.2.12 | Drag the slider instead | The guide, dot and readout follow it — the sync works both ways |
| [ ] 7.2.13 | Read the readout | "x=… · y=… · subset N · step N · strain N" for the **selected** node |
| [ ] 7.2.14 | Step to another node with the plot scrubbed | The readout clears and the slider returns to 0 |

### 7.3 Copy, save and open

| # | Action | Expected |
|---|---|---|
| [ ] 7.3.1 | Double-tap the readout line | "Parameters copied" — subset, step and strain window go to the app's parameter clipboard |
| [ ] 7.3.2 | Start a new single-setting analysis afterwards | Step 2 offers a **Paste params** chip that fills all three (§5.2.13b) |
| [ ] 7.3.3 | Tap **View** with a node focused | The result viewer opens on that combination |
| [ ] 7.3.4 | Tap **Save graph** | A PNG is rendered and handed to the share sheet |
| [ ] 7.3.5 | Open that PNG | A header (study, reference name + deformed count, and either the isolated node's parameters or the combination count), the plot at full fit, and a two-column colour legend of the curves |
| [ ] 7.3.6 | Save a graph while the on-screen plot is zoomed in | The export is rendered fit-to-data from a detached view — your zoom is neither baked in nor disturbed |
| [ ] 7.3.7 | Open a combination, then press Back | You return here, not to Home |

---

## 8. Result viewer

The main results browser. Reached directly for a single-setting run, or through
the Lattice for a sweep.

```
8. Result viewer — ResultViewerActivity
   ├── summary animation                        (the slot before frame 1)
   │   ├── every frame of the selected field, looping, ≤10 s
   │   └── one colour scale for the whole sequence
   ├── field switching: U / V / Exx / Eyy / Exy
   ├── image viewer
   │   ├── pinch zoom (to 10×) and pan
   │   └── jet heatmap over the reference (fixed 0.7 alpha)
   ├── colour scale bar
   │   ├── tap → custom min / max
   │   └── Auto scale
   ├── stats strip: max / min / mean
   ├── frame scrubbing: prev / next + "name (i / N)"
   │   └── type a frame number to jump straight there
   ├── Inspect — point probe
   │   ├── tap / drag readout (location + value)
   │   └── X,Y coordinate entry dialog
   ├── Max/Min markers
   ├── Info — settings used
   │   ├── subset / step / strain window / VSG / method / ROI / image size
   │   └── line-cut plot                         [sweep]
   ├── Share
   │   ├── result photo (current field + frame)
   │   ├── all field photos (5, zipped)
   │   ├── field animations (5 GIFs, zipped)
   │   ├── PDF report (all frames)
   │   ├── CSV data
   │   ├── everything (.zip: raw photos + animations + results + CSV + PDF)
   │   └── → Send to sheet: Save to Files / Share
   ├── Return to home
   └── Back (→ 7. Lattice for sweeps)
```

**Entry:** a finished single-setting run, a Home or Settings row, or a Lattice
node. **Exit:** Home, or back to the Lattice.

### 8.1 Fields, image and scale

| # | Action | Expected |
|---|---|---|
| [ ] 8.1.1 | Open a result | The U field is shown as a jet heatmap over the reference |
| [ ] 8.1.2 | Tap through U, V, Exx, Eyy, Exy | Heatmap, colour scale, stats and any markers all follow |
| [ ] 8.1.3 | Check the scale units | `px` for U and V, `[mε]` for the strain fields |
| [ ] 8.1.4 | Pinch to zoom | Zooms smoothly up to about 10×; panning is clamped to the image |
| [ ] 8.1.5 | Zoom in and pan | The heatmap stays registered to the reference — no drift |
| [ ] 8.1.6 | Zoom, then switch field | Zoom and pan are preserved |
| [ ] 8.1.7 | Tap the colour scale bar | Custom scale dialog, prefilled with the current bounds |
| [ ] 8.1.8 | Enter min ≥ max and apply | Rejected with a validation message |
| [ ] 8.1.9 | Enter valid bounds and apply | The heatmap and the scale labels both change |
| [ ] 8.1.10 | Switch field, then switch back | The custom bounds are remembered *per field* |
| [ ] 8.1.11 | Reopen the dialog and tap **Auto scale** | The override is dropped and auto ranging returns |
| [ ] 8.1.12 | Read the stats strip | Max, min and mean for the field and frame on screen, with units |

### 8.2 Frames

| # | Action | Expected |
|---|---|---|
| [ ] 8.2.1 | Read the frame counter | The original filename (or the sweep label) plus "(i / N)" |
| [ ] 8.2.2 | Tap **Next** | Advances one frame; the heatmap and stats update |
| [ ] 8.2.3 | Reach the last frame | **Next** disables and fades |
| [ ] 8.2.4 | Reach the first frame | **Prev** goes back to the summary, not nowhere |
| [ ] 8.2.5 | Tap Next rapidly | Keeps up without stuttering or showing a stale frame |
| [ ] 8.2.6 | Scrub through a sweep | Each frame is a different combination; the settings sheet follows it |
| [ ] 8.2.7 | Rotate the device | The same frame and field stay on screen |
| [ ] 8.2.8 | Type a frame number and press Go | Jumps straight there; the field has no underline under it |
| [ ] 8.2.9 | Type `0`, a number past the end, or letters | Nothing moves and the current number comes back |
| [ ] 8.2.10 | Step with Next/Prev | The number follows immediately, not after the frame decodes |

### 8.2a Summary animation

| # | Action | Expected |
|---|---|---|
| [ ] 8.2a.1 | Open a result | It lands on the summary, which builds and then loops; the counter reads "Summary · <field> · N frames" |
| [ ] 8.2a.2 | Watch a short (≤33 frame) analysis | Each frame is visible for about 300 ms |
| [ ] 8.2a.3 | Watch a 150-frame analysis | Every frame is there and the loop still finishes inside 10 s |
| [ ] 8.2a.4 | Compare early and late frames of a growing test | Colour rises through the sequence — one scale throughout, no per-frame renormalising |
| [ ] 8.2a.5 | Read the scale labels beside it | The widest bounds in the whole sequence, not the current frame's |
| [ ] 8.2a.6 | Switch field | The animation rebuilds in that field; switching back replays from cache |
| [ ] 8.2a.7 | Set a custom scale for one field | Only that field's animation rebuilds |
| [ ] 8.2a.8 | Tap **Next** on the summary, then **Prev** on frame 1 | Leaves to frame 1 and comes back |
| [ ] 8.2a.9 | Step into the frames while it is still building | The viewer stays responsive throughout |
| [ ] 8.2a.10 | Open a sweep node from the Lattice | Lands on that node, not on the summary |
| [ ] 8.2a.11 | Run on Android 8 | The first frame with a note that animation needs Android 9; sharing still works |

### 8.3 Measurement tools

| # | Action | Expected |
|---|---|---|
| [ ] 8.3.1 | Enable **Inspect** and tap the heatmap | HUD shows "Loc: (x, y)" and the field value with units |
| [ ] 8.3.2 | Drag with Inspect on | The readout follows continuously |
| [ ] 8.3.3 | Tap outside the correlated area | "Out of bounds" / "No data" rather than a wrong number |
| [ ] 8.3.4 | Turn Inspect off and drag | Pan and zoom work again |
| [ ] 8.3.5 | Tap the **x, y** button | Coordinate dialog with the image bounds shown as hints |
| [ ] 8.3.6 | Enter coordinates outside the image | Rejected with an out-of-bounds message |
| [ ] 8.3.7 | Enter valid coordinates and tap Find | Inspect switches on automatically and the probe jumps there |
| [ ] 8.3.8 | Enable **Max/Min** | Red and blue markers appear with a HUD card giving both values and positions |
| [ ] 8.3.9 | Switch field with Max/Min on | The markers move to the new field's extrema |
| [ ] 8.3.10 | Rotate with Inspect and Max/Min on | Both survive |

### 8.4 Settings used

| # | Action | Expected |
|---|---|---|
| [ ] 8.4.1 | Tap the info button | Sheet listing subset, step, strain window, strain method, ROI and image size |
| [ ] 8.4.2 | Compare against what you entered in the wizard | They match |
| [ ] 8.4.3 | Open it on a sweep | A **virtual strain gauge** row appears, and the values match the frame on screen |
| [ ] 8.4.4 | Scrub to another combination and reopen | The values follow the new frame, not the run's first |
| [ ] 8.4.5 | Open it on a sweep | A line-cut plot with colour-matched Exx / Eyy / Exy and the cut axis named |
| [ ] 8.4.6 | Open it on a single-setting run | No line-cut section |

### 8.5 Share and export

| # | Action | Expected |
|---|---|---|
| [ ] 8.5.1 | Tap Share | Sheet with six targets and a caption naming the current frame |
| [ ] 8.5.2 | **Result photo** | One annotated PNG of the field and frame on screen |
| [ ] 8.5.3 | **All field photos** | Five PNGs for the current frame, zipped for hand-off |
| [ ] 8.5.3a | **Field animations (GIF)** | Five GIFs, one per field, zipped; each loops when opened in a gallery app |
| [ ] 8.5.3b | Same, immediately on entering the viewer | Fields not built yet are built under the progress dialog — never silently missing |
| [ ] 8.5.4 | **PDF report** | Every frame's pages plus a telemetry page |
| [ ] 8.5.5 | **CSV data** | Header `x_px,y_px,u_px,v_px,exx,eyy,exy,znssd`; sweeps add subset/step/window columns |
| [ ] 8.5.6 | **Everything (.zip)** | Raw photos, the five animations, per-frame results for all five fields, the CSV and the PDF |
| [ ] 8.5.7 | Check the filename of anything you export | It carries the specimen / analysis name, not a generic `export.zip` |
| [ ] 8.5.8 | Export a very large analysis | Determinate progress dialog, then either a file or a snackbar naming the failure — never a crash, and never an OOM from rendering the report |
| [ ] 8.5.9 | Check an exported PNG | Full resolution, heatmap baked in, min/max annotated |

### 8.5a Send to — the export handoff

Every export in the app — from the viewer, from Settings → Export my data, and
from Download my cloud account data — ends at the same in-app **Send to** bottom
sheet rather than being thrown straight at the system chooser. The sheet has two
rows, so "keep this file" and "send this file somewhere" are separate decisions.

| # | Action | Expected |
|---|---|---|
| [ ] 8.5a.1 | Finish any export | A **Send to** sheet appears with a folder-icon **Save to Files** row and a **Share** row, captioned with the filename |
| [ ] 8.5a.2 | Tap **Save to Files** | A SAF save dialog opens (via the transparent `SaveExportActivity`); the file lands where you choose |
| [ ] 8.5a.3 | Cancel that SAF dialog | You come back to the app cleanly, with the export still available to share |
| [ ] 8.5a.4 | Tap **Share** | The normal system chooser opens with the same file attached |
| [ ] 8.5a.5 | Dismiss the sheet without choosing | Nothing is written and nothing is sent; no error |

### 8.6 Leaving

| # | Action | Expected |
|---|---|---|
| [ ] 8.6.1 | Tap the home button | Home, with the back stack cleared |
| [ ] 8.6.2 | Press Back on a single-setting result | Wherever you came from |
| [ ] 8.6.3 | Press Back on a sweep combination | The Lattice |
| [ ] 8.6.4 | Leave a single-setting result and look at its Home row | The headline reads "<Field> max <value> <unit>" for the last field you viewed |
| [ ] 8.6.5 | Leave a sweep and look at its Home row | The sweep caption is kept, not overwritten |
| [ ] 8.6.6 | Look for rename or delete in the viewer | Neither exists — both live on Home |

---

## 9. Session limit

The quota gate. Not a paywall — there is no billing anywhere in the app; the
route past it is an email to support.

```
9. Session limit — SessionLimitActivity
   ├── Email support (prefilled)
   ├── Re-check limit
   └── Back to my analyses
```

**Entry:** Home cold start, the Home FAB, the quota chip, a pre-run check, a
sweep hitting the cap, or a background upload rejected with a quota error.
**Exit:** Home, once the limit clears.

| # | Action | Expected |
|---|---|---|
| [ ] 9.1 | Reach the cap and tap **+** on Home | This screen instead of the source chooser |
| [ ] 9.2 | Read the chip | "Using N of M analyses" once the backend has reported numbers |
| [ ] 9.3 | Tap **Email support** | Mail app prefilled with account, quota, device ID and build |
| [ ] 9.4 | Tap **Re-check** while still at the cap | "Still at the limit" |
| [ ] 9.5 | Delete an analysis elsewhere, then tap **Re-check** | The screen closes and you can start a new analysis |
| [ ] 9.6 | Tap **Back to my analyses** | Home |

---

## 10. Background work

Uploads, restores and backup deletes run in WorkManager and survive leaving the
screen. They are **no longer silent about failure**: a terminal upload failure
surfaces on Home (snackbar + a "why + retry" dialog on the badge), and a terminal
restore failure surfaces on **both** Home and Settings, each carrying a human
reason. Success is quiet by design — the badge/list simply updates.

Progress is visible in two places: the transfer's foreground notification while
you are elsewhere in the system, and — new — a live badge and progress bar on the
Home row itself whenever Home is on screen, for uploads as well as
restores/downloads.

| # | Action | Expected |
|---|---|---|
| [ ] 10.1 | Finish an analysis with cloud backup on | Upload is queued; the Home badge moves Pending → Synced, with live progress on the row |
| [ ] 10.2 | Queue an upload with no network | It retries and eventually succeeds once you reconnect |
| [ ] 10.3 | Restore from Settings and leave the screen | It completes anyway; the analysis appears on Home / in the list |
| [ ] 10.3a | Cause a terminal upload or restore failure | The reason is surfaced on return (Home snackbar/badge dialog, or Settings snackbar) — not swallowed |
| [ ] 10.3b | Start a restore, then sit on Home while it runs | That row shows a progress bar and badge throughout — you are not left guessing |
| [ ] 10.4 | Background the app during a transfer | Its foreground notification tracks it; returning to Home picks the row progress back up |
| [ ] 10.5 | Delete a backup and background the app inside the undo window | The delete still fires after the window |
| [ ] 10.6 | Pull to refresh on Home with many cloud sessions | The listing pages through the backend until complete — sessions past the first page are not silently missing |
| [ ] 10.7 | Deep-refresh while Drive is unreachable | Nothing is purged from the list; a transient backend outage must not look like deleted data |

---

## 11. Hidden, gated and dead paths

Not part of the test pass. Recorded so nobody rediscovers them the hard way.

### Gated — real, but only under conditions

| Path | Condition |
|---|---|
| **Google SSO** button and its divider | Hidden unless `default_web_client_id` exists — i.e. the build's SHA-1 is registered in Firebase |
| Settings → **Pending access requests** → Admin | Hidden unless the backend reports role `admin` |
| Dev sign-in bypass (skips auth, disables cloud) | Debug build **and** the bypass flag **and** an emulator |
| Splash → Home without auth | Debug build with no `INDIC_API_BASE_URL`. A *release* build with no base URL cannot get past sign-in at all |

### Blocked on external setup

**Email sign-in link** is code-complete on both sides, but the App Link only
opens the app once Digital Asset Links are verified at the Firebase host. Until
then the link opens in a browser and the flow is effectively dead. See
[backend/AUTH_SETUP.md](../backend/AUTH_SETUP.md) §1a. The **password-reset**
App Link (`/finishReset`) rides on the same verification — once asset links are
verified, both are live; §1.13a covers the in-app reset form it opens.

### Dead — implemented but unreachable

| Feature | Where | Why unreachable |
|---|---|---|
| **Circle, ellipse and freeform ROI** | `StudioOverlayView` — full draw, hit-test and mask generation | `activity_roi_draw.xml` only exposes Rect and Square; everything else collapses to Rectangle |
| **Convergence view** (peak strain and noise vs VSG) | documented in `VsgPlotView` / `VsgStudy` | Never built — only line-cut plots exist |
| `VsgStudyRunner.ERROR_ENGINE_FAILED` | `VsgStudyRunner` | Declared, never assigned or matched |
| Frame-order *picker* mode | `FrameOrderHelper` | Only the initial state; the sort menu offers no way back once you sort |
| `cloud_delete_backup_failed` string | `strings.xml` | Leftover from the pre-undo-window delete; the live path uses `delete_cloud_failed`. Its sibling `cloud_delete_backup_done` **is** used, by `SessionSelectionController.eraseCloudBackup` |

### Behavioural gaps worth knowing

- **Account deletion re-authenticates first** (password prompt, or a fresh Google
  credential), so `delete()` is no longer refused as stale and the identity goes
  with the data. If it still fails the user is told the data is gone but the
  sign-in survived, and is signed out regardless.
- **Email verification is enforced for password accounts.** Sign-up and every
  later sign-in are blocked until the address is confirmed; the session is torn
  down and a fresh link sent. Google and email-link users are exempt — both
  arrive verified.
- **Pending approval does not poll**, despite its KDoc saying so. Only the button
  checks.
- **Coach marks cannot be replayed or reset** — the flags are write-once with no
  UI to clear them.
- **Home's empty-state "Restore" button just opens Settings**; there is no
  dedicated restore screen.
- **The advanced-parameters and sweep-settings headers look collapsible** (icon,
  title) but have no click listener. Only the lattice-samples panel really
  collapses.
- **The planned lattice on step 3 is deliberately inert** — node taps are
  disabled there, unlike the result lattice.
- **`DicUploadWorker` starts the Session limit screen from the background** on a
  quota rejection, which Android 10+ blocks — that path likely never fires.
- **`READ_MEDIA_IMAGES` is declared but never requested.** All media access goes
  through the system picker and SAF, so no runtime permission UI exists at all.
- **The only notification channel is for transfers** — `TransferNotifications`
  creates one channel and upload/restore workers post a foreground notification
  on it. There are no *completion* notifications; terminal failures surface in-app
  instead (§10).
- **No open-source licenses screen.** Privacy Policy and Terms of Service are
  linked from the About dialog and open the hosted pages in a browser; there is
  no in-app licence attribution list.
- **Crash reporting is opt-in and off until accepted.** Crashlytics collection
  is disabled in the manifest and enabled only after the first-run prompt or the
  Settings toggle, so a user who never answers sends nothing.
- **An interrupted solve cannot be resumed** — it is a foreground coroutine, so
  process death loses the run.
- **`AnalysisWizardSmokeTest`** opens the analysis wizard and asserts chrome
  (`btnNext` / instruction). Full Home → analyze → results E2E is not automated.
