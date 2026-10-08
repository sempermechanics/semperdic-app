// Run: node --test "firebase-hosting/tests/*.test.mjs"
// requireSignIn (console/auth.js): the shell every console page starts from —
// who may see the dashboard, the return leg of a sign-in or re-authentication
// redirect, and the `resume` a revoke hands across it. Fake Firebase SDK: see
// harness.mjs.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, fake, FakeUser, readyUser, reset, settle, storage, prompts, codes, page, $, loadAuth, until,
} from "./harness.mjs";

const auth = await loadAuth();

const REAUTH_STARTED = "semper.reauthStarted";
const AFTER_REAUTH = "semper.afterReauth";
const RESUME = "semper.resume";

beforeEach(reset);
afterEach(settle);

/** Start the shell the way a page does and record every onReady call. */
async function start(user = null) {
  fake.auth.currentUser = user;
  const ready = [];
  auth.requireSignIn((u, resume) => ready.push({ user: u, resume }));
  await settle();
  return ready;
}

/** The return leg of a Google re-authentication for a revoke. */
function returningFromRevokeStepUp(resume = { action: "revoke", id: "L1" }) {
  storage.setItem(REAUTH_STARTED, String(Date.now() - 5000));
  storage.setItem(AFTER_REAUTH, "Re-authenticated.");
  storage.setItem(RESUME, JSON.stringify(resume));
  return resume;
}

test("a signed-out visitor sees Sign in and no dashboard", async () => {
  const ready = await start(null);
  assert.equal(ready.length, 0);
  assert.equal($("app").hidden, true);
  assert.equal($("signIn").hidden, false);
  assert.equal($("signOut").hidden, true);
  assert.equal($("signedOut").hidden, false);
  assert.equal($("who").textContent, "");

  $("signIn").click();
  await settle();
  assert.deepEqual(fake.callsTo("signInWithRedirect"), [["google.com"]], "redirect, never a popup");
});

test("a failed sign-in redirect is reported on the status line", async () => {
  fake.onSignInWithRedirect = async () => {
    throw Object.assign(new Error("x"), { code: "auth/unauthorized-domain" });
  };
  await start(null);
  $("signIn").click();
  await settle();
  assert.equal($("status").textContent, "Sign-in failed: auth/unauthorized-domain");
  assert.equal($("status").className, "muted err");
});

test("an enrolled session that proved its factor opens the dashboard once", async () => {
  const user = readyUser({ email: "staff@example.com" });
  const ready = await start(user);
  assert.equal(ready.length, 1);
  assert.equal(ready[0].user, user);
  assert.equal(ready[0].resume, null);
  assert.equal($("app").hidden, false);
  assert.equal($("who").textContent, "staff@example.com");
  assert.equal($("signIn").hidden, true);
  assert.equal($("signOut").hidden, false);
  assert.equal($("signedOut").hidden, true);

  // The listener firing again for the same account does not start the page again.
  fake.notify();
  await settle();
  assert.equal(ready.length, 1);

  // Sign out hides the dashboard; signing back in starts it afresh.
  $("signOut").click();
  await settle();
  assert.deepEqual(fake.callsTo("signOut"), [[]]);
  assert.equal($("app").hidden, true);
  assert.equal($("signIn").hidden, false);
  fake.setUser(user);
  await settle();
  assert.equal(ready.length, 2);
  assert.equal(ready[1].resume, null);
});

test("a session without its second factor never reaches onReady", async () => {
  const ready = await start(new FakeUser({ factors: [{}], secondFactor: false }));
  assert.equal(ready.length, 0);
  assert.equal($("app").hidden, true);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.equal($("status").textContent, "Confirming your second factor with Google…");
});

test("the return leg of a revoke step-up hands the resume to onReady, once", async () => {
  const resume = returningFromRevokeStepUp();
  const user = readyUser();
  fake.redirectResult = { user };
  const ready = await start(user);
  assert.equal(ready.length, 1);
  assert.deepEqual(ready[0].resume, resume);
  assert.equal($("status").textContent, "Re-authenticated.");
  assert.equal(storage.getItem(REAUTH_STARTED), null, "a completed redirect clears the loop guard");
  assert.equal(storage.getItem(RESUME), null);

  fake.setUser(null);
  await settle();
  fake.setUser(user);
  await settle();
  assert.equal(ready.length, 2);
  assert.equal(ready[1].resume, null, "the resume is handed over only once");
});

test("a TOTP challenge on the return leg adopts the re-authenticated user, with no second bounce", async () => {
  // currentUser is the object restored from storage, with the old auth_time
  // and no second factor; the SDK resolves the challenge on another object.
  const resume = returningFromRevokeStepUp({ action: "delete", id: "L9" });
  const stale = new FakeUser({ factors: [{}], secondFactor: false, authAgeSeconds: 3600 });
  const fresh = new FakeUser({ factors: [{}], secondFactor: false, authAgeSeconds: 3600 });
  fake.redirectResult = fake.mfaError({ user: fresh });
  codes.answer("123456");
  const ready = await start(stale);

  assert.deepEqual(fake.callsTo("updateCurrentUser"), [["uid-1"]]);
  assert.equal(ready.length, 1, "two listener calls, one page start");
  assert.equal(ready[0].user, fresh);
  assert.deepEqual(ready[0].resume, resume);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0, "no bounce back to Google");
  assert.equal($("app").hidden, false);
});

test("a return leg that came back without a sign-in marks the resume incomplete", async () => {
  const resume = returningFromRevokeStepUp();
  fake.redirectResult = null;
  const ready = await start(readyUser());
  assert.deepEqual(ready[0].resume, { ...resume, reauthFailed: "incomplete" });
});

test("a cancelled code on the return leg says so and marks the resume cancelled", async () => {
  const resume = returningFromRevokeStepUp();
  fake.redirectResult = fake.mfaError();
  codes.answer(null);
  const ready = await start(readyUser());
  assert.deepEqual(ready[0].resume, { ...resume, reauthFailed: "cancelled" });
  assert.equal($("status").textContent,
    "Sign-in cancelled — the authenticator code was not entered.");
});

test("a rejected code on the return leg reports Firebase's code and marks the resume with it", async () => {
  const resume = returningFromRevokeStepUp();
  fake.redirectResult = fake.mfaError({ reject: "auth/invalid-verification-code" });
  codes.answer("000000", null); // the wrong code, then Cancel instead of Try again
  const ready = await start(readyUser());
  assert.deepEqual(ready[0].resume, { ...resume, reauthFailed: "auth/invalid-verification-code" });
  assert.equal($("status").textContent, "Sign-in failed: auth/invalid-verification-code");
  assert.equal($("status").className, "muted err");
});

test("a sign-in that timed out before a code was accepted says to sign in again", async () => {
  fake.redirectResult = fake.mfaError({ reject: "auth/totp-challenge-timeout" });
  codes.answer("123456");
  await start(readyUser());
  assert.equal($("status").textContent, "That sign-in timed out before a code was accepted. Sign in again.");
  assert.equal($("status").className, "muted err");
});

test("enrolling fetches the QR code library then, and draws the code", async () => {
  await start(new FakeUser());
  await until(() => page.inserted.some((e) => e.className === "card enrol"), "the enrolment card");
  const card = page.inserted.find((e) => e.className === "card enrol");
  assert.match(card.querySelector(".qr").innerHTML, /^<svg/);
  assert.ok(!card.querySelector(".qr").hidden, "the picture is shown");
  card.querySelector(".cancel").click();
  await settle();
});

test("declining to enrol signs the account out: every dashboard needs 2FA", async () => {
  const ready = await start(new FakeUser());
  await until(() => page.inserted.some((e) => e.className === "card enrol"), "the enrolment card");
  const card = page.inserted.find((e) => e.className === "card enrol");
  card.querySelector(".cancel").click();
  await settle();
  assert.equal(ready.length, 0);
  assert.deepEqual(fake.callsTo("signOut"), [[]]);
  assert.equal(fake.auth.currentUser, null);
  assert.equal($("app").hidden, true);
  assert.equal($("status").textContent, "Two-factor authentication is required for every dashboard.");
});

test("a redirect that keeps failing stops with a message instead of looping", async () => {
  storage.setItem(REAUTH_STARTED, String(Date.now() - 20_000));
  const ready = await start(new FakeUser({ factors: [{}], secondFactor: false }));
  assert.equal(ready.length, 0);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0);
  assert.equal($("app").hidden, true);
  assert.equal($("status").textContent,
    "Re-authentication with Google did not complete. Sign out, then sign in again.");
  assert.equal($("status").className, "muted err");
});
