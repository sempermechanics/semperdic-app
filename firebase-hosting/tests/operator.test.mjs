// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk (console/operator/operator.js): who sees it, the licence
// table (rows, filter, search, paging), Edit, and revoke / delete — the typed
// key, the fresh re-authentication, and finishing a revoke or delete on the
// return leg of a Google round trip. Each case opens a fresh copy of the page
// on the fake SDK and DOM (harness.mjs).
import { beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, prompts, sent, storage, fake,
  readyUser, FakeUser, $, loadAuth,
} from "./harness.mjs";

await loadAuth();
const { day, isoDay, unfinishedStepUpText } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const DAY = 86400e3;
const at = (days) => new Date(Date.now() + days * DAY).toISOString().slice(0, 10) + "T23:59:59Z";

const IND = {
  id: "lic-individual-1", keyPrefix: "SEMP-IND1", kind: "individual", mode: "licensed",
  status: "redeemed", duration: "timed", expiresAt: at(200), graceDays: 14, maxAnalyses: 50,
  emailLock: "pat@lab.org", note: "Prof. Chen, PO 4471",
};
const UNI = {
  id: "lic-uni-1", keyPrefix: "SEMP-UNI1", kind: "institution", mode: "licensed", status: "redeemed",
  duration: "perpetual", seating: "floating", maxSeats: 5, leasesActive: 1, seatsUsed: 4,
  domainLock: "uni.edu", adminEmails: ["it@uni.edu"],
};
const DEMO = {
  id: "lic-demo-1", keyPrefix: "SEMP-DEMO", kind: "individual", mode: "demo", status: "redeemed",
  duration: "perpetual", createdByUid: "system", emailLock: "d@x.org",
};

const LIST = "GET /v1/admin/licenses?limit=50&include_revoked=false";

/** The desk, signed in as staff, with the first licence page answering `licenses`. */
async function open({ licenses = [IND, UNI], page = {}, routes = {}, user, resume, redirect } = {}) {
  await openPage("operator", {
    user,
    resume,
    redirect,
    routes: {
      "GET /v1/me": () => json(200, { role: "admin", email: "staff@semper.test" }),
      "GET /v1/admin/users?status=PENDING&limit=50": () => json(200, { users: [] }),
      [LIST]: () => json(200, { licenses, page, demoMaxAnalyses: 25 }),
      ...routes,
    },
  });
}

const status = () => [$("status").textContent, $("status").className];
const rows = () => $("licenceRows").querySelectorAll("tr").map((tr) =>
  tr.cells.map((td) => td.textContent.replace(/\s+/g, " ").trim()));
const labels = () => rows().map((r) => r[0]);
const rowButton = (kind, id) => $("licenceRows").querySelector(`button[data-${kind}="${id}"]`);

/* --------------------------------------------------------------- access */

test("an account that is not staff sees where it can go, and no desk", async () => {
  await openPage("operator", {
    user: readyUser({ email: "someone@lab.org" }),
    routes: { "GET /v1/me": () => json(200, { role: "user" }) },
  });
  assert.equal($("app").hidden, true);
  assert.equal($("notOperator").hidden, false);
  assert.equal($("notOperatorWho").textContent, "someone@lab.org");
  assert.deepEqual(sent(), ["GET /v1/me"], "nothing of the desk is loaded");
});

test("when the role cannot be read, the desk stays hidden and says why", async () => {
  await openPage("operator", { routes: { "GET /v1/me": () => json(503, { detail: "unavailable" }) } });
  assert.equal($("app").hidden, true);
  assert.deepEqual(status(), ["Could not check whether operator@example.com is an operator: unavailable", "muted err"]);
});

/* ---------------------------------------------------------------- table */

test("each licence row says what it is and offers only what applies", async () => {
  await open();
  assert.equal($("mfaPill").textContent, "2FA on");
  assert.deepEqual(rows(), [
    ["SEMP-IND1", "individual", "licensed", "—", `until ${day(IND.expiresAt)} +14d`, "50", "redeemed",
      "pat@lab.org", "Edit New device To institution Devices Revoke Delete"],
    ["SEMP-UNI1", "institution · shared", "licensed", "1/5 in use · 4 on roster", "perpetual", "default",
      "redeemed", "uni.edu", "Edit Roster Verify Devices Revoke Delete"],
  ]);
  assert.equal($("licencePaging").textContent, "2 licence(s) (revoked and Demo hidden).");
  assert.equal($("loadMore").hidden, true);
});

test("Demo keys are fetched only when asked for, show the demo allowance, and are never deleted", async () => {
  await open({
    routes: { "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false":
      () => json(200, { licenses: [IND, DEMO], page: {}, demoMaxAnalyses: 25 }) },
  });
  $("showDemo").checked = true;
  $("showDemo").dispatch("change");
  await settle();
  assert.deepEqual(sent(/admin\/licenses/).at(-1),
    "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false");
  const demo = rows().find((r) => r[0] === "SEMP-DEMO");
  assert.equal(demo[5], "demo (25)");
  assert.equal(demo[8], "Edit New device Devices Revoke", "no convert, no delete for a system Demo key");
  assert.equal($("licencePaging").textContent, "2 licence(s) (revoked hidden).");
});

test("a revoked row offers only Delete, and is shown only on request", async () => {
  const revoked = { ...IND, id: "lic-rev", keyPrefix: "SEMP-REV1", status: "revoked" };
  await open({
    routes: { "GET /v1/admin/licenses?limit=50": () => json(200, { licenses: [revoked], page: {} }) },
  });
  $("showRevoked").checked = true;
  $("showRevoked").dispatch("change");
  await settle();
  assert.deepEqual(rows().map((r) => [r[0], r[6], r[8]]), [["SEMP-REV1", "revoked", "Delete"]]);
});

test("Load more appends the next page once, and the paging line follows", async () => {
  await open({
    page: { hasMore: true, nextPageToken: "tok" },
    routes: { "GET /v1/admin/licenses?limit=50&include_revoked=false&page_token=tok":
      () => json(200, { licenses: [UNI, { ...UNI, id: "lic-uni-2", keyPrefix: "SEMP-UNI2" }], page: {} }) },
  });
  assert.equal($("licencePaging").textContent, "Newest 2 shown (revoked and Demo hidden); more exist.");
  assert.equal($("loadMore").hidden, false);
  $("loadMore").click();
  await settle();
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1", "SEMP-UNI2"], "a row already loaded is not repeated");
  assert.equal($("licencePaging").textContent, "3 licence(s) (revoked and Demo hidden).");
  assert.equal($("loadMore").hidden, true);
});

test("the filter narrows loaded rows at once, by key, domain, address or note", async () => {
  await open();
  const type = (text) => { $("filter").value = text; $("filter").dispatch("input"); };
  type("chen");
  assert.deepEqual(labels(), ["SEMP-IND1"]);
  type("UNI.EDU");
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
  type("nothing here");
  assert.equal($("licenceRows").textContent.trim(), "Nothing matches.");
  assert.deepEqual(sent(/q=/), [], "free text is not a backend search");
});

test("an address is also searched on the backend, after a pause in typing", async () => {
  const other = { ...IND, id: "lic-far", keyPrefix: "SEMP-FAR1", emailLock: "far@else.org", note: "" };
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await open({ routes: {
      "GET /v1/admin/licenses?limit=50&include_revoked=false&q=far%40else.org":
        () => json(200, { licenses: [other] }),
    } });
    $("filter").value = "far@else.org";
    $("filter").dispatch("input");
    assert.equal($("licenceRows").textContent.trim(), "Searching…");
    mock.timers.tick(299);
    await settle();
    assert.deepEqual(sent(/q=/), []);
    mock.timers.tick(1);
    await settle();
    assert.deepEqual(labels(), ["SEMP-FAR1"], "a licence on no loaded page is found");
  } finally {
    mock.timers.reset();
  }
});

/* --------------------------------------------------------------- revoke */

const REVOKE_IND = `POST /v1/admin/licenses/${IND.id}/revoke`;

test("revoke asks who is affected, then for the typed key; either refusal sends nothing", async () => {
  await open();
  confirms.answer(false);
  rowButton("revoke", UNI.id).click();
  await settle();
  assert.match(confirms.asked[0], /^Revoking SEMP-UNI1 drops every one of the 4 people on its roster to demo/);
  assert.equal(prompts.asked.length, 0);

  confirms.answer(true);
  prompts.answer("semp-ind1");
  rowButton("revoke", IND.id).click();
  await settle();
  assert.match(confirms.asked[1], /^Revoking SEMP-IND1 drops the person holding it to demo/);
  assert.equal(prompts.asked[0], "This cannot be undone.\n\nType SEMP-IND1 to revoke this licence:");
  assert.deepEqual(status(), ["Revoke cancelled — the key did not match.", "muted"]);
  assert.deepEqual(sent(/[/]revoke$/), []);
});

test("a confirmed revoke inside the fresh window is sent, and the row leaves the table", async () => {
  await open({ routes: { [REVOKE_IND]: () => json(200, { ...IND, status: "revoked" }) } });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  rowButton("revoke", IND.id).click();
  await settle();
  assert.deepEqual(sent(/[/]revoke$/), [REVOKE_IND]);
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 0, "a fresh sign-in needs no step-up");
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
  assert.deepEqual(status(), [
    "SEMP-IND1 revoked — its holder is on demo from their next request. Tick “Show revoked” to see it.", "muted",
  ]);
});

test("a revoke on a stale session goes to Google first, carrying the licence, and sends nothing yet", async () => {
  await open({ user: readyUser({ authAgeSeconds: 600 }) });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  rowButton("revoke", IND.id).click();
  await settle();
  assert.equal(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), { action: "revoke", id: IND.id });
  assert.deepEqual(sent(/[/]revoke$/), []);
});

test("a revoke whose re-authentication is cancelled says so and sends nothing", async () => {
  await open({ user: new FakeUser({ providers: ["password"], factors: [{}], secondFactor: true, authAgeSeconds: 600 }) });
  confirms.answer(true);
  prompts.answer("SEMP-IND1", null); // the typed key, then no password
  rowButton("revoke", IND.id).click();
  await settle();
  assert.deepEqual(status(), ["Revoke cancelled.", "muted"]);
  assert.deepEqual(sent(/[/]revoke$/), []);
});

test("a refused revoke reports the backend's code", async () => {
  await open({ routes: { [REVOKE_IND]: () => json(409, { detail: "license_revoked" }) } });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  rowButton("revoke", IND.id).click();
  await settle();
  assert.deepEqual(status(), ["Could not revoke: license_revoked", "muted err"]);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

/* ------------------------------------------------- revoke, return leg */

test("back from Google, a revoke is finished after one plain confirmation, exactly once", async () => {
  confirms.answer(true);
  await open({
    resume: { action: "revoke", id: IND.id },
    routes: { [REVOKE_IND]: () => json(200, { ...IND, status: "revoked" }) },
  });
  assert.deepEqual(confirms.asked, ["Re-authenticated. Revoke SEMP-IND1 now?"]);
  assert.equal(prompts.asked.length, 0, "the key was typed on the way out");
  assert.deepEqual(sent(/[/]revoke$/), [REVOKE_IND]);
  assert.match($("status").textContent, /^SEMP-IND1 revoked/);

  // The auth listener firing again (the SDK adopting the user) starts nothing twice.
  fake.notify();
  await settle();
  assert.deepEqual(sent(/[/]revoke$/), [REVOKE_IND]);
  assert.equal(confirms.asked.length, 1);
});

test("back from Google, declining the confirmation sends nothing", async () => {
  confirms.answer(false);
  await open({ resume: { action: "revoke", id: IND.id } });
  assert.deepEqual(status(), ["Revoke cancelled.", "muted"]);
  assert.deepEqual(sent(/[/]revoke$/), []);
});

test("back from Google for a licence past the first page, it is read before asking", async () => {
  const far = { ...IND, id: "lic-far-away", keyPrefix: "SEMP-FAR1" };
  confirms.answer(true);
  await open({
    resume: { action: "revoke", id: far.id },
    routes: {
      [`GET /v1/admin/licenses/${far.id}`]: () => json(200, far),
      [`POST /v1/admin/licenses/${far.id}/revoke`]: () => json(200, { ...far, status: "revoked" }),
    },
  });
  // The label comes from the id until the row is loaded; the licence read is what makes the revoke safe.
  assert.deepEqual(sent(new RegExp(far.id)), [`GET /v1/admin/licenses/${far.id}`, `POST /v1/admin/licenses/${far.id}/revoke`]);
});

test("back from Google for a licence that is gone or already revoked, nothing is sent", async () => {
  await open({
    resume: { action: "revoke", id: "lic-gone-000" },
    routes: { "GET /v1/admin/licenses/lic-gone-000": () => json(404, { detail: "license_not_found" }) },
  });
  assert.deepEqual(status(), ["Re-authenticated, but lic-gone-0 no longer exists — nothing revoked.", "muted err"]);
  assert.equal(confirms.asked.length, 0);

  reset();
  const done = { ...IND, id: "lic-done", keyPrefix: "SEMP-DONE", status: "revoked" };
  await open({
    resume: { action: "revoke", id: done.id },
    routes: { "GET /v1/admin/licenses/lic-done": () => json(200, done) },
  });
  assert.deepEqual(status(), ["lic-done is already revoked.", "muted"]);
  assert.deepEqual(sent(/[/]revoke$/), []);
});

test("a return leg that failed says the revoke was not sent, after the list load", async () => {
  await open({ resume: { action: "revoke", id: IND.id }, redirect: null });
  assert.deepEqual(status(), [
    unfinishedStepUpText({ action: "revoke", reauthFailed: "incomplete" }, "SEMP-IND1"), "muted err",
  ]);
  assert.equal($("status").textContent,
    "SEMP-IND1 was not revoked: the Google sign-in did not finish. Revoke it again to retry.");
  assert.equal(confirms.asked.length, 0);
  assert.deepEqual(sent(/[/]revoke$/), []);
});

/* --------------------------------------------------------------- delete */

const DELETE_IND = `DELETE /v1/admin/licenses/${IND.id}`;

test("delete asks, then the typed key; a wrong key sends nothing", async () => {
  await open();
  confirms.answer(true);
  prompts.answer("SEMP-IND");
  rowButton("delete", IND.id).click();
  await settle();
  assert.match(confirms.asked[0], /^Delete SEMP-IND1\?\n\nIt is revoked first: the person holding it drops to demo/);
  assert.equal(prompts.asked[0], "Type SEMP-IND1 to delete this licence:");
  assert.deepEqual(status(), ["Delete cancelled — the key did not match.", "muted"]);
  assert.deepEqual(sent(/DELETE/), []);
});

test("a confirmed delete removes the row and says until when it can be restored", async () => {
  const purgeAt = at(30);
  await open({ routes: { [DELETE_IND]: () => json(200, { purgeAt }) } });
  confirms.answer(true);
  prompts.answer(" SEMP-IND1 ");
  rowButton("delete", IND.id).click();
  await settle();
  assert.deepEqual(sent(/DELETE/), [DELETE_IND]);
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
  assert.deepEqual(status(), [`SEMP-IND1 deleted — restorable under Recently deleted until ${day(purgeAt)}.`, "muted"]);
});

test("a delete on a stale session goes to Google first, carrying the licence, and sends nothing yet", async () => {
  await open({ user: readyUser({ authAgeSeconds: 600 }) });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  rowButton("delete", IND.id).click();
  await settle();
  assert.equal($("status").textContent, "Re-authenticating with Google to delete this licence…");
  assert.deepEqual(JSON.parse(storage.getItem("semper.resume")), { action: "delete", id: IND.id });
  assert.deepEqual(sent(/DELETE/), []);
});

test("a refused delete is explained", async () => {
  for (const [code, text] of [
    ["demo_key_not_deletable", "A system Demo key is not deleted; the account would only get another."],
    ["license_not_found", "SEMP-IND1 no longer exists."],
    ["odd", "Could not delete: odd"],
  ]) {
    reset();
    await open({ routes: { [DELETE_IND]: () => json(409, { detail: code }) } });
    confirms.answer(true);
    prompts.answer("SEMP-IND1");
    rowButton("delete", IND.id).click();
    await settle();
    assert.deepEqual(status(), [text, "muted err"], code);
  }
});

test("back from Google, a delete is finished after one confirmation", async () => {
  confirms.answer(true);
  await open({ resume: { action: "delete", id: IND.id }, routes: { [DELETE_IND]: () => json(200, { purgeAt: at(30) }) } });
  assert.deepEqual(confirms.asked, ["Re-authenticated. Delete SEMP-IND1 now?"]);
  assert.deepEqual(sent(/DELETE/), [DELETE_IND]);
});

test("a delete whose return leg was cancelled says it was not deleted", async () => {
  prompts.answer(null); // the authenticator code on the return leg
  await open({ resume: { action: "delete", id: IND.id }, redirect: fake.mfaError() });
  assert.equal($("status").textContent,
    "SEMP-IND1 was not deleted: the authenticator code was not entered. Delete it again to retry.");
  assert.deepEqual(sent(/DELETE/), []);
});

/* ----------------------------------------------------------------- edit */

const PATCH_IND = `PATCH /v1/admin/licenses/${IND.id}`;

async function openEdit(lic = IND, routes = {}) {
  await open({ licenses: [IND, UNI, { ...DEMO, mode: "demo" }], routes });
  rowButton("edit", lic.id).click();
  await settle();
}

const submitEdit = async () => {
  $("editForm").dispatch("submit");
  await settle();
};

test("Edit opens on the licence's current terms", async () => {
  await openEdit();
  assert.equal($("editDialog").open, true);
  assert.equal($("editName").textContent, "SEMP-IND1");
  assert.equal($("editExpiry").value, isoDay(IND.expiresAt));
  assert.equal($("editPerpetual").checked, false);
  assert.equal($("editGrace").value, 14);
  assert.equal($("editCap").value, 50);
  assert.equal($("editCap").disabled, false);
  assert.equal($("editInstitution").hidden, true);
});

test("a later end is saved without a typed key, and the status says what the server stored", async () => {
  const later = at(400);
  await openEdit(IND, { [PATCH_IND]: (r) => json(200, { ...IND, ...JSON.parse(r.body) }) });
  $("editExpiry").value = isoDay(later);
  await submitEdit();
  assert.equal(prompts.asked.length, 0);
  const patch = net.requests.find((r) => r.method === "PATCH");
  assert.deepEqual(JSON.parse(patch.body), { expiresAt: later });
  assert.equal($("editDialog").open, false);
  assert.deepEqual(status(), [`SEMP-IND1 saved — ends ${day(later)}. Everyone on it has the change.`, "muted"]);
  assert.equal(rows()[0][4], `until ${day(later)} +14d`, "the row shows the stored term");
});

test("an earlier end must be confirmed by typing the key", async () => {
  const sooner = at(100);
  await openEdit(IND, { [PATCH_IND]: (r) => json(200, { ...IND, ...JSON.parse(r.body) }) });
  $("editExpiry").value = isoDay(sooner);
  prompts.answer("nope");
  await submitEdit();
  assert.equal(prompts.asked[0],
    `This shortens SEMP-IND1: its term ends ${isoDay(sooner)} instead of ${isoDay(IND.expiresAt)}, ` +
    "for everyone on it.\n\nType SEMP-IND1 to confirm:");
  assert.deepEqual([$("editHint").textContent, $("editHint").className], ["Not saved: the key was not typed.", "err"]);
  assert.deepEqual(sent(/PATCH/), []);

  prompts.answer("SEMP-IND1");
  await submitEdit();
  const patch = net.requests.find((r) => r.method === "PATCH");
  assert.deepEqual(JSON.parse(patch.body), { expiresAt: sooner, allowShorten: true });
});

test("clearing the cap sends clearMaxAnalyses; a Demo key's cap cannot be edited", async () => {
  await openEdit(IND, { [PATCH_IND]: () => json(200, { ...IND, maxAnalyses: null }) });
  $("editCap").value = "";
  await submitEdit();
  assert.deepEqual(JSON.parse(net.requests.find((r) => r.method === "PATCH").body), { clearMaxAnalyses: true });

  reset();
  await open({
    licenses: [DEMO],
    routes: { "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false": () => json(200, { licenses: [DEMO], demoMaxAnalyses: 25 }) },
  });
  $("showDemo").checked = true;
  $("showDemo").dispatch("change");
  await settle();
  rowButton("edit", DEMO.id).click();
  assert.equal($("editCap").disabled, true);
  assert.equal($("editCap").title, "Demo keys use the demo allowance of 25; issue a licensed key to raise it.");
});

/** The desk with Demo keys shown, and Edit open on `demo`. */
async function openDemoEdit(demo = DEMO) {
  await open({
    licenses: [demo],
    routes: { "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false": () => json(200, { licenses: [demo], demoMaxAnalyses: 25 }) },
  });
  $("showDemo").checked = true;
  $("showDemo").dispatch("change");
  await settle();
  rowButton("edit", demo.id).click();
}

test("Edit on a licensed key says nothing about Demo", async () => {
  await openEdit();
  assert.equal($("editDemo").hidden, true);
});

test("Edit on a Demo key says a licence is issued, not edited in, and hands the address to Issue", async () => {
  await openDemoEdit();
  assert.equal($("editDemo").hidden, false);
  assert.equal($("editDemoEmail").textContent, "d@x.org");
  assert.equal($("editDemoIssue").hidden, false);

  document.querySelector('input[name="kind"][value="institution"]').checked = true;
  document.querySelector('input[name="kind"][value="individual"]').checked = false;
  $("editDemoIssue").click();

  assert.equal($("editDialog").open, false);
  assert.equal(document.querySelector('input[name="kind"]:checked').value, "individual");
  assert.equal($("individualFields").hidden, false);
  assert.equal($("institutionFields").hidden, true);
  assert.equal($("emailLock").value, "d@x.org");
  assert.equal($("emailLock").focused, true);
  assert.deepEqual(status(),
    ["Choose the term, then Issue licence: it attaches to d@x.org and replaces the Demo key.", "muted"]);
  assert.deepEqual(sent(/POST|PATCH/), [], "the term is the operator's to choose; nothing is issued from Edit");
});

test("a Demo key with no address on it offers no hand-off", async () => {
  await openDemoEdit({ ...DEMO, emailLock: "" });
  assert.equal($("editDemo").hidden, false);
  assert.equal($("editDemoEmail").textContent, "the account's address");
  assert.equal($("editDemoIssue").hidden, true);
});

test("nothing changed is said in the dialog, and nothing is sent", async () => {
  await openEdit();
  await submitEdit();
  assert.deepEqual([$("editHint").textContent, $("editHint").className], ["Nothing changed.", "err"]);
  assert.deepEqual(sent(/PATCH/), []);
});

test("a refused edit is explained in the dialog, which stays open", async () => {
  for (const [lic, change, code, text] of [
    [IND, () => { $("editExpiry").value = isoDay(at(400)); }, "expiry_in_past",
      "That date has already passed. Ending a licence now is Revoke."],
    [IND, () => { $("editExpiry").value = isoDay(at(400)); }, "expiry_before_current",
      "That is earlier than the current expiry."],
    [UNI, () => { $("editNote").value = "renewal"; }, "license_perpetual", "This licence is perpetual."],
    [UNI, () => { $("editSeats").value = "2"; }, "max_seats_below_used",
      "More people are on the roster than that many seats. Remove members first, or raise Seats."],
    [IND, () => { $("editNote").value = "x"; }, "odd", "Not saved: odd"],
  ]) {
    reset();
    await openEdit(lic, { [`PATCH /v1/admin/licenses/${lic.id}`]: () => json(422, { detail: code }) });
    change();
    await submitEdit();
    assert.deepEqual([$("editHint").textContent, $("editHint").className], [text, "err"], code);
    assert.equal($("editDialog").open, true);
    assert.equal($("editSave").disabled, false);
  }
});

/* ----------------------------------------------------------- new device */

test("New device asks, then clears the lock and says the next device takes it", async () => {
  await open({ routes: { [PATCH_IND]: () => json(200, IND) } });
  confirms.answer(false, true);
  rowButton("device", IND.id).click();
  await settle();
  assert.deepEqual(sent(/PATCH/), []);
  rowButton("device", IND.id).click();
  await settle();
  assert.deepEqual(JSON.parse(net.requests.find((r) => r.method === "PATCH").body), { clearDeviceLock: true });
  assert.deepEqual(status(), ["SEMP-IND1 unbound — the next device to sign in takes it.", "muted"]);
});
