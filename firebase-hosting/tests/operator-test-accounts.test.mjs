// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's Test accounts card (console/operator/test-accounts.js):
// the create form's body (QC days, CI without an end, phones at once), the
// password shown once in its dialog and gone from the page and storage when
// it closes, each refusal, the list read on demand, Extend, New password and
// End now (typed address), the fresh step-up that leaves for Google carrying
// the change, and the return leg that finishes it or says it was not sent.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, json, net, confirms, prompts, sent, fake, storage, readyUser, $, loadAuth, deferred,
} from "./harness.mjs";
import { openDesk, status, tableRows, offline, bodyOf } from "./operator-desk.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const LIST = "GET /v1/admin/test-accounts";
const CREATE = "POST /v1/admin/test-accounts";
// 20 URL-safe characters, as the backend makes them; plainly a placeholder.
const ISSUED = "test-only-0000000000";
const ENDS = "2026-11-18T23:59:59Z";

const QC = {
  uid: "uid-qc-1", email: "qc-1@test.example", purpose: "qc", expiresAt: ENDS, maxDevices: 3,
  note: "QC rack", createdAt: "2026-10-19T10:00:00Z", activeDevices: 1, status: "active",
};
const CI = {
  uid: "uid-ci-1", email: "ci@test.example", purpose: "ci", expiresAt: null, maxDevices: 10,
  note: "", createdAt: "2026-10-01T10:00:00Z", activeDevices: 0, status: "active",
};

const created = (over = {}) => json(201, {
  uid: "uid-new", email: "new@test.example", password: ISSUED, purpose: "qc",
  expiresAt: ENDS, maxDevices: 3, licenseId: "lic-t1", ...over,
});
const listed = (accounts = [QC, CI]) => () => json(200, { accounts });

/** Fill the create form, as typed. */
function fill({ email = "new@test.example", purpose, days, maxDevices, note } = {}) {
  $("taEmail").value = email;
  if (purpose) {
    $("taPurpose").value = purpose;
    $("taPurpose").dispatch("change");
  }
  if (days !== undefined) $("taDays").value = days;
  if (maxDevices !== undefined) $("taMaxDevices").value = maxDevices;
  if (note !== undefined) $("taNote").value = note;
}

async function create() {
  $("taCreate").click();
  await settle();
}

const createBody = () => bodyOf(net, "POST", "/v1/admin/test-accounts");
const accountRows = () => tableRows("testAccountRows");
const rowButton = (kind, uid) => $("testAccountRows").querySelector(`button[data-ta-${kind}="${uid}"]`);
const dialog = () => $("taPasswordDialog");

/** Nothing the page keeps holds the password: storage, the status line, the dialog's text. */
function passwordForgotten() {
  for (const value of storage.map.values()) assert.ok(!value.includes(ISSUED), "not in sessionStorage");
  assert.ok(!$("status").textContent.includes(ISSUED), "not in the status line");
  assert.equal($("taPassword").textContent, "", "not in the page");
}

/** Open the list (Show), answering `accounts`. */
async function showList(accounts = [QC, CI], routes = {}) {
  await openDesk({ routes: { [LIST]: listed(accounts), ...routes } });
  $("loadTestAccounts").click();
  await settle();
}

/* ---------------------------------------------------------------- create */

test("a QC account is created with the form's values and its password is shown once", async () => {
  const answer = deferred();
  await openDesk({ routes: { [CREATE]: () => answer.promise, [LIST]: listed([QC]) } });
  assert.equal($("testAccountsWrap").hidden, true, "the list is read on demand");
  fill({ email: " new@test.example ", note: " Pixel 6 " });
  $("taCreate").click();
  await settle();
  assert.equal($("taCreate").disabled, true, "one click, one account");
  assert.deepEqual(status(), ["Creating the test account…", "muted"]);
  assert.deepEqual(createBody(),
    { email: "new@test.example", purpose: "qc", days: 30, maxDevices: 3, note: "Pixel 6",
      licence: "individual", apps: "both" },
    "a QC account names its licence: one per app, by default for both");

  answer.resolve(created());
  await settle();
  assert.equal($("taCreate").disabled, false);
  assert.equal(dialog().open, true);
  assert.equal($("taPassword").textContent, ISSUED);
  assert.equal($("taPasswordEmail").textContent, "new@test.example");
  assert.deepEqual(status(), [
    "Test account new@test.example created (QC): approved and Licensed for semper and semper Classroom, " +
    `a licence each (1 activated phone a licence), ends ${day(ENDS)}; up to 3 phones may sign in, ` +
    "for each app. Copy its password now.", "muted"]);
  assert.ok(!$("status").textContent.includes(ISSUED));
  assert.deepEqual([$("taEmail").value, $("taNote").value], ["", ""], "ready for the next one");
  assert.deepEqual(sent(/test-accounts/), [CREATE, LIST], "the list is read again");
  assert.equal($("testAccountsWrap").hidden, false);

  $("taPasswordClose").click();
  assert.equal(dialog().open, false);
  passwordForgotten();
  assert.equal($("taPasswordEmail").textContent, "");
});

test("closing the dialog with Escape forgets the password too", async () => {
  await openDesk({ routes: { [CREATE]: () => created(), [LIST]: listed() } });
  fill();
  await create();
  assert.equal($("taPassword").textContent, ISSUED);
  dialog().close();
  dialog().dispatch("close");
  passwordForgotten();
});

test("a CI account may run without an end; QC always has one", async () => {
  await openDesk({ routes: {
    [CREATE]: () => created({ purpose: "ci", expiresAt: null, maxDevices: 1 }), [LIST]: listed(),
  } });
  fill({ purpose: "ci", maxDevices: "1" });
  assert.equal($("taDays").value, "", "the untouched 30 is cleared for CI");
  assert.equal($("taDaysHint").textContent, "(1–90, or blank: never ends)");
  await create();
  assert.deepEqual(createBody(), { email: "new@test.example", purpose: "ci", days: null, maxDevices: 1 },
    "blank days is sent as null: no expiry");
  assert.match(status()[0], /created \(CI\): approved and Licensed, never ends; up to 1 phone may sign in, for each app/);
  assert.equal($("taQcFields").hidden, true, "the licence fields are QC's alone");

  $("taPurpose").value = "qc";
  $("taPurpose").dispatch("change");
  assert.equal($("taDays").value, "30", "back to QC, the default returns");
  assert.equal($("taDaysHint").textContent, "(1–90)");
  $("taDays").value = "12";
  $("taPurpose").value = "ci";
  $("taPurpose").dispatch("change");
  assert.equal($("taDays").value, "12", "a number typed is kept");
});

test("a form the backend would refuse is stopped before anything is sent", async () => {
  for (const [form, text] of [
    [{ email: "" }, "Enter the test account's email address."],
    [{ email: "not-an-address" }, "Enter the test account's email address."],
    [{ days: "" }, "Days is 1 to 90: a QC account always ends."],
    [{ days: "91" }, "Days is 1 to 90: a QC account always ends."],
    [{ purpose: "ci", days: "0" }, "Days is 1 to 90, or blank for a CI account that never ends."],
    [{ maxDevices: "11" }, "Phones at once is 1 to 10."],
    [{ note: "x".repeat(201) }, "The note is at most 200 characters."],
  ]) {
    reset();
    await openDesk();
    fill(form);
    await create();
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.deepEqual(sent(/test-accounts/), []);
  }
});

test("each refusal of a create is said, and no dialog opens", async () => {
  for (const [reply, text] of [
    [() => json(403, { detail: "test_accounts_disabled" }),
      "Test accounts are switched off on this server, or that address is not on one of its " +
      "test domains (TEST_ACCOUNT_DOMAINS)."],
    [() => json(409, { detail: "email_in_use" }),
      "That address already has a semper account or sign-in. A test account needs an address nobody has used."],
    [() => json(403, { detail: "mfa_required" }), "Set up two-factor authentication first."],
    [() => json(403, { detail: "not_admin" }), "That account is not a semper operator."],
    [() => json(422, { detail: [{ msg: "Value error, days must be at most 90", loc: ["body"] }] }),
      "Could not create new@test.example: days must be at most 90"],
    [offline, "Could not create new@test.example: The server could not be reached — check your connection and try again."],
  ]) {
    reset();
    await openDesk({ routes: { [CREATE]: reply } });
    fill();
    await create();
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal(dialog().open, false);
    assert.equal($("taCreate").disabled, false);
  }
});

/* ------------------------------------------------------------- step-up */

test("a stale sign-in goes to Google carrying the form, and nothing is sent", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }) });
  fill({ note: "rack" });
  await create();
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.equal($("status").textContent, "Re-authenticating with Google to create this test account…");
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), {
    action: "testAccountCreate",
    body: { email: "new@test.example", purpose: "qc", days: 30, maxDevices: 3, note: "rack",
      licence: "individual", apps: "both" },
  });
  assert.deepEqual(sent(/test-accounts/), []);
});

test("a password operator who cancels the step-up sends nothing", async () => {
  await openDesk({ user: readyUser({ providers: ["password"], authAgeSeconds: 600 }) });
  fill();
  prompts.answer(null);
  await create();
  assert.match(prompts.asked[0], /password to create this test account/);
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);
  assert.deepEqual(sent(/test-accounts/), []);
});

test("the return leg creates the stashed account after one confirmation", async () => {
  const body = { email: "new@test.example", purpose: "ci", days: null, note: "CI" };
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountCreate", body },
    routes: { [CREATE]: () => created({ purpose: "ci", expiresAt: null }), [LIST]: listed() },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. Create the test account new@test.example now?");
  assert.deepEqual(createBody(), body);
  assert.equal($("taPassword").textContent, ISSUED);
});

test("a return leg declined, malformed, or without a fresh sign-in sends nothing", async () => {
  confirms.answer(false);
  await openDesk({ resume: { action: "testAccountEnd", uid: CI.uid, email: CI.email } });
  await settle();
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);

  reset();
  await openDesk({ resume: { action: "testAccountExtend", uid: CI.uid, email: CI.email, days: 400 } });
  await settle();
  assert.equal(confirms.asked.length, 0, "a stash the form would refuse is dropped");

  reset();
  await openDesk({
    resume: { action: "testAccountPassword", uid: CI.uid, email: CI.email },
    redirect: null,
  });
  await settle();
  assert.deepEqual(status(), [
    "Did not give ci@test.example a new password: the Google sign-in did not finish. Try it again.",
    "muted err"]);
  assert.deepEqual(sent(/test-accounts/), []);
});

/* ---------------------------------------------------------------- list */

test("the list shows end, phones and status, purpose and note under the address, and a row per account", async () => {
  await showList([QC, CI, { ...QC, uid: "uid-old", email: "old@test.example", status: "ended", note: "<b>x</b>" }]);
  assert.equal($("loadTestAccounts").textContent, "Hide list");
  assert.equal($("loadTestAccounts").getAttribute("aria-expanded"), "true");
  assert.deepEqual(accountRows(), [
    ["qc-1@test.example", day(ENDS), "1 of 3", "active", "Extend More New password End now"],
    ["ci@test.example", "Never", "0 of 10", "active", "Extend More New password End now"],
  ], "live accounts first; the ended one is under Ended");
  // Purpose and note open under the address, as a licence's details do from its key.
  const toggle = () => $("testAccountRows").querySelector(`button[data-ta-details="${QC.uid}"]`);
  assert.equal(toggle().getAttribute("aria-expanded"), "false");
  toggle().click();
  assert.equal(toggle().getAttribute("aria-expanded"), "true");
  assert.equal(toggle().focused, true, "focus stays on the address");
  const details = $(`ta-details-${QC.uid}`).textContent.replace(/\s+/g, " ").trim();
  assert.match(details, /^PurposeQCNoteQC rackCreated.+Account iduid-qc-1$/);
  toggle().click();
  assert.equal($(`ta-details-${QC.uid}`), null);
  assert.equal($("taCount").textContent, "Showing 2 of 2.");
  $("taChips").querySelector('button[data-chip="all"]').click();
  assert.deepEqual(accountRows(), [
    ["qc-1@test.example", day(ENDS), "1 of 3", "active", "Extend More New password End now"],
    ["ci@test.example", "Never", "0 of 10", "active", "Extend More New password End now"],
    ["old@test.example", day(ENDS), "1 of 3", "ended", "Extend More New password End now"],
  ]);
  $("testAccountRows").querySelector('button[data-ta-details="uid-old"]').click();
  assert.match($("ta-details-uid-old").textContent, /<b>x<\/b>/, "the note, as text");
  assert.equal($("testAccountRows").querySelectorAll("b").length, 0, "a note is never markup");
  assert.equal($("testAccountRows").querySelectorAll("span.pill").map((s) => s.className).join(),
    "pill ok,pill ok,pill off");
});

test("the list's chips, filter and sort: Ended, a search over address, purpose and note, Ends soonest first", async () => {
  const soon = new Date(Date.now() + 2 * 86400e3).toISOString();
  await showList([QC, CI, { ...QC, uid: "uid-soon", email: "soon@test.example", expiresAt: soon, note: "Pixel 8" },
    { ...QC, uid: "uid-old", email: "old@test.example", status: "ended" }]);
  const chips = () => $("taChips").querySelectorAll("button").map((b) => b.textContent.replace(/\s+/g, " ").trim());
  assert.deepEqual(chips(), ["Live 3", "Ended 1", "All 4"]);
  $("taChips").querySelector('button[data-chip="ended"]').click();
  assert.deepEqual(accountRows().map((r) => r[0]), ["old@test.example"]);
  assert.equal(globalThis.document.activeElement.dataset.chip, "ended", "focus stays on the chip pressed");
  $("taChips").querySelector('button[data-chip="live"]').click();
  $("taFilter").value = "pixel";
  $("taFilter").dispatch("input");
  assert.deepEqual(accountRows().map((r) => r[0]), ["soon@test.example"], "the note is searched");
  assert.equal($("taCount").textContent, "1 of 3 match.");
  $("taFilter").value = "ci";
  $("taFilter").dispatch("input");
  assert.deepEqual(accountRows().map((r) => r[0]), ["ci@test.example"], "the purpose too");
  $("taFilter").value = "";
  $("taFilter").dispatch("input");
  $("taHead").querySelector('button[data-sort="ends"]').click();
  assert.deepEqual(accountRows().map((r) => r[0]), ["soon@test.example", "qc-1@test.example", "ci@test.example"],
    "about to end first; one that never ends, last");
  assert.equal($("taHead").querySelector('button[data-sort="ends"]').closest("th").getAttribute("aria-sort"), "ascending");
  $("taFilter").value = "nobody";
  $("taFilter").dispatch("input");
  assert.deepEqual(accountRows(), [["No test account matches that filter."]]);
});

test("with every test account ended, the live view says where they are", async () => {
  await showList([{ ...QC, status: "ended" }]);
  assert.deepEqual(accountRows(), [["No live test accounts. Press Ended to see the others."]]);
});

test("an empty list says so, and a failed one offers Retry", async () => {
  await showList([]);
  assert.deepEqual(accountRows(), [["No test accounts."]]);

  reset();
  let fail = true;
  await openDesk({ routes: {
    [LIST]: () => (fail ? json(403, { detail: "test_accounts_disabled" }) : json(200, { accounts: [QC] })),
  } });
  $("loadTestAccounts").click();
  await settle();
  assert.match(accountRows()[0][0], /^Test accounts are switched off on this server/);
  fail = false;
  $("testAccountRows").querySelector("button[data-retry]").click();
  await settle();
  assert.equal(accountRows().length, 1);
});

test("a late list answer is not drawn over a newer one", async () => {
  const first = deferred();
  let calls = 0;
  await openDesk({ routes: {
    [LIST]: () => (++calls === 1 ? first.promise : json(200, { accounts: [CI] })),
  } });
  $("loadTestAccounts").click();
  await settle();
  // Fold it while the first read is out, then open it again: a second read.
  $("loadTestAccounts").click();
  $("loadTestAccounts").click();
  await settle();
  first.resolve(json(200, { accounts: [QC] }));
  await settle();
  assert.deepEqual(accountRows().map((r) => r[0]), ["ci@test.example"]);
});

test("Show list and Hide list take turns, and every Show reads the list afresh", async () => {
  await showList([QC]);
  $("loadTestAccounts").click();
  assert.equal($("testAccountsWrap").hidden, true, "Hide list puts it away");
  assert.equal($("loadTestAccounts").textContent, "Show list");
  assert.equal($("loadTestAccounts").getAttribute("aria-expanded"), "false");
  assert.equal(sent(/test-accounts/).length, 1, "and reads nothing");
  $("loadTestAccounts").click();
  await settle();
  assert.equal($("testAccountsWrap").hidden, false);
  assert.equal($("loadTestAccounts").textContent, "Hide list");
  assert.equal(sent(/test-accounts/).length, 2, "Show list reads it again");
});

test("Show list sits at the right of the card heading, and the list opens above the create form", async () => {
  await openDesk();
  const toggle = $("loadTestAccounts");
  assert.ok(toggle.closest(".card-head"), "in the heading row, with the desk's other list actions");
  assert.equal(toggle.tagName, "BUTTON");
  assert.equal(toggle.getAttribute("aria-controls"), "testAccountsWrap");
  const order = $("testAccountsCard").children.map((c) => c.id).filter(Boolean);
  assert.ok(order.indexOf("testAccountsWrap") < order.indexOf("taCreateBox"),
    "the list opens right under the heading that controls it");
});

/* ------------------------------------------------------------- actions */

test("Extend asks for days, sends them, and reads the list again", async () => {
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(200, { expiresAt: ENDS }) });
  prompts.answer("45");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.equal(prompts.asked[0], "Days from today, 1 to 90. The account and its licence then end that many days from now.");
  assert.deepEqual(bodyOf(net, "POST", `/${QC.uid}/extend`), { days: 45 });
  assert.deepEqual(status(), [`qc-1@test.example now ends ${day(ENDS)}.`, "muted"]);
  assert.deepEqual(sent(/test-accounts/), [LIST, `POST /v1/admin/test-accounts/${QC.uid}/extend`, LIST]);
});

test("Extend without a new end in the answer, refused, out of range or cancelled", async () => {
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(200, {}) });
  prompts.answer("7");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.deepEqual(status(), ["qc-1@test.example extended to 7 days from today.", "muted"]);

  for (const answer of ["91", "1.5", "seven"]) {
    prompts.answer(answer);
    rowButton("extend", QC.uid).click();
    await settle();
    assert.deepEqual(status(), ["Days is 1 to 90 — nothing was changed.", "muted err"], answer);
  }
  prompts.answer(null);
  rowButton("extend", QC.uid).click();
  await settle();
  assert.equal(sent(/extend/).length, 1, "only the first was sent");

  reset();
  await showList([QC], { [`POST /v1/admin/test-accounts/${QC.uid}/extend`]: () => json(404, { detail: "user_not_found" }) });
  prompts.answer("7");
  rowButton("extend", QC.uid).click();
  await settle();
  assert.deepEqual(status(), ["Could not extend qc-1@test.example: No semper account has that address.", "muted err"]);
});

test("New password confirms, then shows the new one once", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}/password`;
  await showList([QC], { [`POST ${path}`]: () => json(200, { password: ISSUED }) });
  confirms.answer(false);
  rowButton("password", QC.uid).click();
  await settle();
  assert.deepEqual(sent(/password/), [], "declined: nothing sent");

  confirms.answer(true);
  rowButton("password", QC.uid).click();
  await settle();
  assert.match(confirms.asked[1], /The old one stops working at once, and every phone signed in with it is signed out/);
  assert.deepEqual(sent(/password/), [`POST ${path}`]);
  assert.equal(dialog().open, true);
  assert.equal($("taPassword").textContent, ISSUED);
  assert.deepEqual(status(), ["New password for qc-1@test.example; its phones are signed out. Copy it now.", "muted"]);
  $("taPasswordClose").click();
  passwordForgotten();
});

test("New password on a stale sign-in goes to Google with no password in the stash", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }), routes: { [LIST]: listed([QC]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  rowButton("password", QC.uid).click();
  await settle();
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")),
    { action: "testAccountPassword", uid: QC.uid, email: QC.email });
  assert.deepEqual(sent(/password/), []);
});

test("End now needs the address typed, then deletes and reads the list again", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}`;
  await showList([QC], { [`DELETE ${path}`]: () => new Response(null, { status: 204 }) });
  confirms.answer(true);
  prompts.answer("qc-1@test.example ".toUpperCase());
  rowButton("end", QC.uid).click();
  await settle();
  assert.match(confirms.asked[0], /its analyses and cloud backups are erased, and its sign-in is deleted\. Its audit rows are kept\./);
  assert.deepEqual(status(), ["Not ended — the address did not match.", "muted"]);
  assert.deepEqual(sent(/DELETE/), []);

  confirms.answer(true);
  prompts.answer("qc-1@test.example");
  rowButton("end", QC.uid).click();
  await settle();
  assert.equal(prompts.asked[1], "This cannot be undone.\n\nType qc-1@test.example to end this test account:");
  assert.deepEqual(sent(/DELETE/), [`DELETE ${path}`]);
  assert.deepEqual(status(), ["qc-1@test.example ended: suspended, its data erased and its sign-in deleted.", "muted"]);
  assert.equal(sent(/GET \/v1\/admin\/test-accounts/).length, 2);
});

test("the return leg of End now finishes it", async () => {
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountEnd", uid: QC.uid, email: QC.email },
    routes: { [`DELETE /v1/admin/test-accounts/${QC.uid}`]: () => new Response(null, { status: 204 }), [LIST]: listed([]) },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. End qc-1@test.example now?");
  assert.deepEqual(sent(/DELETE/), [`DELETE /v1/admin/test-accounts/${QC.uid}`]);
});

test("the return leg of Extend finishes it with the stashed days", async () => {
  const path = `/v1/admin/test-accounts/${QC.uid}/extend`;
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountExtend", uid: QC.uid, email: QC.email, days: 14 },
    routes: { [`POST ${path}`]: () => json(200, {}), [LIST]: listed([QC]) },
  });
  await settle();
  assert.equal(confirms.asked[0], "Re-authenticated. Extend qc-1@test.example to 14 days from today now?");
  assert.deepEqual(bodyOf(net, "POST", path), { days: 14 });
});

/* ------------------------------------------------------ release one phone */

const PHONES = {
  ...QC, activeDevices: 2, devices: [
    { deviceId: "and-phone-1", app: "semper", model: "Pixel 6", registeredAt: "2026-10-19T10:00:00Z" },
    { deviceId: "and-phone-<2>", app: "semperclassroom", model: "", registeredAt: null },
  ],
};
const RELEASE = `/v1/admin/test-accounts/${QC.uid}/devices/and-phone-1/release`;
const releaseButton = (deviceId) => $("testAccountRows").querySelector(
  `button[data-ta-release="${QC.uid}"][data-ta-device="${deviceId}"]`);

test("the list shows each active phone with its own Release", async () => {
  await showList([PHONES, CI]);
  // Model and app, the id under them (or the id alone, without a model), Release.
  assert.deepEqual(accountRows()[0].slice(2, 3), [
    "2 of 3 Pixel 6 semper and-phone-1 Release and-phone-<2> semper Classroom Release"]);
  assert.deepEqual(accountRows()[1].slice(2, 3), ["0 of 10"], "no phones, no buttons");
  const phones = $("testAccountRows").querySelectorAll("li");
  assert.equal(phones.length, 2, "one line per phone");
  assert.ok(releaseButton("and-phone-1"));
  assert.ok(releaseButton("and-phone-<2>"), "an id is text, never markup");
  assert.equal(releaseButton("and-phone-1").getAttribute("aria-label"), "Release Pixel 6 (and-phone-1)",
    "each Release names its phone to a screen reader");
  assert.equal(releaseButton("and-phone-1").className, "danger", "a release cannot be undone");
  assert.equal(rowButton("end", QC.uid).className, "danger");
  assert.equal(rowButton("extend", QC.uid).className, "secondary");
});

const phonesFold = (uid) => $("testAccountRows").querySelector(`details[data-ta-phones="${uid}"]`);

test("each row's buttons and its phone count name the account they act on", async () => {
  await showList([PHONES, CI]);
  const label = (kind, uid) => rowButton(kind, uid).getAttribute("aria-label");
  assert.deepEqual([label("extend", QC.uid), label("password", QC.uid), label("end", QC.uid)],
    ["Extend qc-1@test.example", "New password for qc-1@test.example", "End now: qc-1@test.example"]);
  assert.equal(label("end", CI.uid), "End now: ci@test.example", "every row its own (the visible words first: WCAG 2.5.3)");
  assert.equal(phonesFold(QC.uid).querySelector("summary").getAttribute("aria-label"),
    "2 of 3 phones for qc-1@test.example");
});

test("an account's phones fold under their count, folded at first", async () => {
  await showList([PHONES, CI]);
  const fold = phonesFold(QC.uid);
  assert.ok(fold, "the phones sit in a fold");
  assert.equal(fold.open, false, "a row is one line until its phones are asked for");
  assert.equal(fold.querySelector("summary").textContent.trim(), "2 of 3", "the count is the fold's label");
  assert.equal(fold.querySelectorAll("li").length, 2, "every phone, with its Release, is inside it");
  assert.equal(phonesFold(CI.uid), null, "no phones, no fold: the count alone");
});

test("phones unfolded stay unfolded when the list is read again", async () => {
  await showList([PHONES, CI], { [`POST ${RELEASE}`]: () => json(200, {}) });
  phonesFold(QC.uid).querySelector("summary").click();
  // The list is read again (a Release does this): still unfolded.
  $("loadTestAccounts").click();
  $("loadTestAccounts").click();
  await settle();
  assert.equal(phonesFold(QC.uid).open, true);
  // Folded again, it stays folded.
  phonesFold(QC.uid).open = true;
  phonesFold(QC.uid).querySelector("summary").click();
  $("loadTestAccounts").click();
  $("loadTestAccounts").click();
  await settle();
  assert.equal(phonesFold(QC.uid).open, false);
});

test("a phone registered before ADR-030 is labelled semper Classroom too", async () => {
  // devices/{id}.app is never rewritten, so it may still say materialtesting.
  await showList([{ ...QC, activeDevices: 1, devices: [
    { deviceId: "old-phone", app: "materialtesting", model: "Pixel 5", registeredAt: null },
  ] }]);
  assert.deepEqual(accountRows()[0].slice(2, 3), ["1 of 3 Pixel 5 semper Classroom old-phone Release"]);
});

test("Release confirms, sends, says so and reads the list again", async () => {
  await showList([PHONES], { [`POST ${RELEASE}`]: () => json(200, { ...QC, activeDevices: 1 }) });
  confirms.answer(false);
  releaseButton("and-phone-1").click();
  await settle();
  assert.deepEqual(sent(/release/), [], "declined: nothing sent");

  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.match(confirms.asked[1], /^Release Pixel 6 \(and-phone-1\) from qc-1@test\.example\?/);
  assert.match(confirms.asked[1], /cannot register for it again; its analyses stay\./);
  assert.deepEqual(sent(/test-accounts/), [LIST, `POST ${RELEASE}`, LIST]);
  assert.deepEqual(status(), [
    "Phone and-phone-1 released from qc-1@test.example: its place is free, and that phone " +
    "cannot register for this account again.", "muted"]);
});

test("a phone that is no longer active, or a cancelled step-up, is said", async () => {
  await showList([PHONES], { [`POST ${RELEASE}`]: () => json(404, { detail: "device_not_found" }) });
  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.deepEqual(status(), [
    "That phone is no longer one of this account's active phones. Refresh the list.", "muted err"]);

  reset();
  await openDesk({ user: readyUser({ providers: ["password"], authAgeSeconds: 600 }),
    routes: { [LIST]: listed([PHONES]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  prompts.answer(null);
  releaseButton("and-phone-1").click();
  await settle();
  assert.match(prompts.asked[0], /password to release this phone/);
  assert.deepEqual(status(), ["Cancelled — nothing was sent.", "muted"]);
  assert.deepEqual(sent(/release/), []);
});

test("Release on a stale sign-in goes to Google carrying the phone", async () => {
  await openDesk({ user: readyUser({ authAgeSeconds: 600 }), routes: { [LIST]: listed([PHONES]) } });
  $("loadTestAccounts").click();
  await settle();
  confirms.answer(true);
  releaseButton("and-phone-1").click();
  await settle();
  assert.equal($("status").textContent, "Re-authenticating with Google to release this phone…");
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), {
    action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email,
    deviceId: "and-phone-1", app: "semper",
  });
  assert.deepEqual(sent(/release/), []);
});

test("the return leg of Release finishes it, and a stash without a phone is dropped", async () => {
  confirms.answer(true);
  await openDesk({
    resume: { action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email,
      deviceId: "and-phone-1", app: "semper" },
    routes: { [`POST ${RELEASE}`]: () => json(200, QC), [LIST]: listed([QC]) },
  });
  await settle();
  assert.equal(confirms.asked[0],
    "Re-authenticated. Release phone and-phone-1 from qc-1@test.example now?");
  assert.deepEqual(sent(/release/), [`POST ${RELEASE}`]);

  reset();
  await openDesk({ resume: { action: "testAccountDeviceRelease", uid: QC.uid, email: QC.email } });
  await settle();
  assert.equal(confirms.asked.length, 0);
  assert.deepEqual(sent(/release/), []);
});

/* ------------------------------------------------------------------ copy */

test("Copy puts the password on the clipboard, or says to copy it by hand", async () => {
  const had = Object.getOwnPropertyDescriptor(navigator, "clipboard");
  const written = [];
  let ok = true;
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: { writeText: (text) => { written.push(text); return ok ? Promise.resolve() : Promise.reject(new Error("no")); } },
  });
  try {
    await openDesk({ routes: { [CREATE]: () => created(), [LIST]: listed() } });
    fill();
    await create();
    $("taCopyPassword").click();
    await settle();
    assert.deepEqual(written, [ISSUED]);
    assert.deepEqual(status(), ["Password copied.", "muted"]);
    ok = false;
    $("taCopyPassword").click();
    await settle();
    assert.deepEqual(status(), ["Copy failed — select and copy it by hand.", "muted err"]);
  } finally {
    if (had) Object.defineProperty(navigator, "clipboard", had);
    else delete navigator.clipboard;
  }
});

// AE2-105: Show list answered 401 invalid_token. The page signed out and
// hid the desk, with the reason only in a row of the hidden list.
test("an expired sign-in on Show list says so on the status line", async () => {
  await openDesk({ routes: { [LIST]: () => json(401, { detail: "invalid_token" }) } });
  $("loadTestAccounts").click();
  await settle();
  assert.deepEqual(status(), ["Your sign-in has expired. Sign in again.", "muted err"]);
  assert.equal($("app").hidden, true);
  assert.equal($("signIn").hidden, false);
});
