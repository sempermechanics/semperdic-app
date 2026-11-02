// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The account page's per-app sections (console/account/phones.js, ADR-033):
// each app's own licence from /v1/me's `licences`, how many phones it covers,
// a new licence waiting to be activated, and the holder's own phones from
// /v1/me/devices?app=, with Deactivate on each activated one. Move is offered
// only on a licence from before the split (a lock); Demo lists phones only.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, sent, $, loadAuth, deferred,
} from "./harness.mjs";

await loadAuth();
const { day, when } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const DAY = 86400e3;
const iso = (offsetMs) => new Date(Date.now() + offsetMs).toISOString();
const ACTIVATED_AT = "2026-10-20T09:30:00Z";

const SEMPER = { mode: "licensed", held: true, kind: "individual", prefix: "SEMP-4K2P",
  expiresAt: null, inGrace: false, seating: "assigned", pendingLicence: null };
const CLASSROOM = { mode: "licensed", held: true, kind: "institution", prefix: "SEMP-UNI1",
  expiresAt: iso(90 * DAY), inGrace: false, seating: "assigned", pendingLicence: null };
const DEMO = { mode: "demo", held: false, kind: "individual", prefix: "SEMP-DEMO",
  expiresAt: null, inGrace: false, seating: "assigned", pendingLicence: null };

/** /v1/me/devices's answer: the licence's count and the holder's own phones. */
function phonesOf(app, { model = "activation", pool = false, active = 1, max = 2, devices } = {}) {
  return {
    app, licenseId: model === "none" ? "" : "L1", devicesActive: active, maxDevices: max, pool, model,
    devices: devices ?? [
      { deviceId: "p1", model: "Pixel 8", registeredAt: "2026-10-01T00:00:00Z", activated: true,
        thisDevice: false, activatedAt: ACTIVATED_AT, activatedBy: "self" },
      { deviceId: "p2", model: "Galaxy S22", registeredAt: "2026-10-02T00:00:00Z", activated: false,
        thisDevice: false },
    ],
  };
}

/** The page, with /v1/me carrying `licences` and each app's phones answered as given. */
async function open({ licences = { semper: SEMPER, semperclassroom: CLASSROOM }, semper = phonesOf("semper"),
  classroom = phonesOf("semperclassroom"), routes = {} } = {}) {
  await openPage("account", {
    routes: {
      "GET /v1/me": () => json(200, { email: "user@example.com", license: licences.semper || {}, licences }),
      "GET /v1/sessions": () => json(200, { sessions: [], quota: { used: 0, max: 25 } }),
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      "GET /v1/me/devices?app=semper": () => (typeof semper === "function" ? semper() : json(200, semper)),
      "GET /v1/me/devices?app=semperclassroom": () =>
        (typeof classroom === "function" ? classroom() : json(200, classroom)),
      ...routes,
    },
  });
}

const facts = (id) => {
  const terms = $(id).querySelectorAll("dt").map((el) => el.textContent);
  const values = $(id).querySelectorAll("dd").map((el) => el.textContent);
  return terms.map((term, i) => [term, values[i]]);
};
/** Each phone line as [model, state, the button's text or ""]. */
const phones = (id) => $(id).querySelectorAll("li").map((li) => [
  li.querySelector(".phone-model")?.textContent ?? li.textContent.replace(/\s+/g, " ").trim(),
  li.querySelector(".pill")?.textContent ?? "",
  li.querySelector("button")?.textContent ?? "",
]);
const status = () => [$("status").textContent, $("status").className];

/* ------------------------------------------------------------ sections */

test("each app gets its own section: its licence, its count and the holder's phones", async () => {
  await open();
  assert.deepEqual(sent(/devices/).sort(),
    ["GET /v1/me/devices?app=semper", "GET /v1/me/devices?app=semperclassroom"]);
  assert.equal($("semperCard").hidden, false);
  assert.equal($("classroomCard").hidden, false);
  assert.deepEqual(facts("semperFacts"),
    [["Mode", "Licensed"], ["Licence", "Individual · SEMP-4K2P"], ["Ends", "No end date"]]);
  assert.deepEqual(facts("classroomFacts"), [
    ["Mode", "Licensed"], ["Licence", "Institution · SEMP-UNI1"], ["Seats", "One seat each"],
    ["Ends", day(CLASSROOM.expiresAt)],
  ]);
  assert.equal($("semperCount").textContent, "Licensed on 1 of 2 phones.");
  assert.deepEqual(phones("semperPhones"), [
    ["Pixel 8", `Activated ${when(ACTIVATED_AT)}`, "Deactivate"],
    ["Galaxy S22", "Not activated (Demo)", ""],
  ]);
  assert.equal($("semperPhones").querySelector("button").getAttribute("aria-label"), "Deactivate Pixel 8");
  assert.equal($("semperPending").hidden, true, "no new licence waiting");
});

test("an institution pool counts the whole licence's phones", async () => {
  await open({ classroom: phonesOf("semperclassroom", { pool: true, active: 37, max: 40 }) });
  assert.equal($("classroomCount").textContent, "Your institution's licence: 37 of 40 phones in use.");
  assert.equal($("semperCount").textContent, "Licensed on 1 of 2 phones.");
});

test("a licence in its after-expiry grace says so on its end date", async () => {
  const expiresAt = iso(-2 * DAY);
  await open({ licences: { semper: { ...SEMPER, expiresAt, inGrace: true }, semperclassroom: CLASSROOM } });
  assert.deepEqual(facts("semperFacts")[2], ["Ends", `${day(expiresAt)} (now in its after-expiry grace)`]);
});

test("a phone with no model, and markup in a model, are shown as text", async () => {
  await open({ semper: phonesOf("semper", { devices: [
    { deviceId: "p9", model: "", activated: true, activatedAt: null },
    { deviceId: "p<8>", model: "<b>Phone</b>", activated: false },
  ] }) });
  assert.deepEqual(phones("semperPhones"), [
    ["Unnamed phone", "Activated", "Deactivate"],
    ["<b>Phone</b>", "Not activated (Demo)", ""],
  ]);
  assert.equal($("semperPhones").querySelectorAll("b").length, 0, "a model is text, never markup");
});

test("no phone signed in yet says so", async () => {
  await open({ semper: phonesOf("semper", { active: 0, devices: [] }) });
  assert.deepEqual(phones("semperPhones"),
    [["No phone has signed in to semper on this account yet.", "", ""]]);
});

test("a backend without `licences` shows no app sections and reads no phones", async () => {
  await openPage("account", { routes: {
    "GET /v1/me": () => json(200, { email: "user@example.com", license: { mode: "licensed", held: true } }),
    "GET /v1/sessions": () => json(200, { sessions: [], quota: { used: 0, max: 25 } }),
    "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
  } });
  assert.equal($("semperCard").hidden, true);
  assert.equal($("classroomCard").hidden, true);
  assert.deepEqual(sent(/devices/), []);
  assert.equal($("unbind").hidden, false, "Move as before");
});

test("an app /v1/me does not name gets no section", async () => {
  await open({ licences: { semper: SEMPER } });
  assert.equal($("semperCard").hidden, false);
  assert.equal($("classroomCard").hidden, true);
  assert.deepEqual(sent(/devices/), ["GET /v1/me/devices?app=semper"]);
  assert.equal($("unbindMt").hidden, true);
});

/* --------------------------------------------------- pending licence */

test("a new licence waiting says to activate it in the app, with no button", async () => {
  await open({ licences: { semper: SEMPER, semperclassroom: { ...CLASSROOM, pendingLicence: {
    licenseId: "L9", prefix: "SEMP-NEW9", kind: "institution", expiresAt: iso(365 * DAY), maxDevices: 3,
    supersedes: "L1",
  } } } });
  assert.equal($("classroomPending").hidden, false);
  assert.equal($("classroomPending").textContent,
    "A new licence (SEMP-NEW9) is ready — open the app and tap Activate new licence.");
  assert.equal($("classroomPending").querySelectorAll("button").length, 0);
  assert.equal($("semperPending").hidden, true);
});

/* ---------------------------------------------------------------- Move */

test("Move is hidden on a licence that runs activations, and shown on a lock licence", async () => {
  await open({ classroom: phonesOf("semperclassroom", { model: "lock", active: 1, max: 1, devices: [
    { deviceId: "p3", model: "Pixel 6", activated: true },
  ] }) });
  assert.equal($("unbind").hidden, true, "semper's phones each deactivate on their own");
  assert.equal($("unbindMt").hidden, false, "semper Classroom still holds one phone by a lock");
  assert.equal($("classroomCount").textContent, "Licensed on 1 of 1 phone.");
  assert.deepEqual(phones("classroomPhones"), [["Pixel 6", "Activated", ""]], "a lock moves, it does not deactivate");
});

test("Move is hidden until the phones say how the licence holds them", async () => {
  const held = deferred();
  await open({ semper: () => held.promise, classroom: phonesOf("semperclassroom", { model: "lock" }) });
  assert.equal($("unbind").hidden, true);
  assert.equal($("semperPhones").textContent.trim(), "Loading your phones…");
  held.resolve(json(200, phonesOf("semper", { model: "lock", max: 1 })));
  await settle();
  assert.equal($("unbind").hidden, false);
});

test("a Move on a lock licence reads that app's phones again", async () => {
  await open({
    semper: phonesOf("semper", { model: "lock", max: 1 }),
    routes: { "POST /v1/licenses/unbind": () => json(200, {}) },
  });
  confirms.answer(true);
  $("unbind").click();
  await settle();
  assert.deepEqual(sent(/unbind|devices\?app=semper$/),
    ["GET /v1/me/devices?app=semper", "POST /v1/licenses/unbind", "GET /v1/me/devices?app=semper"]);
  assert.doesNotMatch($("status").textContent, /wait|cooldown|days/i);
});

/* ---------------------------------------------------------------- Demo */

test("an app on Demo says so, and lists its phones with nothing to deactivate", async () => {
  await open({
    licences: { semper: DEMO, semperclassroom: CLASSROOM },
    semper: phonesOf("semper", { model: "none", active: 0, max: 0, devices: [
      { deviceId: "p1", model: "Pixel 8", activated: false },
      { deviceId: "p2", model: "Galaxy S22", activated: false },
    ] }),
  });
  assert.deepEqual(facts("semperFacts"), [["Mode", "Demo"], ["Licence", "None — semper is on Demo"]]);
  assert.equal($("semperCount").textContent, "semper is on Demo, so its phones run with Demo limits.");
  assert.deepEqual(phones("semperPhones"), [
    ["Pixel 8", "Not activated (Demo)", ""], ["Galaxy S22", "Not activated (Demo)", ""],
  ]);
  assert.equal($("unbind").hidden, true, "nothing to move on Demo");
});

test("a shared-seat licence (no phone model) is not called Demo", async () => {
  await open({
    licences: { semper: SEMPER, semperclassroom: { ...CLASSROOM, seating: "floating" } },
    classroom: phonesOf("semperclassroom", { model: "none", active: 0, max: 0, devices: [] }),
  });
  assert.equal($("classroomCount").textContent,
    "Your institution's seats are shared: a phone is Licensed while it has a seat.");
  assert.equal($("unbindMt").hidden, true);
});

/* ---------------------------------------------------------- Deactivate */

test("Deactivate asks first, then deactivates and reads only that app's phones again", async () => {
  let after = false;
  await open({
    semper: () => json(200, after
      ? phonesOf("semper", { active: 0, devices: [{ deviceId: "p1", model: "Pixel 8", activated: false }] })
      : phonesOf("semper")),
    routes: { "DELETE /v1/me/devices/p1?app=semper": () => {
      after = true;
      return json(200, { licenseId: "L1", deviceId: "p1", app: "semper", devicesActive: 0, maxDevices: 2,
        released: false });
    } },
  });
  confirms.answer(true);
  $("semperPhones").querySelector("button").click();
  await settle();
  const [asked] = confirms.asked;
  assert.match(asked, /^Deactivate semper on Pixel 8\?/);
  assert.match(asked, /runs semper as Demo until it is activated again/);
  assert.match(asked, /Its analyses stay on it/);
  assert.match(asked, /free at once/);
  assert.doesNotMatch(asked, /wait|cooldown|days/i, "no cooldown since ADR-033");
  assert.equal(confirms.cards[0].querySelector(".confirm").textContent, "Deactivate");
  assert.deepEqual(sent(/devices\/p1|devices\?app=semper$/), [
    "GET /v1/me/devices?app=semper", "DELETE /v1/me/devices/p1?app=semper", "GET /v1/me/devices?app=semper",
  ]);
  assert.equal(sent(/app=semperclassroom/).length, 1, "the other app's section is left alone");
  assert.deepEqual(status(), ["Deactivated. Pixel 8 runs semper as Demo until it is activated again.", "muted"]);
  assert.deepEqual(phones("semperPhones"), [["Pixel 8", "Not activated (Demo)", ""]]);
  assert.equal($("semperCount").textContent, "Licensed on 0 of 2 phones.");
});

test("a cancelled Deactivate sends nothing", async () => {
  await open();
  confirms.answer(false);
  $("semperPhones").querySelector("button").click();
  await settle();
  assert.deepEqual(sent(/DELETE/), []);
  assert.equal($("semperPhones").querySelector("button").disabled, false);
});

test("Deactivate is held from the first click until the DELETE answers: a second click sends nothing", async () => {
  let deletes = 0;
  await open({ routes: { "DELETE /v1/me/devices/p1?app=semper": () => (deletes++
    ? json(404, { detail: "activation_not_found" })
    : json(200, { licenseId: "L1", deviceId: "p1", app: "semper", devicesActive: 0, maxDevices: 2 })) } });
  confirms.answer(true, true);
  const button = $("semperPhones").querySelector("button");
  button.click();
  assert.equal(button.disabled, true, "disabled while its card is open");
  button.click();
  await settle();
  assert.equal(confirms.cards.length, 1, "one card");
  assert.deepEqual(sent(/DELETE/), ["DELETE /v1/me/devices/p1?app=semper"]);
  assert.deepEqual(status(), ["Deactivated. Pixel 8 runs semper as Demo until it is activated again.", "muted"],
    "no second answer overwrites the success");
});

test("a phone already deactivated is said in words, and the list is read again", async () => {
  await open({ routes: {
    "DELETE /v1/me/devices/p1?app=semper": () => json(404, { detail: "activation_not_found" }),
  } });
  confirms.answer(true);
  $("semperPhones").querySelector("button").click();
  await settle();
  assert.deepEqual(status(),
    ["That phone is no longer activated on your licence. The list is up to date now.", "muted err"]);
  assert.equal(sent(/devices\?app=semper$/).length, 2, "read again after the refusal");
});

test("semper Classroom's Deactivate names its own app, and another refusal keeps the list", async () => {
  await open({ routes: {
    "DELETE /v1/me/devices/p1?app=semperclassroom": () => json(409, { detail: "strange_thing" }),
  } });
  confirms.answer(true);
  const button = $("classroomPhones").querySelector("button");
  button.click();
  await settle();
  assert.match(confirms.asked[0], /^Deactivate semper Classroom on Pixel 8\?/);
  assert.deepEqual(status(), ["Could not deactivate the phone: the server refused it (ref: strange_thing)", "muted err"]);
  assert.equal(sent(/devices\?app=semperclassroom$/).length, 1, "not read again");
  assert.equal(button.disabled, false, "may be tried again");
});

test("a step-up refusal on Deactivate reads as the common sentence", async () => {
  await open({ routes: {
    "DELETE /v1/me/devices/p1?app=semper": () => json(403, { detail: "mfa_required" }),
  } });
  confirms.answer(true);
  $("semperPhones").querySelector("button").click();
  await settle();
  assert.deepEqual(status(), ["Set up two-factor authentication first.", "muted err"]);
});

/* ------------------------------------------------- loads and failures */

test("a failed phones read offers Retry in that app's section", async () => {
  let fail = true;
  await open({ semper: () => (fail ? json(503, { detail: "unavailable" }) : json(200, phonesOf("semper"))) });
  assert.match($("semperPhones").textContent, /Could not list your phones: unavailable/);
  assert.equal($("unbind").hidden, true, "Move waits until the licence's model is known");
  assert.equal(phones("classroomPhones").length, 2, "the other app is unaffected");
  fail = false;
  $("semperPhones").querySelector("button[data-retry]").click();
  await settle();
  assert.equal(phones("semperPhones").length, 2);
});

test("an older phones answer arriving after a newer one is dropped", async () => {
  const first = deferred();
  let calls = 0;
  await open({
    licences: { semper: { ...SEMPER, kind: "institution", seating: "floating" }, semperclassroom: CLASSROOM },
    semper: () => (++calls === 1 ? first.promise : json(200, phonesOf("semper", { active: 2 }))),
    routes: { "POST /v1/licenses/release": () => json(200, {}) },
  });
  // A re-read of the account (here, after giving a seat back) reads the phones again.
  await rereadViaRelease();
  assert.equal(calls, 2);
  first.resolve(json(200, phonesOf("semper", { active: 0, devices: [] })));
  await settle();
  assert.equal($("semperCount").textContent, "Licensed on 2 of 2 phones.");
  assert.equal(phones("semperPhones").length, 2);
});

/** Re-read the account through the Give up my seat button's reload. */
async function rereadViaRelease() {
  $("release").hidden = false;
  confirms.answer(true);
  $("release").click();
  await settle();
}

/* ---------------------------------------------- the top licence card */

test("the top card names semper's licence, and a Classroom licence is not read as Demo", async () => {
  await open({ licences: { semper: DEMO, semperclassroom: CLASSROOM }, semper: phonesOf("semper", { model: "none" }) });
  assert.equal($("licenceTitle").textContent, "semper licence");
  assert.equal($("explain").textContent,
    "semper is on Demo on this account. Your semper Classroom licence is in its card below. " +
    "Ask your institution's IT or semper support if you need a licence for semper too.");
});

test("on Demo for every app, the top card says Demo as before", async () => {
  await open({ licences: { semper: DEMO, semperclassroom: DEMO },
    semper: phonesOf("semper", { model: "none" }), classroom: phonesOf("semperclassroom", { model: "none" }) });
  assert.match($("explain").textContent, /^You are on Demo\. Analyses are capped/);
});
