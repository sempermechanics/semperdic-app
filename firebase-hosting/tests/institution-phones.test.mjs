// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The institution page's Phones card (console/institution/phones.js, ADR-033):
// on a licence that names its app, the pool's count, each active phone of a
// member on the licence's domain, and Deactivate; nothing on a licence from
// before the split, which keeps one phone per member on the roster.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, sent, $, loadAuth, deferred,
} from "./harness.mjs";

await loadAuth();
const { when } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const L1 = "/v1/institutions/licenses/L1";
const DEVICES = `${L1}/devices`;
const AT = "2026-10-20T09:30:00Z";

/** An institution licence for semper Classroom, on the activation model. */
const roster = (license = {}) => ({
  license: {
    keyPrefix: "SEMP-UNI1", seating: "assigned", maxSeats: 10, seatsUsed: 2, status: "redeemed",
    domainLock: "uni.edu", app: "semperclassroom", maxDevices: 10, devicesActive: 3, ...license,
  },
  seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active" },
    { uid: "u2", email: "ben@uni.edu", status: "active" },
  ],
  invites: [],
});

const PHONES = [
  { deviceId: "dev-ana-1", uid: "u1", app: "semperclassroom", status: "active", activatedAt: AT,
    activatedBy: "self", deviceModel: "Pixel 6", email: "ana@uni.edu" },
  { deviceId: "dev-ana-2", uid: "u1", app: "semperclassroom", status: "active", activatedAt: AT,
    activatedBy: "staff", deviceModel: "Galaxy S22+", email: "ana@uni.edu" },
  { deviceId: "dev-ben-1", uid: "u2", app: "semperclassroom", status: "active", activatedAt: AT,
    activatedBy: "it", deviceModel: "", email: "ben@uni.edu" },
];

const pool = (devices = PHONES, extra = {}) => ({
  licenseId: "L1", app: "semperclassroom", devicesActive: devices.length, maxDevices: 10,
  pool: true, model: "activation", devices, ...extra,
});

/** The page deep-linked to L1, its roster `license`, and the phones `answer`. */
async function open({ license, answer = pool(), routes = {} } = {}) {
  await openPage("institution", {
    search: "?license=L1",
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }] }),
      "GET /v1/me": () => json(200, { role: "user" }),
      [`GET ${L1}/seats`]: () => json(200, roster(license)),
      [`GET ${L1}/summary`]: () => json(200, { counts: {} }),
      [`GET ${DEVICES}`]: () => json(200, answer),
      ...routes,
    },
  });
}

const text = (id) => $(id).textContent.replace(/\s+/g, " ").trim();
const status = () => [$("status").textContent, $("status").className];
const rows = () => $("phoneRows").querySelectorAll("tr").map((tr) =>
  tr.cells.map((td) => td.textContent.replace(/\s+/g, " ").trim()));
const deactivate = (id) => $("phoneRows").querySelector(`button[data-phone="${id}"]`);

/* ------------------------------------------------------------- the pool */

test("a licence that names its app shows its app and the pool's count", async () => {
  await open();
  assert.equal($("phonesCard").hidden, false);
  assert.equal(text("phonesPool"), "3 of 10 phones activated on this licence, for semper Classroom.");
  assert.match(text("summary"), /^SEMP-UNI1 semper Classroom one seat each/, "the app is a pill on the licence");
  assert.match(text("phonesHelp"), /Members activate a phone themselves, in the app\./);
  assert.match(text("phonesHelp"), /Only members at uni\.edu are listed\.$/);
  assert.deepEqual(sent(/devices/), [`GET ${DEVICES}`], "read after the roster, once");
});

test("a semper licence is named so, and a pool with no limit says so", async () => {
  await open({ license: { app: "semper" }, answer: pool(PHONES.slice(0, 1), { app: "semper", maxDevices: 1000000 }) });
  assert.equal(text("phonesPool"), "1 phone activated on this licence, for semper, with no limit on how many.");
  assert.match(text("summary"), /^SEMP-UNI1 semper one seat each/);
});

/* ------------------------------------------------------------- the list */

test("each active phone is listed: member, phone, when activated, and by whom when not the member", async () => {
  await open();
  const at = when(AT);
  assert.deepEqual(rows(), [
    ["ana@uni.edu", "Pixel 6", at, "Deactivate"],
    ["ana@uni.edu", "Galaxy S22+", `${at} by semper support`, "Deactivate"],
    ["ben@uni.edu", "—", `${at} by your institution's IT`, "Deactivate"],
  ]);
  assert.equal(deactivate("dev-ana-1").getAttribute("aria-label"), "Deactivate ana@uni.edu's phone (Pixel 6)");
  assert.equal(deactivate("dev-ben-1").getAttribute("aria-label"), "Deactivate ben@uni.edu's phone");
});

test("no phone activated yet is said, and markup in a phone's fields is text", async () => {
  await open({ answer: pool([]) });
  assert.equal(text("phonesPool"), "0 of 10 phones activated on this licence, for semper Classroom.");
  assert.deepEqual(rows(), [["No phone is activated on this licence yet."]]);

  reset();
  await open({ answer: pool([{ ...PHONES[0], deviceModel: "<img src=x>", email: "<b>x</b>@uni.edu" }]) });
  assert.deepEqual(rows()[0].slice(0, 2), ["<b>x</b>@uni.edu", "<img src=x>"]);
  assert.equal($("phoneRows").querySelector("img"), null);
  assert.equal($("phoneRows").querySelector("b"), null);
});

test("a failed read says so in the card, and Retry reads it again", async () => {
  await open({ routes: { [`GET ${DEVICES}`]: () => json(500, { detail: "internal_error" }) } });
  assert.match(rows()[0][0], /^Could not load the phones: Something went wrong on the server\./);
  assert.equal(text("phonesPool"), "");
  net.routes[`GET ${DEVICES}`] = () => json(200, pool());
  $("phoneRows").querySelector("button[data-retry]").click();
  await settle();
  assert.equal(rows().length, 3);
  assert.deepEqual(sent(/devices/), [`GET ${DEVICES}`, `GET ${DEVICES}`]);
});

/* ----------------------------------------------------------- Deactivate */

test("Deactivate asks first, naming the member and phone; declining sends nothing", async () => {
  await open();
  confirms.answer(false);
  deactivate("dev-ana-1").click();
  await settle();
  assert.deepEqual(sent(/DELETE/), []);
  const card = confirms.cards[0];
  assert.equal(card.querySelector("h2").textContent, "Deactivate ana@uni.edu's phone");
  assert.equal(card.querySelector(".confirm").textContent, "Deactivate");
  assert.match(confirms.asked[0], /^Deactivate Pixel 6 for ana@uni\.edu\?/);
  assert.match(confirms.asked[0], /signed out of their semper account/);
  assert.match(confirms.asked[0], /frees at once/);
  assert.match(confirms.asked[0], /Every analysis on the phone stays on it\./);
  assert.deepEqual(status(), ["", "muted"]);
});

test("a confirmed Deactivate sends the DELETE, then reads the roster, the list and the count again", async () => {
  await open({ routes: { [`DELETE ${DEVICES}/dev-ana-1`]: () => json(200, { devicesActive: 2 }) } });
  net.routes[`GET ${DEVICES}`] = () => json(200, pool(PHONES.slice(1)));
  confirms.answer(true);
  deactivate("dev-ana-1").click();
  await settle();
  assert.deepEqual(sent(/seats$|devices/), [
    `GET ${L1}/seats`, `GET ${DEVICES}`,
    `DELETE ${DEVICES}/dev-ana-1`,
    `GET ${L1}/seats`, `GET ${DEVICES}`,
  ]);
  assert.equal(text("phonesPool"), "2 of 10 phones activated on this licence, for semper Classroom.");
  assert.equal(rows().length, 2);
  assert.equal(deactivate("dev-ana-1"), null);
  assert.deepEqual(status(),
    ["ana@uni.edu's phone is deactivated and signed out. Its place in the count is free.", "muted"]);
  assert.equal(document.activeElement, deactivate("dev-ana-2"), "focus carries on from the next phone");
});

test("the success line waits for the phones to be read again", async () => {
  const again = deferred();
  await open({ routes: { [`DELETE ${DEVICES}/dev-ana-1`]: () => json(200, {}) } });
  net.routes[`GET ${DEVICES}`] = () => again.promise;
  confirms.answer(true);
  deactivate("dev-ana-1").click();
  await settle();
  assert.notEqual(status()[0], "ana@uni.edu's phone is deactivated and signed out. Its place in the count is free.");
  again.resolve(json(200, pool(PHONES.slice(1))));
  await settle();
  assert.match(status()[0], /^ana@uni\.edu's phone is deactivated/);
});

test("Deactivate is held from the first click until the DELETE answers: a second click sends nothing", async () => {
  let deletes = 0;
  await open({ routes: { [`DELETE ${DEVICES}/dev-ana-1`]: () => (deletes++
    ? json(404, { detail: "activation_not_found" })
    : json(200, { devicesActive: 2 })) } });
  net.routes[`GET ${DEVICES}`] = () => json(200, pool(PHONES.slice(1)));
  confirms.answer(true, true);
  const button = deactivate("dev-ana-1");
  button.click();
  assert.equal(button.disabled, true, "disabled while its card is open");
  button.click();
  await settle();
  assert.equal(confirms.cards.length, 1, "one card");
  assert.deepEqual(sent(/DELETE/), [`DELETE ${DEVICES}/dev-ana-1`]);
  assert.deepEqual(status(),
    ["ana@uni.edu's phone is deactivated and signed out. Its place in the count is free.", "muted"],
    "no second answer overwrites the success");
});

test("a declined or refused Deactivate gives the button back", async () => {
  await open({ routes: { [`DELETE ${DEVICES}/dev-ben-1`]: () => json(503, { detail: "unavailable" }) } });
  confirms.answer(false);
  deactivate("dev-ben-1").click();
  await settle();
  assert.equal(deactivate("dev-ben-1").disabled, false, "declined");
  confirms.answer(true);
  deactivate("dev-ben-1").click();
  await settle();
  assert.equal(status()[1], "muted err");
  assert.equal(deactivate("dev-ben-1").disabled, false, "refused");
  assert.equal(sent(/DELETE/).length, 1);
});

test("a refused Deactivate is worded", async () => {
  const cases = [
    ["activation_not_found", 404, "That phone is no longer activated on this licence. The list below is read again."],
    ["license_not_found", 404, "No licence with that id that you administer."],
    ["rate_limited", 429, "Too many requests just now — wait a moment and try again."],
    ["claim_contended", 503, "Busy just now — try again."],
    ["firestore_unreachable", 503,
      "Could not deactivate the phone: The server could not reach its database just now. Try again in a moment."],
    ["strange_code", 409, "Could not deactivate the phone: the server refused it (ref: strange_code)"],
  ];
  for (const [code, httpStatus, words] of cases) {
    reset();
    await open({ routes: { [`DELETE ${DEVICES}/dev-ben-1`]: () => json(httpStatus, { detail: code }) } });
    confirms.answer(true);
    deactivate("dev-ben-1").click();
    await settle();
    assert.deepEqual(status(), [words, "muted err"], code);
    // A phone no longer there: the list is read again, so it goes from the page.
    const reads = code === "activation_not_found" ? 2 : 1;
    assert.equal(sent(/GET .*devices/).length, reads, code);
  }
});

/* --------------------------------------------- licences without a pool */

test("a licence from before the split (no app) shows no Phones card and reads none", async () => {
  await open({ license: { app: "", maxDevices: null } });
  assert.equal($("phonesCard").hidden, true);
  assert.deepEqual(sent(/devices/), []);
  assert.doesNotMatch(text("summary"), /Classroom/);
  assert.equal($("rosterCard").hidden, false, "its roster is as it was");
});

test("an answer on the lock model hides the card", async () => {
  await open({ answer: pool([], { model: "lock", pool: false }) });
  assert.equal($("phonesCard").hidden, true);
  assert.equal($("phoneRows").innerHTML, "");
});

test("opening another licence takes the last one's phones off the page", async () => {
  await open({
    routes: {
      "GET /v1/institutions/licenses/L2/seats": () => json(200, { ...roster({ app: "" }), seats: [] }),
      "GET /v1/institutions/licenses/L2/summary": () => json(200, { counts: {} }),
    },
  });
  assert.equal($("phonesCard").hidden, false);
  $("licenseId").value = "L2";
  $("load").click();
  await settle();
  assert.equal($("phonesCard").hidden, true);
  assert.equal($("phoneRows").querySelector("button"), null);
  assert.deepEqual(sent(/L2.*devices/), []);
});

test("a phones answer for a licence no longer open is dropped", async () => {
  const late = deferred();
  await open({
    routes: {
      [`GET ${DEVICES}`]: () => late.promise,
      "GET /v1/institutions/licenses/L2/seats": () => json(200, { ...roster({ app: "" }), seats: [] }),
      "GET /v1/institutions/licenses/L2/summary": () => json(200, { counts: {} }),
    },
  });
  $("licenseId").value = "L2";
  $("load").click();
  await settle();
  late.resolve(json(200, pool()));
  await settle();
  assert.equal($("phonesCard").hidden, true);
  assert.equal($("phoneRows").querySelector("button"), null);
});

test("a Deactivate that finds the sign-in over leaves the line that says so", async () => {
  await open({ routes: { [`DELETE ${DEVICES}/dev-ana-1`]: () => json(401, { detail: "invalid_token" }) } });
  confirms.answer(true);
  deactivate("dev-ana-1").click();
  await settle();
  assert.match(status()[0], /^Your sign-in has expired\. Sign in again/);
  assert.equal(sent(/GET .*devices/).length, 1, "nothing read again for a signed-out page");
});
