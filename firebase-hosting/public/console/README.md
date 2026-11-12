# Consoles

Static pages on the existing auth Hosting site. No build step, no framework, no
`package.json` — the site is served as files, and a toolchain for four pages
would cost more than it saves.

Each page is an HTML document plus one ES module beside it — `router.js`,
`account/account.js`, `institution/institution.js`, `operator/operator.js` —
loaded with `<script type="module" src="…">`. **Not inline.** The console
`script-src` is `'self'` with no `'unsafe-inline'` (see below), so an inline
script on these pages does not run at all: the page renders and every button is
dead. Shared by all four:
- `auth.js` — sign-in, the second factor, `stepUp`, and the Firebase SDK. It
  is the only module that loads Firebase (and waits, at the top level, for
  the project config), so it holds nothing else and re-exports nothing.
- `api.js` — `api()`, `apiBlob()`, `saveBlob()` and `ApiError`: every call
  to the backend, with one step-up retry on `reauth_required`.
- `dialogs.js` — the in-page cards (`askInPage`, `confirmInPage`,
  `tellInPage`, `confirmByTyping`, the authenticator-code and enrolment
  cards) and `ERR_CANCELLED`. DOM only.
- `status.js` — the status line (`setStatus`, `whileWaiting`) and the load
  marks (`markFirstData`). DOM only.
- `config.js` — the API base URL, filled in at deploy.
- `util.js` — the DOM-free helpers (`esc`, `when`, `day`, and the roster cells
  both seat tables render), so `node --test` can run them.

Words (`terminology.test.mjs` holds them): backup, not sync; phone, not device,
to an account holder (operator copy may say device); Demo as the plan ("on
Demo"); "after-expiry grace" and the phone's "offline allowance", never a bare
"grace period"; a seat you *have*, that IT *pauses* and *resumes* (the stored
status is still `disabled`) and *removes* (a seat is removed, a licence is
revoked); "Give up my seat"; "your institution's IT".

Times: every instant is `util.js` `when` (the browser's zone, named:
"7 Oct 2026, 04:53 pm IST"); a licence end, a test account's end and a
deletion are `day`, the UTC calendar day they are stored as, marked "(UTC)".

A page imports each name from the module that owns it. `util.test.mjs` fails
if `util.js`, `dialogs.js`, `status.js` or `messages.js` comes to load
Firebase, or if `auth.js` re-exports anything; `check_console.py` fails a
page that does not `modulepreload` every module it runs.

Shared beside them:
- `roster.js` — one institution roster: the table, its actions, adding a
  member, and what each refusal says. The rows read are drawn 100 at a time
  (Show more), narrowed by a filter box (address, status or device) and by
  status chips with their counts (Current, the default, leaves removed
  members out; Active, Paused, Invited, Removed), and sorted by Member,
  Status, Seat (lease end) or Phone (`aria-sort` on the heading), with
  "Showing n of m". The institution page drives it on the IT
  routes (`/v1/institutions/licenses/{id}`), the operator desk on the staff
  routes (`/v1/admin/licenses/{id}`); the backend serves both from
  `routers/roster.py`. It reads a paged roster to the end.
- `list-tools.js` — what the other tables share with the roster's toolbar:
  `wireSort` (each `button.sort[data-sort]` in a heading sorts its column,
  again the other way, `aria-sort` on its `<th>`), `sortList` (a tie keeps the
  order read), `byText` (numbers in text as numbers, case aside), `byNumber`
  (a missing number last), `matchesText`, `drawChips` / `onChip` (filter
  chips with counts, `aria-pressed`) and `countLine` ("Showing n of m",
  "n of m match", "loaded" while a page is unread), and `floatMenus`, which
  makes a row's More menu a list floating over the rows below (fixed to the
  viewport, placed by `menuPlace` under or above its button, one open at a
  time, closed by a click elsewhere, Escape, a scroll or a resize). DOM only
  through the elements handed in. The desk's licence table and test accounts, the
  institution page's phones and the account page's analyses use it.
- `roster-tools.js` — a roster's bulk tools: the seat-count tiles from
  `GET …/summary`, read after every roster load; Export roster CSV,
  `GET …/seats/export`; and the bulk import, `POST …/seats/import`, with a
  row-count preview and one result line per row. A file the CSV reader
  cannot parse names its line (the backend's `roster_import_malformed: line <n>`),
  and a file over 512,000 characters (413 `roster_import_too_large`) is said
  as too large ([ADMIN_API.md](../../../docs/backend/ADMIN_API.md)).
- `audit-log.js` — the licence's audit log, `GET …/audit`, read on demand
  with a date range, a page of 50 at a time, and exported as CSV or JSON, up
  to 5,000 events each. From / To are local days, sent as instants
  (`localDayRange`), because the rows are shown in local time.
- `messages.js` — `explain(error, sentences, fallback)`: a page's own sentence
  for a refusal code, else a common one (`COMMON`), else the page's fallback
  ("Could not delete: …") around why it failed: `REASONS` (platform refusals
  such as `firestore_unreachable`, shared route ones such as `user_not_found`,
  and `network` for a fetch with no answer), `GATEWAY` by status for a
  non-JSON `http_<status>`, or "the server refused it (ref: <code>)" for a
  code nobody wrote words for. A code is never the whole message. Firebase
  codes go through `util.js` `authErrorText` the same way. And `ACCOUNT`, the
  account page's words, the claim card's refusal sentences among them. Its
  keys are camelCase: a snake_case key reads as a backend code to
  `check_console.py`, so the page maps codes onto them where it matches.

A claim code is redeemed on the web only: the app takes no licence key and
shows no purchase. `account/claim.js` offers its card while `/v1/me` says no
licence is attached, tidies what is typed (upper case; letters, digits and
dashes), sends it once in the body of `POST /v1/me/claim`, never a URL, and
re-reads `/v1/me` on success, then points at the app's Refresh licence. The
box is emptied once the code is used, and nothing else on the page shows it.

Like `roster.js`, the two tool modules take `base()`, the open licence's path
on the page's own tier, and serve both rosters: the institution page on IT's
routes, the operator desk's roster card on the staff routes. Nothing in them
branches on the tier; `operator-roster-tools.test.mjs` opens both pages on
the same answers and asserts they draw the same HTML and send the same
requests. On the staff tier the reads (summary, export, audit) are `admin`
and the import is `admin-stepup`, so a sign-in older than
`ADMIN_WEB_REAUTH_SECONDS` is sent to Google by `api()` as for every other
change on the desk, and the import is repeated by hand after the return.

The operator desk is split by card: `operator.js` (sign-in and the row-button
routing), `state.js` (the loaded licences, shared as one object), `licences.js`
(the table), `mint.js`, `edit.js` (Edit and To institution), `phones.js` (a
licence's phones: Deactivate, Add phone), `upgrade.js` (Issue upgrade, Apply
now), `verify.js`, `lifecycle.js` (revoke, delete, restore), `people.js`,
`roster-card.js` and `test-accounts.js` (the Test accounts card).

A licence's phones (ADR-033) are on all three pages, each on its own tier's
routes: the account page's `account/phones.js` (the holder's own phones per
app, `GET`/`DELETE /v1/me/devices?app=`), the institution page's
`institution/phones.js` (the pool's phones of members on the licence's
domain, `…/institutions/licenses/{id}/devices`) and the operator desk's
`operator/phones.js` (any licence's, `…/admin/licenses/{id}/devices`, plus
Add phone). A licence with no `app`, from before the split, still runs its
device lock: none of the three is drawn for it, and Move / New phone stay.
`licences.js` imports none of the cards; the seat check hears about a re-read
licence through `onLicenceRead`.

### Refusals

`api()` and `apiBlob()` throw an `ApiError` with `status`, `code` (the
backend's code, `backend/app/errors.py`) and `rest` (what follows a
`code: …` suffix). Pages branch on `e.code`, never on the message text, which
used to miss every suffixed code. A gateway refusal that is not JSON still
yields an `ApiError`, coded `http_<status>`.
`scripts/check_console.py` fails a page that matches a code the backend does
not declare.

| Path | Who | What it can do |
|---|---|---|
| `/login` (`/console/`) | Anyone with an account | Signs in and forwards to whichever dashboard below is theirs. |
| `/account` (`/console/account`) | Anyone with an account | See the licence field by field (mode, kind and key, seats, end, grace end, a shared seat's lease, all from `/v1/me`), what a phone does offline, and the saved analyses; per app (semper, semper Classroom, from `/v1/me`'s `licences`), that app's licence, "Licensed on n of m phones", the holder's own phones with **Deactivate** (2FA), and a new licence waiting to be activated in the app; give a floating seat back, move a licence from before the split to a new phone (Move, shown only on one), download an analysis, and, while no licence is attached, redeem a one-time claim code (`account/claim.js`, `POST /v1/me/claim`). The last three need **2FA**. Deleting the account is the app's (device-signed `DELETE /v1/me`); the page says where, and that it removes the sign-in for both apps. |
| `/console/institution` | IT staff named in a licence's `adminEmails` | Add and remove roster members (one at a time or up to 500 from a CSV), withdraw an unclaimed invitation, see who has a seat, pause a member's seat, clear a device lock; on a licence that names its app, the pool's count and each member's activated phone with **Deactivate** (no Activate or Add); seat counts, roster CSV export, and the licence's audit log with CSV / JSON export. |
| `/console/operator` | Semper staff (`ADMIN_EMAILS` / `role=admin`) **with 2FA** | Issue individual and institution licences per app (semper, semper Classroom, or both) with a phone count, edit or upgrade one (**Issue upgrade** and **Apply now** on a licence that names its app), list a licence's phones with **Deactivate** and **Add phone** (past the count with override), revoke or delete a key (30-day restore), drive any institution roster with IT's tools (seat counts, CSV import and export, the audit log with CSV / JSON export), approve accounts, and create, list, extend, re-password and end the test accounts for QC and CI. |

## The front door

`/login` is the one address to hand out. It signs the caller in, reads
`GET /v1/me` and `GET /v1/institutions/licenses`, and forwards: `role=admin`
to the operator console, an address named on a live institution licence to the
roster (deep-linked when there is exactly one), and everyone else to their own
account. Somebody who is both gets a switcher rather than a guess, and
`/login?stay=1` always shows it.

Every page also carries a dashboard switch in its header (`switcher.js`): one
tab per dashboard the account can open, shown whenever there is more than one.
Staff get Operator and Your account, an IT contact Institution seats (deep-linked
when there is exactly one licence) and Your account, and someone who is both
gets all three. `roles.js` decides it from the same two answers the front door
routes on; a page passes the one it already read (or the read it already
started), so the switch adds one request, sent alongside the page's own. A
failed read hides only the tab it would have shown.

The two restricted pages make the same check themselves, because a link can
land anyone on them: the operator desk asks `/v1/me` and shows nothing of the
desk to an account without `role=admin`, and the institution page asks
`/v1/institutions/licenses` and shows nothing of the seat manager to an address
no licence names. Each says so in a card with a link to the account page. The
backend refuses the calls anyway; the gate only spares people a form that every
submit would refuse. Neither waits for its check before asking for its data:
the desk sends its licence list and approvals with `/v1/me`, and the roster of
a `?license=` link goes out with the licence list. The answers are shown only
once the check passes; for anyone else the backend refuses them, and the
refusals are dropped unshown.

Nothing is inferred from the email domain and nothing is remembered in the
browser, so an account that changes hands routes correctly the first time.

Both pretty addresses are Hosting **rewrites** into `/console/`, which has two
consequences worth knowing before editing them. A header is matched against the
request path and knows nothing about the rewrite, so the relaxed console CSP is
restated for `{/login,/account}` in `firebase.json` and the two values must stay
identical. And a relative path in the page would resolve against the site root
at those addresses, so the two pages served there (`index.html` and
`account/index.html`) carry a `<base href>`; the operator and institution pages
are only ever opened at their own address and have none.

## Why the operator console needs a second factor

Every state-changing `/v1/admin/*` route wants proof beyond an ID token. On the
phone that proof is `verified_device` — an ECDSA signature over the request,
from a device keypair registered in Firestore. It exists so that a stolen
session cookie or ID token cannot mint a licence, approve an account or revoke
a key. A browser cannot produce one, which is why this page used to be
read-only.

The browser path is the deliberate substitute, not its removal. A console
caller is accepted only when the ID token records a **completed second factor**
and the sign-in behind it is **recent** (`ADMIN_WEB_REAUTH_SECONDS`, default 15
minutes). Both halves matter: the factor check refuses a stolen password-only
token, and the freshness check stops a token that leaks later from carrying
authority for its full hour.

It is weaker than device binding and worth saying so plainly: someone who
phishes a live MFA session inside the window can act, which the attestation
path made impossible. `ADMIN_WEB_MFA_ENABLED=0` withdraws the browser path
entirely and restores attestation-only admin.

Enrolment is TOTP **only** — the project enrols no SMS factor, so `auth.js`
challenges TOTP and reports anything else rather than half-handling a factor it
cannot complete. Every dashboard — operator, institution, and account — enrols
and challenges that factor before it loads. Consequential account actions
(moving a licence, downloading an analysis, redeeming a claim code) and
institution roster changes also require a fresh second factor on the API side.

Enrolment shows a QR code drawn **in the page** (`qr.js`, over a vendored
copy of qrcode-generator under `vendor/`) from the `otpauth://` URI the SDK
builds, with the key beneath it for anyone who cannot scan. It is never
fetched: every QR *service* is somebody else's server and the payload is the
TOTP secret itself, so a picture from one would hand away the factor that
protects licence issuance. The CSP's `img-src` names no such origin and must
not start to. Every console uses the same card (`enrolInPage` in `dialogs.js`);
the account page hosts it in its own section.

Every later sign-in and step-up asks for the code the same way: a card under
the status line with a code box, Confirm and Cancel (`codeCard`), never
`window.prompt`. Some browsers show no prompt at all (the Claude desktop app's
browser pane answers "prompt() is not supported"), which left the code the one
step of sign-in nobody could complete there. A wrong code keeps the card: it
says the code was not accepted and offers **Try again**, which takes the next
code against the same challenge, with no second trip to Google. Cancel is
`ERR_CANCELLED`, or after a wrong code that code's error, as before. A challenge
that timed out ends the card, and the status line says to sign in again. SDK
12.4.0 reports a lapsed challenge as `auth/invalid-multi-factor-session`,
`auth/missing-multi-factor-session` or `auth/code-expired`, not
`auth/totp-challenge-timeout`; `CHALLENGE_EXPIRED` in `auth.js` holds all four.

A step-up's freshness is measured on Google's clock: `authAge` forces a token
refresh and takes issued-at minus `auth_time`, both Google's. It used
`Date.now()`, so a computer whose clock was a minute off disagreed with the
backend's 90 s / 120 s windows: a step-up whose code had just been accepted was
skipped as fresh and then refused as stale, or every change went back to
Google.

A remembered sign-in that is over — Firebase cannot refresh it
(`auth/user-token-expired`, `auth/invalid-user-token`), or the backend refuses
the token (401 `invalid_token`) — signs the page out (`endSession`), so Sign in
is on screen, the dashboard is hidden, and the status line says "Your sign-in
has expired. Sign in again." (COMMON's `invalid_token` on a page's action).
`endSession` writes that line itself, so a read whose own message lands
somewhere now hidden (the Test accounts list) still says it, and the
institution page stops rather than drawing the roster behind Sign in
(AE2-105). Until the next sign-in `sessionOver()` stays true, and every
page (front door, account, institution, operator desk) checks it after each
read it sends alongside another, on the answer and on the failure alike: an
answer that arrives after another read ended the session, a 200, a 503 or no
network, neither brings the dashboard back nor writes over that line. It used to read "an unexpected sign-in error (ref: …)" beside a
Sign out button.

Nothing else uses `window.prompt` either. The operator desk's typed-key
confirmations (revoke, delete, shortening a licence's term) and the password
before a revoke use `askInPage` in `dialogs.js`: a card with a heading, the
message, a text or password box, the confirm button and Cancel, placed under
the status line and scrolled into view. The shortening check runs inside the
modal Edit dialog, so its card goes inside the dialog (after `#editHint`). The
card's buttons are `type="button"` and Enter is handled by the card, so it
never submits the Edit form. In the tests, `prompts` answers these cards, and
the fake `window.prompt` fails the test if anything calls it.

`window.confirm` and `window.alert` are gone too, for the same reason. Every
yes / no question is a `confirmInPage` card (the same card with no box,
resolving true or false): the desk's who-is-affected step before a revoke or
delete and the one after the return from Google, New device (on a licence and
on a roster row), the roster's Remove and Withdraw (on the desk and the
institution page), the desk's Release phone, and the account page's
give-a-seat-back and move-a-device. Each roster and release confirmation names
the address it acts on, and each roster row's buttons carry it in their
accessible name ("Remove ana@uni.edu"). The box of an ask card is named by its
`label` ("Password", "Licence key", "Days"), or its title. Every card is a
`role="alertdialog"` named by its heading and described by its message;
Escape answers it as Cancel (a tell card: Close), and focus goes back to the
control that opened it (`restoreFocus`; a closed More menu's summary for an
item in it). When a change redraws the roster or the licence table, focus is
put back on the same row (`refocus`), or the roster's Add box when the row is
gone. Device history is a `tellInPage`
card with one Close button; the card keeps single line breaks, one move a
line. In the tests, `confirms` answers the yes / no cards and `alerts` records
each tell card, and the fake `window.confirm` / `window.alert` fail the test
if anything calls them.

## Confirming destructive actions

Revoking a licence drops a whole institution to Demo, so the page asks more
than once: password (or Google) re-auth plus authenticator code, a plain
confirmation naming who is affected, then typing the **key prefix**. The API
also refuses a revoke on a session older than
`ADMIN_WEB_REVOKE_REAUTH_SECONDS`. Nothing is deleted either way — revoking
withdraws entitlement and leaves every saved analysis in place.

**Delete** asks the same (it revokes first) and is offered on revoked rows
too, but not on a system Demo key. The licence moves to **Recently deleted**,
a card loaded only when opened (`GET /v1/admin/deleted-licenses`), which
shows the days left and **Restore** until `purgeAt`, 50 at a time with
**Load more** past them.

## Deploying

Use [`scripts/deploy-console.sh`](../../../scripts/deploy-console.sh) so the
placeholders are always restored, even if deploy fails mid-way. Never commit a
live hostname.

```bash
# From the repo root. The API Gateway host the app talks to.
API_BASE_URL="https://your-gateway-host" ./scripts/deploy-console.sh
```

The script refuses a branch other than `main`, a dirty tree and an
`API_BASE_URL` that is not an `https://` origin (a trailing `/` is stripped), runs
`scripts/check_console.py` and `scripts/render_legal_pages.py --check`, copies
`config.js` / `firebase.json` to a temporary directory, substitutes
`__API_BASE_URL__` and `__API_ORIGIN__`, runs `firebase deploy --only hosting`,
then a `trap` puts the templates back. `LEGAL_PAGES_FROM=<commit>` deploys the
legal pages from that commit ([DEPLOY_CHECKLIST_2026-10.md](../../../docs/ops/DEPLOY_CHECKLIST_2026-10.md) §4),
`DRY_RUN=1` stops before the deploy, and `DEPLOY_CONSOLE_ALLOW_UNCLEAN=1` allows
another branch or a dirty tree. Last it runs
[`scripts/check_live_console.py`](../../../scripts/check_live_console.py), which
reads the live `/console/config.js` and the CSP on `/login`, `/account` and
`/console/`, and fails the run if a placeholder is left or the gateway is not the
one given. Every Hosting deploy replaces the whole site, so a bare `firebase
deploy` (for one `assetlinks.json` line, say) ships the placeholders: the pages
load and every API call fails, as on 2026-10-07. Run the check on its own after
any Hosting deploy: `python scripts/check_live_console.py --api <gateway>`.

### Sign-in flow: redirect, own host as `authDomain`

Google sign-in and every Google re-authentication use
`signInWithRedirect` / `reauthenticateWithRedirect`, never a popup. Two of
the places `auth.js` re-authenticates have no user gesture to open a popup
with — straight after the first sign-in, to enrol TOTP, and inside an API
retry after `reauth_required` — and browsers block those silently; the first
staff sign-in on the live domain found that out.

The redirect result only survives the round-trip when the auth handler is on
the page's own origin (browsers partition third-party storage), so `auth.js`
sets `authDomain` to `window.location.host` instead of the project default
from `init.json`. Every Hosting host serves `/__/auth/handler`, custom domain
included, so nothing is deployed for that — but the **Google OAuth client**
must list the handler as a redirect URI (checklist step 2), or Google answers
`redirect_uri_mismatch`. The CSP's `frame-src` is `'self'` for the same
reason: the SDK's auth iframe is now same-origin.

A Google re-authentication unloads the page. The operator comes back signed
in afresh with a one-line status saying what to repeat; the request that
asked for the step-up was not sent. A revoke or delete is the exception: the desk
stashes the licence id in sessionStorage before leaving (`resume` in
`stepUp`), and on the return leg `requireSignIn` hands it back so the desk
finishes the revoke after one plain confirmation — the who-is-affected
dialog and the typed key already happened on the way out, and the backend's
120 s revoke window is too short for finding the row and typing it again. If
the return leg fails — a wrong or cancelled authenticator code, or Back out of
Google — the stash comes back marked `reauthFailed` and the desk says the
revoke or delete was not sent (the list load used to wipe that line, so a
delete could vanish silently). A retry is the operator's own click, so it is
not held by the 120 s redirect-loop guard. A mint form left for more than
`ADMIN_WEB_REAUTH_SECONDS` is re-entered.
An account with a password provider is asked for it and steps up in place; a
Google-only account (the consoles sign in with Google only) goes straight to Google
without a password prompt it could not answer.

One SDK detail makes the redirect re-auth work at all. When the return leg
raises the TOTP challenge, firebase-auth resolves it against the user it
stashed for the round trip (`auth.redirectUser`), not the one it restored as
`currentUser`, so the fresh tokens land on an object nobody holds and
`currentUser` keeps the old `auth_time` — every step-up looks as if it never
happened and the page bounces back to Google. `resolveChallenge` therefore
adopts the re-authenticated user with `updateCurrentUser`. A plain sign-in
does not need this (the SDK makes that user current itself), and neither does
a same-page password re-auth.

`updateCurrentUser` fires the auth listener a second time, so `requireSignIn`
starts a page once per signed-in account and hands the stashed `resume` to
that one start. Without this the desk loaded twice on every return leg, both
copies received the revoke, and the second load's status reset erased
whatever the first reported.

Results stay on screen. A change refreshes only the row it touched — from
the PATCH or revoke answer, or `GET /v1/admin/licenses/{id}` after a mint or a
roster change — so "SEMP-4K2P revoked." or "Could not revoke: …" is never
overwritten by a reload, and pages loaded with "Load more" stay loaded. The
status line is pinned to the top of the viewport while it holds a message — it
sits above the mint card, and the licence table is well below it. The list is
newest first, 50 a page; Demo keys and revoked licences are left out by the
backend unless "Show Demo keys" / "Show revoked" is ticked, and a staff test
account's licences unless "Show test accounts" is (`include_test`). Kind,
App (one from before the split names none) and State (in force, ends within
30 days, in grace, expired, upgrade waiting) narrow the rows read, beside the
text box, which matches what a row shows (kind, app, state, "shared"), the
address or domain it is locked to and its note, saying under the kind which
of those two matched; Clear filters empties the four. A row is what is read
most: Key, Kind, Seats and Status, each heading sorting; while pages are unread the
paging line says the sort covers only those, and Load all reads the rest 200
at a time. The filters, toggles and sort are kept for the tab in
`sessionStorage` (`semper.desk.licenceView`), the text typed is not: it is
often an address. `/` jumps to the text box. Term, Locked to, Mode and Analyses /
person are no longer columns: a Demo or test key is tagged beside its kind,
as is a limit set at issue ("200 analyses each"); a licence's key opens its
details under the row (term, locked to, id, mode, app, seating, phones,
analyses limit, IT contacts, issued, claimed, grace, support date, note).
The Test accounts list does the same: a row is the address, when it ends,
its phones and its status, with Extend beside them and New password and End
now under More; the address opens its purpose, note, creation time and uid.
Live accounts show first (chips Live, Ended, All), with a search over
address, purpose and note and sortable Account, Ends and Status. The row a Roster or Verify card
is open for stays marked until Close. A revoke that left
its row in place with only the pill changed read as one that had not
happened. The filter narrows the loaded rows at once, and an email, domain or
key prefix is also searched on the backend, so a licence on a page nobody
loaded is found. The search is `POST /v1/admin/licenses/search` with
`{"q", "limit", "page_token"}` in the body, never `?q=` on the list: an
address in a URL lands in every request log on the way (TD-244). Its answer
has the list's shape; the desk reads up to ten pages of it. One licence per person: the backend refuses an
individual mint for an address that already holds or is promised a live
licence (`409 email_already_licensed: <id>`), and the desk puts that licence
on screen with the address in the filter — renewal is Edit on it. A Demo
account is licensed by issuing, not editing: its Demo key is not a licence
anyone bought, so Edit on one says so and "Issue a licence to this address"
fills the Issue form (Individual, the key's address) for the operator to set
the term. The mint attaches to the signed-in account and replaces the Demo
key (`_attach_to_existing_holder`). The IT
and operator rosters say the same for `member_already_licensed`. Every mint
says whether the licence reached the person: attached, waiting for their
first sign-in, or not delivered and why.

### Test accounts

The Test accounts card (`operator/test-accounts.js`, ADR-027)
makes the sign-ins QC staff and CI use: `POST /v1/admin/test-accounts` with
`{email, purpose, days, maxDevices, note}` (QC: days 1–90, default 30; CI: days
blank is sent as `days: null`, no expiry; phones at once 1–10, default 3). The
address must be on one of the server's `TEST_ACCOUNT_DOMAINS`; with none set,
every create is refused (`403 test_accounts_disabled`), and an address with an
account or a sign-in already is refused too (`409 email_in_use`). The list
(`GET …`) is read on demand, like Recently deleted. Each row offers Extend
(`POST …/{uid}/extend {days}`), New password (`POST …/{uid}/password`) and End
now (`DELETE …/{uid}`, after the address is typed).

Every one of those changes is held to the revoke window, so each first steps
up with `stepUpFresh` (`stepUpForRevoke` is the same step-up in a licence's
words), and a Google re-authentication carries the change through
sessionStorage and finishes it after one plain confirmation, as a revoke does.
The password, from a create or a New password, is shown once in its dialog with
Copy and a "shown once" warning. It is never written to the status line,
storage or a log, the stash carries none, and its text is removed from the page
when the dialog closes (Close or Escape).

### Go-live checklist (Identity Platform + consoles)

Same Firebase project as the app (`indicvision-dic-app-auth`). Do **not** open a
second Auth directory.

1. The dashboards live on **`app.sempermechanics.com`**, a custom domain of this Hosting site (`indicvision-dic-app-auth`). `sempermechanics.com` itself is the marketing site on Netlify, which only links here and redirects `/login`, `/account` and `/terms/` to this host. Add the custom domain in Firebase Console → Hosting (TXT verification, then the A records) — the DNS zone is Netlify DNS. Until the certificate is issued the site still answers on `indicvision-dic-app-auth.firebaseapp.com`.
2. Upgrade the project to **Identity Platform**, enable the **TOTP** second factor, leave **SMS** off. Add `app.sempermechanics.com` (and any preview channel) to authorised domains. Then, in the Auth project's Google Cloud console → APIs & Services → Credentials → the OAuth 2.0 client Firebase created for Google sign-in ("Web client (auto created by Google Service)"), add `https://app.sempermechanics.com/__/auth/handler` to **Authorised redirect URIs** — the consoles use the page's own host as `authDomain` (see "Sign-in flow" above), and Google refuses a redirect to a URI it was not told about.
3. Put your address in `ADMIN_EMAILS` / `role: admin` for the operator desk.
4. Deploy Cloud Run with the intended `DEMO_MAX_ANALYSES` (no lower than any live user's session count — every pre-licensing account becomes demo), keep `ADMIN_WEB_MFA_ENABLED=1` and `APP_CHECK_MODE=off`; `deploy-backend.yml` pins all three from repository variables. A production dispatch also redeploys **API Gateway** from the committed spec (the `gateway` job, `gateway_mode` `dry-run` then `apply`, [ADR-006](../../../docs/adr/ADR-006-gateway-deploy-job.md)); the manual fallback is [BACKEND_SETUP_GCP.md](../../../docs/backend/BACKEND_SETUP_GCP.md) "Redeploying the gateway". The full ordered checklist is the "Licensing rollout" section of [PRODUCTION_READINESS_GATE.md](../../../docs/ops/PRODUCTION_READINESS_GATE.md).
5. `./scripts/deploy-firestore.sh` — the deny-all rules and the backend indexes, into the backend project (this site's `firebase.json` has no `firestore` block: the CLI cannot reach files outside `firebase-hosting/`).
6. Run `./scripts/deploy-console.sh` with the live gateway host.
7. Hand-check: enrol TOTP at `/login` as staff, as institution IT, and as an account holder; revoke a test licence only after password + TOTP (+ key prefix); sign in on the phone and complete the authenticator challenge.

Identity Platform itself is free to enable. Email/social stays free to the usual
MAU tier; TOTP has no SMS charge when SMS stays off.

The console's Content-Security-Policy is scoped to `/console/**` and the two
addresses that rewrite into it. Every other page — the legal pages, the auth
continue-URLs — keeps the strict
`default-src 'self'` policy. `connect-src` is widened for the API and
Firebase Auth's token endpoints. `script-src` is `'self'` plus two Google
sources: the SDK modules come from `https://www.gstatic.com/firebasejs/<ver>/`,
which is also the whole of gstatic the policy allows (pinned to the version
`auth.js` imports; an SDK bump edits both, and `check_console.py` fails until
it does), and the auth iframe loads gapi from `https://apis.google.com` — without both
the page renders and *Sign in* does nothing (the first production deploy
proved it). `auth.js` imports **both** `firebase-app.js` and `firebase-auth.js`
from gstatic, never Hosting's `/__/firebase/<ver>/` copies: Hosting's
`firebase-app.js` is a full copy of the package while its `firebase-auth.js`
imports `@firebase/app` from gstatic, so mixing the two leaves `initializeApp`
and `getAuth` on different registries ("Component auth has not been registered
yet"). Only `/__/firebase/init.json` (the project config) is read from Hosting.
There is still no `'unsafe-inline'`, which is why no page may carry an inline
`<script>` body or an `onclick=` attribute: the policy admits module files from
those three origins and nothing else.

Firebase Auth must have this Hosting domain in its authorised domains, or
sign-in is rejected.

### CORS

The pages are served from Hosting and call the API Gateway on another origin
with an `Authorization` header, so the browser preflights every request. Two
things answer that preflight, and both must be in place or the page renders
and every button silently does nothing: the gateway config carries
`x-google-endpoints … allowCors: true` (so ESPv2 forwards the unauthenticated
`OPTIONS` instead of refusing it), and Cloud Run's `CONSOLE_ORIGINS` names the
page origin (`app.sempermechanics.com` and the `firebaseapp.com` host by
default; add a preview channel while testing). The phone never sends an
`Origin` and is untouched by either.

### Chrome

`console.css` is the marketing site's theme (`semper-website/css/style.css`:
its `:root` tokens, DM Sans / Plus Jakarta Sans, the `.btn-primary` /
`.btn-secondary` shapes, the footer-meta strip) at console density, so
`sempermechanics.com` → *Sign in* reads as one product. Take a value from
there before inventing one here. The brand mark and favicon are copies of the
site's `assets/semper/semper-mark.webp` / `semper-icon.webp`; the fonts come
from Google Fonts, which is why the console CSP names
`fonts.googleapis.com` (`style-src`) and `fonts.gstatic.com` (`font-src`) — the
site-wide policy does not. The two pages served at a rewritten address declare
`<base href="/console/…">` so they work there too, which is why the console
CSP's `base-uri` is `'self'` rather than `'none'`; `check_console.py` refuses
the pair any other way. `chrome.js` holds the footer year, and the preconnect
to the API (below): the CSP is `script-src 'self'`, so an inline one-liner
would never run.

### Load speed

Sign-in cannot start until the Firebase SDK, every page module and the
project config (`/__/firebase/init.json`) have arrived, and with no build step
the browser used to discover the modules one level of imports at a time, each
a round trip of its own. Each page's `<head>` now names everything at once:

- `<link rel="modulepreload">` for every module its scripts import, the two
  gstatic SDK files included. `check_console.py` keeps the list equal to the
  import graph, so a new import that is not preloaded fails CI.
- `<link rel="preload">` for `init.json`, `as="fetch" crossorigin="anonymous"`
  — the mode of `auth.js`'s plain `fetch`; any other mode is fetched twice.
- A preconnect to Google's sign-in server. The preconnect to the API is added
  by `chrome.js` from `config.js`, so no hostname is written into the pages.

`qr.js` and its vendored library are not among them: `ensureDashboardMfa`
imports them on demand, the one time an account enrols. Pages then send their
reads together rather than one after another (see "The front door").

Files stay `Cache-Control: no-cache`: each load revalidates every file, now
in one parallel round of 304s. Long-lived caching would need versioned file
names, which means a build step.

Each page marks `semper:auth-ready` (sign-in and the second factor settled)
and `semper:first-data` (its first loaded data shown) with the Performance
API, so a load can be timed with `performance.getEntriesByType("mark")`.

### Waiting

A load is never a blank page or a silent one:

- **Before the sign-in state is known** a page says "Checking your sign-in…"
  and does not offer Sign in: `<body data-auth="pending">` and a pending
  `#status` in the markup, and `console.css` keeps `#signIn` / `#signedOut`
  hidden until `auth.js` sets `data-auth` to `in` or `out`. If the scripts
  never run, a CSS animation shows them after 8 s.
- **Restricted pages stay hidden until allowed.** The operator desk and the
  institution page call `requireSignIn(onReady, { showApp: false })` and show
  `#app` only once `/v1/me` (or the licence list) says the account may see it.
- **Each step is named** on the status line by `whileWaiting` (`status.js`):
  "Checking your access…", "Loading licences…", "Loading the roster…",
  "Loading your analyses…", "Finding your dashboard…". After 4 s it adds
  "Still working — the server can take a few seconds to start.": the API
  scales to zero, and its first answer after idling was measured at 10.7 s.
  `#status` is a polite live region, so a screen reader hears each step.
- **Tables show their shape while they load** (`placeholderRows` in
  `util.js`; the account's licence card has them in its markup), pulsing only
  without `prefers-reduced-motion`.
- **A failed table says so in the table, with Retry** (`retryRow`), which
  reruns that one load.
- **No double loads, no stale answers.** A list's Refresh / Show more / Open /
  Re-check is disabled while it loads (two clicks on Show more listed a page
  twice), and each load takes a ticket so an older answer arriving late is
  dropped rather than drawn over a newer one.

### Screen sizes

Phone up to 600px, tablet 601–1024px, desktop beyond, the same in every
media query in `console.css`.

- **Phone:** every table in a `.wrap` becomes a stack of cards, its first
  cell as the heading and the rest as "label value" lines. `chrome.js`
  copies each column's heading onto its cells (`data-label`) whenever a
  table's rows change. The header keeps to two rows: brand, page, the
  signed-in address (cut short; the whole of it on hover), Sign out; then
  the dashboard switch, full width.
- **Tablet and desktop:** tables stay tables and scroll sideways inside
  their card, the first column pinned.
- **Touch screens** (`pointer: coarse`): every control at least 44px high,
  and text boxes at 16px so iOS does not zoom when one is tapped.
- **Keyboard:** a visible accent outline on whatever has focus.

On the desk (`<body class="desk">`, up to 1400px wide), a pinned row of
anchors (`.desk-nav`) jumps to each card, with the number waiting for approval
beside Access requests (`#pendingCount`, filled by `people.js`). The licence table
comes first and **Issue a licence** opens its card from the table's heading;
Roster and Verified seats open straight under the table. Access requests and the
Demo phone release share a row on desktop; then Test accounts, whose create
form is a `<details>` folded under it; Recently deleted is last. Each licence
row keeps Edit (and Roster) in view; the rest is under **More**, a `<details>`
that opens in place, Revoke and Delete last and set apart. Destructive row
buttons (`.danger`: Revoke, Delete, Remove, End now, a phone's Release) are
outlined in the danger colour and stand apart from the others in their cell.
A test account's Phones cell lists each active phone on its own line (model
and app, device id under them, its Release at the end of the line).

## Checking them

There is no compiler here, so nothing else in the repository fails when a
page's wiring goes stale — the page loads, looks right, and does nothing.
[`scripts/check_console.py`](../../../scripts/check_console.py) is the gate
that reads them instead, and runs as the **Console pages** CI job:

| It checks | Because |
|---|---|
| No inline script or `on*=` handler | The CSP above forbids both; such code never executes |
| Every module loads and parses as an ES module | A typo in one is otherwise found by a browser, in production |
| Every `$("id")` is an id its own page defines, in the page's module and every module it imports | Renaming an element silently unwires the code that used it |
| `__API_BASE_URL__`, `__API_ORIGIN__` still hold placeholders | A deploy that fails to restore them commits a live hostname |
| Both console CSPs carry `frame-src 'self'` | `authDomain` is the page's own host; the auth iframe is same-origin |
| Every rewrite destination exists | `/login` pointing at a missing file 404s |
| The two console CSPs are identical | The rewrite addresses would otherwise be served a different policy |
| Every `/v1` path a console calls is in `gateway/openapi.yaml` | ESPv2 is an allowlist; an undeclared route 404s in production |
| Every refusal code a page matches (`e.code === "…"`, or a key of a sentence map) is declared in `backend/app/errors.py` | A renamed code leaves the page's sentence unreachable, and the page shows the raw code instead |
| Every page preloads exactly the modules it imports (not those imported on demand), and `init.json` as `fetch`/`anonymous` | A missing preload costs a round trip per level of imports; a wrong mode or an extra one downloads twice or for nothing |
| Every page starts `<body data-auth="pending">`, its `#status` a polite live region marked pending | Without them a signed-in visitor is offered Sign in first, and a screen reader hears none of the loading steps |

Run it directly with `python scripts/check_console.py` (`--root <repo>` checks
another tree). Node is used for the syntax check when it is on `PATH` and
skipped with a note when it is not. The auth continue pages
(`public/finishSignIn`, `public/finishReset`) get the first three checks: their
code is in files (`finishReset/reset.js`, loaded by an absolute path because
the page is served at two addresses), present, parsing, and wired to ids that
exist. `backend/tests/test_check_console.py` plants each failure above in a
small tree and asserts the gate reports it.

A page's modules are followed through their relative imports, those imported
on demand included, so a split page is checked as a whole. Each test loads a page afresh with `?load=N`;
`tests/firebase-hooks.mjs` gives the modules in the page's own folder the same
query, so their state starts empty as well. The shared modules one folder up
stay one copy, as in a browser.

The same job runs `node --test "firebase-hosting/tests/*.test.mjs"` (Node 22,
no `npm install`). `util.test.mjs` covers `util.js`: escaping, dates, and the
roster cells (an expired floating lease reads "—", not "until <past time>").
The other files cover `auth.js`, `api.js`, `dialogs.js`, `status.js`,
`router.js` and the three pages against a fake Firebase SDK:

| File | Pins |
|---|---|
| `auth.test.mjs` | `api()` sends `Bearer <ID token>` to `API_BASE_URL + path` and throws the backend's code; `reauth_required` leaves for Google once and never retries with the stale token; a step-up that completes retries once with a force-refreshed token; `allowStepUp: false` and `mfa_required` are handed back untouched; a cancelled code is `ERR_CANCELLED`; the 120 s redirect-loop guard and `operatorAsked`; `apiBlob()`; a resolved challenge adopts the re-authenticated user; `sessionHasSecondFactor` reads the token claim; TOTP enrolment in the page; `stepUpForRevoke`'s 90 s window and password-versus-Google choice, and `stepUpFresh` in its caller's words; `confirmByTyping`; a refusal carries its `code`, the `rest` after it, and the `status` |
| `signin.test.mjs` | `requireSignIn`: nothing reaches `onReady` signed out or without this session's second factor; one start per account; the return leg hands `resume` over once, or marks it `reauthFailed`; declining to enrol signs out; a redirect that keeps failing stops with a message |
| `router.test.mjs` | `/login` forwards staff, IT contacts (deep-linked to one licence) and everyone else, and offers a switcher when it cannot or should not choose |
| `operator-list.test.mjs` | The licence table's list tools: Kind, App and State, the count and Clear filters; the text box over kind, app, state, the lock and the note (said under the kind); each heading's sort both ways, an unread page said, Load all 200 at a time and its failure part-way; a row's details and a redraw keeping them; Show test accounts in the list and the search; the view kept for the tab without the text typed, a stale one ignored; `/`; the marked row |
| `list-tools.test.mjs` | `list-tools.js` alone: the orderings and their ties, the text match, the chips (an empty one left out, labels escaped) and the count line |
| `operator.test.mjs` | The desk only for `role=admin`; licence rows (seats, term, cap, state, which actions apply — no Delete on a system Demo key, only Delete on a revoked row); Show revoked / Show Demo refetch; Load more without duplicates; the filter, and the backend search after a 300 ms pause (a POST with the text in its body, never in a URL); revoke and delete: who-is-affected confirmation, typed key, `stepUpForRevoke` (a stale session goes to Google carrying the licence, nothing sent), the sent request and its message, each refusal's wording; the return leg finishes a revoke or delete after one plain confirmation, once, reads a licence past the first page first, refuses a gone or already-revoked one, and says a failed leg sent nothing; Edit prefill, a later end without a typed key, an earlier one only with it, clearing the cap, a Demo key's cap locked, a Demo key's hand-off to Issue (address filled, Individual ticked, nothing sent), each `editError` code in the dialog; New device; the roster card on the staff routes, with the same actions as IT, and a refusal said in the card |
| `account.test.mjs` | Licence pills and explanation (licensed, Demo, perpetual, expired past grace, ended in grace, shared seat held or not, a backend without `held`); Move licence hidden without a held licence; "N of M", "N analyses stored", and the inactive-licence wording, also when the analyses answer first; each analysis row's state, file count and stored size ("—" for failed or nothing, "… when done" while uploading), Download disabled until something finished, a specimen name never markup; Show more; give a seat back and move the licence (confirmation, request, the cooldown instant from `device_change_too_soon: <ISO>`, each refusal); Download saves `semper-analysis-<id>.zip`, and its refusals; the licence's facts (mode, licence, seats, end, grace end, lease; none without a licence; an unknown value as it came), the offline line only with a licence, and the delete card naming both apps |
| `institution.test.mjs` | Nothing of the roster for an address no licence names (or an unverified one); a failed check leaves the page usable; the deep link; "N of M seats taken" / "in use right now" with invites counted apart; member rows (New device, Pause / Resume, Remove; nothing for a removed member; invites with Withdraw); not-found wording; Pause / Resume / New device bodies and reload; New device, Remove and Withdraw confirm first, naming the address; row buttons named after the member; `ACT_ERRORS`; adding a member (on now vs invited, each refusal, `claim_contended`); the licence picker; a roster longer than one page read to the end |
| `institution-tools.test.mjs` | The seat-count tiles (attention marked, a failure said in place, a late answer for another licence dropped); Export roster CSV saves `roster-<keyPrefix>.csv`; the import's row-count preview and 500-row stop, a chosen file, the sent body, every row's result, a roster reload only when someone was added, each whole-file refusal; another licence clears the results, one not yours hides the tools; the audit log read on demand with its dates, members named by address, Older events, an empty range, `invalid_audit_range`, a late page dropped, CSV and JSON exports and the 5,000-event note |
| `operator-test-accounts.test.mjs` | The Test accounts card: the create body (QC days, CI `days: null`, phones), each form error before anything is sent, the password in its dialog only and gone from the page, the status line and storage once closed (Close or Escape), Copy; `test_accounts_disabled`, `email_in_use` and the step-up refusals; a stale sign-in sent to Google with the change stashed (no password in it) and nothing sent, and the return leg finishing a create, Extend or End now after one confirmation, or saying a failed leg sent nothing; the list on demand (purpose, end or Never, phones, status, note never markup, Retry, a late answer dropped); Extend's days, New password, End now behind the typed address |
| `operator-per-app.test.mjs` | Issue per app and for both (two claim codes, a refusal after the first said with what was issued), the phone count's defaults, the app tag and Phones n/m on a row, Issue upgrade in place of New phone and To institution only on a licence that names its app, Edit's phone count and its refusals, a QC test account's licence fields |
| `operator-phones.test.mjs` | The Phones dialog: the list (who activated, an institution's members), Deactivate asked first and the list read again, Add phone within and past the count, `device_limit_reached` with its count, Issue upgrade (the code once, Apply now, Escape clears it), each refusal in words |
| `institution-phones.test.mjs` | The Phones card on a licence that names its app: the pool count and its no-limit wording, rows with who activated, Deactivate confirmed or cancelled and the licence read again, each refusal, none for a licence from before the split, cleared on another licence, a late answer dropped |
| `account-phones.test.mjs` | The semper and semper Classroom cards: facts, the count (a pool's own line), the holder's phones activated or on Demo, Deactivate confirmed, cancelled and refused, a new licence waiting, Move shown only on a licence from before the split, an old backend with no `licences` unchanged, a late answer dropped |
| `operator-roster-tools.test.mjs` | The desk's roster card on the staff routes: the tiles (a failure in place, another licence's late answer dropped); Export roster CSV; the import's preview, 500-row stop, chosen file, sent body, every row's result, and the roster and licence row re-read; a refused import; a stale sign-in sent to Google with nothing else sent; another licence clears the results and the log; the audit log by date and page, `invalid_audit_range`, CSV and JSON exports and the 5,000-event note; and both pages drawing the same HTML and sending the same requests for the same answers |

How the fake gets in: `tests/harness.mjs` calls `module.register` with
`tests/firebase-hooks.mjs`, whose `resolve` hook maps the two
`www.gstatic.com/firebasejs/<ver>/` imports to `tests/fakes/` and refuses any
other remote import. A test file therefore imports `auth.js` dynamically
(`loadAuth()`, which answers `auth.js`, `api.js`, `dialogs.js` and `status.js`
as one namespace), after the harness has run. The harness also puts a small
`window` / `document` / `location` on `globalThis` and a `fetch` that answers
only what a test scripted (by order, or by method and path), so nothing
reaches the network. `document` is `tests/fake-dom.mjs`: the page's real
markup parsed into a tree, and whatever a module writes into `innerHTML`
parsed the same way, so rows can be clicked, delegated handlers see
`closest(...)`, and an unmodelled selector throws instead of matching
nothing. `openPage("operator", {...})` mounts a page and imports a fresh copy
of its module (`?load=N`); a `resume` makes that load the return leg of a
Google re-authentication. A new SDK function in `auth.js` needs a matching
export in `tests/fakes/firebase-auth.mjs`.

**In a browser** (CI job "Console accessibility"): `tests/a11y/` opens the
sign-in page, the front door, the account page (licensed, and on Demo with the
claim card), the institution roster (120 seats, so Show more is drawn) and the
operator desk in Chromium at 360, 768 and 1280 px, and asserts that axe-core
finds no serious or critical WCAG 2.1 A/AA violation, that no page scrolls
sideways and that nothing throws. It also opens a confirmation card (role,
name, focus in and back out on Escape) and checks that every roster action is
in view at 768 px. `tests/a11y/stub-site.mjs` serves `public/` on 127.0.0.1,
answers the gstatic SDK imports with the same fakes, answers the API (the
`__API_BASE_URL__` placeholder stays, so calls are page-relative) from fixtures
and refuses every other host. The only npm dependencies in the repository are
here, pinned: `@playwright/test` and `@axe-core/playwright`
(`firebase-hosting/package-lock.json`). Locally, from `firebase-hosting/`:
`npm ci`, `npx playwright install chromium`, `npm run test:a11y`.

What the fakes cannot prove is that Firebase itself still behaves the way
they model it — the redirect round trip, the handler on the page's own host,
real TOTP codes, the `redirectUser` quirk described below. That stays on the
hand-check (checklist step 7).

## Downloading an analysis

`GET /v1/sessions/{sid}/bundle` answers with a zip rather than JSON, so it does
not go through `api()` — that helper reads the whole response as text and parses
it, which would both corrupt an archive and throw on its first byte. `apiBlob()`
is the same call shape for a binary body, keeping the one thing that matters:
the retry on `reauth_required`, so a tab left open past the re-authentication
window does not report a refusal for a download the caller is entitled to.

The backend streams the archive so its own memory stays flat; the browser still
holds the whole thing, because a page cannot write to disk incrementally without
the File System Access API. An analysis is tens of megabytes, which is a cost
worth paying for a download that behaves the same everywhere.

## The second seat count

The **Verify** button on an institution licence asks
`GET /v1/admin/licenses/{id}/reconcile` what that licence still entitles, and
opens a panel comparing it with what IT believes.

The two numbers cannot be made to agree by being more careful, which is the
point of showing both. A revoke reaches the seat inside a transaction, the
holder's user document just after it, and the holder's *device* only when the
app next fetches `/v1/config`. `seatsUsed` — the only number the institution
console has — moves at the first of those three.

Each seat is placed in a bucket with a reason, and the panel prints the reason
in words rather than leaving a code to be looked up. Two of them matter:

- **`still_licensed`** is a fault. The demotion never landed and the backend
  would still answer `licensed`. Revoking the seat again repairs it — that path
  is idempotent and re-runs the demotion.
- **`no_checkin_since_revoke`** is not. The record is right; the device has
  simply not been back to hear it.

The read costs one user lookup per seat, so it is never run for the whole
table: nothing happens until someone presses **Verify**, and the result is
cached only until the next action changes a licence, at which point the panel
re-checks itself. That is deliberate — a revoke is exactly the moment to ask
again whether it landed.

The licence row's Seats cell picks up the second number once it exists, so the
verified count sits beside the intended one where the mismatch is visible.

See §20.12 of [the architecture doc](../../../docs/backend/CLOUD_ARCHITECTURE_GCP.md)
for why confirmation takes two conditions rather than one.

## Note on the licence table

`maxSeats` means two different things and the table says which:

- **assigned** — it caps the roster. `seatsUsed/maxSeats` is the whole story.
- **floating** — it caps *concurrent* seats. The roster is uncapped, so the
  table shows both `leasesActive/maxSeats in use` and the roster size.

Reading a floating licence's `seatsUsed` as though it were the cap is the
easiest mistake to make here.
