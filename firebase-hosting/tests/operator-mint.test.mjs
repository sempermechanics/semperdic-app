// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's Issue card (console/operator/mint.js): the fields each
// kind and term shows, the POST it builds, what the status line says the mint
// did (attached, waiting, or not delivered and why), the one-licence-per-person
// refusal that puts the held licence on screen, each other refusal, and Copy.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, UNI, openDesk, status, labels, offline, bodyOf } from "./operator-desk.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const MINT = "POST /v1/admin/licenses";
const NEW = { id: "lic-new-1", keyPrefix: "SEMP-NEW1", kind: "individual", mode: "licensed", status: "issued", duration: "perpetual" };

const radio = (value) => document.querySelector(`input[name="kind"][value="${value}"]`);

/** Tick a kind, as a person clicking the radio: the other unticks, then change fires. */
function pickKind(value) {
  for (const r of document.querySelectorAll('input[name="kind"]')) r.checked = r.value === value;
  radio(value).dispatch("change");
}

function pickDuration(value) {
  $("duration").value = value;
  $("duration").dispatch("change");
}

async function mint() {
  $("mint").click();
  await settle();
}

const mintBody = () => bodyOf(net, "POST", "/v1/admin/licenses");

/* ---------------------------------------------------------------- fields */

test("the kind shows its own fields, and the term enables the dates only when timed", async () => {
  await openDesk();
  assert.equal($("individualFields").hidden, false);
  assert.equal($("institutionFields").hidden, true);
  pickKind("institution");
  assert.equal($("individualFields").hidden, true);
  assert.equal($("institutionFields").hidden, false);
  pickKind("individual");
  assert.equal($("institutionFields").hidden, true);

  assert.equal($("expiresAt").disabled, true, "perpetual by default");
  pickDuration("timed");
  assert.equal($("expiresAt").disabled, false);
  assert.equal($("graceDays").disabled, false);
  $("expiresAt").value = "2027-06-30";
  $("graceDays").value = "7";
  pickDuration("perpetual");
  assert.deepEqual([$("expiresAt").disabled, $("graceDays").disabled], [true, true]);
  assert.deepEqual([$("expiresAt").value, $("graceDays").value], ["", ""], "a perpetual term carries no date");
});

test("a time-limited licence without an expiry is refused before anything is sent", async () => {
  await openDesk();
  pickDuration("timed");
  await mint();
  assert.deepEqual(status(), ["A time-limited licence needs an expiry date.", "muted err"]);
  assert.deepEqual(sent(/POST/), []);
  assert.equal($("mint").disabled, false);
});

/* ------------------------------------------------------------ individual */

test("an individual mint sends only what was filled, shows the key once, and reads the new row", async () => {
  const answer = deferred();
  await openDesk({ routes: {
    [MINT]: () => answer.promise,
    [`GET /v1/admin/licenses/${NEW.id}`]: () => json(200, NEW),
  } });
  $("emailLock").value = " new@lab.org ";
  $("note").value = "PO 1";
  $("mint").click();
  await settle();
  assert.equal($("mint").disabled, true, "one click, one licence");
  assert.deepEqual(status(), ["Issuing…", "muted"]);
  assert.deepEqual(mintBody(), { kind: "individual", duration: "perpetual", note: "PO 1", emailLock: "new@lab.org" },
    "empty fields (expiry, grace, cap) are left out, not sent as null");

  answer.resolve(json(200, { key: "SEMP-NEW1-ABCD-EFGH", license: { id: NEW.id }, claimedByUid: "u9" }));
  await settle();
  assert.equal($("mint").disabled, false);
  assert.equal($("mintedBox").hidden, false);
  assert.equal($("mintedKey").textContent, "SEMP-NEW1-ABCD-EFGH");
  assert.deepEqual(status(), ["Issued and attached to new@lab.org — licensed from their next request.", "muted"]);
  assert.deepEqual(sent(/lic-new-1/), [`GET /v1/admin/licenses/${NEW.id}`], "read back, not taken from the mint's answer");
  assert.deepEqual(labels(), ["SEMP-NEW1", "SEMP-IND1", "SEMP-UNI1"], "the new licence heads the table");
});

test("a timed mint ends at the last second of the chosen day, with its grace and cap as numbers", async () => {
  await openDesk({ routes: { [MINT]: () => json(200, {}) } });
  pickDuration("timed");
  $("expiresAt").value = "2027-06-30";
  $("graceDays").value = "7";
  $("maxAnalyses").value = "100";
  await mint();
  assert.deepEqual(mintBody(), {
    kind: "individual", duration: "timed", expiresAt: "2027-06-30T23:59:59Z", graceDays: 7, maxAnalyses: 100,
  });
  assert.equal($("mintedBox").hidden, true, "no key in the answer, nothing to copy");
  assert.deepEqual(status(), ["Issued. It attaches when that address first signs in.", "muted"]);
});

test("every way an individual mint did not reach the person is said, as an error", async () => {
  for (const [out, text, isError] of [
    [{}, "Issued. It attaches when pat@new.org first signs in.", false],
    [{ inviteError: "invite_exists" },
      "Issued, but pat@new.org is already promised another licence — this one will not attach. " +
      "Revoke whichever of the two is not wanted.", true],
    [{ inviteError: "write_failed" },
      "Issued, but the invite for pat@new.org failed (write_failed) — it will not attach at sign-in. " +
      "The key below still redeems it.", true],
    [{ claimError: "holder_already_licensed" },
      "Issued, but pat@new.org already holds a live licence, so this one was not attached. " +
      "Revoke or extend the other one.", true],
    [{ claimError: "claim_contended" },
      "Issued, but it could not be attached to pat@new.org (claim_contended). The key below redeems it.", true],
  ]) {
    reset();
    await openDesk({ routes: { [MINT]: () => json(200, { key: "K", ...out }) } });
    $("emailLock").value = "pat@new.org";
    await mint();
    assert.deepEqual(status(), [text, isError ? "muted err" : "muted"], JSON.stringify(out));
    assert.equal($("mintedKey").textContent, "K", "the key is shown whatever happened to the delivery");
  }
});

/* ----------------------------------------------------------- institution */

test("an institution mint sends the domain, IT contacts, seats and seating", async () => {
  await openDesk({ routes: {
    [MINT]: () => json(200, { key: "SEMP-UNI9-KEY", license: { id: "lic-uni-9" } }),
    "GET /v1/admin/licenses/lic-uni-9": () => json(200, { ...UNI, id: "lic-uni-9", keyPrefix: "SEMP-UNI9" }),
  } });
  pickKind("institution");
  $("emailLock").value = "ignored@lab.org";
  $("domainLock").value = "uni.edu";
  $("adminEmails").value = " it@uni.edu, ops@uni.edu ,, ";
  $("maxSeats").value = "20";
  $("seating").value = "floating";
  await mint();
  assert.deepEqual(mintBody(), {
    kind: "institution", duration: "perpetual", domainLock: "uni.edu",
    adminEmails: ["it@uni.edu", "ops@uni.edu"], maxSeats: 20, seating: "floating",
  }, "no email lock on an institution licence");
  assert.deepEqual(status(), [
    "Institution licence issued. People with a verified uni.edu address can redeem the key below, " +
    "or IT adds them from the roster.", "muted",
  ]);
  assert.equal($("mintedKey").textContent, "SEMP-UNI9-KEY");
  assert.equal(labels()[0], "SEMP-UNI9");
});

test("an institution mint with no domain says 'domain' and sends an empty contact list", async () => {
  await openDesk({ routes: { [MINT]: () => json(200, {}) } });
  pickKind("institution");
  await mint();
  assert.deepEqual(mintBody(), { kind: "institution", duration: "perpetual", adminEmails: [], seating: "assigned" });
  assert.match($("status").textContent, /^Institution licence issued\. People with a verified domain address/);
});

/* -------------------------------------------------------------- refusals */

test("an address that already holds a licence: nothing issued, and that licence is put on screen", async () => {
  const held = { ...IND, id: "lic-held-1", keyPrefix: "SEMP-HELD", emailLock: "pat@lab.org" };
  await openDesk({ licenses: [UNI], routes: {
    [MINT]: () => json(409, { detail: `email_already_licensed: ${held.id}` }),
    [`GET /v1/admin/licenses/${held.id}`]: () => json(200, held),
    "GET /v1/admin/licenses?limit=50&include_revoked=false&q=pat%40lab.org": () => json(200, { licenses: [held] }),
  } });
  $("emailLock").value = "pat@lab.org";
  await mint();
  assert.deepEqual(status(), [
    "Not issued: pat@lab.org already has a live licence (shown below). To renew it, use Edit. " +
    "To replace it, revoke it first, then issue again.", "muted err",
  ]);
  assert.equal($("filter").value, "pat@lab.org", "the filter is the address: it may be an institution seat");
  assert.deepEqual(sent(/held|q=/), [
    `GET /v1/admin/licenses/${held.id}`, "GET /v1/admin/licenses?limit=50&include_revoked=false&q=pat%40lab.org",
  ]);
  assert.deepEqual(labels(), ["SEMP-HELD"], "only the held licence matches the filter");
  assert.equal($("mintedBox").hidden, true);
  assert.equal($("mint").disabled, false);
});

test("each other refused mint is explained, and the button comes back", async () => {
  for (const [reply, text] of [
    [() => json(403, { detail: "mfa_required" }), "Enrol a second factor before issuing licences."],
    [() => json(429, { detail: "rate_limited" }), "Too many admin calls just now — wait a moment."],
    [() => json(403, { detail: "not_admin" }), "That account is not a Semper operator."],
    [() => json(422, { detail: [{ msg: "Value error, maxAnalyses must be at least 25", loc: ["body"] }] }),
      "Could not issue: maxAnalyses must be at least 25"],
    [() => json(409, { detail: "email_already_licensed" }), "Could not issue: email_already_licensed"],
    [() => new Response("<html>bad gateway</html>", { status: 502 }), "Could not issue: http_502"],
    [offline, "Could not issue: Failed to fetch"],
  ]) {
    reset();
    await openDesk({ routes: { [MINT]: reply } });
    $("emailLock").value = "pat@new.org";
    await mint();
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal($("mint").disabled, false);
    assert.equal($("mintedBox").hidden, true);
  }
});

test("a licence issued whose row cannot be re-read says so instead", async () => {
  await openDesk({ routes: {
    [MINT]: () => json(200, { key: "K", license: { id: NEW.id } }),
    [`GET /v1/admin/licenses/${NEW.id}`]: () => json(503, { detail: "unavailable" }),
  } });
  $("emailLock").value = "new@lab.org";
  await mint();
  assert.deepEqual(status(), ["Changed, but the row could not be re-read: unavailable. Refresh to see it.", "muted err"]);
  assert.equal($("mintedKey").textContent, "K", "the key is still on screen to copy");
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

/* ------------------------------------------------------------------ copy */

/** Run `fn` with `navigator.clipboard.writeText` answering `write`. */
async function withClipboard(write, fn) {
  const had = Object.getOwnPropertyDescriptor(navigator, "clipboard");
  const written = [];
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: (text) => { written.push(text); return write(text); } },
  });
  try {
    await fn(written);
  } finally {
    if (had) Object.defineProperty(navigator, "clipboard", had);
    else delete navigator.clipboard;
  }
}

test("Copy puts the key on the clipboard, or says to copy it by hand", async () => {
  await withClipboard(() => Promise.resolve(), async (written) => {
    await openDesk({ routes: { [MINT]: () => json(200, { key: "SEMP-COPY-ME" }) } });
    await mint();
    $("copyKey").click();
    await settle();
    assert.deepEqual(written, ["SEMP-COPY-ME"]);
    assert.deepEqual(status(), ["Key copied.", "muted"]);
  });
  await withClipboard(() => Promise.reject(new Error("denied")), async () => {
    $("copyKey").click();
    await settle();
    assert.deepEqual(status(), ["Copy failed — select and copy it by hand.", "muted err"]);
  });
});
