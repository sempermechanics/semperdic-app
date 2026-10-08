// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's licence table and roster card
// (console/operator/licences.js, roster-card.js, state.js), past what
// operator.test.mjs pins: the cells for licences with less filled in, a list
// that fails or answers with nothing, Refresh, a search that fails or whose
// answer is for old text, Load more failing, and the roster card's own
// failure, Close, and switching licence while a removal is being confirmed.
import { test as nodeTest, beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, confirms, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, UNI, DEMO, LIST, openDesk, status, rows, labels, rowButton, tableRows } from "./operator-desk.mjs";

await loadAuth();
const { day } = await import("../public/console/util.js");

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

async function click(button) {
  button.click();
  await settle();
}

/* ---------------------------------------------------------------- table */

test("a row with less filled in still reads sensibly", async () => {
  const sparse = [
    { id: "lic-no-prefix-123", kind: "individual", mode: "licensed", status: "redeemed", duration: "timed",
      expiresAt: "2027-06-30T23:59:59Z", supersededBy: "lic-uni-9" },
    { id: "lic-assigned", keyPrefix: "SEMP-ASG1", kind: "institution", mode: "licensed", status: "redeemed",
      duration: "perpetual", seating: "assigned", seatsUsed: 2 },
    { id: "lic-float", keyPrefix: "SEMP-FLT1", kind: "institution", mode: "licensed", status: "redeemed",
      duration: "perpetual", seating: "floating", maxSeats: 5 },
  ];
  await openDesk({ licenses: sparse });
  const kind = $("licenceRows").querySelector("tr").cells[1];
  assert.equal(kind.childNodes[0], "individual");
  assert.equal(kind.querySelector("span").textContent, "replaced by an institution licence", "on a line of its own");
  assert.deepEqual(rows().map((r) => r.slice(0, 8)), [
    ["lic-no-pre", "individualreplaced by an institution licence", "licensed", "—",
      `until ${day("2027-06-30T23:59:59Z")}`, "default", "redeemed", "—"],
    ["SEMP-ASG1", "institution", "licensed", "2/∞", "perpetual", "default", "redeemed", "—"],
    ["SEMP-FLT1", "institution · shared", "licensed", "0/5 in use · 0 on roster", "perpetual", "default", "redeemed", "—"],
  ]);
});

test("a list answer with nothing in it, and no demo allowance, still renders", async () => {
  await openDesk({ routes: {
    [LIST]: () => json(200, {}),
    "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false": () => json(200, { licenses: [DEMO] }),
  } });
  assert.equal($("licenceRows").textContent.trim(), "No licences to show. Tick Show revoked or Show Demo keys to include those, or issue one with Issue a licence.");
  assert.equal($("licencePaging").textContent, "0 licence(s) (revoked and Demo hidden).");
  $("showDemo").checked = true;
  $("showDemo").dispatch("change");
  await settle();
  assert.equal(rows()[0][5], "demo", "no number when the backend does not send the allowance");
  await click(rowButton("edit", DEMO.id));
  assert.equal($("editCap").title, "Demo keys use the demo allowance; issue a licensed key to raise it.");
});

test("a licence list that cannot be read says why", async () => {
  for (const [reply, text] of [
    [() => json(503, { detail: "unavailable" }), "Could not load licences: unavailable"],
    [() => json(403, { detail: "not_admin" }), "That account is not a Semper operator."],
  ]) {
    reset();
    await openDesk({ routes: { [LIST]: reply } });
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal($("licenceRows").textContent.trim(), `${text} Retry`, "the table says it too, with a Retry");
  }
});

test("Refresh reads the first page again and drops the pages loaded after it", async () => {
  let n = 0;
  await openDesk({
    page: { hasMore: true, nextPageToken: "tok" },
    routes: {
      [LIST]: () => json(200, n++ ? { licenses: [UNI], page: {} } : { licenses: [IND], page: { hasMore: true, nextPageToken: "tok" } }),
      "GET /v1/admin/licenses?limit=50&include_revoked=false&page_token=tok": () => json(200, { licenses: [UNI], page: {} }),
    },
  });
  await click($("loadMore"));
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
  await click($("reload"));
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
  assert.deepEqual(status(), ["", "muted"]);
});

test("Load more that fails says so, and the button can be pressed again", async () => {
  await openDesk({
    page: { hasMore: true, nextPageToken: "tok" },
    routes: { "GET /v1/admin/licenses?limit=50&include_revoked=false&page_token=tok": () => json(503, { detail: "unavailable" }) },
  });
  await click($("loadMore"));
  assert.deepEqual(status(), ["Could not load more licences: unavailable", "muted err"]);
  assert.equal($("loadMore").disabled, false);
  assert.equal($("loadMore").hidden, false);
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1"]);
});

const SEARCH = "GET /v1/admin/licenses?limit=50&include_revoked=false&q=far%40else.org";

test("a failed backend search says so; the loaded rows still filter", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await openDesk({ routes: { [SEARCH]: () => json(503, { detail: "unavailable" }) } });
    $("filter").value = "far@else.org";
    $("filter").dispatch("input");
    mock.timers.tick(300);
    await settle();
    assert.deepEqual(status(), ["Could not search: unavailable", "muted err"]);
  } finally {
    mock.timers.reset();
  }
});

test("a search answer for text since changed is dropped", async () => {
  const far = { ...IND, id: "lic-far", keyPrefix: "SEMP-FAR1", emailLock: "far@else.org", note: "" };
  const answer = deferred();
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await openDesk({ routes: { [SEARCH]: () => answer.promise } });
    $("filter").value = "far@else.org";
    $("filter").dispatch("input");
    mock.timers.tick(300);
    await settle();
    assert.deepEqual(sent(/q=/), [SEARCH]);
    $("filter").value = "chen";
    $("filter").dispatch("input");
    answer.resolve(json(200, { licenses: [far] }));
    await settle();
    assert.deepEqual(labels(), ["SEMP-IND1"], "the answer for the old text is not shown");
  } finally {
    mock.timers.reset();
  }
});

test("typing again before the pause ends sends one search, for the last text", async () => {
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await openDesk({ routes: { [SEARCH]: () => json(200, { licenses: [] }) } });
    $("filter").value = "far@else.o";
    $("filter").dispatch("input");
    mock.timers.tick(200);
    $("filter").value = "far@else.org";
    $("filter").dispatch("input");
    mock.timers.tick(300);
    await settle();
    assert.deepEqual(sent(/q=/), [SEARCH]);
    assert.equal($("licenceRows").textContent.trim(), "No licences match. Clear the filter, or tick Show revoked or Show Demo keys.");
  } finally {
    mock.timers.reset();
  }
});

/* --------------------------------------------------------------- roster */

const UNI2 = { ...UNI, id: "lic-uni-2", keyPrefix: "SEMP-UNI2" };
const seatsOf = (lic) => `/v1/admin/licenses/${lic.id}/seats`;
const ROSTER = {
  license: { keyPrefix: "SEMP-UNI1" },
  seats: [{ uid: "u1", email: "a@uni.edu", status: "active" }],
  invites: [],
};

test("a roster that cannot be read says so in the card; Close hides it", async () => {
  await openDesk({ routes: { [`GET ${seatsOf(UNI)}`]: () => json(503, { detail: "unavailable" }) } });
  await click(rowButton("roster", UNI.id));
  assert.equal($("rosterCard").hidden, false);
  assert.equal($("rosterName").textContent, "SEMP-UNI1");
  assert.deepEqual(tableRows("rosterRows"), [["Could not load the roster: unavailable Retry"]]);
  await click($("rosterClose"));
  assert.equal($("rosterCard").hidden, true);
});

test("adding a member on the desk goes to the open licence's staff route and re-reads the row", async () => {
  await openDesk({ licenses: [IND, UNI, UNI2], routes: {
    [`GET ${seatsOf(UNI)}`]: () => json(200, ROSTER),
    [`GET ${seatsOf(UNI2)}`]: () => json(200, { ...ROSTER, seats: [] }),
    [`POST ${seatsOf(UNI2)}`]: () => json(200, { seat: { uid: "u5" } }),
    [`GET /v1/admin/licenses/${UNI2.id}`]: () => json(200, { ...UNI2, seatsUsed: 5 }),
  } });
  await click(rowButton("roster", UNI.id));
  await click(rowButton("roster", UNI2.id));
  assert.equal($("rosterName").textContent, "SEMP-UNI2", "the card follows the last Roster pressed");
  assert.deepEqual(tableRows("rosterRows"), [["Nobody on this licence yet."]]);
  $("memberEmail").value = "new@uni.edu";
  await click($("addMember"));
  assert.deepEqual(sent(/POST/), [`POST ${seatsOf(UNI2)}`]);
  assert.deepEqual([$("rosterHint").textContent, $("rosterHint").className], ["new@uni.edu is on the licence now.", "muted"]);
  assert.equal(rows().find((r) => r[0] === "SEMP-UNI2")[3], "1/5 in use · 5 on roster", "the row re-read after the change");
});

test("a roster change that finishes after the card closed reads nothing more", async () => {
  const answer = deferred();
  await openDesk({ routes: {
    [`GET ${seatsOf(UNI)}`]: () => json(200, ROSTER),
    [`PATCH ${seatsOf(UNI)}/u1`]: () => answer.promise,
  } });
  await click(rowButton("roster", UNI.id));
  await click($("rosterRows").querySelector('button[data-act="hold"]'));
  await click($("rosterClose"));
  answer.resolve(json(200, {}));
  await settle();
  assert.deepEqual(sent(/lic-uni-1/), [`GET ${seatsOf(UNI)}`, `PATCH ${seatsOf(UNI)}/u1`],
    "no roster or licence re-read for a closed card");
});

// Found while writing these tests: roster.js once resolved `base()` only after
// the Remove confirmation was answered, so a removal confirmed after another
// licence's roster was opened went to that other licence.
nodeTest("a removal confirmed after switching rosters goes to the licence it was asked on", {
  timeout: 5000,
}, async () => {
  await openDesk({ licenses: [IND, UNI, UNI2], routes: {
    [`GET ${seatsOf(UNI)}`]: () => json(200, ROSTER),
    [`GET ${seatsOf(UNI2)}`]: () => json(200, { ...ROSTER, seats: [] }),
    [`DELETE ${seatsOf(UNI)}/u1`]: () => json(200, { revoked: true }),
    [`DELETE ${seatsOf(UNI2)}/u1`]: () => json(404, { detail: "seat_not_found" }),
    [`GET /v1/admin/licenses/${UNI.id}`]: () => json(200, UNI),
    // The reload after it re-reads the roster on screen, which is now UNI2's.
    [`GET /v1/admin/licenses/${UNI2.id}`]: () => json(200, UNI2),
  } });
  await click(rowButton("roster", UNI.id));
  $("rosterRows").querySelector('button[data-act="remove"]').click(); // the confirmation waits
  await click(rowButton("roster", UNI2.id));
  confirms.answer(true);
  await settle();
  assert.deepEqual(sent(/DELETE/), [`DELETE ${seatsOf(UNI)}/u1`]);
});
