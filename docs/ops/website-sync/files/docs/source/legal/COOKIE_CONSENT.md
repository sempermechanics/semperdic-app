# Cookie consent — Firebase Hosting auth pages

**Status:** applicability note (no banner shipped without evidence)

Semper’s Android client does not use a web cookie banner. The only first-party
web surfaces in this repo are Firebase Hosting pages under
[`firebase-hosting/public/`](../../firebase-hosting/public/) used for auth
completion flows (`finishSignIn`, `finishReset`).

## What to verify before adding a banner

1. Open the production Hosting site in a browser with DevTools → Application →
   Cookies (and local/session storage).
2. Confirm whether any **non-essential** cookies are set (marketing, analytics,
   A/B). Firebase Auth and Hosting may set cookies/storage required for the
   sign-in redirect to complete — those are typically **strictly necessary** for
   the auth flow you asked the user to perform.
3. If only essential tech cookies/storage appear, a consent banner is **not**
   required solely for these pages under common EU guidance for strictly
   necessary cookies. Document the finding in the completion gate.
4. If non-essential cookies appear (e.g. third-party analytics tags added
   later), add a consent mechanism **before** those tags load — do not add a
   decorative banner “just in case.”

## Current repo evidence

- Hosting config: [`firebase-hosting/firebase.json`](../../firebase-hosting/firebase.json)
  (security headers; no analytics snippets checked in).
- Auth HTML pages: email-link / reset finishers only.

**Production cookie inventory:** UNKNOWN until an operator records a console
capture against the live Hosting URL.
