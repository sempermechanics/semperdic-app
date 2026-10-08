// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The institution roster page (console/institution/institution.js) on the
// answers institution.test.mjs does not send: a licence list with no list in
// it, a roster summary without its counts, and the controls pressed with no
// licence open or off any button.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, openPage, json, net, sent, readyUser, $, loadAuth } from "./harness.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const SEATS = "/v1/institutions/licenses/L1/seats";
const summary = () => $("summary").textContent.replace(/\s+/g, " ").trim();
const status = () => [$("status").textContent, $("status").className];

async function open({ roster, search = "", licenses = [{ id: "L1", keyPrefix: "SEMP-UNI1" }] } = {}) {
  await openPage("institution", {
    search,
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses }),
      "GET /v1/me": () => json(200, { role: "user" }),
      ...(roster ? { [`GET ${SEATS}`]: () => json(200, roster) } : {}),
    },
  });
}

test("a licence list answer without a list reads as administering nothing", async () => {
  await openPage("institution", {
    user: readyUser({ email: "it@uni.edu" }),
    routes: { "GET /v1/institutions/licenses": () => json(200, {}) },
  });
  assert.equal($("app").hidden, true);
  assert.equal($("notAdmin").hidden, false);
  assert.equal($("notAdminWho").textContent, "it@uni.edu");
  assert.deepEqual(status(), ["", "muted"]);
});

test("Add with no licence open sends nothing", async () => {
  await open();
  $("addEmail").value = "new@uni.edu";
  $("add").click();
  await settle();
  assert.deepEqual(sent(/seats/), []);
  assert.equal($("addEmail").value, "new@uni.edu", "kept for when a licence is open");
});

test("a click between the licence buttons opens nothing", async () => {
  await open();
  $("licenceChoices").click();
  await settle();
  assert.equal($("licenseId").value, "");
  assert.deepEqual(sent(/seats/), []);
});

test("a roster summary without its counts reads them as none", async () => {
  await open({ search: "?license=L1", roster: { license: { seating: "floating", maxSeats: 3 }, seats: [], invites: [] } });
  assert.equal(summary(), "licence shared seats unused 0 of 3 seats in use right now, across 0 people on the roster.");

  reset();
  await open({ search: "?license=L1", roster: { license: { maxSeats: 3 }, seats: [] } });
  assert.equal(summary(), "licence one seat each unused 0 of 3 seats taken.", "assigned when the seating is not said");
  assert.equal($("rosterCard").hidden, false);
});

test("a roster with nothing but seats in its answer still renders", async () => {
  await open({ search: "?license=L1", roster: { seats: [{ uid: "u1", email: "a@uni.edu", status: "active" }] } });
  assert.equal(summary(), "licence one seat each unused 0 of unlimited seats taken.");
  assert.equal($("rows").querySelectorAll("tr").length, 1);
});
