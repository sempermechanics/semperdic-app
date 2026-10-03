# Firebase Hosting — auth domain (`indicvision-dic-app-auth`)

This site answers on **`https://app.sempermechanics.com`** (custom domain) and
on its own `https://indicvision-dic-app-auth.firebaseapp.com`. It serves the
dashboards ([`public/console/`](public/console/README.md)), the legal pages,
and two things the passwordless **email-link sign-in** depends on:

| Path | Purpose |
|---|---|
| `/.well-known/assetlinks.json` | Digital Asset Links — lets Android verify the App Link and route the sign-in link to the app instead of a browser. |
| `/auth/finishSignIn` | The email link's continue URL (`EMAIL_LINK_CONTINUE_URL` in [`AuthRepository.kt`](../app/src/main/java/com/sempermechanics/semper/data/account/AuthRepository.kt)) on `app.sempermechanics.com`. On-device the app's App Link intercepts it; in a plain browser it shows a "finish on your phone" page. |
| `/auth/finishReset` | Password-reset continue URL (`RESET_CONTINUE_URL`) on `app.sempermechanics.com`. App Link opens the in-app reset screen; browser falls through to Firebase's `/__/auth/action` handler. |
| `/finishSignIn`, `/finishReset` | The same two pages at the paths every build before `AUTH_HOST = app.sempermechanics.com` uses, on the `firebaseapp.com` host. The **custom action URL** in Firebase Console → Authentication → Templates → Password reset stays `https://indicvision-dic-app-auth.firebaseapp.com/finishReset` until no such build is installed (TD-29): those builds intercept only that host. |
| `/privacy/` | Public Privacy Policy summary (canonical markdown in `docs/legal/PRIVACY_POLICY.md`). |
| `/terms/` | Public Terms of Service summary (canonical markdown in `docs/legal/TERMS_OF_SERVICE.md`). |

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

`firebase.json` sets `"appAssociation": "AUTO"`. That means **Firebase Hosting
generates and serves** `/.well-known/assetlinks.json` from the Android apps
registered on this Firebase project (package name + SHA-256 fingerprints in the
Firebase / Play console). The live URL above is what Android verifies against.

The checked-in [`public/.well-known/assetlinks.json`](public/.well-known/assetlinks.json)
is a **local reference / template** of the fingerprints we expect — useful when
adding a release key or debugging — not the deploy-time source of truth while
`AUTO` is set. Keep the console fingerprints in sync with that file.

## Release fingerprint (already shipped)

The checked-in template lists **both** the debug signing key and the release
signing certificate SHA-256 (no placeholder). Keep Firebase Console / Play App
Signing fingerprints in sync with that file, and redeploy Hosting after any
rotation. Get a keystore's SHA-256 with:

```bash
keytool -list -v -keystore <path-to-release.keystore> -alias <alias>
```

Both fingerprints (debug + release) can coexist in the array. The release
workflow greps this file for the APK's certificate before publishing.

## Deploy

Requires the Firebase CLI (`npm i -g firebase-tools`) and access to the
`indicvision-dic-app-auth` project.

```bash
cd firebase-hosting
firebase deploy --only hosting
```

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
