// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The account page (console/account/account.js) on the answers
// account.test.mjs does not send: an account with no licence block, a shared
// licence reported licensed with no seat lease, analyses and quota answers
// with parts missing, and a download refused with a code the page has no
// sentence for.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, openPage, json, net, $, loadAuth } from "./harness.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

async function open({ me = { email: "user@example.com" }, sessions = {}, routes = {} } = {}) {
  await openPage("account", {
    routes: {
      "GET /v1/me": () => json(200, me),
      "GET /v1/sessions": () => json(200, sessions),
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      ...routes,
    },
  });
}

const pills = () => $("pills").querySelectorAll(".pill").map((p) => [p.textContent, p.className]);
const status = () => [$("status").textContent, $("status").className];

test("an account answer with no licence block reads as the demo, with nothing to move", async () => {
  await open({ sessions: { sessions: [], quota: { used: 0, max: 25 } } });
  assert.deepEqual(pills(), [["demo", "pill off"]]);
  assert.match($("explain").textContent, /^You are on the demo\./);
  assert.equal($("unbind").hidden, true);
  assert.equal($("unbindMt").hidden, true);
  assert.equal($("release").hidden, true);
});

test("a shared licence reported licensed without a seat lease has nothing to give back", async () => {
  await open({ me: { email: "user@example.com", license: { mode: "licensed", held: true, seating: "floating" } } });
  assert.deepEqual(pills(), [["licensed", "pill ok"], ["no seat right now", "pill warn"]]);
  assert.equal($("explain").textContent, "Your institution's seats are shared.");
  assert.equal($("release").hidden, true);
});

test("an analyses answer with no list and no quota reads as nothing stored", async () => {
  await open({ sessions: {} });
  assert.equal($("rows").textContent.trim(), "Nothing backed up yet.");
  assert.equal($("quota").textContent, "0 analyses stored.");
  assert.equal($("more").hidden, true);
  assert.deepEqual(status(), ["", "muted"]);
});

test("a quota without a count is counted from the analyses listed", async () => {
  await open({ sessions: { quota: { max: 25 }, sessions: [
    { sessionId: "a", status: "COMPLETED", completedCount: 1, totalBytes: 10 },
    { sessionId: "b", status: "UPLOADING", completedCount: 0, totalBytes: 10 },
  ] } });
  assert.equal($("quota").textContent, "2 of 25 analyses stored.");
});

test("a download refused with a code the page has no sentence for names the code alone", async () => {
  await open({
    sessions: { quota: { used: 1, max: 25 }, sessions: [
      { sessionId: "s1", specimen: "Steel", status: "COMPLETED", completedCount: 2, totalBytes: 2048 },
    ] },
    routes: { "GET /v1/sessions/s1/bundle": () => json(500, { detail: "bundle_build_failed: zip writer crashed" }) },
  });
  const button = $("rows").querySelector("button");
  button.click();
  await settle();
  assert.deepEqual(status(), ["Could not download: bundle_build_failed", "muted err"]);
  assert.equal(button.disabled, false);
});
