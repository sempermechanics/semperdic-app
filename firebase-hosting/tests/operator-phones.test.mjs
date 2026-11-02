// Run: node --test "firebase-hosting/tests/*.test.mjs"
// A licence's phones and Issue upgrade on the operator desk (ADR-033;
// console/operator/phones.js, upgrade.js): the phones dialog's list, each
// Deactivate confirmed first, Add phone within the count, past it, and the
// limit's refusal; then an upgrade issued, waiting for the holder and applied
// now, one already waiting, one for a holder who never signed in, and the
// refusals of each.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, confirms, sent, $, loadAuth, deferred } from "./harness.mjs";
import { APP_IND, APP_UNI, openDesk, status, rows, rowButton, bodyOf, offline } from "./operator-desk.mjs";

await loadAuth();
const { when } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const AT = "2026-10-20T09:30:00Z";
const DEVICES = `GET /v1/admin/licenses/${APP_IND.id}/devices`;
const PIXEL = "and-pixel-0001";
const GALAXY = "and-galaxy-0002";
const phone = (deviceId, over = {}) => ({
  deviceId, uid: "u1", app: "semper", status: "active", activatedAt: AT, activatedBy: "self",
  deactivatedAt: null, deactivatedBy: "", deviceModel: "Pixel 6", ...over,
});
const listing = (devices, over = {}) => json(200, {
  licenseId: APP_IND.id, app: "semper", devicesActive: devices.length, maxDevices: 2, pool: false,
  model: "activation", devices, ...over,
});

const hint = () => [$("phonesHint").textContent, $("phonesHint").className];
const phoneLines = () => $("phonesList").querySelectorAll("li")
  .map((li) => li.textContent.replace(/\s+/g, " ").trim());
const deactivateButton = (id) => $("phonesList").querySelector(`button[data-deactivate="${id}"]`);

async function openPhones(lic, routes) {
  await openDesk({ licenses: [lic], routes });
  rowButton("phones", lic.id).click();
  await settle();
}

/* ---------------------------------------------------------------- list */

test("Phones lists each active phone: model, id, when and by whom it was activated", async () => {
  await openPhones(APP_IND, {
    [DEVICES]: () => listing([phone(PIXEL), phone(GALAXY, { deviceModel: "", activatedBy: "staff" })]),
  });
  assert.equal($("phonesDialog").open, true);
  assert.equal($("phonesName").textContent, "SEMP-APP1");
  assert.deepEqual([$("phonesApp").textContent, $("phonesApp").hidden], ["semper", false]);
  assert.equal($("phonesCount").textContent, "2 of 2 phones active.");
  assert.deepEqual(phoneLines(), [
    `Pixel 6 ${PIXEL} Activated ${when(AT)} by the holder Deactivate`,
    `Unknown model ${GALAXY} Activated ${when(AT)} by semper staff Deactivate`,
  ]);
  assert.equal(deactivateButton(PIXEL).getAttribute("aria-label"), `Deactivate Pixel 6 (${PIXEL})`);
  assert.equal(rowButton("phones", APP_IND.id).textContent, "Phones 2/2", "the row follows the count read");
  $("phonesClose").click();
  assert.equal($("phonesDialog").open, false);
});

test("an institution's phones name each member, and the count is the licence's pool", async () => {
  const devices = `GET /v1/admin/licenses/${APP_UNI.id}/devices`;
  await openPhones(APP_UNI, {
    [devices]: () => json(200, {
      licenseId: APP_UNI.id, app: "semperclassroom", devicesActive: 1, maxDevices: 1000000, pool: true,
      model: "activation", devices: [phone(PIXEL, { app: "semperclassroom", email: "ben@uni.edu", activatedBy: "legacy" })],
    }),
  });
  assert.equal($("phonesCount").textContent, "1 of ∞ phone active, a pool across the whole licence.");
  assert.deepEqual(phoneLines(), [
    `Pixel 6 ben@uni.edu ${PIXEL} Activated ${when(AT)} by the app itself (a build without Activate) Deactivate`,
  ]);
  assert.equal(rowButton("phones", APP_UNI.id).textContent, "Phones 1/10");
});

test("no phones, and a list that cannot be read, are said in the dialog", async () => {
  await openPhones(APP_IND, { [DEVICES]: () => listing([]) });
  assert.deepEqual(phoneLines(), ["No phone is active on this licence."]);
  assert.equal($("phonesCount").textContent, "0 of 2 phones active.");

  for (const [reply, text] of [
    [() => json(404, { detail: "license_not_found" }), "This licence no longer exists. Refresh the table."],
    [offline, "Could not read the phones: The server could not be reached — check your connection and try again."],
  ]) {
    reset();
    await openPhones(APP_IND, { [DEVICES]: reply });
    assert.deepEqual(phoneLines(), [text]);
    assert.equal($("phonesCount").textContent, "");
  }
});

/* ---------------------------------------------------------- deactivate */

test("Deactivate asks first, signs the phone out, and reads the list again", async () => {
  let listed = [phone(PIXEL, { email: "" })];
  await openPhones(APP_IND, {
    [DEVICES]: () => listing(listed),
    [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]: () => {
      listed = [];
      return json(200, { licenseId: APP_IND.id, deviceId: PIXEL, app: "semper", uid: "u1",
        devicesActive: 0, maxDevices: 2, released: true });
    },
  });
  confirms.answer(false);
  deactivateButton(PIXEL).click();
  await settle();
  assert.match(confirms.asked[0], new RegExp(`^Deactivate Pixel 6 \\(${PIXEL}\\) on SEMP-APP1\\?`));
  assert.match(confirms.asked[0], /signed out of the holder's account and held off for 24 hours/);
  assert.equal(confirms.cards[0].parentNode, $("phonesHint").parentNode, "the card is inside the modal dialog");
  assert.deepEqual(sent(/DELETE/), [], "declined: nothing sent");

  confirms.answer(true);
  deactivateButton(PIXEL).click();
  await settle();
  assert.deepEqual(sent(/DELETE/), [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]);
  assert.deepEqual(hint(), [`Pixel 6 (${PIXEL}) deactivated and signed out: its place is free. 0 of 2 active.`, "muted"]);
  assert.deepEqual(phoneLines(), ["No phone is active on this licence."]);
  assert.equal(rowButton("phones", APP_IND.id).textContent, "Phones 0/2");
});

test("a phone already gone is said, and the list is read again", async () => {
  await openPhones(APP_IND, {
    [DEVICES]: () => listing([phone(PIXEL)]),
    [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]: () => json(404, { detail: "activation_not_found" }),
  });
  confirms.answer(true);
  deactivateButton(PIXEL).click();
  await settle();
  assert.deepEqual(hint(), ["That phone is no longer active on this licence. The list has been read again.", "err"]);
  assert.equal(sent(/devices$/).length, 2, "read on open, and again after the refusal");
});

test("Deactivate is held from the first click until the DELETE answers: a second click sends nothing", async () => {
  let deletes = 0;
  await openPhones(APP_IND, {
    [DEVICES]: () => listing(deletes ? [] : [phone(PIXEL)]),
    [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]: () => (deletes++
      ? json(404, { detail: "activation_not_found" })
      : json(200, { devicesActive: 0, maxDevices: 2, released: true })),
  });
  confirms.answer(true, true);
  const button = deactivateButton(PIXEL);
  button.click();
  assert.equal(button.disabled, true, "disabled while its card is open");
  button.click();
  await settle();
  assert.equal(confirms.cards.length, 1, "one card");
  assert.deepEqual(sent(/DELETE/), [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]);
  assert.deepEqual(hint(), [`Pixel 6 (${PIXEL}) deactivated and signed out: its place is free. 0 of 2 active.`, "muted"],
    "no second answer overwrites the success");
});

test("a declined or refused Deactivate gives the button back", async () => {
  await openPhones(APP_IND, {
    [DEVICES]: () => listing([phone(PIXEL)]),
    [`DELETE /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]: () => json(503, { detail: "unavailable" }),
  });
  confirms.answer(false);
  deactivateButton(PIXEL).click();
  await settle();
  assert.equal(deactivateButton(PIXEL).disabled, false, "declined");
  confirms.answer(true);
  deactivateButton(PIXEL).click();
  await settle();
  assert.equal(hint()[1], "err");
  assert.equal(deactivateButton(PIXEL).disabled, false, "refused");
  assert.equal(sent(/DELETE/).length, 1);
});

/* ----------------------------------------------------------------- add */

const ADD = `POST /v1/admin/licenses/${APP_IND.id}/devices/${GALAXY}`;

test("Add phone activates a phone the holder registered, within the count", async () => {
  let listed = [phone(PIXEL)];
  const answer = deferred();
  await openPhones(APP_IND, { [DEVICES]: () => listing(listed), [ADD]: () => answer.promise });
  $("phonesAdd").click();
  await settle();
  assert.deepEqual(hint(), ["Enter the phone's id.", "err"]);
  assert.deepEqual(sent(/POST/), []);

  $("phonesAddId").value = ` ${GALAXY} `;
  $("phonesAdd").click();
  await settle();
  assert.equal($("phonesAdd").disabled, true, "one click, one activation");
  assert.deepEqual(bodyOf(net, "POST", `/devices/${GALAXY}`), { override: false });
  listed = [phone(PIXEL), phone(GALAXY, { activatedBy: "staff" })];
  answer.resolve(json(200, { licenseId: APP_IND.id, app: "semper", deviceId: GALAXY, already: false,
    devicesActive: 2, maxDevices: 2, model: "activation", pool: false }));
  await settle();
  assert.equal($("phonesAdd").disabled, false);
  assert.deepEqual(hint(), [`${GALAXY} added: 2 of 2 active.`, "muted"]);
  assert.equal($("phonesAddId").value, "");
  assert.equal(phoneLines().length, 2);
});

test("Add phone past the count is sent with override, and said so", async () => {
  await openPhones(APP_IND, {
    [DEVICES]: () => listing([phone(PIXEL), phone("and-third-0003")]),
    [ADD]: () => json(200, { licenseId: APP_IND.id, deviceId: GALAXY, already: false,
      devicesActive: 3, maxDevices: 2, model: "activation" }),
  });
  $("phonesAddId").value = GALAXY;
  $("phonesOverride").checked = true;
  $("phonesAdd").click();
  await settle();
  assert.deepEqual(bodyOf(net, "POST", `/devices/${GALAXY}`), { override: true });
  assert.deepEqual(hint(), [`${GALAXY} added: 3 of 2 active, past the count.`, "muted"]);
  assert.equal($("phonesOverride").checked, false, "the override is for that one phone");
});

test("a full licence refuses Add with its count and the way past it; other refusals in words", async () => {
  for (const [reply, text] of [
    [() => json(409, { detail: "device_limit_reached: 2/2" }),
      "Not added: every place on this licence is taken (2 of 2 active). Deactivate a phone first, or tick Past the count."],
    [() => json(409, { detail: "device_limit_reached" }),
      "Not added: every place on this licence is taken. Deactivate a phone first, or tick Past the count."],
    [() => json(404, { detail: "device_not_found" }),
      "No phone with that id is signed in to this licence's holder for its app. Check the id; the holder signs in on the phone first."],
    [() => json(409, { detail: "license_seat_disabled" }), "That member's seat is paused. Resume it on the roster first."],
    [() => json(409, { detail: "brand_new_refusal" }), "Not added: the server refused it (ref: brand_new_refusal)"],
  ]) {
    reset();
    await openPhones(APP_IND, { [DEVICES]: () => listing([phone(PIXEL)]), [ADD]: reply });
    $("phonesAddId").value = GALAXY;
    $("phonesAdd").click();
    await settle();
    assert.deepEqual(hint(), [text, "err"], text);
    assert.equal($("phonesAddId").value, GALAXY, "kept for another try");
  }
});

test("an already active phone is a success; an answer for a dialog since closed is not drawn", async () => {
  await openPhones(APP_IND, {
    [DEVICES]: () => listing([phone(PIXEL)]),
    [`POST /v1/admin/licenses/${APP_IND.id}/devices/${PIXEL}`]: () => json(200, { already: true, devicesActive: 1, maxDevices: 2 }),
  });
  $("phonesAddId").value = PIXEL;
  $("phonesAdd").click();
  await settle();
  assert.deepEqual(hint(), [`${PIXEL} was already active on this licence.`, "muted"]);

  reset();
  const answer = deferred();
  await openPhones(APP_IND, { [DEVICES]: () => answer.promise });
  $("phonesClose").click();
  $("phonesDialog").dispatch("close");
  answer.resolve(listing([phone(PIXEL)]));
  await settle();
  assert.deepEqual(phoneLines(), [], "nothing drawn into a closed dialog");
});

/* ------------------------------------------------------------- upgrade */

const UPGRADE = `POST /v1/admin/licenses/${APP_IND.id}/upgrade`;
const NEW = { ...APP_IND, id: "lic-up-1", keyPrefix: "SEMP-UP01", status: "unused", maxDevices: 3, devicesActive: 0 };
const APPLY = `POST /v1/admin/licenses/${NEW.id}/apply-upgrade`;
const upHint = () => [$("upgradeHint").textContent, $("upgradeHint").className];

async function openUpgrade(routes = {}) {
  await openDesk({ licenses: [APP_IND], routes });
  rowButton("upgrade", APP_IND.id).click();
  await settle();
}

test("Issue upgrade sends what changes, shows the code once, and offers Apply now while it waits", async () => {
  await openUpgrade({
    [UPGRADE]: () => json(200, { key: "SEMP-UP01-KEY", license: NEW, supersededId: APP_IND.id,
      holderUid: "u1", pending: true }),
    [APPLY]: () => json(200, { licenseId: NEW.id, supersededId: APP_IND.id, activation: null }),
    [`GET /v1/admin/licenses/${APP_IND.id}`]: () => json(200, { ...APP_IND, status: "revoked", supersededBy: NEW.id }),
    [`GET /v1/admin/licenses/${NEW.id}`]: () => json(200, { ...NEW, status: "redeemed" }),
  });
  assert.equal($("upgradeDialog").open, true);
  assert.equal($("upgradeName").textContent, "SEMP-APP1");
  assert.equal($("upgradeMaxDevices").placeholder, "2", "what carries over, shown");
  $("upgradeMaxDevices").value = "3";
  $("upgradePerpetual").checked = true;
  $("upgradePerpetual").dispatch("change");
  assert.equal($("upgradeExpiry").disabled, true);
  $("upgradeNote").value = " more phones ";
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(bodyOf(net, "POST", "/upgrade"), { kind: "individual", maxDevices: 3, perpetual: true, note: "more phones" });
  assert.equal($("upgradedKey").textContent, "SEMP-UP01-KEY");
  assert.equal($("upgradedBox").hidden, false);
  assert.deepEqual([$("upgradeFields").hidden, $("upgradeSave").hidden, $("upgradeApply").hidden],
    [true, true, false]);
  assert.equal($("upgradeCancel").textContent, "Done");
  assert.deepEqual(upHint(), [
    "Upgrade issued, waiting for the holder: SEMP-APP1 keeps working until they tap Activate on SEMP-UP01 " +
    "in the app, which revokes SEMP-APP1. Apply now moves them without a phone, for an app that has no Activate.",
    "muted",
  ]);
  assert.deepEqual(status(), ["Upgrade for SEMP-APP1 issued as SEMP-UP01.", "muted"]);
  assert.ok(rows().some((r) => r[0] === "SEMP-UP01"), "the new licence is on the table");

  confirms.answer(true);
  $("upgradeApply").click();
  await settle();
  assert.match(confirms.asked[0], /^Move the holder of SEMP-APP1 onto SEMP-UP01 now\?/);
  assert.deepEqual(sent(/apply-upgrade/), [APPLY]);
  assert.deepEqual(upHint(), ["Applied: the holder of SEMP-APP1 is on SEMP-UP01, and SEMP-APP1 is revoked.", "muted"]);
  assert.equal($("upgradeApply").hidden, true);
  assert.deepEqual(rows().map((r) => r[0]), ["SEMP-UP01"], "the old licence, revoked, leaves the table");

  $("upgradeCancel").click();
  assert.equal($("upgradeDialog").open, false);
  assert.equal($("upgradedKey").textContent, "", "the code goes with the dialog");
});

test("closing the upgrade dialog with Escape takes the code out of the page too", async () => {
  await openUpgrade({
    [UPGRADE]: () => json(200, { key: "SEMP-UP01-KEY", license: NEW, holderUid: "u1", pending: true }),
  });
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.equal($("upgradedKey").textContent, "SEMP-UP01-KEY");
  $("upgradeDialog").close();
  $("upgradeDialog").dispatch("close");
  assert.equal($("upgradedKey").textContent, "");
});

test("an upgrade to an institution needs its domain and IT; a holder who never signed in moves at once", async () => {
  await openUpgrade({
    [UPGRADE]: () => json(200, { key: "K", license: { ...NEW, kind: "institution" }, holderUid: "", pending: false }),
    [`GET /v1/admin/licenses/${APP_IND.id}`]: () => json(200, { ...APP_IND, status: "revoked" }),
  });
  $("upgradeKind").value = "institution";
  $("upgradeKind").dispatch("change");
  assert.equal($("upgradeInstitution").hidden, false);
  assert.equal($("upgradeDomain").value, "lab.org", "the holder's domain, to start from");
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(upHint(), ["An institution licence needs a domain and at least one IT contact.", "err"]);
  assert.deepEqual(sent(/upgrade/), []);

  $("upgradeAdmins").value = "it@lab.org";
  $("upgradeSeats").value = "12";
  $("upgradeExpiry").value = "2027-12-31";
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(bodyOf(net, "POST", "/upgrade"), {
    kind: "institution", expiresAt: "2027-12-31T23:59:59Z", domainLock: "lab.org",
    adminEmails: ["it@lab.org"], maxSeats: 12,
  });
  assert.deepEqual(upHint(), ["Nobody had signed in yet: the invitation moved to SEMP-UP01, and SEMP-APP1 is revoked.", "muted"]);
  assert.equal($("upgradeApply").hidden, true, "nothing waits");
});

test("an upgrade already waiting can be applied from its refusal; the other refusals in words", async () => {
  await openUpgrade({
    [UPGRADE]: () => json(409, { detail: `upgrade_pending: ${NEW.id}` }),
    [APPLY]: () => json(404, { detail: "no_pending_upgrade" }),
  });
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(upHint(), [
    "Not issued: an upgrade (lic-up-1) is already waiting for the holder. Apply it now, or revoke it to issue another.",
    "err",
  ]);
  assert.equal($("upgradeApply").hidden, false);
  assert.equal($("upgradeFields").hidden, false, "the form stays");
  confirms.answer(true);
  $("upgradeApply").click();
  await settle();
  assert.deepEqual(sent(/apply-upgrade/), [APPLY]);
  assert.deepEqual(upHint(), [
    "That upgrade is no longer waiting: the holder has activated it already, or it was revoked.", "err",
  ]);

  for (const [code, text] of [
    ["license_not_upgradable", "Only a Licensed individual licence for one app upgrades this way. " +
      "One from before the split moves with To institution."],
    ["convert_domain_mismatch", "The holder's address is not on that domain."],
    ["expiry_in_past", "That date has already passed."],
  ]) {
    reset();
    await openUpgrade({ [UPGRADE]: () => json(409, { detail: code }) });
    $("upgradeForm").dispatch("submit");
    await settle();
    assert.deepEqual(upHint(), [text, "err"], code);
  }
});

test("Apply now declined sends nothing; bad numbers are refused before an issue", async () => {
  await openUpgrade({
    [UPGRADE]: () => json(200, { key: "K", license: NEW, holderUid: "u1", pending: true }),
  });
  $("upgradeMaxDevices").value = "0";
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(upHint(), ["Phones is a whole number, 1 or more.", "err"]);
  $("upgradeMaxDevices").value = "";
  $("upgradeCap").value = "x";
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(upHint(), ["Analyses per person is a whole number.", "err"]);
  $("upgradeCap").value = "200";
  $("upgradeForm").dispatch("submit");
  await settle();
  assert.deepEqual(bodyOf(net, "POST", "/upgrade"), { kind: "individual", maxAnalyses: 200 });
  confirms.answer(false);
  $("upgradeApply").click();
  await settle();
  assert.deepEqual(sent(/apply-upgrade/), []);
});
