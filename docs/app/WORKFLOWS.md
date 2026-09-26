# App manual test pass

Every user-facing flow in Semper as a checkable step. Walk the tables top to
bottom on a debug build and tick the boxes; a full pass should never land you on
a screen this file doesn't name.

This is the **test script**. The map — which files each flow runs through, what
it writes, where its failures surface — is
[../WORKFLOWS.md](../WORKFLOWS.md), whose §A ids match the section numbers here
(§5.4 below is `A5.4` there). For the *automated* suite see
[TESTING.md](TESTING.md); for how the code is laid out see
[ARCHITECTURE.md](ARCHITECTURE.md).

Semper is Activity-based — no NavHost, no Compose, no Fragments — so most of what
follows happens inside four files. §11 records the paths that are gated, blocked
or dead, and is not part of the pass.

## Legend

| Mark | Meaning |
|---|---|
| `[admin]` `[sweep]` `[single]` `[debug]` | only reachable in that condition |
| `→` | navigates to another flow |
| `[ ]` | tick when the step passes |

---

## 0. App launch

Decides where you land. No user input; the whole flow is a routing decision.

**Entry:** launcher icon. **Exit:** Login, Pending approval, Home or Session limit.

| # | Action | Expected |
|---|---|---|
| [ ] 0.1 | Cold start, signed out | Login screen; no spinner flash on a fast device |
| [ ] 0.2 | Cold start, signed in and approved | Home, no re-authentication prompt |
| [ ] 0.3 | Cold start, signed in but not yet approved | Pending approval screen |
| [ ] 0.4 | Cold start in airplane mode, previously approved | Home opens with an "Offline mode" toast |
| [ ] 0.5 | Cold start in airplane mode, never approved | Login with an explanatory message |
| [ ] 0.6 | Cold start with the analysis quota already full | Home opens and the Session limit screen comes up on top of it — the quota check lives in `HomeActivity.onCreate`, not in Splash |
| [ ] 0.7 | Slow network on launch | Spinner appears after ~400 ms, not instantly |

---

## 1. Login

**One screen**, not five. The sign-in / create-account distinction is a mode
toggle on the same layout, and forgot-password is a link that fires an email —
neither opens a separate screen.

**Entry:** Splash, sign-out, or the sign-in deep link. **Exit:** Home or Pending
approval, depending on the backend's answer.

| # | Action | Expected |
|---|---|---|
| [ ] 1.1 | Tap **Sign in with Google** | Account chooser; on success you land in Home or Pending approval |
| [ ] 1.2 | Dismiss the Google chooser | Returns to Login silently — no error toast |
| [ ] 1.3 | Google sign-in on a device with no Google account | "No Google account available on this device." |
| [ ] 1.4 | Sign in with a valid email + password | Routes onward per account status |
| [ ] 1.5 | Sign in with a wrong password | Red message pill at the bottom (`CrispToast`, not a Snackbar), fields keep their contents |
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
| [ ] 1.12 | Tap **Forgot password** with a registered email | Green message pill confirming the email was sent |
| [ ] 1.13 | Tap **Forgot password** with an unregistered email | Same success message — enumeration is deliberately not leaked |
| [ ] 1.13a | Open the reset link from that email on this device | The **app** opens on a set-new-password form — you never land on a Firebase web page |
| [ ] 1.13b | Enter a new password there | Same policy as registration applies; on success you are signed in and routed onward |
| [ ] 1.13c | Open a reset link that has expired or was already used | The form reports the link is no longer valid and offers to request a fresh one |
| [ ] 1.14 | Tap **Email me a sign-in link** | Confirmation pill; the email arrives with a link |
| [ ] 1.15 | Open that link on the same device | App opens and completes sign-in (needs verified asset links — see §11) |
| [ ] 1.16 | Open that link on a different device | "Open the sign-in link on the device that requested it." |
| [ ] 1.17 | Open the link while Login is already in the foreground | Handled in place, no duplicate screen |
| [ ] 1.18 | Arrive here from Splash after a status failure | The routing error is shown as a red message pill |

---

## 2. Pending approval

The allow-list gate. Firebase says who you are; the backend says whether you're
allowed in. A new account sits here until an admin approves it.

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
   ├── Cloud backups card ............ backups this phone has no row for
   │   ├── Restore ................... checklist (all ticked) → background restores
   │   └── Hide ...................... until a new backup appears
   ├── Session list
   │   ├── open a session ............ → 8. Result viewer, or 7. Lattice for sweeps
   │   ├── sync badge tap ............ retry backup / open Settings
   │   ├── "Only in cloud" row ....... "Restore this analysis?" → background restore
   │   ├── live row progress ......... backup (prepare/upload) and restore/download
   │   └── "session data gone" dialog  (local frames deleted, no cloud copy either)
   ├── Selection mode (long-press)
   │   ├── select all
   │   ├── rename                      (only with exactly one selected)
   │   └── delete → phone / cloud backup / everywhere when every row has both;
   │                otherwise one Delete that removes every copy
   ├── Quota chip ...................... → 9. Session limit / 4. Settings
   ├── Pull-to-refresh                  (deep cloud reconcile, repairs blobs)
   ├── Empty state → "Start analysis"   (same as the FAB — no longer Settings)
   ├── Start new analysis (FAB)
   │   ├── quota gate .................. → 9. Session limit
   │   └── opens the source chooser .... → 3a. Media picker sheet
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
| [ ] 3.4 | Look at a session row | Thumbnail, name, "date · N frames" (or "Parameter sweep"), headline value, sync badge. A single-setting headline is the first frame's convergence — "97.5% converged", or "97.5% converged on frame 1" when the run has several frames |
| [ ] 3.5 | Tap a normal session | Result viewer opens on frame 1 |
| [ ] 3.6 | Tap a sweep session | **Lattice** opens, not the viewer |
| [ ] 3.7 | Tap a row whose local files were deleted but which has a cloud backup | It carries an **"Only in cloud"** badge; tapping raises a **"Restore this analysis?"** dialog with a **Restore** button, which queues a background restore and **leaves you on Home** — it does not open the analysis when it lands |
| [ ] 3.7a | Tap a row with no local files *and* no cloud copy | "Session data gone" dialog — this is now the only case that reaches it |
| [ ] 3.7b | Watch a row during a backup | The badge and an inline progress bar track the prepare and upload phases |
| [ ] 3.7c | Watch a row during a restore or download | Same row progress. The bundle phase is deliberately **indeterminate** until the backend reports a percentage |
| [ ] 3.7d | Let a restore fail terminally while on Home | A message pill names the reason here too, not only in Settings |
| [ ] 3.7e | After 3.7d, reopen Home, then open Settings | The same failure is **not** announced again on either screen |
| [ ] 3.7f | Sign in on a phone that has none of the account's backups (a new phone, or after a reinstall) | Once the cloud check finishes, a card above the list reads "4 analyses in your cloud backup aren't on this phone." with **Hide** and **Restore**. With no rows, the empty state's title reads **No analyses on this phone** instead of "No analyses yet". Demo accounts never see the card |
| [ ] 3.7g | Tap **Restore** on that card | A **Restore to this phone** checklist, every backup ticked, each named with its size. Untick all and **Restore** greys out. Restore queues the ticked ones, toasts once, and each lands as a row with its own progress; the card counts down to what is left |
| [ ] 3.7h | Tap **Hide** | The card goes, with a message saying Settings can still restore them. It stays gone across relaunches until the account gains a backup it has not seen, which brings the card back for that one only |
| [ ] 3.7i | Delete a backup from Settings, or sign out and in as another account | The card stops offering the deleted backup at once; the other account starts with nothing hidden and nothing offered until its own cloud check |
| [ ] 3.8 | Tap a "Pending" sync badge | Upload is retried / queued |
| [ ] 3.8a | Tap a "Failed" sync badge | A dialog names *why* the last backup failed (device conflict, too large, render ran out of memory, result files no longer on the device) with a **Try again** action — not a silent re-queue |
| [ ] 3.8b | Let a background backup fail terminally while on Home | A message pill surfaces the reason once (quota-full is excluded — it has its own screen) |
| [ ] 3.9 | Tap a badge with cloud backup switched off | Settings opens |
| [ ] 3.10 | Long-press a row | Selection bar with count, select-all, rename, delete, close |
| [ ] 3.10a | Select several **Only in cloud** rows | A **Restore** (cloud-download) button joins the bar; tapping it queues one restore per row, shows "Restoring 3 analyses…" once, and each row shows its own progress. Add a row that is on the phone and the button goes away. Demo accounts never see it |
| [ ] 3.11 | Select two rows | Rename disappears; delete still offered |
| [ ] 3.12 | Rename a single selection | Text dialog; the new name persists after leaving and returning |
| [ ] 3.13 | Delete one session that exists **both** on the phone and in the cloud | Choice of **Delete from this phone**, **Delete the cloud backup** and **Delete everywhere**, plus Cancel |
| [ ] 3.13a | Choose **Delete from this phone** | Message pill: "Removed from this phone. Tap the row to restore it from the cloud." The row stays, now badged "Only in cloud" |
| [ ] 3.13b | Delete a row that is already cloud-only, on device only | No-op branch — there is nothing local left to remove |
| [ ] 3.14 | Delete several sessions | Same three choices when every row is on both, with the count in the message. A selection with no cloud copy gets one plural confirm; a mixed selection or cloud-only stubs get one Delete that removes every copy |
| [ ] 3.14a | Choose **Delete everywhere** for ten rows | The rows disappear at once; a message pill offers **Undo** for 5 s, then reads "Deleting 4 of 10…", then "10 analyses deleted." Production logs show ten DELETEs and no 404 |
| [ ] 3.14b | Tap **Undo** inside the 5 s | The rows come back and nothing reaches the backend |
| [ ] 3.14c | Delete while offline | The rows stay hidden; the delete runs when the network returns. If it still cannot reach the cloud, the pill names how many are left, with **Try again** |
| [ ] 3.15 | Press Back in selection mode | Selection clears; the app does not exit |
| [ ] 3.16 | Tap the quota chip below the cap | Settings (or the limit screen at the cap) |
| [ ] 3.17 | Reach the quota cap | The chip turns red |
| [ ] 3.17a | Finish one analysis under a cap above 1 | The chip reads "1 / M analyses used" with the real cap M, in the secondary colour — not "1 / 1" |
| [ ] 3.18 | Pull to refresh | Cloud reconcile runs; a repair or failure is reported by toast |
| [ ] 3.19 | Open Home with no sessions | Empty state reading "Import photos or a video to start an analysis." with a **Start analysis** button — it does what the FAB does; it no longer opens Settings |
| [ ] 3.20 | Tap **+** below the quota | The **New analysis** sheet (§3a) opens straight away — there is no intermediate menu |
| [ ] 3.21 | Tap **+** at the quota cap | Session limit screen instead of the sheet |
| [ ] 3.22 | Look for a transfer banner, feedback prompt or upgrade prompt on Home | There is none. Home's only progress surface is the per-row badge and bar; the transfer banner lives in Settings and the result viewer |
| [ ] 3.24 | Press Back on Home | "Exit app?" confirmation |

### 3a. New analysis — the media picker sheet

Not an Activity: `MediaPickerSheet`, a full-height bottom sheet titled **New
analysis**. It is what the Home **+** button opens directly — there is no
intermediate menu — and the same sheet the wizard's two dropzones open (§5.1),
so test it once here.

| # | Action | Expected |
|---|---|---|
| [ ] 3a.1 | Tap **+** on Home | The **New analysis** sheet opens **full height**; for ~1 s the grid is dimmed behind a large centred hint ("Select the reference image"), then tiles unlock |
| [ ] 3a.1a | Tap a tile during the dim | Nothing is selected until the hint ends |
| [ ] 3a.1b | Open the deformed-frames picker | Multi-select works immediately — no dim, no delay |
| [ ] 3a.2 | First open, having never granted media access | An empty state with an **Allow access** button; granting fills the grid without reopening the sheet |
| [ ] 3a.3 | Look at the grid | Three columns of device media; videos carry a badge so they are distinguishable from stills |
| [ ] 3a.4 | Pick a still as the reference | The sheet closes and step 1 shows it |
| [ ] 3a.5 | Pick a video | The sampling sheet opens instead (§5.1a) |
| [ ] 3a.6 | Open the sheet for deformed frames and tap several tiles | Multi-select; the confirm button counts them ("Use 12") |
| [ ] 3a.7 | Look for select-all | It is under the three-dot menu, which the first-run coach mark points out |
| [ ] 3a.8 | Tap the **Files** tab | The sheet dismisses and the system SAF browser opens for images *and* video — this is still the only route to DNG/RAW |
| [ ] 3a.9 | Open the sheet the first time in each mode | Coach marks run once for the reference pick and once for the deformed pick, then never again |
| [ ] 3a.10 | Check what permission is asked for, and when | `READ_MEDIA_IMAGES` (and `READ_MEDIA_VIDEO` from Home) is requested when the **Images** tab needs it — never on the Files path |

## 4. Settings

One scrolling screen of seven collapsible sections, all collapsed on open, plus a
two-button footer. Long-running work here does **not** block the screen: restores,
downloads and the two data exports run behind a **transfer banner** pinned at the
top of Settings (§4.0).

**Entry:** the Home gear (also the quota chip and any sync badge).
**Exit:** Home, Admin, a result, or Login.

#### 4.0 The transfer banner

A non-modal strip at the top of Settings, not a dialog: title, a progress bar
(determinate once a percentage is known), **Cancel**, and — when more than one
transfer is live — **‹ ›** arrows with an "n / N" page count. It carries restores,
bundle downloads, **Export my data** and **Download my cloud account data**.

| # | Action | Expected |
|---|---|---|
| [ ] 4.0.1 | Start any restore, download or export | A banner appears at the top of Settings; the rest of the screen stays usable |
| [ ] 4.0.2 | Start a second one while the first runs | The banner pages: "1 / 2", with ‹ › to step between them |
| [ ] 4.0.3 | Tap **Cancel** on a page | That transfer stops; the others keep running and the paging recounts |
| [ ] 4.0.4 | Let one finish | Its page disappears; the banner hides itself once the last one is done |

| # | Action | Expected |
|---|---|---|
| [ ] 4.1 | Open Settings | All seven sections are collapsed; chevrons rotate on tap |
| [ ] 4.2 | Expand **Account** | Your email and "Device ID · …" are shown; the device ID can be selected and copied |
| [ ] 4.2a | Expand **Account** on a licensed account | "Licensed as SEMP-…" is shown below the device ID — the key prefix support asks for, never the key. Absent whenever the account is not entitled: a Demo account (even though its Demo key has a prefix), and a licence that is revoked, past grace, or waiting on a floating seat |
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
| [ ] 4.12a | Tap a row with no local data | The restore confirm, not a "session data gone" dialog |
| [ ] 4.13 | Tap **Back up now** on a local-only row | Upload is queued; the row state changes |
| [ ] 4.14 | Tap **Restore** on a cloud-only row | A **"Restore this analysis?"** confirm first; accepting toasts that it continues in the background, adds a **stub row immediately** so you can see it, and raises a banner entry (§4.0). The analysis appears in the list without reopening Settings |
| [ ] 4.14a | Restore a backup that fails terminally (deleted server-side, or not this account) | A message pill names the failure — the restore is no longer silent |
| [ ] 4.14b | Look at a row that is on the phone **and** in the cloud | It offers **Download**, but not Restore — Restore only appears when the local frames are missing |
| [ ] 4.14c | Tap **Download** | A SAF save dialog opens **first**, suggesting `<name>_Session.zip`; choosing a location starts a `DicBundleDownloadWorker` and the row reads "Downloading…" |
| [ ] 4.14d | Leave the section, or Settings entirely, mid-download | It keeps going — this is WorkManager, not an Activity scope — and reports the result when it lands |
| [ ] 4.14e | Look for a **Send to** sheet after a Download | There is none, by design: you already chose the destination, so the bytes go straight there |
| [ ] 4.14f | Download a row whose cloud zip is unavailable | It falls back to packing the local session into the same destination |
| [ ] 4.14g | Cause a Download to fail | The empty destination file is removed rather than left as a 0-byte zip, and the failure is named |
| [ ] 4.15 | Tap the bin on a row with a local copy | The same three choices as Home §3.13: phone / cloud backup / everywhere, plus Cancel |
| [ ] 4.16 | Tap the bin on a cloud-only row | "Delete this backup forever?" naming the analysis |
| [ ] 4.17 | Confirm any backup delete, then tap **Undo** within 5 s | The row returns; nothing is deleted server-side |
| [ ] 4.18 | Confirm and wait past the undo window | The backup is really gone after a refresh |
| [ ] 4.18a | Expand **Storage** | Analyses and cache sizes are measured and shown, not left on "Measuring…" |
| [ ] 4.18b | Tap **Free up space** with backed-up analyses present | A confirm dialog first, **naming how much it will reclaim** — only the frames, raw images and processed/staging folders it drops, not the kept `reference.png` / small files (`LocalArtifacts`), so the figure matches what is freed; accepting drops those local frames, the rows become "Only in cloud" on Home and the analyses total falls |
| [ ] 4.18c | Tap it with nothing safely backed up | The button is **disabled** and the subtitle says there is nothing to free — it cannot strand un-backed-up data |
| [ ] 4.18d | Tap **Clear cache** | The cache total drops; open analyses still work — only regenerable files go. At 0 bytes the button is disabled |
| [ ] 4.18e | Drag the **auto-free** slider off 0 | The label names the budget in GB; at 0 it reads "off" |
| [ ] 4.18f | Set a budget below current usage and restart the app | Space is reclaimed at start-up, oldest backed-up analyses first |
| [ ] 4.18g | Tap the ⓘ beside it | Explains that only cloud-backed analyses are ever dropped |
| [ ] 4.19 | Tap **Export my data** | A master ZIP is built behind the **transfer banner** (§4.0) — not a blocking dialog — then handed to the **Send to** sheet (§8.5a) |
| [ ] 4.19a | Tap **Download my cloud account data** | The server-side export of the account is fetched the same way, banner and all, then offered through the same sheet |
| [ ] 4.19b | Trigger either export with no network | It fails with a named reason, not a silent no-op |
| [ ] 4.19c | Toggle **Send crash reports** off, then force a crash on a debug build | Nothing is uploaded; turning it on again resumes collection without a restart |
| [ ] 4.19d | Read what that toggle actually controls | It gates **both** crash reporting and consent-gated product analytics (analysis started/completed/failed, exports, feedback), and the label now says so: **Send crash reports and usage data**, with the subtitle naming the usage events and what is never sent |
| [ ] 4.20 | Tap **Delete my account and data** | Dialog listing exactly what goes: local analyses, cloud backups, profile and device |
| [ ] 4.20a | Confirm it | The **sign-in screen** opens to re-verify, with your email filled in and locked, and no "create account" toggle |
| [ ] 4.20b | Enter the wrong password there | "Incorrect password." and nothing is deleted |
| [ ] 4.20c | Confirm it, as a Google account | Same screen; **Sign in with Google** re-authenticates instead |
| [ ] 4.20d | Confirm it, as an email-link-only account | **Email me a sign-in link** works too — this account could not be deleted at all before |
| [ ] 4.20e | Press back on that screen | Returns to Settings; nothing is deleted |
| [ ] 4.21 | Confirm the delete with no network | Nothing local is touched; a failure toast is shown |
| [ ] 4.22 | Confirm the delete online | Everything is wiped, including the sign-in identity, and you land back on Login |
| [ ] 4.22a | Sign up again with the same email afterwards | It behaves as a brand-new account — the old identity is gone |
| [ ] 4.23 | Drag the **Max frames** slider | Value label tracks in steps of 10 from 10 up to the ceiling. 150 is the compile-time fallback; the live ceiling comes from remote config, so a backend can lower it |
| [ ] 4.24 | Tap the ⓘ next to it | Explains the cost of more frames |
| [ ] 4.25 | Set it to 20, then import 40 frames in an analysis | Only the first 20 are kept, with a "capped" toast |
| [ ] 4.26 | Expand **Help & support** | Five actions: **Open Manual**, Report a bug, Request a feature, **Send feedback**, Email support — plus the support address, selectable and copyable |
| [ ] 4.26a | Tap **Open Manual** | The hosted manual opens in a browser |
| [ ] 4.26b | Tap **Send feedback** | Mail app opens to support@, subject "Semper feedback (v… / …)", body carrying app version, device model, Android level and build type — no account address needed |
| [ ] 4.27 | Tap **Email support** | Mail app opens to support@, subject "Semper support request", body carrying account, device ID, app version and device model |
| [ ] 4.28 | Same with no mail app installed | "No email app found…" toast naming the address; no crash |
| [ ] 4.29 | Tap **About** | "Semper v<name> (<code>)" plus **Privacy Policy** and **Terms of Service** buttons |
| [ ] 4.29a | Tap either legal button | The hosted page opens in a browser; both are reachable without an account |
| [ ] 4.30 | Tap **Sign out** → confirm | Login, back stack cleared |

### 4.1 Admin `[admin]`

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

| # | Action | Expected |
|---|---|---|
| [ ] 5.1.1 | Tap the reference dropzone | The **New analysis** sheet (§3a) opens on Images — the same sheet the Home FAB uses |
| [ ] 5.1.2 | Pick a `.dng` or `.tif` via **Files** | Card shows the filename and `W × H`; no decode error |
| [ ] 5.1.3 | Tap **Change** on the reference card | Source chooser reopens; the new image replaces the old |
| [ ] 5.1.4 | Pick deformed frames from the sheet's grid (multi-select, then **Use N**) | Card shows "N frames" and the first…last filenames |
| [ ] 5.1.5 | Pick more frames than *Max frames* | The first N are kept, with a "capped" toast |
| [ ] 5.1.6 | Load a reference only | **Next** is disabled — a grey button with grey text, not sky blue — with "add at least one deformed frame to continue" |
| [ ] 5.1.7 | Load deformed frames only | **Next** is disabled with the matching reference message |
| [ ] 5.1.8 | Include one frame of a different pixel size | **Compute** stays disabled. On step 2 a warning chip says the image resolution isn't matching the reference (W×H) and lists the mismatched filename(s); its info icon asks first whether to leave the app, then opens the frame-size FAQ |
| [ ] 5.1.9 | Load JPEGs | A non-blocking accuracy warning chip appears; its info icon asks first whether to leave the app, then opens the JPEG FAQ |
| [ ] 5.1.10 | Load a poorly speckled reference | A low-texture warning names a suggested subset size; its info icon opens the speckle FAQ behind the same leave-the-app confirm |
| [ ] 5.1.11 | Load any speckled reference and open step 2 | A muted line under the subset slider reads "Speckle measures about N px across. Good practice asks for 3–9 px." |
| [ ] 5.1.12 | Load a reference shot far back, so the dots are 1–2 px | A chip **on step 1** says the speckle is below the 3 px minimum and to shoot closer or use a coarser pattern |
| [ ] 5.1.13 | Load a close-up whose dots span more than 9 px | The step 1 chip says the pattern is over-resolved — correlates fine, but a finer pattern would give more points. No span chip appears on step 2: the size verdict suppresses it |
| [ ] 5.1.14 | Load a reference inside the band whose speckle needs a larger subset than the slider is on (e.g. dots ~7 px against a subset of 15) | A chip appears **on step 2, under the subset slider**, naming the subset in use and the one wanted. Raise the slider past it and the chip clears in place, without leaving step 2 |
| [ ] 5.1.15 | Load a reference with no measurable pattern at all (blank card) | Neither the readout nor either chip appears; no number is invented |
| [ ] 5.1.16 | Open the sort menu → **Name A–Z** | Thumbnails reorder; the badge numbers renumber 1…N |
| [ ] 5.1.17 | Choose **Date oldest first** | Order follows capture date, not filename |
| [ ] 5.1.18 | Choose **Manual** | Hint toast about dragging; drag a thumbnail and it stays where dropped, back at its normal size, with the badges renumbered |
| [ ] 5.1.19 | Load a single deformed frame | The sort control is hidden |
| [ ] 5.1.20 | Press Back on step 1 with inputs loaded | "Exit analysis?" confirmation. On steps 2 and 3 Back walks back a step instead — the confirm is step 1 only |
| [ ] 5.1.21 | Open step 1 for the first time | Coach marks point at the reference dropzone, then the deformed one |

#### 5.1a Video source

Reached whenever the file picked — from the grid or through Files — is a video.

| # | Action | Expected |
|---|---|---|
| [ ] 5.1a.1 | Pick a video (a badged tile in the grid, or via Files) | Sampling sheet opens with resolution, source fps and duration |
| [ ] 5.1a.2 | Switch the mode between **Keyframes (DIC)** and **Fixed interval** | Keyframes hides the fps slider and notes it extracts sync I-frames; Fixed interval shows the slider, and dragging it updates the estimated frame count live |
| [ ] 5.1a.2a | Extract in **Fixed interval** over the whole clip, then scrub the deformed frames | Consecutive frames differ — not runs of repeats of the same I-frame |
| [ ] 5.1a.3 | Drag the time-segment handles | Estimate updates; the button relabels to "Extract N frames" |
| [ ] 5.1a.4 | Choose settings that exceed *Max frames* | The estimate shows the cap being applied |
| [ ] 5.1a.5 | Tap **Extract** | Progress overlay; frame 0 becomes the reference, the rest deformed |
| [ ] 5.1a.6 | Look for the sort control afterwards | Hidden — video frames are already in time order |
| [ ] 5.1a.7 | Pick an `.avi` from a lab or UTM camera (uncompressed or motion-JPEG) | Sampling sheet opens with its resolution, rate and duration; **Extract** writes the same lossless grayscale frames an MP4 does |
| [ ] 5.1a.8 | Pick an `.avi` whose codec this device has no decoder for (Xvid on a device without MPEG-4 ASP) | Snackbar naming the four-letter codec and what to do instead, with **Why?** → video-read FAQ |
| [ ] 5.1a.9 | Pick an `.avi` that is truncated or not a video at all | The ordinary "could not read this video" snackbar — no crash |
| [ ] 5.1a.10 | **Fixed interval**, whole clip, the source's own rate, then **Extract** | The deformed count is exactly the sheet's estimate minus the reference — a 20-frame clip gives 1 + 19, not a promised 21 |
| [ ] 5.1a.11 | Read the codec snackbar from 5.1a.8 | The whole message shows — both remedies, not cut after two lines — and it stays up long enough to read (about 9 s) |

`VideoFrameExtractionDeviceTest` (instrumented) covers the extraction itself on an
emulator: it encodes MP4 and AVI (Y800, MJPG, H.264) clips whose frames are stamped
with their own index, and checks keyframe and fixed-interval picks, portrait
rotation, and that every PNG came from the lossless Y-plane path rather than the
retriever fallback. Rows 5.1a.1–11 remain for the sheet itself and for real
vendor decoders and camera AVIs.

### 5.2 Step 2 — Confirm settings

| # | Action | Expected |
|---|---|---|
| [ ] 5.2.1 | Arrive on step 2 | Analysis mode is at the top; there is no inputs-summary card |
| [ ] 5.2.2 | Read the ROI line before editing | "Full image W × H" |
| [ ] 5.2.3 | Tap **Edit** → draw an ROI → save | The line becomes "W × H at (x, y)" |
| [ ] 5.2.4 | Tap **Edit** → cancel | Falls back to full image; any mask is cleared |
| [ ] 5.2.5 | Switch to **Parameter sweep** | Advanced parameters hide; sweep settings appear. Bottom nav reads **Next: Summary →** |
| [ ] 5.2.6 | Type **step size** as subset ÷ N in sweep settings | N is 2–9 (default 3); overlap on the same row is `1 − 1/N`; each subset uses `step = round(subset / N)` |
| [ ] 5.2.7 | Tap the ⓘ next to the mode toggle | Explains single setting vs sweep |
| [ ] 5.2.8 | Switch back to **Single setting** | Advanced parameters return with their previous values |
| [ ] 5.2.9 | Drag the **subset size** slider | Only odd values between 15 and 121; the field mirrors it; overlap updates from the current step |
| [ ] 5.2.10 | Type an even subset size and press Done | Snapped to the nearest valid odd value |
| [ ] 5.2.11 | Type nonsense in a parameter field | Reverts to the previous value on commit |
| [ ] 5.2.12 | Drag **step size** | Max is `min(30, subset/2)` so overlap stays ≥ 0.5; the overlap field mirrors it |
| [ ] 5.2.12a | Type **overlap** on the step-size row | 0.50–0.99; step size rewrites to `round(subset × (1 − overlap))` |
| [ ] 5.2.13 | Drag **strain window** | Odd values 3–31, in **points**; the field mirrors it and the line under the slider reads "VSG N px at step S px", N = (window − 1) × step + 1. It starts at 5 |
| [ ] 5.2.13a | Open step 2 having never copied params from a lattice | No **Paste params** chip — it only appears when the clipboard holds a set |
| [ ] 5.2.13b | Copy params from a sweep lattice (§7.3), then return here | The chip appears beside **Reset**; tapping it fills subset, step and strain window (overlap follows step) and scrolls them into view. The window is the one whose VSG at the pasted step matches the node's |
| [ ] 5.2.13c | Change the **step** with the window fixed | The VSG line follows: window 5 reads VSG 21 px at step 5, 41 px at step 10 |
| [ ] 5.2.14 | Tap each ⓘ | Subset, step, overlap and strain window each explain themselves |
| [ ] 5.2.15 | Switch the interpolator to **Keys 6×6** | Selection sticks; the run uses it |
| [ ] 5.2.16 | Change several parameters, then tap **Reset** | Subset returns to the recommended value, step to 5, overlap follows step, strain window to 5 points, interpolator to Bicubic |
| [ ] 5.2.16a | After a failed run leaves an ❌ line on step 2, change subset / paste params / replace frames | The run-status line clears; the frame-size chip (if any) only shows when sizes still mismatch |
| [ ] 5.2.17 | Load a well-speckled reference and watch the subset | It is pre-seeded from the SSSIG recommendation — until you touch it |
| [ ] 5.2.18 | Draw an ROI smaller than the subset and tap **Compute** | "ROI too small" snackbar with a **Why?** action; that asks first whether to leave the app, then opens the ROI FAQ. The run does not start |
| [ ] 5.2.19 | Edit a parameter field and tap **Compute** without pressing Done | The typed value is committed and used |
| [ ] 5.2.20 | Open step 2 for the first time | Coach marks point at the analysis-mode toggle, the ROI card, then the advanced-parameters header |
| [ ] 5.2.21 | Tap **Pick frame** (sweep, multi-frame) | Dialog with a radio list, a frame-number field and a live preview |
| [ ] 5.2.23 | Drag the subset range handles | Both ends stay odd; min never crosses max |
| [ ] 5.2.24 | Type a subset min above the max | Clamped so min ≤ max |
| [ ] 5.2.25 | Drag the strain window range | Two handles like the subset's, in points (3–31, default 3–11); the min and max boxes track it |

### 5.3 Step 3 — Sweep summary `[sweep]`

| # | Action | Expected |
|---|---|---|
| [ ] 5.3.1 | Arrive on step 3 | Toolbar reads "Step 3 of 3"; planned lattice is first, line cut below it |
| [ ] 5.3.6 | Tap each ⓘ | Line-cut axis and samples each explain themselves |
| [ ] 5.3.6a | Open the summary page for the first time | Three coach marks in order: the planned lattice, the line cut, then the **Compute** button |
| [ ] 5.3.6f | Toggle the line-cut axis X ↔ Y | The preview redraws the cut line through the ROI centre |
| [ ] 5.3.11 | Look at the planned lattice | Grid of nodes, subset across, VSG up; taps do nothing (it's a preview) |
| [ ] 5.3.12 | Open the samples panel (gear) and set 4 × 4 | The plan summary reads 16 analyses and the lattice redraws |
| [ ] 5.3.13 | Set samples to 9 | Clamped to 8 |
| [ ] 5.3.14 | Set the subset min (on step 2) above what the ROI can hold | Warning chip: "Subset range starts above what this image and ROI can hold"; info icon opens the sweep-subset FAQ behind the leave-the-app confirm. **Compute** is disabled |
| [ ] 5.3.15 | Set a strain window range that no subset can satisfy | Warning chip: "No combination fits this ceiling — raise Max strain window or lower the subset range"; info icon opens the empty-plan FAQ behind the same confirm. **Compute** is disabled |
| [ ] 5.3.16 | Read a valid plan summary | "N analyses · subset a–b px · window c–d points"; a one-combination plan reads "1 analysis" with its real subset and window, not "1–1" |

### 5.4 Running

The same overlay is reused for importing frames and extracting video, but the two
compute tiles are **hidden** there — they would only ever read zero. Import and
extraction show determinate progress instead.

| # | Action | Expected |
|---|---|---|
| [ ] 5.4.1 | Start a run | Overlay with title, percentage, status, elapsed seconds |
| [ ] 5.4.2 | Watch the two tiles during a solve | **# converged** and **convergence** update as it goes |
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
| [ ] 5.5.1 | Run on a featureless image pair | Engine failure dialog naming the feature-detection cause, **and the frame and image it failed on**, with a **Why?** that opens the features FAQ behind the leave-the-app confirm |
| [ ] 5.5.1a | Run a batch where a later frame decorrelates | "Stopped early" — not "Analysis failed" — naming the frame and how many were kept |
| [ ] 5.5.1c | Acknowledge that dialog | The kept frames open in the viewer — the run does not leave you back on the settings page |
| [ ] 5.5.1d | Press Back on that dialog | Nothing dismisses it; the only way on is through to the results |
| [ ] 5.5.1e | Return to Home afterwards | The short analysis is listed with the frames it kept — not a phantom row from a run reported as failed |
| [ ] 5.5.1n | First run of a batch whose frame 1 keeps no points, then later frames solve and one fails or decorrelates | The failure dialog explaining frame 1 (strain window, nothing correlated, or unreadable) — **not** "Stopped early … saved and open next": a run is saved only when frame 1 solves, and no Home row appears |
| [ ] 5.5.1f | Read that Home row | "39 of 50 frames" and the reason, not a bare "39 frames" |
| [ ] 5.5.1g | Open it and tap ⓘ | Settings used lists **Stopped early** and **Frames solved** |
| [ ] 5.5.1h | Force-stop the app, reopen, look again | Both still say why — the reason is stored, not held in memory |
| [ ] 5.5.1i | Run with a strain window the correlated area cannot support (0 points solved) | Engine-failure dialog naming VSG failure — not the generic "No data produced" copy — with **Why?** → VSG FAQ; after OK, ⓘ beside the error line opens the same FAQ confirm |
| [ ] 5.5.1j | Trigger an OOM (huge ROI, step 1) | "Analysis stopped unexpectedly" dialog naming the error and what causes it |
| [ ] 5.5.1k | Fail to decode a reference (corrupt / unsupported) | Snackbar with **Why?** → import-reference FAQ |
| [ ] 5.5.1l | Fail to import deformed frames | Snackbar with **Why?** → import-deformed FAQ |
| [ ] 5.5.1m | Pick a video the app cannot read | Snackbar with **Why?** → video-read FAQ |
| [ ] 5.5.1b | Sweep a decorrelated pair | Stops after two combinations under 50% rather than sweeping the rest |
| [ ] 5.5.2 | Run with an unusable ROI | Engine failure dialog naming the ROI cause |
| [ ] 5.5.3 | Finish a single-setting run | Result viewer opens on frame 1 |
| [ ] 5.5.4 | Finish a sweep with some combinations failing | "N of M skipped" toast, then the Lattice |
| [ ] 5.5.4a | Tap a hollow node | A dialog titled with that combination (S · St · W · VSG) and a one-line reason — decorrelated, subset too large, VSG failure — plus **Why?** to the matching engine FAQ |
| [ ] 5.5.4f | Reopen that sweep from Home, tap a hollow node | The same specific reason, not the generic "was skipped" — codes are stored on the record |
| [ ] 5.5.4g | Restore that sweep from the cloud, tap a hollow node | Same again; the reasons survive the round-trip |
| [ ] 5.5.4c | Tap a hollow node from a sweep with no recorded code | Still explains itself rather than doing nothing |
| [ ] 5.5.4d | Open the lattice for the first time | Coach marks point out the graph, then the strain plot's drag readout |
| [ ] 5.5.4e | Drag across the strain plot | Every curve's value at that position, each in its own curve's colour |
| [ ] 5.5.4b | Run a sweep where **every** combination fails | The Lattice opens — not the parameter screen — all nodes hollow, summary says all failed, tapping the summary opens the VSG FAQ confirm, and **View** and **Save graph** are both disabled |
| [ ] 5.5.5 | Finish a sweep cleanly | Lattice opens with every node filled |
| [ ] 5.5.6 | Hit the quota during a run | Session limit screen |
| [ ] 5.5.7 | Re-run with the same inputs after changing a parameter | The same Home row is updated, not duplicated |
| [ ] 5.5.8 | Change the inputs and run again | A new Home row is created |

---

## 6. ROI editor

Full-screen editor over the reference image. Two edit modes crossed with two
tools — the Crop/Erase toggle persists when you switch between Draw and Manual.

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

**Entry:** finishing a sweep, or tapping a sweep row on Home or in Settings.
**Exit:** Result viewer, or back to Home.

### 7.1 Lattice and stepper

| # | Action | Expected |
|---|---|---|
| [ ] 7.1.1 | Open a sweep | Lattice with **subset across and VSG (px) up**: one window in points is a larger VSG at a larger subset's step, so the rows need not line up; the coach mark explains filled vs hollow (no persistent legend row) |
| [ ] 7.1.1a | Look for axis titles on the result lattice | There are none — it draws compact, so only tick numbers and a "px" unit on the last subset tick. The coach mark is what names the axes |
| [ ] 7.1.2 | Open a sweep that had failures | Skipped combinations are hollow rings with no fill — any surface behind them shows through |
| [ ] 7.1.3 | Compare two different filled nodes | Same fill colour — node identity comes from position + the selection ring, not a colour key. The plot below only puts colour on the *focused* curve (§7.2.4) |
| [ ] 7.1.4 | Read the summary line, above the lattice | "N combinations · S solved · K skipped · step subset÷D" |
| [ ] 7.1.4a | Open a sweep where every combination failed | The line is replaced by "All combinations failed — tap a node for details." |
| [ ] 7.1.4b | Open one where the step denominator cannot be derived | The "step subset÷D" tail is dropped rather than printing a wrong number |
| [ ] 7.1.5 | Tap a solved node | A selection ring appears; the stepper chip and the plot follow it |
| [ ] 7.1.6 | Tap a skipped node | A dialog explains why it was skipped; nothing is focused |
| [ ] 7.1.7 | Double-tap a solved node | The result viewer opens on that combination |
| [ ] 7.1.8 | Long-press a solved node | Same as double-tap |
| [ ] 7.1.9 | Tap **›** repeatedly | Focus walks the solved nodes in order; the chip names each one |
| [ ] 7.1.10 | Reach the first or last solved node | **‹** or **›** disables rather than wrapping |
| [ ] 7.1.11 | Open a sweep with no solved nodes at all | The stepper row is hidden and both bottom buttons are disabled |
| [ ] 7.1.12 | Scroll the screen down | Summary, lattice, stepper and plot scroll together; the **Save graph · View** bar stays pinned |

### 7.2 Strain plot

| # | Action | Expected |
|---|---|---|
| [ ] 7.2.1 | Read the plot title | It names the cut axis — "Strain along X axis" or "…Y axis" |
| [ ] 7.2.2 | Change the strain component spinner | The plot redraws for Exx / Eyy / Exy |
| [ ] 7.2.3 | Look at the **All / Node** pill on open | It is one physical pill, and **All** is selected — a sweep opens on every curve, not on one |
| [ ] 7.2.4 | Read the plot in **All** | Every solved combination is drawn: the focused curve at full strength in its own colour, every other curve sharing one muted neutral (not each its own dimmed colour) — only one hue ever carries meaning at a time |
| [ ] 7.2.4a | Switch to **Node** | Only the focused combination is drawn |
| [ ] 7.2.5 | Pinch to zoom on the plot | It zooms about the pinch centre |
| [ ] 7.2.6 | Drag with two fingers | The zoomed plot pans |
| [ ] 7.2.7 | Double-tap the plot | The viewport resets to fit |
| [ ] 7.2.8 | Zoom in, then step to another node | The zoom **survives** — the viewport is kept while the component is unchanged |
| [ ] 7.2.9 | Zoom in, then change the strain component | The viewport **resets**, because Exx/Eyy/Exy differ in magnitude and a stale zoom would clip |
| [ ] 7.2.10 | Drag one finger across the plot | A vertical guide follows it; a dot marks the selected curve and its value is drawn beside it |
| [ ] 7.2.11 | Watch the slider while dragging | It tracks the finger |
| [ ] 7.2.12 | Drag the slider instead | The guide, dot and readout follow it — the sync works both ways |
| [ ] 7.2.13 | Read the readout | "x=…  y=…" for one unmuted series; when **All** shows several curves, "x=…" plus each `label=value` |
| [ ] 7.2.14 | Step to another node with the plot scrubbed | The readout clears and the slider returns to 0 |
| [ ] 7.2.15 | Drag one finger across the plot, then lift it | The guide, dot and readout clear and the slider returns to 0, so it never marks a line that is gone |
| [ ] 7.2.16 | Drag the slider off 0, then pinch, or double-tap the plot | The slider returns to 0 once the gesture ends — it is not left where the first finger landed |

### 7.3 Copy, save and open

| # | Action | Expected |
|---|---|---|
| [ ] 7.3.1 | Double-tap **or long-press** the parameter chip | "Parameters copied" — subset, step and strain window go to the app's parameter clipboard. (The readout line no longer carries params, so the chip is the only copy target; its content description still mentions only double-tap.) |
| [ ] 7.3.2 | Start a new single-setting analysis afterwards | Step 2 offers a **Paste params** chip that fills all three (§5.2.13b) |
| [ ] 7.3.3 | Tap **View** with a node focused | The result viewer opens on that combination |
| [ ] 7.3.4 | Tap **Save graph** | A PNG is rendered and handed straight to the **system chooser**. This is the one export that skips the in-app **Send to** sheet (§8.5a), so there is no "Save to Files" row here |
| [ ] 7.3.5 | Open that PNG | A header — study, reference name + deformed frame count, the focused node's parameters **always**, and *additionally* the combination count when the plot is in **All** — then the plot at full fit and a **single-column** colour legend of the curves |
| [ ] 7.3.6 | Save a graph while the on-screen plot is zoomed in | The export is rendered fit-to-data from a detached view — your zoom is neither baked in nor disturbed |
| [ ] 7.3.7 | Open a combination, then press Back | You return here, not to Home |

---

## 8. Result viewer

The main results browser. Reached directly for a single-setting run, or through
the Lattice for a sweep.

**Entry:** a finished single-setting run, a Home or Settings row, or a Lattice
node. **Exit:** Home, or back to the Lattice.

### 8.1 Fields, image and scale

| # | Action | Expected |
|---|---|---|
| [ ] 8.1.1 | Open a result | The U field is shown as a jet heatmap over the reference |
| [ ] 8.1.2 | Tap the field FAB, then pick V / Exx / Eyy / Exy | Heatmap and colour scale follow; edge title updates; the live field stays checked in the popup |
| [ ] 8.1.2a | Open the field popup | All five fields are listed; the one on screen is highlighted |
| [ ] 8.1.2b | Check fit at rest | Heatmap (ROI or accepted points) is contained between the top bar and scrub bar; the colour scale may overlay the right edge and stays put while the figure pans |
| [ ] 8.1.2c | Zoom, pan a region that was under the scale into the open area, then tap to probe | Probe readout shows a real point; tapping the scale itself still opens the custom-scale dialog, not a probe |
| [ ] 8.1.3 | Check the scale units | `px` for U and V, `mε` for the strain fields |
| [ ] 8.1.3b | Compare the scale labels with the ⓘ sheet's max/min | On a frame, scale labels read "≤ x" / "≥ y" and may be narrower than the ⓘ sheet (display clamp vs true extrema). On the summary, the colour bar and ⓘ both quote the lowest scale-min and highest scale-max across frames (those two ends may come from different frames) |
| [ ] 8.1.4 | Pinch to zoom | Zooms smoothly up to about 10×; panning is clamped to the image |
| [ ] 8.1.5 | Zoom in and pan | The heatmap stays registered to the reference — no drift |
| [ ] 8.1.6 | Zoom, then switch field | Zoom and pan are preserved |
| [ ] 8.1.7 | Tap the colour scale bar | Custom scale dialog, prefilled with the bounds the bar shows (the auto ones until a custom scale is set); **Apply** without edits leaves the scale as it is |
| [ ] 8.1.8 | Enter min ≥ max and apply | Rejected with a snackbar and a **Why?** that opens the custom-scale FAQ |
| [ ] 8.1.9 | Enter valid bounds and apply | The heatmap and the scale labels both change |
| [ ] 8.1.10 | Switch field, then switch back | The custom bounds are remembered *per field* |
| [ ] 8.1.11 | Reopen the dialog and tap **Auto scale** | The override is dropped; the scale returns to this frame's clamped bounds — not to its true extrema |
| [ ] 8.1.12 | Open ⓘ | Peek sheet shows the specimen name, max / min with coordinates, mean, a histogram of this field, and settings used |
| [ ] 8.1.13 | Scrub frames without a custom scale | Scale labels follow each frame's own 2nd/98th-percentile clamp |

### 8.2 Frames

| # | Action | Expected |
|---|---|---|
| [ ] 8.2.1 | Read the frame counter | The original filename (or the sweep label) plus "(i / N)" |
| [ ] 8.2.1a | Open a batch the run skipped a frame of (one kept no points or would not read) | Every later frame keeps its own filename — in the counter, the share CSV's `image` column, each PDF page title and the ZIP's `results/NNN_<name>/` folders, numbered as planned (frame 3 stays `003_…`) — matching the cloud backup's CSV and `Frame_N` folders |
| [ ] 8.2.2 | Tap **Next** | Advances one frame; the heatmap and stats update |
| [ ] 8.2.3 | Reach the last frame | **Next** disables and fades |
| [ ] 8.2.4 | Reach the first frame | **Prev** goes back to the summary, not nowhere |
| [ ] 8.2.5 | Tap Next rapidly | Keeps up without stuttering or showing a stale frame |
| [ ] 8.2.6 | Scrub through a sweep | Each frame is a different combination; the settings sheet follows it |
| [ ] 8.2.6a | On a sweep, **Prev** on the first combination | Stays on that combination (no overview slot) |
| [ ] 8.2.6b | Open Share on a sweep | No **Animations** row; **Everything** has no `animations/` folder |
| [ ] 8.2.7 | Rotate the device | The same frame and field stay on screen |
| [ ] 8.2.8 | Type a frame number and press Go | Jumps straight there; the field has no underline under it |
| [ ] 8.2.9 | Type `0`, a number past the end, or letters | Nothing moves and the current number comes back |
| [ ] 8.2.10 | Step with Next/Prev | The number follows immediately, not after the frame decodes |
| [ ] 8.2.11 | Double-tap the image | Zooms about 2× on the tap; double-tap again resets to fit |
| [ ] 8.2.12 | Horizontal fling while fit-to-screen | Steps one frame (same as Next/Prev) |
| [ ] 8.2.13 | Open a session with no `.dat` frames | Snackbar `no_batch_data` with **Why?** → FAQ |
| [ ] 8.2.14 | Force a frame OOM (huge session, low memory) | Snackbar with **Why?** → viewer-oom FAQ |

### 8.2a Summary animation `[single]`

Single-setting analyses only (not parameter sweeps — those open from the lattice
onto a combination, with no summary slot and no Animations share target).

| # | Action | Expected |
|---|---|---|
| [ ] 8.2a.1 | Open a result | It lands on the summary, which builds and then loops. The **counter** reads "Summary GIF"; the **edge title** carries "<field> · Summary" |
| [ ] 8.2a.1b | Open a result with a sub-frame ROI (or a small accepted patch) | The summary GIF is framed on that coloured region — same rest-fit contain scale as the live viewer, not a letterboxed full photo |
| [ ] 8.2a.1a | Watch it build | Determinate progress with a status ("Reading frames…", then "Rendering <field>…") and a **Cancel** button |
| [ ] 8.2a.2 | Watch a short (≤33 frame) analysis | Each frame is visible for about 300 ms |
| [ ] 8.2a.3 | Watch a 150-frame analysis | Every frame is there and the loop still finishes inside 10 s |
| [ ] 8.2a.4 | Compare early and late frames of a growing test | Colour rises through the sequence — one scale throughout, no per-frame renormalising |
| [ ] 8.2a.5 | Read the scale labels beside it | The widest bounds in the whole sequence, not the current frame's |
| [ ] 8.2a.5a | Open ⓘ on the summary | Max and min of that GIF scale, no mean, and no histogram |
| [ ] 8.2a.6 | Switch field | The animation rebuilds in that field; switching back replays from cache |
| [ ] 8.2a.7 | Set a custom scale for one field | Only that field's animation rebuilds |
| [ ] 8.2a.8 | Tap **Next** on the summary, then **Prev** on frame 1 | Leaves to frame 1 and comes back |
| [ ] 8.2a.9 | Step into the frames while it is still building | The viewer stays responsive throughout |
| [ ] 8.2a.11 | Run on Android 8 | The first frame with a note that animation needs Android 9; sharing still works |

### 8.3 Tap to probe

No Inspect / X,Y / Max-Min tools. A short tap on the heatmap is the reading;
drag and pinch keep pan and zoom. Chrome hides only after the idle timer;
a centre double-tap brings the bars back when they have faded.

| # | Action | Expected |
|---|---|---|
| [ ] 8.3.1 | Short-tap the heatmap (including the centre) | Plain-text readout shows the nearest correlated point: field value with units and `(x, y)` |
| [ ] 8.3.1a | Wait for chrome to fade, then centre double-tap | Bars come back; no zoom from that double-tap |
| [ ] 8.3.1b | Centre double-tap while chrome is already visible | Zooms about 2× (same as off-centre double-tap) |
| [ ] 8.3.1c | Swipe down while fit-to-screen | Chrome shows if it was hidden; swipe does not hide chrome |
| [ ] 8.3.2 | Drag past the touch slop | The image pans (when zoomed) or a horizontal fling steps frames (when fit); no probe is placed mid-drag |
| [ ] 8.3.3 | Tap outside the correlated area | Readout says "No data" rather than a wrong number |
| [ ] 8.3.4 | Pinch while a probe is up | Zoom works; the crosshair stays glued to the image point |
| [ ] 8.3.5 | Switch field or frame with a probe up | The value updates for the same image location (or "No data") |
| [ ] 8.3.6 | Tap the readout chip | The probe dismisses |
| [ ] 8.3.7 | Open ⓘ | Stats list max and min with coordinates, plus mean and a histogram — no Max/Min toggle |
| [ ] 8.3.8 | Rotate with a probe up | Frame, field and probe survive |

### 8.4 Details (ⓘ peek sheet)

| # | Action | Expected |
|---|---|---|
| [ ] 8.4.1 | Tap the info button | Peek sheet titled **Details** with the specimen name under it, then max / min (coordinates) / mean, a histogram of this field's accepted values, then subset, step, strain window, strain method, ROI and image size |
| [ ] 8.4.1a | Open it on a run that stopped early | Two extra rows: **Stopped early** and **Frames solved (n of N)** — the provenance survives a restart |
| [ ] 8.4.1b | Open it on the summary GIF | Max and min of the GIF colour-bar ends, no mean, and no histogram |
| [ ] 8.4.2 | Compare against what you entered in the wizard | They match |
| [ ] 8.4.3 | Read the **strain window** row | "9-point window · VSG 41 px": the window in points and the VSG it gave, (window − 1) × step + 1. A session from before the window was counted in points shows "VSG 15 px" alone |
| [ ] 8.4.4 | Scrub to another combination and reopen | The values follow the new frame, not the run's first |
| [ ] 8.4.5 | Open it on a sweep | A line-cut plot with colour-matched Exx / Eyy / Exy and the cut axis named |
| [ ] 8.4.6 | Open it on a single-setting run | No line-cut section |
| [ ] 8.4.7 | Look for **Go to Home** in the sheet | It is not there any more — Home is a chrome icon in the top bar (§8.6.1) |
| [ ] 8.4.8 | Tap a histogram bar | Caption under the plot names that bin's count and value range, in px or millistrain |
| [ ] 8.4.9 | Compare the histogram with the colour bar | The histogram includes the outliers the colour bar has clamped; its ends are the true min and max |

### 8.5 Share and export

| # | Action | Expected |
|---|---|---|
| [ ] 8.5.1 | Tap Share | Sheet with six targets, captioned positionally — "frame N of M shown · photos share the current frame". It no longer names the frame; the frame's own name is on the **Single Field** row's sub-line |
| [ ] 8.5.2 | **Single Field** | One annotated PNG of the field and frame on screen |
| [ ] 8.5.3 | **All fields** | Five PNGs for the current frame, zipped for hand-off. The row's sub-line and each PNG's stamp name the **source image**; the file names still come from the analysis name |
| [ ] 8.5.3a | **Animations** `[single]` | Five GIFs, one per field, zipped; each loops when opened in a gallery app. Row is absent on a parameter sweep |
| [ ] 8.5.3b | Same, immediately on entering the viewer `[single]` | Fields not built yet are built under the progress dialog — never silently missing |
| [ ] 8.5.4 | **PDF report** | Every frame's pages plus a telemetry page. Its sheet row reads "fields, stats, telemetry · All N frames", with the same "·" as the other rows |
| [ ] 8.5.5 | **CSV data** | `#` preamble (version, reference, strain method, ROI, per-frame U/V/Exx/Eyy/Exy max/min/mean), blank line, then point header `image,x_px,y_px,u_px,v_px,exx,eyy,exy,znssd,shift_u_px,shift_v_px,shift_rot_deg` — the three motion columns are written for every session, empty when a frame admits no fit; sweeps insert `subset_px,step_px,strain_window,vsg_px` after `image` (`strain_window` in points, empty for a sweep stored before points; `vsg_px` the VSG) |
| [ ] 8.5.6 | **Everything (.zip)** | Raw photos, per-frame results for all five fields, the CSV and the PDF; single-setting also includes the five field GIFs under `animations/` |
| [ ] 8.5.7 | Check the filename of anything you export | It carries the specimen / analysis name, not a generic `export.zip` |
| [ ] 8.5.8 | Export a very large analysis | Determinate progress dialog, then either a file or a message naming the failure — never a crash, and never an OOM from rendering the report |
| [ ] 8.5.8a | Dismiss that dialog with Back, or by tapping outside | The export keeps running behind a **transfer banner** at the top of the viewer, with its own progress, Cancel and ‹ › paging — the same strip Settings uses (§4.0) |
| [ ] 8.5.9 | Check an exported PNG | Heatmap baked in, min/max annotated, composited to a **1280 px long edge** — not the reference's full sensor resolution |

### 8.5a Send to — the export handoff

Viewer exports and the two Settings data exports end at the same in-app **Send
to** bottom sheet rather than being thrown straight at the system chooser. Two
rows, so "keep this file" and "send this file somewhere" are separate decisions.

**When the sheet appears depends on the target.** The single **Single Field**
photo is generated first and then offered. The five slow targets — All fields,
Animations, PDF, CSV, Everything — ask **first**: the sheet comes up before any
work, and choosing **Save to Files** opens SAF straight away so the export is
written directly into the document you picked. Two exports skip the sheet
entirely by design: Settings' **Download** (§4.14e, destination already chosen)
and the lattice's **Save graph** (§7.3.4, straight to the system chooser).

| # | Action | Expected |
|---|---|---|
| [ ] 8.5a.1 | Trigger any viewer export | A **Send to** sheet with a folder-icon **Save to Files** row ("Save a copy to this device") and a **Share** row ("Send to another app"). There is no filename caption on it |
| [ ] 8.5a.2 | Tap **Save to Files** for **Single Field** | A SAF save dialog opens via the transparent `SaveExportActivity`; the already-built file lands where you choose |
| [ ] 8.5a.2a | Tap **Save to Files** for a slow target (PDF, Everything, …) | SAF opens **before** generation, and the export is written straight into that document — nothing is staged and re-offered |
| [ ] 8.5a.3 | Cancel that SAF dialog | You come back to the app cleanly, with nothing half-written |
| [ ] 8.5a.4 | Tap **Share** | The normal system chooser opens with the file attached |
| [ ] 8.5a.5 | Dismiss the sheet without choosing | Nothing is written and nothing is sent; no error |

### 8.6 Leaving

| # | Action | Expected |
|---|---|---|
| [ ] 8.6.1 | Tap the home icon in the top chrome | Home, with the back stack cleared |
| [ ] 8.6.2 | Press Back on a single-setting result | Wherever you came from |
| [ ] 8.6.3 | Press Back on a sweep combination | The Lattice |
| [ ] 8.6.4 | Leave a single-setting result and look at its Home row | The headline is unchanged by viewing — still the first frame's convergence (§3.4), whatever frame or field was on screen |
| [ ] 8.6.5 | Leave a sweep and look at its Home row | The sweep caption is kept, not overwritten |
| [ ] 8.6.6 | Look for rename or delete in the viewer | Neither exists — both live on Home |

---

## 9. Session limit

The quota gate. Not a paywall — there is no billing anywhere in the app; the
route past it is an email to support.

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

**Where the cap (`M`) comes from.** `LicenseEntitlements.analysisCap()` reads
`AppRemoteConfig`, which is populated from the backend's `GET /v1/config` (see
[CLOUD_ARCHITECTURE_GCP.md §20](../backend/CLOUD_ARCHITECTURE_GCP.md#20-licensing--entitlements)):

- **Demo** (the default for every account until activated): capped at 25
  saved analyses, this screen included. Demo analyses are still **recorded** —
  each finished analysis uploads silently (`CloudSync.uploadsEnabled` ignores
  the Save-to-cloud toggle, which demo is not shown) — but demo has no
  backup/restore *feature*: Home shows no sync badge or row progress (3.4,
  3.7, 3.8 do not apply), Settings has no **Cloud backup**, **Analyses data
  management**, **Free up space** or auto-free controls (4.5–4.18g do not
  apply), and a stored copy is never pulled back. The upload is what the cap
  counts.
- **Professional — individual key**: capped at the backend's licensed
  `maxSessions` (999 by default, never below demo's 25) once `/v1/config` has
  reported it, and uncapped before; Semper staff mint and hand over the key.
- **Professional — institution seat**: identical entitlement to an
  individual key — an institution seat and an individual key resolve to
  the exact same `mode=licensed` on device. What differs is only how the
  seat is administered: institution IT self-service via backend routes (see
  §20.4 of the doc above), not Semper staff, and not through this app.

**Status at this revision.** There is no screen to type a key into, and there
is not meant to be one. A licence is minted against the customer's email
address and attaches at their next sign-in — an individual licence directly, an
institution seat through the roster — so nothing is read off a phone, dictated,
or typed. `POST /v1/licenses/activate` and `IndicApi.activateLicense()` remain
for support recovery and have no caller in `app/src/`. What the app shows of a
licence is its prefix, in Settings → Account (4.2a); the key itself never
reaches the device. This screen's behaviour for a Professional account is
unaffected either way: once `GET /v1/config` reports `mode=licensed`, the cap
is the licensed `maxSessions`, so 9.1 triggers only at that ceiling.

**9.4 No seat right now (floating institution licence).** A separate gate from
this screen, and not a limit: the account is on the roster but every seat is in
use. It appears when starting new work — the Home **+** button, before the
Import/Record menu opens, and again at Compute for a run started from inside an
analysis. Saved analyses stay open throughout, and a run already in flight is
never interrupted.

The screen is one button that asks for a seat again. Unlike 9.1 it needs no
email to support: seats free themselves as colleagues finish.

**9.3 License expiry notice (Home).** Separate from this screen, and not a
gate. A licensed account whose key expires within 14 days — or which is past
expiry but still inside its grace window — shows a small chip under the Home
title: "License expires in N days", or "License expired — still working, email
support to renew". Nothing is withdrawn while it shows; during grace the
account keeps cloud backup, share and the uncapped analysis count, and the only
thing that ever changes entitlement is the backend flipping `mode` to `demo`
once grace ends (at which point 9.1 applies exactly as it does for any Demo
account).

The chip is suppressed when the cached config is more than a week old. A
renewal may have landed while the device was offline, and warning from a stale
cache would be a false alarm the user cannot act on. A perpetual license never
shows it.

---

## 10. Background work

Uploads, restores, **bundle downloads** and backup deletes run in WorkManager and
survive leaving the screen. Their file chains, failure outputs and log signals
are `B1`–`B4` in [../WORKFLOWS.md](../WORKFLOWS.md#b-app--background-and-data-workflows):

| Worker | Job |
|---|---|
| `DicUploadWorker` | Back up a session to the cloud |
| `DicRestoreWorker` | Pull a session back into the app |
| `DicBundleDownloadWorker` | Write a `Session.zip` into a SAF document the user picked first (§4.14c) |
| `BackupDeleteWorker` | Run a queued `SessionDeletes` job after the undo window: one analysis at a time, progress per row, and the ids still in the cloud |

They are **no longer silent about failure**: a terminal upload failure surfaces on
Home (message pill + a "why + retry" dialog on the badge), and a terminal restore
failure surfaces on **both** Home and Settings, each carrying a human reason.
Success is quiet by design — the badge/list simply updates.

Progress is visible in three places: the transfer's foreground notification while
you are elsewhere in the system; a live badge and progress bar on the Home row
whenever Home is on screen, for downloads as well as uploads; and the **transfer
banner** inside Settings (§4.0) and the result viewer (§8.5.8a), which is what
carries exports and anything started from those screens.

| # | Action | Expected |
|---|---|---|
| [ ] 10.1 | Finish an analysis with cloud backup on | Upload is queued; the Home badge moves Pending → Synced, with live progress on the row |
| [ ] 10.2 | Queue an upload with no network | It retries and eventually succeeds once you reconnect |
| [ ] 10.2a | Queue an upload for an analysis older than 15 min whose `.dat` files were deleted from its session folder | After about 15 minutes of retries the badge turns Failed ("not backed up") and its dialog says the results are no longer on the device — it does not sit on Pending forever |
| [ ] 10.3 | Restore from Settings and leave the screen | It completes anyway; the analysis appears on Home / in the list |
| [ ] 10.3a | Cause a terminal upload or restore failure | The reason is surfaced on return (Home message pill / badge dialog, or the same pill in Settings) — not swallowed |
| [ ] 10.3b | Start a restore, then sit on Home while it runs | That row shows a progress bar and badge throughout — you are not left guessing |
| [ ] 10.3c | Start a Download from Settings and leave Settings | It finishes anyway and reports the outcome; it is a worker, not an Activity-scoped job |
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
| **Google SSO** button and its divider | Hidden unless `default_web_client_id` is in the APK (from `google-services.json`). Release resource shrinking must not strip it — see [AUTH_SETUP.md](../backend/AUTH_SETUP.md). Separately, the build's signing SHA-1 must be registered in Firebase or the button shows but sign-in fails |
| Settings → **Pending access requests** → Admin | Hidden unless the backend reports role `admin` |
| Dev sign-in bypass (skips auth, disables cloud) | Debug build **and** the bypass flag **and** an emulator |
| Splash → Home without auth | Debug build with no `INDIC_API_BASE_URL`. A *release* build with no base URL cannot get past sign-in at all |
| `DebugViewerSeedActivity` | A 13th activity, declared only in `app/src/debug/AndroidManifest.xml` and exported so `adb` can drop straight into the result viewer for emulator screenshots. The "12 activities" count above is the **main** manifest |

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
| `home_empty_restore` string | `strings.xml` | Orphaned when the empty state became **Start analysis** (§3.19) |
| `download_analysis_save_title` / `_save_body` | `strings.xml` | Orphaned when Download started picking its SAF destination *before* enqueue — there is no confirm dialog left to title |
| `delete_device_restore_action` string | `strings.xml` | Unused |
| `viewer_details_home` ("Go to Home") | `strings.xml` | Orphaned when Home moved out of the ⓘ peek sheet into the viewer's top chrome (§8.4.7) |
| `summary_counter_fmt` plurals | `strings.xml` | Orphaned when the summary counter became the flat "Summary GIF" (§8.2a.1) |

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
- **There is still no dedicated restore screen** — restoring is done from a Home
  row or from Settings → Analyses data management.
- **The advanced-parameters and sweep-settings headers look collapsible** (icon,
  title) but have no click listener. Only the lattice-samples panel really
  collapses.
- **The planned lattice on step 3 is deliberately inert** — node taps are
  disabled there, unlike the result lattice.
- **`DicUploadWorker` starts the Session limit screen from the background** on a
  quota rejection, which Android 10+ blocks — that path likely never fires.
- **`READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` are requested** when the in-sheet
  Images tab needs them (Home also asks for video). The Files tab opens SAF
  instead, so Drive and DNG work without that grant (§3a).
- **The only notification channel is for transfers** — `TransferNotifications`
  creates one channel (`semper_transfers`) carrying three notifications: upload,
  restore and download. There are no *completion* notifications; terminal failures
  surface in-app instead (§10).
- **No open-source licenses screen.** Privacy Policy and Terms of Service are
  linked from the About dialog and open the hosted pages in a browser; there is
  no in-app licence attribution list.
- **Crash reporting is opt-in and off until accepted.** Crashlytics collection
  is disabled in the manifest and enabled only after the first-run prompt or the
  Settings toggle, so a user who never answers sends nothing.
- **The same consent flag also gates product analytics.** `SemperAnalytics` fires
  Firebase Analytics events for analysis started / completed / failed, the two
  data exports and Send feedback — all buckets and enums, never images, results,
  session ids or specimen names — and every one of them is dropped unless
  `DicSettings.diagnosticsEnabled` is on. The consent copy names both halves —
  **Send crash reports and usage data** — matching
  [the privacy policy](../legal/PRIVACY_POLICY.md) §2.4.
- **An interrupted solve cannot be resumed** — it is a foreground coroutine, so
  process death loses the run.
- **`AnalysisWizardSmokeTest`** opens the analysis wizard and asserts chrome
  (`btnNext` / instruction). Full Home → analyze → results E2E is not automated.
