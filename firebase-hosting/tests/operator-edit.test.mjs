// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's Edit and To-institution dialogs
// (console/operator/edit.js), past what operator.test.mjs pins: the term
// toggle, making a licence perpetual or putting an end on a perpetual one,
// an institution's seats and IT contacts, the remaining refusals, and the
// whole To-institution dialog — prefill, its own checks, the POST, the key
// shown once, and each refusal.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, prompts, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, UNI, at, openDesk, status, rows, labels, rowButton, offline, bodyOf } from "./operator-desk.mjs";

await loadAuth();
const { day, isoDay } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const hint = (id) => [$(id).textContent, $(id).className];

/* ------------------------------------------------------------------ edit */

async function openEdit(lic, routes = {}) {
  await openDesk({ routes });
  rowButton("edit", lic.id).click();
  await settle();
}

const submitEdit = async () => {
  $("editForm").dispatch("submit");
  await settle();
};

const editBody = (lic) => bodyOf(net, "PATCH", `/v1/admin/licenses/${lic.id}`);

test("Edit clears the last action's line; Cancel closes the dialog and sends nothing", async () => {
  await openDesk();
  $("status").textContent = "SEMP-UNI1 saved — perpetual.";
  rowButton("edit", IND.id).click();
  assert.deepEqual(status(), ["", "muted"], "an old outcome would read as this edit's");
  assert.equal($("editDialog").open, true);
  $("editCancel").click();
  assert.equal($("editDialog").open, false);
  assert.deepEqual(sent(/PATCH/), []);
});

test("Never expires and a date are one choice: ticking it disables the date and the grace", async () => {
  await openEdit(IND);
  assert.deepEqual([$("editExpiry").disabled, $("editGrace").disabled], [false, false]);
  $("editPerpetual").checked = true;
  $("editPerpetual").dispatch("change");
  assert.deepEqual([$("editExpiry").disabled, $("editGrace").disabled], [true, true]);
  $("editPerpetual").checked = false;
  $("editPerpetual").dispatch("change");
  assert.equal($("editExpiry").disabled, false);

  reset();
  await openEdit(UNI);
  assert.equal($("editPerpetual").checked, true, "a perpetual licence opens ticked");
  assert.deepEqual([$("editExpiry").disabled, $("editGrace").disabled], [true, true]);
  assert.equal($("editInstitution").hidden, false);
  assert.deepEqual([$("editSeats").value, $("editSeating").value, $("editAdmins").value], [5, "floating", "it@uni.edu"]);
});

test("making a timed licence perpetual is saved as such, and the status says perpetual", async () => {
  await openEdit(IND, {
    [`PATCH /v1/admin/licenses/${IND.id}`]: () => json(200, { ...IND, duration: "perpetual", expiresAt: null }),
  });
  $("editPerpetual").checked = true;
  $("editPerpetual").dispatch("change");
  await submitEdit();
  assert.equal(prompts.asked.length, 0, "a longer term needs no typed key");
  assert.deepEqual(editBody(IND), { perpetual: true });
  assert.deepEqual(status(), ["SEMP-IND1 saved — perpetual. Everyone on it has the change.", "muted"]);
  assert.equal(rows()[0][4], "perpetual");
});

test("putting an end on a perpetual licence is a shortening: the key is typed first", async () => {
  const end = at(100);
  await openEdit(UNI, {
    [`PATCH /v1/admin/licenses/${UNI.id}`]: () => json(200, { ...UNI, duration: "timed", expiresAt: end }),
  });
  $("editPerpetual").checked = false;
  $("editPerpetual").dispatch("change");
  $("editExpiry").value = isoDay(end);
  prompts.answer("SEMP-UNI1");
  await submitEdit();
  assert.equal(prompts.asked[0],
    `This shortens SEMP-UNI1: its term ends ${isoDay(end)} (it was perpetual), for everyone on it.` +
    "\n\nType SEMP-UNI1 to confirm:");
  assert.deepEqual(editBody(UNI), { expiresAt: end, allowShorten: true });
  assert.deepEqual(status(), [`SEMP-UNI1 saved — ends ${day(end)}. Everyone on it has the change.`, "muted"]);
});

test("an institution's seats, seating and IT contacts are sent as changed, while Save waits", async () => {
  const answer = deferred();
  await openEdit(UNI, { [`PATCH /v1/admin/licenses/${UNI.id}`]: () => answer.promise });
  $("editSeats").value = "8";
  $("editSeating").value = "assigned";
  $("editAdmins").value = "it@uni.edu, New@Uni.edu, it@uni.edu";
  $("editForm").dispatch("submit");
  await settle();
  assert.equal($("editSave").disabled, true, "one save at a time");
  assert.deepEqual(editBody(UNI), { maxSeats: 8, seating: "assigned", adminEmails: ["it@uni.edu", "new@uni.edu"] });
  answer.resolve(json(200, { ...UNI, maxSeats: 8, seating: "assigned", seatsUsed: 4 }));
  await settle();
  assert.equal($("editSave").disabled, false);
  assert.equal($("editDialog").open, false);
  assert.equal(rows().find((r) => r[0] === "SEMP-UNI1")[3], "4/8", "an assigned licence's seats read used/cap");
});

test("a change the dialog can tell is wrong is said there, before anything is sent", async () => {
  await openEdit(IND);
  $("editGrace").value = "999";
  await submitEdit();
  assert.deepEqual(hint("editHint"), ["Grace is 0 to 365 days.", "err"]);
  assert.equal($("editDialog").open, true);
  assert.deepEqual(sent(/PATCH/), []);
});

test("the remaining edit refusals are explained in the dialog", async () => {
  for (const [lic, change, reply, text] of [
    [IND, () => { $("editCap").value = "40"; }, () => json(422, { detail: "cap_on_demo_key" }),
      "Demo keys use the demo allowance of 25; issue a licensed key to raise it."],
    [UNI, () => { $("editNote").value = "x"; }, () => json(422, { detail: "floating_needs_max_seats" }),
      "A floating licence needs a number of seats."],
    [UNI, () => { $("editNote").value = "x"; }, () => json(422, { detail: "institution_only" }),
      "Seats, seating and IT contacts are for institution licences."],
    [IND, () => { $("editNote").value = "x"; }, () => json(429, { detail: "rate_limited" }),
      "Too many requests just now — wait a moment and try again."],
    [IND, () => { $("editNote").value = "x"; }, offline, "Not saved: Failed to fetch"],
  ]) {
    reset();
    await openEdit(lic, { [`PATCH /v1/admin/licenses/${lic.id}`]: reply });
    change();
    await submitEdit();
    assert.deepEqual(hint("editHint"), [text, "err"], text);
    assert.equal($("editDialog").open, true);
    assert.equal($("editSave").disabled, false);
  }
});

/* ---------------------------------------------------------- To institution */

const CONVERT = `POST /v1/admin/licenses/${IND.id}/convert`;
const LAB = {
  id: "lic-lab-1", keyPrefix: "SEMP-LAB1", kind: "institution", mode: "licensed", status: "issued",
  duration: "timed", expiresAt: IND.expiresAt, seating: "floating", maxSeats: 10, leasesActive: 0, seatsUsed: 1,
  domainLock: "lab.org",
};

async function openConvert(routes = {}, licenses) {
  await openDesk({ licenses, routes });
  rowButton("convert", IND.id).click();
  await settle();
}

const submitConvert = async () => {
  $("convertForm").dispatch("submit");
  await settle();
};

test("To institution opens on the holder's domain, with the rest blank; Cancel closes it", async () => {
  await openConvert();
  assert.equal($("convertDialog").open, true);
  assert.equal($("convertName").textContent, "SEMP-IND1");
  assert.deepEqual(
    [$("convertDomain").value, $("convertAdmins").value, $("convertSeats").value, $("convertSeating").value],
    ["lab.org", "", "", "assigned"],
  );
  assert.deepEqual([$("convertFields").hidden, $("convertedBox").hidden, $("convertSave").hidden], [false, true, false]);
  assert.equal($("convertCancel").textContent, "Cancel");
  $("convertCancel").click();
  assert.equal($("convertDialog").open, false);
  assert.deepEqual(sent(/convert/), []);
});

test("a licence locked to no address opens with no domain", async () => {
  const bare = { ...IND, emailLock: "" };
  await openConvert({}, [bare]);
  assert.equal($("convertDomain").value, "");
});

test("a domain and an IT contact are needed, and floating needs seats; nothing is sent until then", async () => {
  await openConvert();
  $("convertDomain").value = "";
  $("convertAdmins").value = "it@lab.org";
  await submitConvert();
  assert.deepEqual(hint("convertHint"), ["A domain and at least one IT contact are needed.", "err"]);

  $("convertDomain").value = "lab.org";
  $("convertAdmins").value = " , ";
  await submitConvert();
  assert.deepEqual(hint("convertHint"), ["A domain and at least one IT contact are needed.", "err"]);

  $("convertAdmins").value = "it@lab.org";
  $("convertSeating").value = "floating";
  await submitConvert();
  assert.deepEqual(hint("convertHint"), ["A floating licence needs a number of seats.", "err"]);
  assert.deepEqual(sent(/convert/), []);
  assert.equal($("convertDialog").open, true);
});

test("a convert sends the cleaned fields, shows the new key once, and both rows follow", async () => {
  await openConvert({
    [CONVERT]: () => json(200, { key: "SEMP-LAB1-KEY-ONCE", license: LAB, claimedByUid: "u1" }),
    [`GET /v1/admin/licenses/${IND.id}`]: () => json(200, { ...IND, status: "revoked", supersededBy: LAB.id }),
  });
  $("convertDomain").value = " Lab.ORG ";
  $("convertAdmins").value = "IT@lab.org, it@lab.org, ops@lab.org";
  $("convertSeats").value = " 10 ";
  $("convertSeating").value = "floating";
  await submitConvert();
  assert.deepEqual(bodyOf(net, "POST", `/v1/admin/licenses/${IND.id}/convert`), {
    domainLock: "lab.org", adminEmails: ["it@lab.org", "ops@lab.org"], seating: "floating", maxSeats: 10,
  });
  assert.equal($("convertedKey").textContent, "SEMP-LAB1-KEY-ONCE");
  assert.deepEqual([$("convertFields").hidden, $("convertedBox").hidden, $("convertSave").hidden], [true, false, true]);
  assert.equal($("convertCancel").textContent, "Done");
  assert.deepEqual(hint("convertHint"), ["The holder is on the new roster, on the same device.", "muted"]);
  assert.deepEqual(status(), ["SEMP-IND1 is now institution licence SEMP-LAB1.", "muted"]);
  assert.deepEqual(sent(new RegExp(IND.id)), [CONVERT, `GET /v1/admin/licenses/${IND.id}`],
    "the old licence is read back: the convert revoked it");
  assert.deepEqual(labels(), ["SEMP-LAB1", "SEMP-UNI1"], "the new licence in, the revoked one out of view");
  assert.equal($("convertSave").disabled, false);
  $("convertCancel").click();
  assert.equal($("convertDialog").open, false, "Done closes it");
});

test("a convert nobody had signed in for says the invitation moved; reopening starts afresh", async () => {
  await openConvert({
    [CONVERT]: () => json(200, { key: "K2", license: LAB }),
    [`GET /v1/admin/licenses/${IND.id}`]: () => json(200, IND),
  });
  $("convertAdmins").value = "it@lab.org";
  await submitConvert();
  assert.deepEqual(bodyOf(net, "POST", `/v1/admin/licenses/${IND.id}/convert`),
    { domainLock: "lab.org", adminEmails: ["it@lab.org"], seating: "assigned" }, "no seats typed, none sent");
  assert.deepEqual(hint("convertHint"), ["Nobody had signed in yet: the invitation moved to the new licence.", "muted"]);
  rowButton("convert", IND.id).click();
  assert.deepEqual([$("convertFields").hidden, $("convertedBox").hidden, $("convertSave").hidden], [false, true, false]);
  assert.equal($("convertCancel").textContent, "Cancel");
  assert.deepEqual(hint("convertHint"), ["", "muted"]);
});

test("a refused convert is explained in the dialog, which stays as it was", async () => {
  for (const [reply, text] of [
    [() => json(409, { detail: "convert_domain_mismatch" }), "The holder's address is not on that domain."],
    [() => json(409, { detail: "license_not_convertible" }), "Only an individual licensed key converts."],
    [() => json(409, { detail: "license_revoked" }), "This licence is revoked."],
    [() => json(409, { detail: "odd" }), "Not converted: odd"],
    [offline, "Not converted: Failed to fetch"],
  ]) {
    reset();
    await openConvert({ [CONVERT]: reply });
    $("convertAdmins").value = "it@lab.org";
    await submitConvert();
    assert.deepEqual(hint("convertHint"), [text, "err"], text);
    assert.equal($("convertFields").hidden, false);
    assert.equal($("convertedBox").hidden, true);
    assert.equal($("convertSave").disabled, false);
    assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
  }
});
