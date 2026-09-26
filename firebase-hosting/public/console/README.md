# Consoles

Static pages on the existing auth Hosting site. No build step, no framework, no
`package.json` — the site is served as files, and a toolchain for four pages
would cost more than it saves.

Each page is an HTML document plus one ES module beside it — `router.js`,
`account/account.js`, `institution/institution.js`, `operator/operator.js` —
loaded with `<script type="module" src="…">`. **Not inline.** The console
`script-src` is `'self'` with no `'unsafe-inline'` (see below), so an inline
script on these pages does not run at all: the page renders and every button is
dead. `auth.js` and `config.js` are shared by all four. `util.js` holds the
DOM-free helpers (`esc`, `when`, and the roster cells both seat tables render),
so `node --test` can run them; `auth.js` re-exports `esc` and `when`.

| Path | Who | What it can do |
|---|---|---|
| `/login` (`/console/`) | Anyone with an account | Signs in and forwards to whichever dashboard below is theirs. |
| `/account` (`/console/account`) | Anyone with an account | See the licence, its term, the seat and the saved analyses; give a floating seat back, move the licence to a new device, download an analysis. The last two need **2FA**. |
| `/console/institution` | IT staff named in a licence's `adminEmails` | Add and remove roster members, withdraw an unclaimed invitation, see who holds a seat, put a member on hold, clear a device lock. |
| `/console/operator` | Semper staff (`ADMIN_EMAILS` / `role=admin`) **with 2FA** | Issue individual and institution licences, edit or upgrade one, revoke or delete a key (30-day restore), drive any institution roster, approve accounts. |

## The front door

`/login` is the one address to hand out. It signs the caller in, reads
`GET /v1/me` and `GET /v1/institutions/licenses`, and forwards: `role=admin`
to the operator console, an address named on a live institution licence to the
roster (deep-linked when there is exactly one), and everyone else to their own
account. Somebody who is both gets a switcher rather than a guess, and
`/login?stay=1` always shows it.

The two restricted pages make the same check themselves, because a link can
land anyone on them: the operator desk asks `/v1/me` and shows nothing of the
desk to an account without `role=admin`, and the institution page asks
`/v1/institutions/licenses` and shows nothing of the seat manager to an address
no licence names. Each says so in a card with a link to the account page. The
backend refuses the calls anyway; the gate only spares people a form that every
submit would refuse.

Nothing is inferred from the email domain and nothing is remembered in the
browser, so an account that changes hands routes correctly the first time.

Both pretty addresses are Hosting **rewrites** into `/console/`, which has two
consequences worth knowing before editing them. A header is matched against the
request path and knows nothing about the rewrite, so the relaxed console CSP is
restated for `{/login,/account}` in `firebase.json` and the two values must stay
identical. And a relative path in the page would resolve against the site root
at those addresses, so both pages carry a `<base href>`.

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
(moving a licence, downloading an analysis) and institution roster changes
also require a fresh second factor on the API side.

Enrolment shows a QR code drawn **in the page** (`qr.js`, over a vendored
copy of qrcode-generator under `vendor/`) from the `otpauth://` URI the SDK
builds, with the key beneath it for anyone who cannot scan. It is never
fetched: every QR *service* is somebody else's server and the payload is the
TOTP secret itself, so a picture from one would hand away the factor that
protects licence issuance. The CSP's `img-src` names no such origin and must
not start to. Every console uses the same card (`enrolInPage` in `auth.js`);
the account page hosts it in its own section.

## Confirming destructive actions

Revoking a licence drops a whole institution to demo, so the page asks more
than once: password (or Google) re-auth plus authenticator code, a plain
confirmation naming who is affected, then typing the **key prefix**. The API
also refuses a revoke on a session older than
`ADMIN_WEB_REVOKE_REAUTH_SECONDS`. Nothing is deleted either way — revoking
withdraws entitlement and leaves every saved analysis in place.

**Delete** asks the same (it revokes first) and is offered on revoked rows
too, but not on a system Demo key. The licence moves to **Recently deleted**,
a card loaded only when opened (`GET /v1/admin/deleted-licenses`), which
shows the days left and **Restore** until `purgeAt`.

## Deploying

Use [`scripts/deploy-console.sh`](../../../scripts/deploy-console.sh) so the
placeholders are always restored, even if deploy fails mid-way. Never commit a
live hostname.

```bash
# From the repo root. The API Gateway host the app talks to.
API_BASE_URL="https://your-gateway-host" ./scripts/deploy-console.sh
```

The script copies `config.js` / `firebase.json`, substitutes `__API_BASE_URL__`
and `__API_ORIGIN__`, runs `firebase deploy --only hosting`, then a `trap` puts
the templates back.

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
backend unless "Show Demo keys" / "Show revoked" is ticked. A revoke that left
its row in place with only the pill changed read as one that had not
happened. The filter narrows the loaded rows at once, and an email, domain or
key prefix is also searched on the backend (`q=`), so a licence on a page
nobody loaded is found. One licence per person: the backend refuses an
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
origins: the SDK modules come from `https://www.gstatic.com/firebasejs/<ver>/`
and the auth iframe loads gapi from `https://apis.google.com` — without both
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
site-wide policy does not. Every page declares `<base href="/console/…">` so it
works at its rewritten address too, which is why the console CSP's `base-uri`
is `'self'` rather than `'none'`; `check_console.py` refuses the pair any other
way. `chrome.js` holds the footer year: the CSP is `script-src 'self'`, so an
inline one-liner would never run.

## Checking them

There is no compiler here, so nothing else in the repository fails when a
page's wiring goes stale — the page loads, looks right, and does nothing.
[`scripts/check_console.py`](../../../scripts/check_console.py) is the gate
that reads them instead, and runs as the **Console pages** CI job:

| It checks | Because |
|---|---|
| No inline script or `on*=` handler | The CSP above forbids both; such code never executes |
| Every module loads and parses as an ES module | A typo in one is otherwise found by a browser, in production |
| Every `$("id")` is an id its own page defines | Renaming an element silently unwires the code that used it |
| `__API_BASE_URL__`, `__API_ORIGIN__` still hold placeholders | A deploy that fails to restore them commits a live hostname |
| Both console CSPs carry `frame-src 'self'` | `authDomain` is the page's own host; the auth iframe is same-origin |
| Every rewrite destination exists | `/login` pointing at a missing file 404s |
| The two console CSPs are identical | The rewrite addresses would otherwise be served a different policy |
| Every `/v1` path a console calls is in `gateway/openapi.yaml` | ESPv2 is an allowlist; an undeclared route 404s in production |

Run it directly with `python scripts/check_console.py`. Node is used for the
syntax check when it is on `PATH` and skipped with a note when it is not.

The same job runs `node --test "firebase-hosting/tests/*.test.mjs"` (Node 22,
no `npm install`). `util.test.mjs` covers `util.js`: escaping, dates, and the
roster cells (an expired floating lease reads "—", not "until <past time>").
The other files cover `auth.js`, `router.js` and the three pages against a
fake Firebase SDK:

| File | Pins |
|---|---|
| `auth.test.mjs` | `api()` sends `Bearer <ID token>` to `API_BASE_URL + path` and throws the backend's code; `reauth_required` leaves for Google once and never retries with the stale token; a step-up that completes retries once with a force-refreshed token; `allowStepUp: false` and `mfa_required` are handed back untouched; a cancelled code is `ERR_CANCELLED`; the 120 s redirect-loop guard and `operatorAsked`; `apiBlob()`; a resolved challenge adopts the re-authenticated user; `sessionHasSecondFactor` reads the token claim; TOTP enrolment in the page; `stepUpForRevoke`'s 90 s window and password-versus-Google choice; `confirmByTyping` |
| `signin.test.mjs` | `requireSignIn`: nothing reaches `onReady` signed out or without this session's second factor; one start per account; the return leg hands `resume` over once, or marks it `reauthFailed`; declining to enrol signs out; a redirect that keeps failing stops with a message |
| `router.test.mjs` | `/login` forwards staff, IT contacts (deep-linked to one licence) and everyone else, and offers a switcher when it cannot or should not choose |
| `operator.test.mjs` | The desk only for `role=admin`; licence rows (seats, term, cap, state, which actions apply — no Delete on a system Demo key, only Delete on a revoked row); Show revoked / Show Demo refetch; Load more without duplicates; the filter, and the backend search after a 300 ms pause; revoke and delete: who-is-affected confirmation, typed key, `stepUpForRevoke` (a stale session goes to Google carrying the licence, nothing sent), the sent request and its message, each refusal's wording; the return leg finishes a revoke or delete after one plain confirmation, once, reads a licence past the first page first, refuses a gone or already-revoked one, and says a failed leg sent nothing; Edit prefill, a later end without a typed key, an earlier one only with it, clearing the cap, a Demo key's cap locked, a Demo key's hand-off to Issue (address filled, Individual ticked, nothing sent), each `editError` code in the dialog; New device |
| `account.test.mjs` | Licence pills and explanation (licensed, Demo, perpetual, expired past grace, ended in grace, shared seat held or not, a backend without `held`); Move licence hidden without a held licence; "N of M", "N analyses stored", and the inactive-licence wording, also when the analyses answer first; each analysis row's state, file count and stored size ("—" for failed or nothing, "… when done" while uploading), Download disabled until something finished, a specimen name never markup; Show more; give a seat back and move the licence (confirmation, request, the cooldown instant from `device_change_too_soon: <ISO>`, each refusal); Download saves `semper-analysis-<id>.zip`, and its refusals |
| `institution.test.mjs` | Nothing of the roster for an address no licence names (or an unverified one); a failed check leaves the page usable; the deep link; "N of M seats taken" / "in use right now" with invites counted apart; member rows (New device, Hold / Resume, Remove; nothing for a removed member; invites with Withdraw); not-found wording; Hold / Resume / New device bodies and reload; Remove and Withdraw confirm first; `ACT_ERRORS`; adding a member (on now vs invited, each refusal, `claim_contended`) |

How the fake gets in: `tests/harness.mjs` calls `module.register` with
`tests/firebase-hooks.mjs`, whose `resolve` hook maps the two
`www.gstatic.com/firebasejs/<ver>/` imports to `tests/fakes/` and refuses any
other remote import. A test file therefore imports `auth.js` dynamically
(`loadAuth()`), after the harness has run. The harness also puts a small
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
