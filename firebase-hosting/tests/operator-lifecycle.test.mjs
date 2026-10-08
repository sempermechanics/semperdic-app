// Run: node --test "firebase-hosting/tests/*.test.mjs"
// Revoke, delete and restore on the operator desk
// (console/operator/lifecycle.js), past what operator.test.mjs pins: what the
// delete confirmation says for a revoked or an institution licence, every
// way out before anything is sent, a revoke with Show revoked ticked, the
// roster card closing with its licence, network failures, and the Recently
// deleted card — its rows, days left, Restore and each restore refusal.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, json, net, confirms, prompts, sent, fake, FakeUser, $, loadAuth, deferred,
} from "./harness.mjs";
import { IND, UNI, at, openDesk, status, rows, labels, rowButton, tableRows, offline } from "./operator-desk.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const DAY = 86400e3;
const REVOKE = (lic) => `POST /v1/admin/licenses/${lic.id}/revoke`;
const DELETE = (lic) => `DELETE /v1/admin/licenses/${lic.id}`;
const DELETED = "GET /v1/admin/deleted-licenses?limit=50";
const ALL = "GET /v1/admin/licenses?limit=50"; // the list with Show revoked ticked

async function click(button) {
  button.click();
  await settle();
}

/* --------------------------------------------------------------- delete */

test("deleting a revoked licence does not say it is revoked first; an institution names its roster", async () => {
  const revoked = { ...IND, id: "lic-rev", keyPrefix: "SEMP-REV1", status: "revoked" };
  await openDesk({ routes: { [ALL]: () => json(200, { licenses: [revoked, UNI] }) } });
  $("showRevoked").checked = true;
  $("showRevoked").dispatch("change");
  await settle();
  confirms.answer(false, false);
  await click(rowButton("delete", revoked.id));
  await click(rowButton("delete", UNI.id));
  assert.deepEqual(confirms.asked, [
    "Delete SEMP-REV1?\n\nIt leaves this list and is held under Recently deleted for 30 days, then purged. " +
    "Nobody's saved analyses are touched.",
    "Delete SEMP-UNI1?\n\nIt is revoked first: every one of the 4 people on its roster drops to demo immediately. " +
    "It leaves this list and is held under Recently deleted for 30 days, then purged. Nobody's saved analyses are touched.",
  ]);
  assert.equal(prompts.asked.length, 0, "declining the confirmation asks for no key");
  assert.deepEqual(sent(/DELETE/), []);
});

test("cancelling the typed key sends no delete", async () => {
  await openDesk();
  confirms.answer(true);
  prompts.answer(null);
  await click(rowButton("delete", IND.id));
  assert.deepEqual(status(), ["Delete cancelled — the key did not match.", "muted"]);
  assert.deepEqual(sent(/DELETE/), []);
});

test("a delete whose password re-authentication is cancelled says so and sends nothing", async () => {
  await openDesk({ user: new FakeUser({ providers: ["password"], factors: [{}], secondFactor: true, authAgeSeconds: 600 }) });
  confirms.answer(true);
  prompts.answer("SEMP-IND1", null); // the typed key, then no password
  await click(rowButton("delete", IND.id));
  assert.deepEqual(status(), ["Delete cancelled.", "muted"]);
  assert.deepEqual(sent(/DELETE/), []);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

test("a delete the network lost keeps the row and says why", async () => {
  await openDesk({ routes: { [DELETE(IND)]: offline } });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  await click(rowButton("delete", IND.id));
  assert.deepEqual(status(), ["Could not delete: Failed to fetch", "muted err"]);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

test("a deleted licence found by a search leaves the search results too", async () => {
  await openDesk({ routes: {
    "GET /v1/admin/licenses?limit=50&include_revoked=false&q=pat%40lab.org": () => json(200, { licenses: [IND] }),
    [DELETE(IND)]: () => json(200, { purgeAt: at(30) }),
  } });
  $("filter").value = "pat@lab.org";
  await click($("reload")); // a reload with a searchable filter searches again
  assert.deepEqual(sent(/q=/), ["GET /v1/admin/licenses?limit=50&include_revoked=false&q=pat%40lab.org"]);
  assert.deepEqual(labels(), ["SEMP-IND1"]);
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  await click(rowButton("delete", IND.id));
  assert.deepEqual(sent(/DELETE/), [DELETE(IND)]);
  assert.equal($("licenceRows").textContent.trim(), "Nothing matches.", "not kept on screen as a search hit");
});

test("back from Google, declining the delete sends nothing, and a second start does not ask again", async () => {
  confirms.answer(false);
  await openDesk({ resume: { action: "delete", id: IND.id } });
  assert.deepEqual(confirms.asked, ["Re-authenticated. Delete SEMP-IND1 now?"]);
  assert.deepEqual(status(), ["Delete cancelled.", "muted"]);
  fake.notify();
  await settle();
  assert.equal(confirms.asked.length, 1);
  assert.deepEqual(sent(/DELETE/), []);
});

/* --------------------------------------------------------------- revoke */

test("with Show revoked ticked, a revoked row stays, offering only Delete, and no hint to tick it", async () => {
  await openDesk({ routes: {
    [ALL]: () => json(200, { licenses: [IND, UNI] }),
    [REVOKE(IND)]: () => json(200, IND), // the backend's answer; the row is marked revoked either way
  } });
  $("showRevoked").checked = true;
  $("showRevoked").dispatch("change");
  await settle();
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  await click(rowButton("revoke", IND.id));
  assert.deepEqual(status(), ["SEMP-IND1 revoked — its holder is on demo from their next request.", "muted"]);
  assert.deepEqual(rows().map((r) => [r[0], r[6], r[8]]), [["SEMP-IND1", "revoked", "Delete"], ["SEMP-UNI1", "redeemed",
    "Edit Roster Verify Devices Revoke Delete"]]);
});

test("revoking the licence whose roster is open closes the roster; another licence leaves it", async () => {
  const seats = `/v1/admin/licenses/${UNI.id}/seats`;
  await openDesk({ routes: {
    [`GET ${seats}`]: () => json(200, { license: { keyPrefix: "SEMP-UNI1" }, seats: [], invites: [] }),
    [REVOKE(IND)]: () => json(200, IND),
    [REVOKE(UNI)]: () => json(200, UNI),
  } });
  await click(rowButton("roster", UNI.id));
  assert.equal($("rosterCard").hidden, false);
  confirms.answer(true, true);
  prompts.answer("SEMP-IND1", "SEMP-UNI1");
  await click(rowButton("revoke", IND.id));
  assert.equal($("rosterCard").hidden, false, "a different licence");
  await click(rowButton("revoke", UNI.id));
  assert.equal($("rosterCard").hidden, true);
  assert.deepEqual(labels(), ["Nothing matches."], "both revoked, both out of view");
});

test("a revoke the network lost says why and keeps the row", async () => {
  await openDesk({ routes: { [REVOKE(IND)]: offline } });
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  await click(rowButton("revoke", IND.id));
  assert.deepEqual(status(), ["Could not revoke: Failed to fetch", "muted err"]);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

/* ------------------------------------------------------- recently deleted */

const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString();
const DELETED_AT = iso(-3 * DAY);
const GONE = [
  { id: "lic-del-1", keyPrefix: "SEMP-DEL1", kind: "individual", emailLock: "a@b.org", priorStatus: "redeemed",
    deletedAt: DELETED_AT, purgeAt: iso(11.5 * DAY) },
  { id: "lic-deleted-two", kind: "institution", domainLock: "uni.edu", priorStatus: "revoked",
    deletedAt: DELETED_AT, purgeAt: iso(0.5 * DAY) },
  { id: "lic-del-3", keyPrefix: "SEMP-DEL3", kind: "individual", deletedAt: DELETED_AT, purgeAt: iso(-DAY) },
];

/** Answer the deleted list with each of `pages` in turn, the last one from then on. */
function deletedPages(...pages) {
  let n = 0;
  return () => json(200, { licenses: pages[Math.min(n++, pages.length - 1)] });
}

const deletedRows = () => tableRows("deletedRows");

test("Recently deleted is read only when asked, and says what each licence was and how long is left", async () => {
  await openDesk({ routes: { [DELETED]: deletedPages(GONE) } });
  assert.equal($("deletedWrap").hidden, true);
  assert.deepEqual(sent(/deleted/), [], "nothing read on load");
  await click($("loadDeleted"));
  assert.equal($("deletedWrap").hidden, false);
  assert.equal($("loadDeleted").textContent, "Refresh");
  assert.deepEqual(deletedRows(), [
    ["SEMP-DEL1", "individual", "a@b.org", "redeemed", day(DELETED_AT), "12 days", "Restore"],
    ["lic-delete", "institution", "uni.edu", "revoked", day(DELETED_AT), "1 day", "Restore"],
    ["SEMP-DEL3", "individual", "—", "—", day(DELETED_AT), "due", ""],
  ]);
  await click($("loadDeleted"));
  assert.deepEqual(sent(/deleted/), [DELETED, DELETED], "Refresh reads it again");
});

test("an empty or unreadable deleted list says so", async () => {
  await openDesk({ routes: { [DELETED]: deletedPages([]) } });
  await click($("loadDeleted"));
  assert.deepEqual(deletedRows(), [["Nothing deleted in the last 30 days."]]);

  reset();
  await openDesk({ routes: { [DELETED]: () => json(503, { detail: "unavailable" }) } });
  await click($("loadDeleted"));
  assert.deepEqual(deletedRows(), [["Could not load: unavailable"]]);
  assert.equal($("deletedRows").querySelector("td").className, "err");
});

test("a delete with Recently deleted open lists it there at once", async () => {
  const gone = { id: IND.id, keyPrefix: "SEMP-IND1", kind: "individual", deletedAt: iso(0), purgeAt: at(30) };
  await openDesk({ routes: {
    [DELETED]: deletedPages([], [gone]),
    [DELETE(IND)]: () => json(200, { purgeAt: at(30) }),
  } });
  await click($("loadDeleted"));
  confirms.answer(true);
  prompts.answer("SEMP-IND1");
  await click(rowButton("delete", IND.id));
  assert.deepEqual(sent(/deleted|DELETE/), [DELETED, DELETE(IND), DELETED]);
  assert.equal(deletedRows()[0][0], "SEMP-IND1");
});

test("Restore puts the licence back in the table, re-reads the list and says who is back on it", async () => {
  const restored = { ...IND, id: "lic-del-1", keyPrefix: "SEMP-DEL1", emailLock: "a@b.org", note: "" };
  const answer = deferred();
  await openDesk({ routes: {
    [DELETED]: deletedPages(GONE, GONE.slice(1)),
    "POST /v1/admin/deleted-licenses/lic-del-1/restore": () => answer.promise,
  } });
  await click($("loadDeleted"));
  const restore = $("deletedRows").querySelector('button[data-restore="lic-del-1"]');
  await click(restore);
  assert.equal(restore.disabled, true, "one restore at a time");
  answer.resolve(json(200, restored));
  await settle();
  assert.deepEqual(labels(), ["SEMP-DEL1", "SEMP-IND1", "SEMP-UNI1"]);
  assert.deepEqual(deletedRows().map((r) => r[0]), ["lic-delete", "SEMP-DEL3"]);
  assert.deepEqual(status(), [
    "SEMP-DEL1 restored — its holders are back on it, except anyone who took another licence meanwhile.", "muted",
  ]);
});

test("a licence restored revoked says so, named by its id when it has no key prefix", async () => {
  await openDesk({ routes: {
    [DELETED]: deletedPages(GONE),
    "POST /v1/admin/deleted-licenses/lic-deleted-two/restore": () =>
      json(200, { id: "lic-deleted-two", kind: "institution", status: "revoked" }),
  } });
  await click($("loadDeleted"));
  await click($("deletedRows").querySelector('button[data-restore="lic-deleted-two"]'));
  assert.deepEqual(status(), ["lic-delete restored, revoked as it was.", "muted"]);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"], "revoked, so out of view");
});

test("a refused restore is explained, and Restore can be pressed again", async () => {
  for (const [reply, text] of [
    [() => json(410, { detail: "deleted_license_purged" }), "Too late: the 30 days have passed."],
    [() => json(404, { detail: "deleted_license_not_found" }), "Already restored or purged."],
    [() => json(409, { detail: "license_exists" }), "A licence with that key exists again."],
    [() => json(409, { detail: "odd" }), "Could not restore: odd"],
    [offline, "Could not restore: Failed to fetch"],
  ]) {
    reset();
    await openDesk({ routes: {
      [DELETED]: deletedPages(GONE),
      "POST /v1/admin/deleted-licenses/lic-del-1/restore": reply,
    } });
    await click($("loadDeleted"));
    const restore = $("deletedRows").querySelector('button[data-restore="lic-del-1"]');
    await click(restore);
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal(restore.disabled, false);
    assert.deepEqual(sent(/deleted-licenses\?/).length, 1, "the list is not re-read after a refusal");
  }
});

test("a click in the deleted table off any Restore button does nothing", async () => {
  await openDesk({ routes: { [DELETED]: deletedPages(GONE) } });
  await click($("loadDeleted"));
  await click($("deletedRows").querySelector("td"));
  assert.deepEqual(sent(/restore/), []);
});
