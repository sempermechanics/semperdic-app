// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The institution roster (console/institution/institution.js): who may see
// it, what the seat count and rows say, and what IT's actions send and report.
// Each case opens a fresh copy of the page on the fake SDK and DOM
// (harness.mjs).
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, sent, readyUser, $, loadAuth, deferred,
} from "./harness.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const DAY = 86400e3;
const SEATS = "/v1/institutions/licenses/L1/seats";

const assigned = {
  license: { keyPrefix: "SEMP-UNI1", seating: "assigned", maxSeats: 10, seatsUsed: 3, status: "redeemed" },
  seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active", deviceIdLock: "device-abcdef-123" },
    { uid: "u2", email: "ben@uni.edu", status: "disabled" },
    { uid: "u3", email: "cy@uni.edu", status: "revoked" },
  ],
  invites: [{ id: "i1", email: "dee@uni.edu" }, { id: "i2", email: "eve@uni.edu" }],
};

/** The seat counts every opened roster reads after it (roster-tools.js), for each licence a test opens. */
const SUMMARIES = Object.fromEntries(["L1", "L%201", "L2"].map((id) => [
  `GET /v1/institutions/licenses/${id}/summary`, () => json(200, { counts: { assigned: 3 } }),
]));

/** The roster page for licence L1 (deep-linked unless `search` says otherwise). */
async function open({ roster = assigned, routes = {}, search = "?license=L1", user } = {}) {
  await openPage("institution", {
    search,
    user,
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }] }),
      [`GET ${SEATS}`]: () => json(200, roster),
      "GET /v1/me": () => json(200, { role: "user" }),
      ...SUMMARIES,
      ...routes,
    },
  });
}

const status = () => [$("status").textContent, $("status").className];
const summary = () => $("summary").textContent.replace(/\s+/g, " ").trim();
const rows = () => $("rows").querySelectorAll("tr").map((tr) =>
  tr.cells.map((td) => td.textContent.replace(/\s+/g, " ").trim()));
const button = (act, uid) => $("rows").querySelector(`button[data-act="${act}"][data-uid="${uid}"]`);

/* --------------------------------------------------------------- access */

test("an IT contact gets Institution seats | Your account, staff all three", async () => {
  await open();
  const tabs = () => $("switch").querySelectorAll("a").map((a) => [a.textContent, a.getAttribute("aria-current") ?? ""]);
  assert.equal($("switch").hidden, false);
  assert.deepEqual(tabs(), [["Institution seats", "page"], ["Your account", ""]]);
  await settle();
  reset();
  await open({ routes: { "GET /v1/me": () => json(200, { role: "admin" }) } });
  assert.deepEqual(tabs(), [["Operator", ""], ["Institution seats", "page"], ["Your account", ""]]);
});

test("a deep-linked roster is read alongside the licence list, not after it", async () => {
  const list = deferred();
  await open({ routes: { "GET /v1/institutions/licenses": () => list.promise } });
  assert.deepEqual(sent().sort(), ["GET /v1/institutions/licenses", `GET ${SEATS}`]);
  assert.equal($("summary").textContent, "", "not shown before the list says this address may");
  list.resolve(json(200, { licenses: [{ id: "L1" }] }));
  await settle();
  assert.match(summary(), /^SEMP-UNI1/);
  assert.equal(sent(/seats/).length, 1, "the roster read early is the one shown");
});

test("an address no licence names is told so, and sees no roster form", async () => {
  await openPage("institution", {
    user: readyUser({ email: "stranger@example.com" }),
    search: "?license=L1",
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      // Read alongside the list; the backend refuses it to a stranger.
      "GET /v1/institutions/licenses/L1/seats": () => json(404, { detail: "license_not_found" }),
    },
  });
  assert.equal($("app").hidden, true);
  assert.equal($("notAdmin").hidden, false);
  assert.equal($("notAdminWho").textContent, "stranger@example.com");
  assert.equal($("status").textContent, "", "the refused roster is never reported");
  assert.equal($("licenseId").value, "", "the deep link is not followed");
});

test("an unverified address is treated as administering nothing", async () => {
  await openPage("institution", {
    routes: { "GET /v1/institutions/licenses": () => json(403, { detail: "email_not_verified" }) },
  });
  assert.equal($("notAdmin").hidden, false);
});

test("a failed licence check leaves the page usable and says why", async () => {
  await open({ routes: { "GET /v1/institutions/licenses": () => json(500, { detail: "boom" }) }, search: "" });
  assert.equal($("app").hidden, false);
  assert.equal($("notAdmin").hidden, true);
  assert.deepEqual(status(), ["Could not list your institution licences: boom", "muted err"]);
});

test("a deep link opens that licence's roster", async () => {
  await open({ search: "?license=L%201", routes: { "GET /v1/institutions/licenses/L%201/seats": () => json(200, assigned) } });
  assert.equal($("licenseId").value, "L 1");
  assert.deepEqual(sent(/seats/), ["GET /v1/institutions/licenses/L%201/seats"]);
  assert.equal($("rosterCard").hidden, false);
});

test("without a deep link the id is typed; blank asks nothing, Enter opens it", async () => {
  await open({ search: "" });
  $("licenseId").value = "   ";
  $("load").click();
  await settle();
  assert.deepEqual(sent(/seats/), []);
  $("licenseId").value = " L1 ";
  $("licenseId").dispatch("keydown", { key: "Enter" });
  await settle();
  assert.deepEqual(sent(/seats/), [`GET ${SEATS}`]);
});

/* ------------------------------------------------------------ the count */

test("an assigned licence counts seats taken and the invites apart", async () => {
  await open();
  assert.equal(summary(),
    "SEMP-UNI1 one seat each redeemed 3 of 10 seats taken. 2 more are invited and take a seat " +
    "when they first sign in, if one is free.");
  assert.match($("rosterHelp").textContent, /^Everyone here is Licensed./);
});

test("a floating licence counts seats in use against the roster", async () => {
  const expiresAt = new Date(Date.now() + 90 * DAY).toISOString();
  await open({ roster: {
    license: { keyPrefix: "SEMP-UNI2", seating: "floating", maxSeats: 5, leasesActive: 1, seatsUsed: 4, expiresAt },
    seats: [1, 2, 3, 4].map((n) => ({ uid: `u${n}`, status: "active" })),
    invites: [{ id: "i1", email: "x@uni.edu" }],
  } });
  assert.equal(summary(),
    `SEMP-UNI2 shared seats unused ends ${day(expiresAt)} 1 of 5 seats in use right now, across 4 people ` +
    "on the roster. 1 more is invited and join the roster when they first sign in.");
  assert.match($("rosterHelp").textContent, /only the number of seats above at the same time/);
});

test("no cap reads unlimited, and no invites adds nothing", async () => {
  await open({ roster: { license: { seating: "assigned", seatsUsed: 0 }, seats: [], invites: [] } });
  assert.equal(summary(), "licence one seat each unused 0 of unlimited seats taken.");
  assert.deepEqual(rows(), [["Nobody on this licence yet."]]);
});

/* ------------------------------------------------------------- the rows */

test("each member row offers only what applies to that seat", async () => {
  await open();
  assert.deepEqual(rows(), [
    ["ana@uni.edu", "active", "—", "device-abc…", "New phone Pause Remove"],
    ["ben@uni.edu", "paused", "—", "not yet", "Resume Remove"],
    ["dee@uni.edu", "invited", "—", "joins at first sign-in", "Withdraw"],
    ["eve@uni.edu", "invited", "—", "joins at first sign-in", "Withdraw"],
  ], "a removed member is under Removed, not among the people on the licence");
  $("rowChips").querySelector('button[data-chip="removed"]').click();
  assert.deepEqual(rows(), [["cy@uni.edu", "removed", "—", "not yet", "add again to restore"]]);
  assert.equal($("rows").querySelectorAll('[data-uid="u3"]').length, 0, "nothing to act on for a removed member");
});

test("a seat bound only on semper Classroom offers New phone, read from the old field", async () => {
  // Every backend writes the old field (ADR-030, TD-330): a seat this one
  // bound holds both, one not yet migrated only the old. A new field alone was
  // left behind by a backend from before the rename that cleared the old: stale.
  await open({ roster: { ...assigned, invites: [], seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active", deviceIdLockSemperClassroom: "sc-device-123",
      deviceIdLockMaterialTesting: "sc-device-123" },
    { uid: "u2", email: "ben@uni.edu", status: "active", deviceIdLockMaterialTesting: "mt-device-456" },
    { uid: "u3", email: "cy@uni.edu", status: "active", deviceIdLockSemperClassroom: "stale-device-7" },
  ] } });
  assert.deepEqual(rows(), [
    ["ana@uni.edu", "active", "—", "semper Classroom sc-device-…", "New phone Pause Remove"],
    ["ben@uni.edu", "active", "—", "semper Classroom mt-device-…", "New phone Pause Remove"],
    ["cy@uni.edu", "active", "—", "not yet", "Pause Remove"],
  ]);
});

/* -------------------------------------------- filter, sort and pages */

const cellsOf = (col) => rows().map((r) => r[col]);
const sortHead = (key) => $("rosterCard").querySelector(`button[data-sort="${key}"]`);
const ariaSort = (key) => sortHead(key).parentNode.getAttribute("aria-sort");

test("a long roster shows a hundred rows at a time, and says how many there are", async () => {
  const seats = Array.from({ length: 230 }, (_, i) => ({
    uid: `u${i}`, email: `m${String(i).padStart(3, "0")}@uni.edu`, status: "active",
  }));
  await open({ roster: { ...assigned, seats, invites: [] } });
  assert.equal(rows().length, 101, "100 rows and the Show more row");
  assert.equal($("rowCount").textContent, "Showing 100 of 230");
  const more = () => $("rows").querySelector("button[data-more]");
  assert.equal(more().textContent, "Show 100 more");
  more().click();
  assert.equal(rows().length, 201);
  assert.equal(document.activeElement.dataset.uid, "u100", "focus on the first row just shown");
  more().click();
  assert.equal(rows().length, 230);
  assert.equal(more(), null);
  assert.equal($("rowCount").textContent, "Showing 230 of 230");
});

test("the filter narrows the roster by address, status or phone", async () => {
  await open();
  const filter = (text) => {
    $("rowFilter").value = text;
    $("rowFilter").dispatch("input");
  };
  filter("paused");
  assert.deepEqual(cellsOf(0), ["ben@uni.edu"]);
  filter("DEE@");
  assert.deepEqual(cellsOf(0), ["dee@uni.edu"]);
  filter("device-abc");
  assert.deepEqual(cellsOf(0), ["ana@uni.edu"]);
  assert.equal($("rowCount").textContent, "Showing 1 of 1 matching, 5 on the roster");
  filter("invited");
  assert.deepEqual(cellsOf(0), ["dee@uni.edu", "eve@uni.edu"]);
  filter("nobody-here");
  assert.deepEqual(rows(), [["Nobody on the roster matches that filter."]]);
  filter("");
  assert.equal(rows().length, 4, "the removed member is under Removed");
  assert.equal($("rowCount").textContent, "Showing 4 of 4, 5 on the roster");
});

test("Member and Status sort the roster, and say which way", async () => {
  await open({ roster: { ...assigned, invites: [{ id: "i1", email: "abe@uni.edu" }] } });
  assert.equal(ariaSort("member"), "none");
  sortHead("member").click();
  assert.equal(ariaSort("member"), "ascending");
  assert.deepEqual(cellsOf(0), ["abe@uni.edu", "ana@uni.edu", "ben@uni.edu"]);
  sortHead("member").click();
  assert.equal(ariaSort("member"), "descending");
  assert.deepEqual(cellsOf(0), ["ben@uni.edu", "ana@uni.edu", "abe@uni.edu"]);
  sortHead("status").click();
  assert.equal(ariaSort("status"), "ascending");
  assert.equal(ariaSort("member"), "none");
  assert.deepEqual(cellsOf(1), ["active", "invited", "paused"]);
});

test("the chips show each status with its count, and keep focus on the one pressed", async () => {
  await open();
  const chips = () => $("rowChips").querySelectorAll("button").map((b) =>
    [b.textContent.replace(/\s+/g, " ").trim(), b.getAttribute("aria-pressed")]);
  assert.deepEqual(chips(), [
    ["Current 4", "true"], ["Active 1", "false"], ["Paused 1", "false"], ["Invited 2", "false"], ["Removed 1", "false"],
  ]);
  $("rowChips").querySelector('button[data-chip="invited"]').click();
  assert.deepEqual(cellsOf(0), ["dee@uni.edu", "eve@uni.edu"]);
  assert.equal($("rowCount").textContent, "Showing 2 of 2, 5 on the roster");
  assert.equal(document.activeElement.dataset.chip, "invited");
  $("rowFilter").value = "eve";
  $("rowFilter").dispatch("input");
  assert.deepEqual(cellsOf(0), ["eve@uni.edu"], "the filter works within the chip");
});

test("a status with nobody in it has no chip; a roster of removals says where they are", async () => {
  await open({ roster: { ...assigned, invites: [], seats: [{ uid: "u3", email: "cy@uni.edu", status: "revoked" }] } });
  assert.deepEqual($("rowChips").querySelectorAll("button").map((b) => b.dataset.chip), ["current", "removed"]);
  assert.deepEqual(rows(), [["Everyone on this licence has been removed. Press Removed to see them."]]);
});

test("Seat sorts by when a lease runs out, Phone by the phone; nobody's goes last", async () => {
  const soon = new Date(Date.now() + 3600e3).toISOString();
  const later = new Date(Date.now() + 7200e3).toISOString();
  await open({ roster: { ...assigned, invites: [], seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active" },
    { uid: "u2", email: "ben@uni.edu", status: "active", leaseExpiresAt: later, deviceIdLock: "zz-phone-0001" },
    { uid: "u3", email: "cy@uni.edu", status: "active", leaseExpiresAt: soon, deviceIdLock: "aa-phone-0001" },
  ] } });
  sortHead("seat").click();
  assert.equal(ariaSort("seat"), "ascending");
  assert.deepEqual(cellsOf(0), ["cy@uni.edu", "ben@uni.edu", "ana@uni.edu"]);
  sortHead("phone").click();
  assert.deepEqual(cellsOf(0), ["cy@uni.edu", "ben@uni.edu", "ana@uni.edu"]);
  sortHead("phone").click();
  assert.deepEqual(cellsOf(0), ["ana@uni.edu", "ben@uni.edu", "cy@uni.edu"]);
});

test("the seat manager stays hidden until the licence list names this address", async () => {
  const list = deferred();
  await open({ routes: { "GET /v1/institutions/licenses": () => list.promise } });
  assert.equal($("app").hidden, true);
  assert.equal($("status").textContent, "Checking your access…");
  list.resolve(json(200, { licenses: [{ id: "L1" }] }));
  await settle();
  assert.equal($("app").hidden, false);
});

test("opening another licence while one loads shows the one asked for last", async () => {
  const slow = deferred();
  await open({ routes: {
    [`GET ${SEATS}`]: () => slow.promise,
    "GET /v1/institutions/licenses/L2/seats": () => json(200, {
      ...assigned, license: { ...assigned.license, keyPrefix: "SEMP-UNI2" } }),
  } });
  assert.equal($("rows").querySelectorAll("tr.placeholder").length, 3, "placeholders, not the last roster");
  $("licenseId").value = "L2";
  $("load").disabled = false; // a person can also press Enter in the box
  $("licenseId").dispatch("keydown", { key: "Enter" });
  await settle();
  assert.match(summary(), /^SEMP-UNI2/);
  slow.resolve(json(200, assigned));
  await settle();
  assert.match(summary(), /^SEMP-UNI2/, "L1 answered late and was dropped");
});

test("a roster that fails to load offers Retry", async () => {
  let fail = true;
  await open({ routes: { [`GET ${SEATS}`]: () => (fail ? json(500, { detail: "boom" }) : json(200, assigned)) } });
  assert.deepEqual(status(), ["Could not load: boom", "muted err"]);
  fail = false;
  $("rows").querySelector("button[data-retry]").click();
  await settle();
  assert.match(summary(), /^SEMP-UNI1/);
});

test("a licence that is not yours reads as not found, and the old roster goes", async () => {
  await open();
  net.routes[`GET ${SEATS}`] = () => json(404, { detail: "license_not_found" });
  $("load").click();
  await settle();
  assert.deepEqual(status(), ["No licence with that id that you administer.", "muted err"]);
  assert.equal($("rosterCard").hidden, true);
  assert.equal($("summary").innerHTML, "");

  net.routes[`GET ${SEATS}`] = () => json(500, { detail: "boom" });
  $("load").click();
  await settle();
  assert.deepEqual(status(), ["Could not load: boom", "muted err"]);
});

/* -------------------------------------------------------------- actions */

/** Click `act` on member `uid`, with the PATCH/DELETE answered by `response`. */
async function act(actName, uid, response = () => json(200, {}), confirm = null) {
  await open({ routes: {
    [`PATCH ${SEATS}/${uid}`]: response,
    [`DELETE ${SEATS}/${uid}`]: response,
  } });
  if (confirm != null) confirms.answer(confirm);
  button(actName, uid).click();
  await settle();
}

test("Hold, Resume and New phone send their change and reload the roster", async () => {
  for (const [actName, uid, body] of [
    ["hold", "u1", { enabled: false }],
    ["unhold", "u2", { enabled: true }],
    ["clear", "u1", { clearDeviceLock: true }],
  ]) {
    reset();
    await act(actName, uid, undefined, actName === "clear" ? true : null);
    const patch = net.requests.find((r) => r.method === "PATCH");
    assert.equal(patch.url.endsWith(`${SEATS}/${uid}`), true);
    assert.deepEqual(JSON.parse(patch.body), body, actName);
    assert.deepEqual(sent(/seats/), [`GET ${SEATS}`, `PATCH ${SEATS}/${uid}`, `GET ${SEATS}`]);
    assert.deepEqual(status(), ["", "muted"]);
  }
});

test("Remove asks first, naming the member; declining sends nothing", async () => {
  await act("remove", "u1", undefined, false);
  assert.deepEqual(sent(/DELETE/), []);
  assert.match(confirms.asked[0], /^Remove ana@uni\.edu from this licence\?/);
  assert.equal(confirms.cards[0].querySelector("h2").textContent, "Remove ana@uni.edu");
  reset();
  await act("remove", "u1", undefined, true);
  assert.deepEqual(sent(/DELETE/), [`DELETE ${SEATS}/u1`]);
});

test("New phone asks first, naming the member; declining sends nothing", async () => {
  await act("clear", "u1", undefined, false);
  assert.deepEqual(sent(/PATCH/), []);
  assert.match(confirms.asked[0], /^Free ana@uni\.edu for a new phone\?/);
  // One name for the action (AE3-106): the button, the card and its confirm.
  assert.equal(confirms.cards[0].querySelector("h2").textContent, "New phone for ana@uni.edu");
  assert.equal(confirms.cards[0].querySelector(".confirm").textContent, "New phone");
});

test("each row's buttons name the member they act on", async () => {
  await open();
  assert.equal(button("remove", "u1").getAttribute("aria-label"), "Remove ana@uni.edu");
  assert.equal(button("hold", "u1").getAttribute("aria-label"), "Pause ana@uni.edu");
  assert.equal(button("clear", "u1").getAttribute("aria-label"), "New phone for ana@uni.edu");
  assert.equal(button("unhold", "u2").getAttribute("aria-label"), "Resume ben@uni.edu");
  assert.equal($("rows").querySelector('button[data-invite="i1"]').getAttribute("aria-label"),
    "Withdraw the invitation to dee@uni.edu");
});

test("after a change redraws the roster, focus is back on that member's row", async () => {
  await act("hold", "u1", () => json(200, {}));
  // The reload answers with the same roster; u1's buttons are new elements.
  assert.equal(document.activeElement.dataset.uid, "u1");
  assert.equal(document.activeElement.localName, "button");
  reset();
  await act("remove", "u1", () => json(200, {}), true);
  assert.equal(document.activeElement.dataset.uid, "u1", "a member still listed keeps the focus");
  reset();
  await open({ routes: { [`PATCH ${SEATS}/u1`]: () => json(200, {}) } });
  net.routes[`GET ${SEATS}`] = () => json(200, { ...assigned, seats: assigned.seats.slice(1) });
  confirms.answer(true);
  button("remove", "u1").click();
  net.routes[`DELETE ${SEATS}/u1`] = () => json(200, {});
  await settle();
  assert.equal(document.activeElement, $("addEmail"), "a member gone hands focus to the Add box");
});

test("a refused seat change is explained", async () => {
  const cases = [
    ["seat_revoked", "That member was removed. Add their address again to restore them."],
    ["license_revoked", "This licence has been revoked, so its seats cannot be changed.", 403],
    ["seat_busy", "That seat changed while you were acting on it. Try again."],
    ["seat_not_found", "That member is no longer on this licence. Reload to see the roster.", 404],
    ["strange", "Could not complete that: strange"],
  ];
  for (const [code, text, httpStatus = 409] of cases) {
    reset();
    await act("hold", "u1", () => json(httpStatus, { detail: code }));
    assert.deepEqual(status(), [text, "muted err"], code);
  }
});

test("Withdraw asks first, deletes the invitation and reloads; a failure says so", async () => {
  await open({ routes: { [`DELETE ${SEATS.replace("seats", "invites")}/i1`]: () => json(200, {}) } });
  confirms.answer(false, true);
  const withdraw = () => $("rows").querySelector('button[data-invite="i1"]').click();
  withdraw();
  await settle();
  assert.deepEqual(sent(/invites/), []);
  assert.match(confirms.asked[0], /^Withdraw the invitation to dee@uni\.edu\?/);
  withdraw();
  await settle();
  assert.deepEqual(sent(/invites|seats/), [`GET ${SEATS}`, "DELETE /v1/institutions/licenses/L1/invites/i1", `GET ${SEATS}`]);

  net.routes["DELETE /v1/institutions/licenses/L1/invites/i1"] = () => json(404, { detail: "invite_not_found" });
  confirms.answer(true);
  $("rows").querySelector('button[data-invite="i1"]').click();
  await settle();
  assert.deepEqual(status(), ["Could not withdraw: That invitation is no longer open: it was withdrawn or claimed. Reload to see the roster.", "muted err"]);
});

/* ---------------------------------------------------------- add member */

async function add(email, response) {
  await open({ routes: { [`POST ${SEATS}`]: response } });
  $("addEmail").value = email;
  $("add").click();
  await settle();
}

test("adding someone says whether they are on now or invited", async () => {
  await add(" new@uni.edu ", () => json(200, { seat: { uid: "u9" } }));
  const post = net.requests.find((r) => r.method === "POST");
  assert.deepEqual(JSON.parse(post.body), { email: "new@uni.edu" });
  assert.equal($("addEmail").value, "");
  assert.deepEqual(sent(/seats/), [`GET ${SEATS}`, `POST ${SEATS}`, `GET ${SEATS}`]);
  assert.deepEqual(status(), ["new@uni.edu is on the licence now.", "muted"]);

  reset();
  await add("later@uni.edu", () => json(200, { invite: { id: "i9" } }));
  assert.deepEqual(status(), [
    "later@uni.edu has not signed in yet — invited. They join automatically the first time they do.", "muted",
  ]);
});

test("a blank address adds nobody", async () => {
  await add("  ", () => json(200, {}));
  assert.deepEqual(sent(/POST/), []);
});

test("a refused add is explained, and an unknown refusal keeps its code", async () => {
  const cases = [
    ["claim_contended", "Busy just now — try again."],
    ["member_already_licensed", "x@uni.edu already has a semper licence of their own. Ask semper support to move them onto this one."],
    ["invite_exists", "x@uni.edu is already promised a place on a different licence."],
    ["license_seats_exhausted", "This licence has no seats left."],
    ["odd", "Could not add x@uni.edu: odd"],
  ];
  for (const [code, text] of cases) {
    reset();
    await add("x@uni.edu", () => json(409, { detail: code }));
    assert.deepEqual(status(), [text, "muted err"], code);
    assert.equal($("addEmail").value, "x@uni.edu", "the address stays for a retry");
  }
});

/* ------------------------------------------------------- picker, paging */

test("the licences this address administers are one click each", async () => {
  await open({
    search: "",
    routes: { "GET /v1/institutions/licenses": () => json(200, { licenses: [
      { id: "L1", keyPrefix: "SEMP-UNI1", domainLock: "uni.edu" },
      { id: "L2", keyPrefix: "SEMP-UNI2" },
    ] }) },
  });
  const choices = $("licenceChoices").querySelectorAll("button[data-pick]");
  assert.deepEqual(choices.map((b) => b.textContent.trim()), ["SEMP-UNI1 · uni.edu", "SEMP-UNI2"]);
  assert.deepEqual(sent(/seats/), [], "nothing opens until one is chosen");
  choices[0].click();
  await settle();
  assert.equal($("licenseId").value, "L1");
  assert.deepEqual(sent(/seats/), [`GET ${SEATS}`]);
  assert.equal($("rosterCard").hidden, false);
});

test("a roster longer than one page is read to the end", async () => {
  await open({ roster: { ...assigned, seats: assigned.seats.slice(0, 1), page: { nextPageToken: "u1" } },
    routes: { [`GET ${SEATS}?page_token=u1`]: () => json(200, {
      seats: assigned.seats.slice(1), invites: [], page: { nextPageToken: null },
    }) } });
  assert.deepEqual(sent(/seats/), [`GET ${SEATS}`, `GET ${SEATS}?page_token=u1`]);
  assert.deepEqual(rows().map((r) => r[0]),
    ["ana@uni.edu", "ben@uni.edu", "dee@uni.edu", "eve@uni.edu"], "every page; the removed member under Removed");
  assert.match($("rowChips").textContent, /Removed 1/);
});

// TE3-102 (ADDENDUM-2): IT may add only addresses on the licence's domain;
// the backend answers anything else 422 member_off_domain.
test("an address off the licence's domain is refused in words that name the domain", async () => {
  await open({
    roster: { ...assigned, license: { ...assigned.license, domainLock: "uni.edu" } },
    routes: { [`POST ${SEATS}`]: () => json(422, { detail: "member_off_domain" }) },
  });
  $("addEmail").value = "x@gmail.com";
  $("add").click();
  await settle();
  assert.deepEqual(status(), [
    "x@gmail.com is outside this licence's domain (uni.edu). Only addresses on it can be added.", "muted err"]);
  assert.equal($("addEmail").value, "x@gmail.com", "the address stays to be corrected");

  reset();
  await add("x@gmail.com", () => json(422, { detail: "member_off_domain" }));
  assert.deepEqual(status(), [
    "x@gmail.com is outside this licence's domain. Only addresses on it can be added.", "muted err"],
    "no domain on the roster's answer: the sentence without it");
});
