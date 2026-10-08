// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The second seat count on the operator desk (console/operator/verify.js):
// Verify asks the backend what an institution licence still entitles, the
// panel says it in words (each bucket and reason, the counter drifting), the
// Seats column picks up the verified number, and the panel asks again when
// that licence changes or the table reloads — and not otherwise.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, confirms, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, UNI, openDesk, rows, rowButton, tableRows, offline } from "./operator-desk.mjs";

await loadAuth();
const { when } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const RECONCILE = `GET /v1/admin/licenses/${UNI.id}/reconcile`;
const SEEN = "2026-10-01T10:00:00Z";

const REPORT = {
  intended: 3, intendedRecounted: 3, entitled: 2,
  counts: { revokedStillRunning: 1, notEntitled: 1 },
  seats: [
    { email: "a@uni.edu", status: "active", bucket: "active", reason: "" },
    { uid: "u2", status: "revoked", bucket: "revokedStillRunning", reason: "still_licensed", lastSeenAt: SEEN },
    { email: "c@uni.edu", status: "revoked", bucket: "revokedConfirmed", reason: "moved_on" },
    { email: "d@uni.edu", status: "active", bucket: "active", reason: "moved_on" },
    { email: "e@uni.edu", status: "disabled", bucket: "notEntitled", reason: "brand_new_reason" },
    { email: "f@uni.edu", status: "revoked", bucket: "revokedConfirmed", reason: "no_checkin_since_revoke" },
  ],
};

const summary = () => $("verifySummary").textContent.replace(/\s+/g, " ").trim();
const verifyRows = () => tableRows("verifyRows");
const pills = () => $("verifyRows").querySelectorAll(".pill").map((p) => p.className);
const seatsCell = () => rows().find((r) => r[0] === "SEMP-UNI1")[3];

async function click(button) {
  button.click();
  await settle();
}

test("Verify says what IT intends, what is entitled, and which revokes have not landed, seat by seat", async () => {
  const answer = deferred();
  await openDesk({ routes: { [RECONCILE]: () => answer.promise } });
  assert.equal($("verifyCard").hidden, true, "nothing is checked until asked: one lookup per seat");
  await click(rowButton("verify", UNI.id));
  assert.equal($("verifyCard").hidden, false);
  assert.equal($("verifyName").textContent, "SEMP-UNI1");
  assert.equal(summary(), "Checking every seat against its holder…");
  answer.resolve(json(200, REPORT));
  await settle();
  assert.equal(summary(),
    "IT's roster says 3 seats are in use. 2 accounts are actually entitled. " +
    "1 revoke has not landed yet — see below for which, and why. " +
    "1 seat is on the roster without entitling anyone — see below for why.");
  assert.deepEqual(verifyRows(), [
    ["a@uni.edu", "active", "On the roster and holding the licence.", "never"],
    ["u2", "revoked", "The revoke did not land — this account is still licensed. Revoke the seat again to repair it.",
      when(SEEN)],
    ["c@uni.edu", "revoked", "Revoked, and the account is on a different licence now.", "never"],
    ["d@uni.edu", "active", "The account is on a different licence now. Occupies a seat here; entitles nobody.", "never"],
    ["e@uni.edu", "disabled", "brand_new_reason", "never"],
    ["f@uni.edu", "revoked",
      "Revoked, but this account has not been back since. The device may still be running on the licence it cached.",
      "never"],
  ]);
  assert.deepEqual(pills(), ["pill ok", "pill warn", "pill off", "pill ok", "pill off", "pill off"]);
  assert.equal(seatsCell(), "1/5 in use · 4 on roster · 1 revoke not landed", "the second count beside the first");
  assert.deepEqual(sent(/reconcile/), [RECONCILE]);
});

test("all revokes landed, one of each, an older backend's count, and a drifted counter", async () => {
  await openDesk({ routes: { [RECONCILE]: () => json(200, {
    intended: 1, intendedRecounted: 1, entitled: 1, counts: {}, seats: [],
  }) } });
  await click(rowButton("verify", UNI.id));
  assert.equal(summary(), "IT's roster says 1 seat is in use. 1 account is actually entitled. Every revoke has landed.");
  assert.equal($("verifySummary").querySelector(".ok").textContent, "Every revoke has landed.");
  assert.deepEqual(verifyRows(), [["No seats on this licence yet."]]);
  assert.equal(seatsCell(), "1/5 in use · 4 on roster · 1 verified");

  reset();
  await openDesk({ routes: { [RECONCILE]: () => json(200, {
    intended: 4, intendedRecounted: 2, entitled: 0, counts: { revokedStillRunning: 2, neverClaimed: 2 },
  }) } });
  await click(rowButton("verify", UNI.id));
  assert.equal(summary(),
    "IT's roster says 4 seats are in use. 0 accounts are actually entitled. " +
    "2 revokes have not landed yet — see below for which, and why. " +
    "2 seats are on the roster without entitling anyone — see below for why. " +
    "seatsUsed says 4 but there are 2 live seats — the counter has drifted.");
  assert.equal(seatsCell(), "1/5 in use · 4 on roster · 2 revokes not landed");
});

test("a licence with no roster, or a check that fails, says so in the panel", async () => {
  for (const [reply, text] of [
    [() => json(409, { detail: "kind_not_institution" }),
      "This is an individual licence: one holder, no roster, so there are no two counts to compare."],
    [() => json(503, { detail: "unavailable" }), "Could not check the seats: unavailable"],
    [offline, "Could not check the seats: Failed to fetch"],
  ]) {
    reset();
    await openDesk({ routes: { [RECONCILE]: reply } });
    await click(rowButton("verify", UNI.id));
    assert.equal(summary(), text);
    assert.equal($("verifySummary").querySelector(".err").textContent, text);
    assert.equal(seatsCell(), "1/5 in use · 4 on roster", "no second count without an answer");
  }
});

test("Close hides the panel; Reload asks again; Reload with nothing opened asks nothing", async () => {
  await openDesk({ routes: { [RECONCILE]: () => json(200, REPORT) } });
  await click($("verifyReload"));
  assert.deepEqual(sent(/reconcile/), []);
  await click(rowButton("verify", UNI.id));
  await click($("verifyReload"));
  assert.deepEqual(sent(/reconcile/), [RECONCILE, RECONCILE]);
  await click($("verifyClose"));
  assert.equal($("verifyCard").hidden, true);
});

test("a change to the licence on the panel, or a table reload, re-checks; anything else does not", async () => {
  await openDesk({ routes: {
    [RECONCILE]: () => json(200, REPORT),
    [`PATCH /v1/admin/licenses/${IND.id}`]: () => json(200, IND),
    [`PATCH /v1/admin/licenses/${UNI.id}`]: () => json(200, { ...UNI, note: "renewal" }),
  } });
  await click(rowButton("verify", UNI.id));
  assert.equal(sent(/reconcile/).length, 1);

  // Another licence changed: the panel's answer still stands.
  confirms.answer(true);
  await click(rowButton("device", IND.id));
  assert.equal(sent(/reconcile/).length, 1);

  // This licence changed: exactly when to ask again.
  await click(rowButton("edit", UNI.id));
  $("editNote").value = "renewal";
  $("editForm").dispatch("submit");
  await settle();
  assert.equal(sent(/reconcile/).length, 2);

  // The whole table re-read.
  await click($("reload"));
  assert.equal(sent(/reconcile/).length, 3);

  // A closed panel is not re-checked.
  await click($("verifyClose"));
  await click($("reload"));
  assert.equal(sent(/reconcile/).length, 3);
});
