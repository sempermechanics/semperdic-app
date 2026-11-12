// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk speaking ADR-033's licence per app: the Issue card's App
// and Phones (one app, or Both as two mints with two claim codes), the
// licence rows' app tag, Phones n/m and Issue upgrade in place of To
// institution and New phone, Edit's phone count and its refusals, and the
// Test accounts card's licence fields, QC's alone.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, sent, confirms, $, loadAuth } from "./harness.mjs";
import {
  IND, UNI, DEMO, APP_IND, APP_UNI, openDesk, status, rows, rowButton, bodyOf,
} from "./operator-desk.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const APP_FLOAT = {
  ...APP_UNI, id: "lic-appflt-1", keyPrefix: "SEMP-AFL1", seating: "floating", maxSeats: 4, app: "semper",
};

const MINT = "POST /v1/admin/licenses";
const minted = () => net.requests.filter((r) => r.method === "POST" && r.url.endsWith("/v1/admin/licenses"))
  .map((r) => JSON.parse(r.body));
const row = (prefix) => rows().find((r) => r[0] === prefix);

/* ------------------------------------------------------------------ mint */

test("Issue sends the chosen app and phone count, and labels the claim code with its app", async () => {
  await openDesk({ routes: {
    [MINT]: () => json(200, { key: "SEMP-CLS1-KEY", license: { id: "lic-cls-1" }, claimedByUid: "u1" }),
    "GET /v1/admin/licenses/lic-cls-1": () => json(200, { ...APP_IND, id: "lic-cls-1", keyPrefix: "SEMP-CLS1", app: "semperclassroom" }),
  } });
  assert.equal($("mintApp").value, "semper", "one app, semper, unless chosen");
  assert.equal($("mintMaxDevices").placeholder, "1");
  assert.equal($("mintDevicesHint").textContent, "Phones the holder may have active at once. Empty gives 1.");
  $("mintApp").value = "semperclassroom";
  $("emailLock").value = "new@lab.org";
  $("mintMaxDevices").value = "2";
  $("mint").click();
  await settle();
  assert.deepEqual(minted(), [{
    kind: "individual", duration: "perpetual", emailLock: "new@lab.org", maxDevices: 2, app: "semperclassroom",
  }]);
  assert.equal($("mintedApp").textContent, "For semper Classroom");
  assert.equal($("mintedKey").textContent, "SEMP-CLS1-KEY");
  assert.equal($("mintedSecond").hidden, true, "one app, one code");
  assert.deepEqual(status(), ["Issued and attached to new@lab.org — Licensed from their next request.", "muted"]);
  assert.match(row("SEMP-CLS1")[1], /semper Classroom/, "the new row carries its app");
});

test("Both issues one licence per app, shows both claim codes, and says each outcome", async () => {
  await openDesk({ routes: {
    [MINT]: (req) => (JSON.parse(req.body).app === "semper"
      ? json(200, { key: "KEY-SEMPER", license: { id: "lic-b1" }, claimedByUid: "u1" })
      : json(200, { key: "KEY-CLASSROOM", license: { id: "lic-b2" }, claimError: "claim_contended" })),
    "GET /v1/admin/licenses/lic-b1": () => json(200, { ...APP_IND, id: "lic-b1", keyPrefix: "SEMP-B1" }),
    "GET /v1/admin/licenses/lic-b2": () => json(200, { ...APP_IND, id: "lic-b2", keyPrefix: "SEMP-B2", app: "semperclassroom" }),
  } });
  $("mintApp").value = "both";
  $("emailLock").value = "pat@new.org";
  $("mint").click();
  await settle();
  assert.deepEqual(minted().map((b) => b.app), ["semper", "semperclassroom"], "two mints, one per app");
  assert.deepEqual(minted().map((b) => b.emailLock), ["pat@new.org", "pat@new.org"], "the same terms on each");
  assert.deepEqual(
    [$("mintedApp").textContent, $("mintedKey").textContent, $("mintedApp2").textContent, $("mintedKey2").textContent],
    ["For semper", "KEY-SEMPER", "For semper Classroom", "KEY-CLASSROOM"],
  );
  assert.equal($("mintedSecond").hidden, false);
  assert.deepEqual(status(), [
    "semper: Issued and attached to pat@new.org — Licensed from their next request. " +
    "semper Classroom: Issued, but it could not be attached to pat@new.org (claim_contended). " +
    "The claim code below redeems it.", "muted err",
  ]);
  assert.equal($("mint").disabled, false);

  $("closeIssue").click();
  assert.deepEqual([$("mintedKey").textContent, $("mintedKey2").textContent, $("mintedSecond").hidden],
    ["", "", true], "Close takes both codes out of the page");
});

test("Both stops at a refusal and says what was issued before it", async () => {
  const held = { ...APP_IND, id: "lic-held-2", keyPrefix: "SEMP-HLD2", app: "semperclassroom", emailLock: "pat@new.org" };
  await openDesk({ routes: {
    [MINT]: (req) => (JSON.parse(req.body).app === "semper"
      ? json(200, { key: "KEY-SEMPER", license: { id: "lic-b1" } })
      : json(409, { detail: `email_already_licensed: ${held.id}` })),
    "GET /v1/admin/licenses/lic-b1": () => json(200, { ...APP_IND, id: "lic-b1", keyPrefix: "SEMP-B1" }),
    [`GET /v1/admin/licenses/${held.id}`]: () => json(200, held),
    "POST /v1/admin/licenses/search": () => json(200, { licenses: [held] }),
  } });
  $("mintApp").value = "both";
  $("emailLock").value = "pat@new.org";
  $("mint").click();
  await settle();
  assert.deepEqual(status(), [
    "semper: Issued. It attaches when pat@new.org first signs in. semper Classroom: Not issued: " +
    "pat@new.org already has a live licence for that app (shown below). To renew it, use Edit. " +
    "To replace it, revoke it first, then issue again.", "muted err",
  ]);
  assert.equal($("mintedKey").textContent, "KEY-SEMPER", "the code issued is still there to copy");
  assert.equal($("mintedSecond").hidden, true);
  assert.equal($("filter").value, "pat@new.org");

  reset();
  await openDesk({ routes: {
    [MINT]: (req) => (JSON.parse(req.body).app === "semper"
      ? json(200, { key: "KEY-SEMPER" }) : json(429, { detail: "rate_limited" })),
  } });
  $("mintApp").value = "both";
  $("emailLock").value = "pat@new.org";
  $("mint").click();
  await settle();
  assert.deepEqual(status(), [
    "semper: Issued. It attaches when pat@new.org first signs in. " +
    "semper Classroom: Too many admin calls just now — wait a moment.", "muted err",
  ]);
});

test("Phones defaults as the backend does: one a seat on an assigned institution, none on a shared one", async () => {
  await openDesk({ routes: { [MINT]: () => json(200, {}) } });
  const pick = (value) => {
    for (const r of document.querySelectorAll('input[name="kind"]')) r.checked = r.value === value;
    document.querySelector(`input[name="kind"][value="${value}"]`).dispatch("change");
  };
  pick("institution");
  assert.equal($("mintDevicesField").hidden, false);
  assert.equal($("mintMaxDevices").placeholder, "one a seat");
  assert.equal($("mintDevicesHint").textContent,
    "Phones active at once across the whole licence, a pool. Empty gives one a seat.");
  $("mintMaxDevices").value = "7";
  $("seating").value = "floating";
  $("seating").dispatch("change");
  assert.equal($("mintDevicesField").hidden, true, "a shared licence counts seats in use instead");
  $("domainLock").value = "uni.edu";
  $("maxSeats").value = "4";
  $("mint").click();
  await settle();
  assert.equal("maxDevices" in bodyOf(net, "POST", "/v1/admin/licenses"), false, "a hidden count is not sent");

  reset();
  await openDesk();
  $("mintMaxDevices").value = "0";
  $("mint").click();
  await settle();
  assert.deepEqual(status(), ["Phones is a whole number, 1 or more.", "muted err"]);
  assert.deepEqual(sent(/POST/), []);
});

/* ------------------------------------------------------------------ rows */

test("a licence for one app shows its app, Phones n/m and Issue upgrade; one from before the split keeps its tools", async () => {
  await openDesk({ licenses: [APP_IND, APP_UNI, APP_FLOAT, IND, UNI, { ...DEMO, app: "" }] });
  assert.equal(row("SEMP-APP1")[1], "individual semper");
  assert.equal(row("SEMP-AUN1")[1], "institution semper Classroom");
  assert.equal(row("SEMP-IND1")[1], "individual 50 analyses each", "no app tag on a licence from before the split");

  assert.equal(rowButton("phones", APP_IND.id).textContent, "Phones 1/2");
  assert.equal(rowButton("phones", APP_IND.id).getAttribute("aria-label"), "Phones on SEMP-APP1: 1 of 2 active");
  assert.equal(rowButton("phones", APP_UNI.id).textContent, "Phones 3/10", "an institution's pool is its seats");
  assert.equal(rowButton("phones", APP_FLOAT.id), null, "a shared licence's lease is the activation");
  assert.equal(rowButton("phones", IND.id), null);

  assert.ok(rowButton("upgrade", APP_IND.id), "an app licence upgrades by issue");
  assert.equal(rowButton("convert", APP_IND.id), null, "…not To institution");
  assert.equal(rowButton("device", APP_IND.id), null, "…and frees no lock with New phone");
  assert.equal(rowButton("upgrade", APP_UNI.id), null, "an institution is resized by Edit");
  assert.ok(rowButton("convert", IND.id), "a licence from before the split still converts");
  assert.ok(rowButton("device", IND.id), "…and still has New phone");
  assert.equal(rowButton("upgrade", IND.id), null, "the backend refuses its upgrade");
});

test("an institution licence with no seat cap and no count reads ∞; a replaced app licence says an upgrade", async () => {
  await openDesk({ licenses: [
    { ...APP_UNI, maxSeats: null, devicesActive: 0 },
    { ...APP_IND, id: "lic-old-1", keyPrefix: "SEMP-OLD1", supersededBy: "lic-new-1" },
  ] });
  assert.equal(rowButton("phones", APP_UNI.id).textContent, "Phones 0/∞");
  assert.equal(row("SEMP-OLD1")[1], "individual semperreplaced by an upgrade");
});

test("an upgrade waiting is said on both rows, and Apply upgrade on the old one moves the holder", async () => {
  const OLD = { ...APP_IND, upgradePendingId: "lic-up-9" };
  const NEXT = { ...APP_IND, id: "lic-up-9", keyPrefix: "SEMP-UP09", status: "unused", supersedes: APP_IND.id };
  const apply = `POST /v1/admin/licenses/${NEXT.id}/apply-upgrade`;
  await openDesk({
    licenses: [OLD, NEXT],
    routes: {
      [apply]: () => json(200, { licenseId: NEXT.id, supersededId: OLD.id, activation: null }),
      [`GET /v1/admin/licenses/${OLD.id}`]: () => json(200, { ...APP_IND, status: "revoked", supersededBy: NEXT.id }),
      [`GET /v1/admin/licenses/${NEXT.id}`]: () => json(200, { ...NEXT, status: "redeemed" }),
    },
  });
  assert.equal(row("SEMP-APP1")[1], "individual semperupgrade issued (SEMP-UP09), waiting for the holder");
  assert.equal(row("SEMP-UP09")[1], "individual semperupgrade of SEMP-APP1");
  assert.equal(rowButton("upgrade", OLD.id), null, "no second upgrade while one waits");

  confirms.answer(false);
  rowButton("apply", OLD.id).click();
  await settle();
  assert.deepEqual(sent(/apply-upgrade/), [], "declined: nothing sent");

  confirms.answer(true);
  rowButton("apply", OLD.id).click();
  await settle();
  assert.deepEqual(sent(/apply-upgrade/), [apply]);
  assert.deepEqual(status(), ["Applied: the holder of SEMP-APP1 is on SEMP-UP09, and SEMP-APP1 is revoked.", "muted"]);
  assert.equal(row("SEMP-APP1"), undefined, "revoked, it leaves a table that hides revoked licences");
});

test("Apply upgrade refused is said on the status line", async () => {
  const OLD = { ...APP_IND, upgradePendingId: "lic-up-9" };
  await openDesk({
    licenses: [OLD],
    routes: { "POST /v1/admin/licenses/lic-up-9/apply-upgrade": () => json(404, { detail: "no_pending_upgrade" }) },
  });
  confirms.answer(true);
  rowButton("apply", OLD.id).click();
  await settle();
  assert.deepEqual(status(), [
    "That upgrade is no longer waiting: the holder has activated it already, or it was revoked.", "muted err",
  ]);
});

/* ------------------------------------------------------------------ edit */

test("Edit shows an app licence's phone count and sends a change to it alone", async () => {
  await openDesk({ licenses: [APP_IND, IND], routes: {
    [`PATCH /v1/admin/licenses/${APP_IND.id}`]: () => json(200, { ...APP_IND, maxDevices: 3 }),
  } });
  rowButton("edit", IND.id).click();
  assert.equal($("editDevicesField").hidden, true, "a licence from before the split has no count");
  $("editCancel").click();

  rowButton("edit", APP_IND.id).click();
  assert.equal($("editDevicesField").hidden, false);
  assert.equal($("editMaxDevices").value, 2);
  assert.equal($("editDevicesHint").textContent,
    "1 active now. Lowering it deactivates nobody, so it cannot go below that.");
  $("editMaxDevices").value = "3";
  $("editForm").dispatch("submit");
  await settle();
  assert.deepEqual(bodyOf(net, "PATCH", `/v1/admin/licenses/${APP_IND.id}`), { maxDevices: 3 });
  assert.equal($("editDialog").open, false);
  assert.equal(rowButton("phones", APP_IND.id).textContent, "Phones 1/3");
});

test("Edit's phone count: checked here, and the backend's refusals in words", async () => {
  await openDesk({ licenses: [APP_IND] });
  rowButton("edit", APP_IND.id).click();
  $("editMaxDevices").value = "two";
  $("editForm").dispatch("submit");
  await settle();
  assert.deepEqual([$("editHint").textContent, $("editHint").className], ["Phones is a whole number, 1 or more.", "err"]);
  $("editMaxDevices").value = "2";
  $("editForm").dispatch("submit");
  await settle();
  assert.equal($("editHint").textContent, "Nothing changed.", "the same count is no change");
  assert.deepEqual(sent(/PATCH/), []);

  for (const [code, text] of [
    ["max_devices_below_active", "More phones are active on it than that (1). Lowering the count deactivates " +
      "nobody: deactivate phones under Phones first, or keep the number higher."],
    ["max_devices_needs_app", "This licence is from before the split and has no phone count: it holds one phone per app."],
  ]) {
    reset();
    await openDesk({ licenses: [APP_IND], routes: {
      [`PATCH /v1/admin/licenses/${APP_IND.id}`]: () => json(422, { detail: code }),
    } });
    rowButton("edit", APP_IND.id).click();
    $("editMaxDevices").value = "1";
    $("editForm").dispatch("submit");
    await settle();
    assert.deepEqual([$("editHint").textContent, $("editHint").className], [text, "err"], code);
    assert.equal($("editDialog").open, true);
  }
});

test("Edit sends the phone count beside other changes, and none on a shared licence", async () => {
  await openDesk({ licenses: [APP_IND, APP_FLOAT], routes: {
    [`PATCH /v1/admin/licenses/${APP_IND.id}`]: () => json(200, APP_IND),
  } });
  rowButton("edit", APP_FLOAT.id).click();
  assert.equal($("editDevicesField").hidden, true);
  $("editCancel").click();
  rowButton("edit", APP_IND.id).click();
  $("editNote").value = "renewed";
  $("editMaxDevices").value = "4";
  $("editForm").dispatch("submit");
  await settle();
  assert.deepEqual(bodyOf(net, "PATCH", `/v1/admin/licenses/${APP_IND.id}`), { note: "renewed", maxDevices: 4 });
});

/* --------------------------------------------------------- test accounts */

const TA_CREATE = "POST /v1/admin/test-accounts";
const TA_LIST = "GET /v1/admin/test-accounts";
const taCreated = (over = {}) => () => json(201, {
  uid: "uid-new", email: "qc@test.example", password: "test-only-0000000000", purpose: "qc",
  expiresAt: "2026-11-30T23:59:59Z", maxDevices: 3, ...over,
});

async function createTestAccount(fill) {
  await openDesk({ routes: { [TA_CREATE]: taCreated(), [TA_LIST]: () => json(200, { accounts: [] }) } });
  $("taEmail").value = "qc@test.example";
  fill();
  $("taCreate").click();
  await settle();
}

test("a QC account's licence: one app with its licensed phones, or none (Demo)", async () => {
  await createTestAccount(() => {
    $("taApps").value = "semper";
    $("taLicenceMaxDevices").value = "2";
  });
  assert.deepEqual(bodyOf(net, "POST", "/v1/admin/test-accounts"), {
    email: "qc@test.example", purpose: "qc", days: 30, maxDevices: 3,
    licence: "individual", apps: "semper", licenceMaxDevices: 2,
  });
  assert.deepEqual(status(), [
    "Test account qc@test.example created (QC): approved and Licensed for semper (2 activated phones a licence), " +
    `ends ${day("2026-11-30T23:59:59Z")}; up to 3 phones may sign in, for each app. Copy its password now.`, "muted",
  ]);
  $("taPasswordClose").click();

  reset();
  await createTestAccount(() => {
    $("taLicence").value = "none";
    $("taLicence").dispatch("change");
    assert.deepEqual([$("taApps").disabled, $("taLicenceMaxDevices").disabled], [true, true],
      "a Demo account chooses no apps and no licensed phones");
    $("taLicenceMaxDevices").value = "5";
  });
  assert.deepEqual(bodyOf(net, "POST", "/v1/admin/test-accounts"),
    { email: "qc@test.example", purpose: "qc", days: 30, maxDevices: 3, licence: "none" });
  assert.match(status()[0], /approved, on Demo with no licence, ends/);
  $("taPasswordClose").click();
});

test("licensed phones out of range is refused before anything is sent; CI sends no licence fields", async () => {
  await openDesk();
  $("taEmail").value = "qc@test.example";
  $("taLicenceMaxDevices").value = "11";
  $("taCreate").click();
  await settle();
  assert.deepEqual(status(), ["Licensed phones is 1 to 10.", "muted err"]);
  assert.deepEqual(sent(/test-accounts/), []);

  reset();
  await createTestAccount(() => {
    $("taPurpose").value = "ci";
    $("taPurpose").dispatch("change");
    assert.equal($("taQcFields").hidden, true);
    $("taLicenceMaxDevices").value = "2";
  });
  assert.deepEqual(bodyOf(net, "POST", "/v1/admin/test-accounts"),
    { email: "qc@test.example", purpose: "ci", days: null, maxDevices: 3 },
    "the backend refuses the licence fields for CI");
  $("taPurpose").value = "qc";
  $("taPurpose").dispatch("change");
  assert.equal($("taQcFields").hidden, false);
  $("taPasswordClose").click();
});
