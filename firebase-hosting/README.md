# Firebase Hosting — auth domain (`indicvision-dic-app-auth`)

This site answers on **`https://app.sempermechanics.com`** (custom domain) and
on its own `https://indicvision-dic-app-auth.firebaseapp.com`. It serves the
dashboards ([`public/console/`](public/console/README.md)), the legal pages,
and two things the passwordless **email-link sign-in** depends on:

| Path | Purpose |
|---|---|
| `/.well-known/assetlinks.json` | Digital Asset Links — lets Android verify the App Link and route the sign-in link to the app instead of a browser. |
| `/auth/finishSignIn` | The email link's continue URL (`EMAIL_LINK_CONTINUE_URL` in [`AuthRepository.kt`](../app/src/main/java/com/sempermechanics/semper/data/account/AuthRepository.kt)) on `app.sempermechanics.com`. On-device the app's App Link intercepts it; in a plain browser it shows a "finish on your phone" page. |
| `/auth/finishReset` | Password-reset continue URL (`RESET_CONTINUE_URL`) on `app.sempermechanics.com`. App Link opens the in-app reset screen; browser falls through to Firebase's `/__/auth/action` handler. The page's code is `finishReset/reset.js` (no inline script; `tests/auth-pages.test.mjs`). |
| `/finishSignIn`, `/finishReset` | The same two pages at the paths every build before `AUTH_HOST = app.sempermechanics.com` uses, on the `firebaseapp.com` host. The **custom action URL** in Firebase Console → Authentication → Templates → Password reset stays `https://indicvision-dic-app-auth.firebaseapp.com/finishReset` until no such build is installed (TD-29): those builds intercept only that host. |
| `/privacy/` | Public Privacy Policy summary (canonical markdown in `docs/legal/PRIVACY_POLICY.md`). |
| `/terms/` | Public Terms of Service summary (canonical markdown in `docs/legal/TERMS_OF_SERVICE.md`). |
| any other address | `404.html`, which Hosting serves with status 404: "This page doesn't exist" and a link to `/login` (this host has no page at `/`); no timed redirect (AE2-102). No script, so it works under the `/console/**` policy too. |

Firebase Auth is configured to use **direct continue-URL handlers** (not the
retired Dynamic Links), so the emailed link lands on `/finishSignIn` carrying
the `oobCode`/`mode=signIn` params, and `AuthActivity` completes sign-in.

## `public/privacy/` and `public/terms/` are generated — never hand-edit them

Both directories are rendered from the canonical markdown in
[`docs/legal/`](../docs/legal/) by
[`scripts/render_legal_pages.py`](../scripts/render_legal_pages.py). The HTML is
committed so Hosting can serve it, but it is **output**, not source.

Editing the HTML directly is a build failure, not a style preference. CI runs
`python scripts/render_legal_pages.py --check` in a job (`legal-pages`) that has
no path filter and gates `ci-ok`, and the release workflow runs the same check
before it will sign anything. A hand-edit shows up as drift on the next PR.

The reason for the rule: the app links these pages as its real, user-facing
policy. Before this was enforced, they were placeholders whose own body said
"keep this in sync before launch" — which nobody did.

To change a policy:

```bash
# 1. Edit the markdown
#    docs/legal/PRIVACY_POLICY.md  /  docs/legal/TERMS_OF_SERVICE.md
# 2. Regenerate
python scripts/render_legal_pages.py
# 3. Commit BOTH the markdown and the regenerated HTML
# 4. Verify
python scripts/render_legal_pages.py --check
```

Then deploy Hosting (below) so the live pages match what shipped.

## Digital Asset Links source of truth

The checked-in [`public/.well-known/assetlinks.json`](public/.well-known/assetlinks.json)
**is what Hosting serves** at `/.well-known/assetlinks.json` on both hosts, and
the URL Android verifies against. `firebase.json` also sets
`"appAssociation": "AUTO"`, but a deployed file at that path is what both hosts
answer with: the 2026-10-07 deploy of this file changed what each served
([CHANGELOG](../docs/ops/CHANGELOG.md), "Material Testing's debug key in
`assetlinks.json`"). So an edit here goes live with the next Hosting deploy, and
`release.yml` checks this file for the release certificate before publishing.

## Release fingerprint (already shipped)

The checked-in file lists **both** the debug signing key and the release
signing certificate SHA-256 (no placeholder). Keep Firebase Console / Play App
Signing fingerprints in sync with that file, and redeploy Hosting after any
rotation. Get a keystore's SHA-256 with:

```bash
keytool -list -v -keystore <path-to-release.keystore> -alias <alias>
```

Both fingerprints (debug + release) can coexist in the array. The release
workflow greps this file for the APK's certificate before publishing.

## Deploy

Never a bare `firebase deploy`: it ships `config.js` with `__API_BASE_URL__` /
`__API_ORIGIN__` unfilled and every console API call fails (2026-10-07), and any
Hosting deploy replaces the whole site, an `assetlinks.json`-only change too. From
a **clean `main`** checkout, with the Firebase CLI and access to
`indicvision-dic-app-auth`:

```bash
API_BASE_URL=<INDIC_API_BASE_URL repo variable> ./scripts/deploy-console.sh
```

The script fills the placeholders, deploys, restores the templates, and runs
`scripts/check_live_console.py`, which fails if the live site still holds a `__`
placeholder. It refuses a branch other than `main`, a dirty tree and an
`API_BASE_URL` that is not an `https://` origin, and runs `scripts/check_console.py`
and `scripts/render_legal_pages.py --check` before deploying (`DRY_RUN=1` stops
there). While `main` holds legal text counsel has not cleared, add
`LEGAL_PAGES_FROM=b940cc29`, as
[DEPLOY_CHECKLIST_2026-10.md](../docs/ops/DEPLOY_CHECKLIST_2026-10.md) §4 says: it
deploys `/privacy` and `/terms` from that commit and skips the render check.

## Verify the App Link

```bash
# Confirm the file is live and served as JSON on both hosts
curl -s https://app.sempermechanics.com/.well-known/assetlinks.json
curl -s https://indicvision-dic-app-auth.firebaseapp.com/.well-known/assetlinks.json

# On a connected device/emulator (Android 12+):
adb shell pm verify-app-links --re-verify com.sempermechanics.semper
adb shell pm get-app-links com.sempermechanics.semper   # expect: verified
```

Until the file is live and verified, both App Links fall back to a browser:
`/finishSignIn` cannot complete at all, and `/finishReset` drops through to
Firebase's `/__/auth/action` handler instead of opening the app's in-app
set-new-password screen. Email **+ password** sign-in is unaffected.

The release workflow will not sign a build whose release certificate is missing
from `public/.well-known/assetlinks.json` — see
[RELEASING.md](../docs/ops/RELEASING.md).
