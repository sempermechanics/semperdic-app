/* Sign-in and the second factor, shared by every console page.
 *
 * The API calls themselves are `api.js`, the in-page cards `dialogs.js`, the
 * status line `status.js`; this module is what holds the Firebase SDK, so a
 * page that only draws or formats need not load it.
 *
 * The Firebase SDK is loaded from www.gstatic.com — the console CSP's
 * `script-src` names that origin (and apis.google.com, which the auth iframe
 * loads); see firebase-hosting/firebase.json. Only the project config comes
 * from Hosting's reserved namespace (/__/firebase/init.json); the SDK copies
 * under /__/firebase/<ver>/ are not used, for the reason at the imports below.
 *
 * ── Redirect, never popup ─────────────────────────────────────────────────
 * Google sign-in and every Google re-authentication go through
 * signInWithRedirect / reauthenticateWithRedirect. A popup needs a user
 * gesture that is still fresh when the SDK opens the window, and two of the
 * places this file re-authenticates have none: straight after sign-in, to
 * enrol the second factor, and inside an API retry after `reauth_required`.
 * Browsers block those popups silently — the first staff sign-in on the live
 * domain did exactly that. A redirect has no such rule.
 *
 * The redirect flow needs the auth handler on the page's own origin, or the
 * browsers that partition third-party storage lose the result on the way
 * back. Every Hosting host — the firebaseapp.com default and a custom domain
 * alike — serves /__/auth/handler, so `authDomain` is set to the page's host
 * rather than the project default from init.json. The Google OAuth client
 * must list https://<host>/__/auth/handler as a redirect URI (README).
 *
 * There is no build step and no framework here on purpose: the site is static
 * files, and a toolchain for two pages would cost more than it saves.
 *
 * ── Why the second factor lives here ──────────────────────────────────────
 * Every state-changing /v1/admin/* route wants proof beyond an ID token. On
 * the phone that proof is a device attestation — an ECDSA signature from an
 * enrolled key. A browser cannot produce one, so the staff console proves
 * itself the other way the backend accepts: a completed second factor plus a
 * RECENT sign-in (see backend/app/deps.py attested_or_mfa_admin).
 *
 * "Recent" is the part that shapes this file. The backend rejects a token
 * whose auth_time is older than its window, so a console left open for an
 * hour must re-authenticate before it can act. `api()` (api.js) handles that
 * transparently: on `reauth_required` it re-authenticates once through `stepUp` — a redirect
 * round-trip through Google — and the operator repeats the action; a
 * `sessionStorage` note tells them so when the page comes back.
 */
// Both SDK modules from one origin. Hosting's /__/firebase/12.4.0/firebase-app.js
// is a full copy of the package while its firebase-auth.js imports @firebase/app
// from www.gstatic.com, so mixing them puts initializeApp and getAuth on two
// different registries: "Component auth has not been registered yet".
import { initializeApp } from "https://www.gstatic.com/firebasejs/12.4.0/firebase-app.js";
import {
  getAuth,
  GoogleAuthProvider,
  EmailAuthProvider,
  signInWithRedirect,
  reauthenticateWithRedirect,
  updateCurrentUser,
  getRedirectResult,
  reauthenticateWithCredential,
  signOut,
  onAuthStateChanged,
  multiFactor,
  getMultiFactorResolver,
  TotpMultiFactorGenerator,
} from "https://www.gstatic.com/firebasejs/12.4.0/firebase-auth.js";
import { ERR_CANCELLED, askInPage, codeCard, enrolInPage } from "./dialogs.js";
import { COMMON } from "./messages.js";
import { clearPendingStatus, mark, setStatus } from "./status.js";
import { authErrorText, challengeExpired, reauthMethods, sessionEnded } from "./util.js";

// Hosting's /__/firebase/init.js is the classic-SDK script
// (`firebase.initializeApp({...})`), not a module — there is nothing to
// import from it. The same config as JSON is one fetch away; top-level await
// holds every page's module until it is here, which is what they want anyway.
// Every page's <head> preloads it (`as="fetch" crossorigin="anonymous"`, the
// mode of this plain fetch), so the browser has usually fetched it before
// this line runs; a preload in any other mode would be fetched twice.
const firebaseConfig = await fetch("/__/firebase/init.json").then((r) => {
  if (!r.ok) throw new Error(`hosting/init-error: /__/firebase/init.json ${r.status}`);
  return r.json();
});

// Own host as authDomain: see "Redirect, never popup" above. The default
// firebaseapp.com host also serves /__/auth/*, so this is a no-op there.
const app = initializeApp({ ...firebaseConfig, authDomain: window.location.host });
const auth = getAuth(app);
const provider = new GoogleAuthProvider();

/** Error codes this module raises, so pages can branch instead of matching prose. */
export const ERR_NO_SECOND_FACTOR = "console_no_second_factor";
export const ERR_NO_PASSWORD = "console_no_password";
export const ERR_REAUTH_INCOMPLETE = "console_reauth_incomplete";

/* Redirect bookkeeping. `REAUTH_STARTED` stops a step-up that keeps failing
   from bouncing the tab to Google forever; `AFTER_REAUTH` carries the one line
   the operator should read when the page comes back. sessionStorage is
   per-tab and may be unavailable (private mode), so every access is guarded. */
const REAUTH_STARTED = "semper.reauthStarted";
const AFTER_REAUTH = "semper.afterReauth";
const RESUME = "semper.resume";
const REAUTH_RETRY_SECONDS = 120;
const SESSION_ENDED_TEXT = "Your sign-in has expired. Sign in again.";

function stash(key, value) {
  try {
    if (value == null) window.sessionStorage.removeItem(key);
    else window.sessionStorage.setItem(key, value);
  } catch (_) { /* no storage: the guard degrades to "always redirect" */ }
}

function unstash(key) {
  try {
    const value = window.sessionStorage.getItem(key);
    window.sessionStorage.removeItem(key);
    return value;
  } catch (_) {
    return null;
  }
}

/**
 * Seconds since this session's sign-in (the token's auth_time), or Infinity.
 *
 * On Google's clock (a fresh token's issued-at): `Date.now()` put the console's
 * windows and the backend's apart by however far this computer's clock was
 * off, so a step-up was skipped as fresh and then refused as stale. The
 * refresh also finds a remembered session Firebase can no longer renew.
 */
async function authAge(user = auth.currentUser) {
  if (!user) return Infinity;
  const result = await user.getIdTokenResult(true);
  const at = Date.parse(result.authTime);
  const now = Date.parse(result.issuedAtTime);
  return Number.isFinite(at) && Number.isFinite(now) ? Math.max(0, now - at) / 1000 : Infinity;
}

/** The error a page shows once the remembered sign-in is over: COMMON's `invalid_token`. */
function sessionEndedError() {
  return Object.assign(new Error("invalid_token"), { code: "invalid_token", status: 401 });
}

// Sign the page out because the remembered sign-in is over, and return the error to throw: the status
// line says to sign in again and the dashboard hides, whoever called, until the next sign-in (AE2-105).
let ended = false;
/** Whether `endSession` ran since the last sign-in: a later answer must not show the dashboard. */
export const sessionOver = () => ended;
export async function endSession() {
  ended = true;
  try {
    await signOut(auth);
  } catch (_) { /* already out: Firebase drops a session it cannot refresh */ }
  const app = document.getElementById("app");
  if (app) app.hidden = true;
  setStatus(SESSION_ENDED_TEXT, true);
  return sessionEndedError();
}

/**
 * Leave for Google and never come back to this promise: the page unloads.
 * Holding the caller here stops an `api()` retry from firing with the stale
 * token in the moment between the SDK's navigation and the actual unload.
 *
 * `resume` is what the page was in the middle of, handed back to
 * `requireSignIn`'s callback on the return leg so the operator does not
 * start over inside the backend's freshness window.
 */
async function leaveForGoogle(start, note, resume) {
  stash(REAUTH_STARTED, String(Date.now()));
  stash(AFTER_REAUTH, note);
  stash(RESUME, resume == null ? null : JSON.stringify(resume));
  await start();
  await new Promise(() => {});
}

/** The `resume` stashed by `leaveForGoogle`, or null. */
function unstashResume() {
  try {
    return JSON.parse(unstash(RESUME));
  } catch (_) {
    return null;
  }
}

/** The signed-in Firebase user, or null: what `api.js` takes its ID token from. */
export function signedInUser() {
  return auth.currentUser;
}

/* ---------------------------------------------------------------- challenge */

/**
 * Complete a second-factor challenge raised during sign-in or re-auth.
 *
 * TOTP only. The project enrols no SMS factor, so a phone hint cannot reach
 * this code: handling one meant carrying an invisible reCAPTCHA and two more
 * SDK imports for a branch nothing can enter. An unrecognised factor is
 * reported rather than half-handled — a page that cannot challenge a factor
 * should say so, not fail obscurely inside the SDK.
 */
async function resolveChallenge(error) {
  const resolver = getMultiFactorResolver(auth, error);
  const hints = resolver.hints.filter(
    (h) => h.factorId === TotpMultiFactorGenerator.FACTOR_ID,
  );
  if (!hints.length) throw new Error(ERR_NO_SECOND_FACTOR);

  // A wrong code keeps the challenge: Try again asks for the next code
  // against the same resolver, with no second trip to Google. Anything else
  // (a challenge that timed out, say) ends it, as does Cancel.
  const card = codeCard();
  let result;
  try {
    for (;;) {
      const code = await card.next();
      if (!code) throw new Error(ERR_CANCELLED);
      try {
        result = await resolveWithAny(resolver, hints, code);
        break;
      } catch (e) {
        if (e.code !== "auth/invalid-verification-code") throw e;
        if (!(await card.rejected())) throw e;
      }
    }
  } finally {
    card.close();
  }
  // After a re-authentication *redirect* the SDK resolves the challenge
  // against the user it stashed for the round trip, not the one it restored
  // as `currentUser` on this page load. The fresh tokens then land on an
  // object nobody holds, `currentUser` keeps the old auth_time, and every
  // step-up looks as if it never happened. Adopt the user that actually
  // re-authenticated. (A plain sign-in is already current; a same-page
  // password re-auth resolves against `currentUser` itself.)
  if (result.user && result.user !== auth.currentUser) {
    await updateCurrentUser(auth, result.user);
  }
  return result;
}

/* `code` against each enrolled authenticator in turn: only the first was
   asked, so a valid code from another one read as "not accepted". */
async function resolveWithAny(resolver, hints, code) {
  for (const [i, hint] of hints.entries()) {
    try {
      return await resolver.resolveSignIn(
        TotpMultiFactorGenerator.assertionForSignIn(hint.uid, code),
      );
    } catch (e) {
      if (e.code !== "auth/invalid-verification-code" || i === hints.length - 1) throw e;
    }
  }
}

/** Sign in. The result — and any second-factor challenge — arrives in
 * `finishRedirect()` after the round-trip. */
function signIn() {
  return signInWithRedirect(auth, provider);
}

/**
 * Collect the result of a sign-in or re-authentication redirect, resolving
 * the second-factor challenge it raises for an enrolled account. Null when
 * this page load is not the return leg of a redirect.
 */
async function finishRedirect() {
  try {
    const result = await getRedirectResult(auth);
    if (result) stash(REAUTH_STARTED, null);
    return result;
  } catch (e) {
    if (e.code !== "auth/multi-factor-auth-required") throw e;
    const result = await resolveChallenge(e);
    stash(REAUTH_STARTED, null);
    return result;
  }
}

/* ------------------------------------------------------------------ step-up */

/**
 * Re-authenticate so the token carries a fresh `auth_time`, then force a token
 * refresh so the *next* request actually carries it.
 *
 * `getIdToken(true)` is not optional here. Without it Firebase serves the
 * cached token, which still has the old auth_time, and the retry fails
 * identically to the call that triggered it — an infinite-looking loop that
 * looks like a backend bug.
 *
 * Google accounts re-auth by redirect, which unloads the page: this function
 * then never returns, the operator comes back signed in afresh and repeats
 * what they were doing. Email/password accounts may pass `password`, which
 * re-authenticates in place. A redirect that came back without a fresh
 * session is not retried — `ERR_REAUTH_INCOMPLETE` instead of a tab that
 * bounces to Google until closed. `operatorAsked` lifts that guard for a
 * step-up the operator started by hand (a click and a typed key): a bounce
 * needs no one at the keyboard, and a retry after a failed round trip is the
 * operator's own choice.
 */
export async function stepUp({ password, note, resume, operatorAsked = false } = {}) {
  const user = auth.currentUser;
  if (!user) throw new Error("not_signed_in");
  try {
    if (password != null && password !== "") {
      if (!user.email) throw new Error(ERR_NO_PASSWORD);
      await reauthenticateWithCredential(
        user,
        EmailAuthProvider.credential(user.email, password),
      );
    } else {
      const started = Number(unstash(REAUTH_STARTED));
      if (!operatorAsked && started &&
          Date.now() - started < REAUTH_RETRY_SECONDS * 1000) {
        throw new Error(ERR_REAUTH_INCOMPLETE);
      }
      await leaveForGoogle(
        () => reauthenticateWithRedirect(user, provider),
        note || "Re-authenticated. Repeat what you were doing — the request " +
          "that asked for it was not sent.",
        resume,
      );
    }
  } catch (e) {
    if (e.code !== "auth/multi-factor-auth-required") {
      throw sessionEnded(e) ? await endSession() : e;
    }
    await resolveChallenge(e);
  }
  return auth.currentUser.getIdToken(true);
}

/** Whether this account has any second factor enrolled at all. */
export function hasSecondFactor(user = auth.currentUser) {
  return Boolean(user && multiFactor(user).enrolledFactors.length);
}

/** The enrolled factors, for display. */
export function enrolledFactors(user = auth.currentUser) {
  return user ? multiFactor(user).enrolledFactors : [];
}

/** Whether this ID token records a completed second factor for *this* session. */
export async function sessionHasSecondFactor(user = auth.currentUser) {
  if (!user) return false;
  const result = await user.getIdTokenResult();
  const firebase = result.claims && result.claims.firebase;
  return Boolean(firebase && firebase.sign_in_second_factor);
}

/**
 * Enrol TOTP if missing, then ensure this session completed a second factor.
 * Every dashboard login uses this before routing or loading data.
 */
export async function ensureDashboardMfa() {
  const user = auth.currentUser;
  if (!user) throw new Error("not_signed_in");
  // A session that proved a factor has one; else ask the server. The SDK
  // fills `enrolledFactors` only from a reload, which a step-up redirect's
  // return leg never had: every step-up enrolled another authenticator.
  if (await sessionHasSecondFactor(user)) return;
  await user.reload();
  if (!hasSecondFactor(user)) {
    setStatus("Enrol an authenticator app to open any semper dashboard.");
    // The QR library is needed once per account, here, so it is fetched
    // here rather than on every page load. If it cannot be fetched the card
    // shows the key to type instead.
    const qr = import("./qr.js").then((m) => m.qrSvg, () => null);
    const enrolment = await beginTotpEnrolment(user.email);
    await enrolInPage(enrolment, user.email, await qr);
    setStatus("Authenticator enrolled.");
  }
  if (!(await sessionHasSecondFactor())) {
    setStatus("Confirming your second factor with Google…");
    await stepUp({ note: "Second factor confirmed." });
  }
}

/* --------------------------------------------------------------- enrolment */

/**
 * Begin TOTP enrolment. Returns the secret and a QR URL to show, plus a
 * `finish(code)` that completes it.
 *
 * TOTP rather than SMS for a new enrolment: no per-message cost, no delivery
 * failure abroad, and not interceptable by SIM swap — which matters more here
 * than elsewhere, because this factor is what stands between a stolen password
 * and the ability to mint licences.
 *
 * Enrolment needs a recent sign-in (Firebase's window is five minutes), so
 * this steps up first rather than letting Firebase throw
 * `auth/requires-recent-login` at the operator — but only when the sign-in is
 * not already fresh. It almost always is: enrolment runs straight after the
 * first sign-in, and a redirect there would loop.
 */
const ENROL_FRESH_SECONDS = 240;

export async function beginTotpEnrolment(accountLabel) {
  if ((await authAge()) > ENROL_FRESH_SECONDS) {
    await stepUp({ note: "Re-authenticated. Enrol your authenticator app now." });
  }
  const user = auth.currentUser;
  const session = await multiFactor(user).getSession();
  const secret = await TotpMultiFactorGenerator.generateSecret(session);
  return {
    secret: secret.secretKey,
    qrUrl: secret.generateQrCodeUrl(accountLabel || user.email, "semper"),
    finish: (code) =>
      multiFactor(user).enroll(
        TotpMultiFactorGenerator.assertionForEnrollment(secret, code),
        "Authenticator app",
      ),
  };
}


/* ------------------------------------------------------------------- shell */

/**
 * Run `onReady(user, resume)` once someone is signed in *and* has completed
 * dashboard MFA (enrolled TOTP + this session's second factor). Called by
 * every console before it fetches anything. `resume` is whatever the page
 * stashed before a re-authentication redirect that has just completed, or
 * null.
 *
 * Until the sign-in state is known the page says "Checking your sign-in…"
 * (its markup: `<body data-auth="pending">` and a pending `#status`), and
 * console.css keeps Sign in out of sight, so someone already signed in is
 * not offered it first. This sets `data-auth` to "in" or "out" once known.
 *
 * `showApp: false` leaves `#app` hidden for the page to show once its own
 * check passes: the operator desk and the institution page are only for
 * some accounts, and showed their controls before saying whose they were.
 */
export function requireSignIn(onReady, { showApp = true } = {}) {
  const signInBtn = document.getElementById("signIn");
  const signOutBtn = document.getElementById("signOut");
  const who = document.getElementById("who");
  const appEl = document.getElementById("app");

  signInBtn.addEventListener("click", () => {
    signIn().catch((e) => setStatus(`Sign-in failed: ${authErrorText(e)}.`, true));
  });
  signOutBtn.addEventListener("click", () => signOut(auth));

  // The return leg of a redirect resolves here. The auth listener below
  // waits for it: after a re-authentication the SDK reports the persisted
  // user before the second-factor prompt is answered, and judging that stale
  // token would send the page back to Google (or trip the loop guard). A
  // cancelled TOTP prompt during sign-in simply leaves nobody signed in.
  //
  // A re-authentication that fails on the way back (a wrong or cancelled
  // code, or Back out of Google) still leaves the old session signed in, so
  // the page loads as usual and its own status line would hide the failure.
  // The stashed `resume` is handed over anyway, marked `reauthFailed`, so the
  // page can say the action it was in the middle of was not sent.
  const afterReauth = unstash(AFTER_REAUTH);
  const stashedResume = unstashResume();
  const unfinished = (reason) =>
    stashedResume ? { ...stashedResume, reauthFailed: reason } : null;
  const redirectDone = finishRedirect()
    .then((result) => {
      if (result && afterReauth) setStatus(afterReauth);
      return result ? stashedResume : unfinished("incomplete");
    })
    .catch(async (e) => {
      if (e.message === ERR_CANCELLED) {
        setStatus("Sign-in cancelled — the authenticator code was not entered.");
        return unfinished("cancelled");
      }
      if (sessionEnded(e)) {
        await endSession(); // which says so on the status line
      } else {
        setStatus(challengeExpired(e)
          ? "That sign-in timed out before a code was accepted. Sign in again."
          : `Sign-in failed: ${authErrorText(e)}.`, true);
      }
      return unfinished(e.code || e.message);
    });

  const signedOut = document.getElementById("signedOut");

  // The page is started once per signed-in account. The return leg of a re-authentication fires
  // this listener twice — for the user restored from storage, again when `resolveChallenge` makes
  // the re-authenticated object current — and both wait on the same redirect, so without this the
  // page loaded twice with the same `resume`: two revoke confirmations, the second load wiping the first's.
  let readyUid = null;
  let resumeHanded = false;
  onAuthStateChanged(auth, async () => {
    const stashed = await redirectDone;
    // Read after the redirect settled: a resolved re-authentication may have
    // replaced the user object the listener was called with.
    const user = auth.currentUser;
    const signedIn = Boolean(user);
    document.body.dataset.auth = signedIn ? "in" : "out";
    if (!signedIn) clearPendingStatus();
    signInBtn.hidden = signedIn;
    signOutBtn.hidden = !signedIn;
    if (signedOut) signedOut.hidden = signedIn;
    who.textContent = signedIn ? user.email : "";
    who.title = who.textContent; // the whole address, where a phone cuts it short
    if (!signedIn) {
      readyUid = null;
      appEl.hidden = true;
      return;
    }
    if (user.uid === readyUid) return;
    readyUid = user.uid;
    ended = false;
    // Whatever was stashed is handed over once, on the first ready state.
    const resume = resumeHanded ? null : stashed;
    resumeHanded = true;
    try {
      await ensureDashboardMfa();
      mark("semper:auth-ready");
      clearPendingStatus();
      if (showApp) appEl.hidden = false;
      onReady(user, resume);
    } catch (e) {
      readyUid = null;
      if (e.message === ERR_CANCELLED) {
        setStatus("Two-factor authentication is required for every dashboard.");
        await signOut(auth);
        return;
      }
      appEl.hidden = true;
      if (e.message === ERR_REAUTH_INCOMPLETE) {
        setStatus(COMMON[ERR_REAUTH_INCOMPLETE], true);
        return;
      }
      // A remembered sign-in Firebase can no longer renew: Sign in, not Sign out.
      if (sessionEnded(e)) {
        await endSession(); // which says so on the status line
        return;
      }
      setStatus(`Could not open the dashboard: ${authErrorText(e)}.`, true);
    }
  });
}

/**
 * Fresh password (or Google re-auth) plus TOTP before whole-licence revoke,
 * and before a delete, which revokes first (`resume.action` says which).
 *
 * The backend refuses a revoke on a stale MFA session
 * (ADMIN_WEB_REVOKE_REAUTH_SECONDS, 120 s). Step up here so the token's
 * auth_time is new. A Google account steps up by redirect, which unloads the
 * page: `resume` is handed back to the desk on the return leg so it can
 * finish the revoke with one confirmation, inside the window, rather than
 * asking the operator to find the row and retype the key.
 *
 * Only an account with a password is asked for one. A Google-only operator
 * (the consoles sign in with Google only) goes straight to Google: a password prompt
 * they could not answer ended in `auth/invalid-credential`.
 */
const REVOKE_FRESH_SECONDS = 90;

export async function stepUpForRevoke(resume) {
  const verb = resume && resume.action === "delete" ? "delete" : "revoke";
  await stepUpFresh(resume, `${verb} this licence`);
}

/**
 * The same fresh step-up for any change the backend holds to the revoke
 * window (`attested_or_mfa_admin_fresh`): today also every change to a test
 * account. `what` finishes the sentence "Re-enter your account password to
 * …" ("end this test account"); `resume` is what the page finishes on the
 * return leg of a Google re-authentication, as for a revoke.
 */
export async function stepUpFresh(resume, what) {
  const age = await authAge().catch(async (e) => {
    throw sessionEnded(e) ? await endSession() : e;
  });
  if (age < REVOKE_FRESH_SECONDS) return;
  const can = reauthMethods(auth.currentUser && auth.currentUser.providerData);
  let password;
  if (can.password) {
    password = await askInPage({
      title: "Re-enter your password",
      message: `Re-enter your account password to ${what}.\n\n` +
        (can.google
          ? "Leave blank to re-authenticate with Google, then enter your " +
            "authenticator code when asked."
          : "Then enter your authenticator code when asked."),
      password: true,
      label: "Password",
      confirm: "Continue",
    });
    if (password === null || (!password && !can.google)) {
      throw new Error(ERR_CANCELLED);
    }
  } else {
    setStatus(`Re-authenticating with Google to ${what}…`);
  }
  await stepUp({
    password: password || undefined,
    note: "Re-authenticated.",
    resume,
    operatorAsked: true,
  });
}
