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
import { IND, UNI, DEMO, LIST, openDesk, status, rows, labels, rowButton, tableRows, detailsOf } from "./operator-desk.mjs";

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
  const kind = $("licenceRows").querySelector("tr").cells[1].childNodes[0]; // its one wrapping line
  assert.equal(kind.childNodes[0], "individual");
  assert.equal(kind.querySelector("span").textContent, "replaced by an institution licence", "on a line of its own");
  assert.deepEqual(rows().map((r) => r.slice(0, 4)), [
    ["lic-no-pre", "individualreplaced by an institution licence", "—", "redeemed"],
    ["SEMP-ASG1", "institution", "2/∞", "redeemed"],
    ["SEMP-FLT1", "institution · shared", "0/5 in use · 0 on roster", "redeemed"],
  ]);
  const first = detailsOf("lic-no-prefix-123");
  assert.equal(first.Term, `until ${day("2027-06-30T23:59:59Z")}`);
  assert.equal(first["Locked to"], "nobody yet");
  assert.equal(detailsOf("lic-assigned").Term, "perpetual");
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
  assert.equal(detailsOf(DEMO.id)["Cloud analyses per person"], "Demo limit",
    "no number when the backend does not send the allowance");
  await click(rowButton("edit", DEMO.id));
  assert.equal($("editCap").title, "Demo keys use the Demo limit; issue a Licensed key to raise it.");
});

test("a licence list that cannot be read says why", async () => {
  for (const [reply, text] of [
    [() => json(503, { detail: "unavailable" }), "Could not load licences: unavailable"],
    [() => json(403, { detail: "not_admin" }), "That account is not a semper operator."],
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

test("a Load more page that answers after Refresh is dropped, not appended to the new table", async () => {
  const late = deferred();
  const lateFail = deferred();
  let n = 0;
  let more = 0;
  await openDesk({
    page: { hasMore: true, nextPageToken: "tok" },
    routes: {
      [LIST]: () => json(200, n++ ? { licenses: [UNI], page: { hasMore: true, nextPageToken: "tok" } }
        : { licenses: [IND], page: { hasMore: true, nextPageToken: "tok" } }),
      "GET /v1/admin/licenses?limit=50&include_revoked=false&page_token=tok": () => (more++ ? lateFail : late).promise,
    },
  });
  await click($("loadMore"));
  await click($("reload"));
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
  late.resolve(json(200, { licenses: [DEMO, IND], page: {} }));
  await settle();
  assert.deepEqual(labels(), ["SEMP-UNI1"], "the old table's page is not drawn on the new one");
  assert.equal($("loadMore").hidden, false, "the new table still has its own next page");
  assert.equal($("loadMore").disabled, false);
  // A failure that lands late is the old table's too: it says nothing.
  await click($("loadMore"));
  await click($("reload"));
  lateFail.resolve(json(503, { detail: "unavailable" }));
  await settle();
  assert.deepEqual(status(), ["", "muted"]);
  assert.deepEqual(labels(), ["SEMP-UNI1"]);
});

const SEARCH = "POST /v1/admin/licenses/search";

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
    assert.deepEqual(sent(/search/), [SEARCH]);
    $("filter").value = "chen";
    $("filter").dispatch("input");
    answer.resolve(json(200, { licenses: [far] }));
    await settle();
    assert.deepEqual(labels(), ["SEMP-IND1"], "the answer for the old text is not shown");
  } finally {
    mock.timers.reset();
  }
});

test("a search with more pages asks for each with its token in the body, and stops on a repeated one", async () => {
  const hit = (n) => ({ ...UNI, id: `lic-hit-${n}`, keyPrefix: `SEMP-HIT${n}`, domainLock: "far.edu" });
  const answers = [
    { licenses: [hit(1)], page: { nextPageToken: "t2", hasMore: true } },
    { licenses: [hit(2)], page: { nextPageToken: "t3", hasMore: true } },
    { licenses: [hit(3)], page: { nextPageToken: "t3", hasMore: true } }, // a token it already sent
  ];
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await openDesk({ routes: { [SEARCH]: () => json(200, answers.shift()) } });
    $("filter").value = "far.edu";
    $("filter").dispatch("input");
    mock.timers.tick(300);
    await settle();
    const bodies = net.requests.filter((r) => r.method === "POST").map((r) => JSON.parse(r.body));
    const hidden = { include_demo: false, include_revoked: false };
    assert.deepEqual(bodies, [
      { q: "far.edu", limit: 50, ...hidden },
      { q: "far.edu", limit: 50, page_token: "t2", ...hidden },
      { q: "far.edu", limit: 50, page_token: "t3", ...hidden },
    ]);
    assert.deepEqual(labels(), ["SEMP-HIT1", "SEMP-HIT2", "SEMP-HIT3"]);
    assert.deepEqual(sent(/far/), [], "nothing searched for is in a URL");
  } finally {
    mock.timers.reset();
  }
});

test("with Show Demo keys ticked a search asks for Demo keys, and finds one", async () => {
  const demoHit = { ...DEMO, id: "lic-demo-far", keyPrefix: "SEMP-DFAR", emailLock: "far@else.org" };
  mock.timers.enable({ apis: ["setTimeout"] });
  try {
    await openDesk({ routes: {
      "GET /v1/admin/licenses?limit=50&include_demo=true&include_revoked=false": () => json(200, { licenses: [] }),
      [SEARCH]: (req) => json(200, { licenses: JSON.parse(req.body).include_demo ? [demoHit] : [] }),
    } });
    $("showDemo").checked = true;
    $("showDemo").dispatch("change");
    await settle();
    $("filter").value = "far@else.org";
    $("filter").dispatch("input");
    mock.timers.tick(300);
    await settle();
    const [body] = net.requests.filter((r) => r.method === "POST").map((r) => JSON.parse(r.body));
    assert.deepEqual(body, { q: "far@else.org", limit: 50, include_demo: true, include_revoked: false });
    assert.deepEqual(labels(), ["SEMP-DFAR"]);
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
    assert.deepEqual(sent(/search/), [SEARCH]);
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
  assert.equal(rows().find((r) => r[0] === "SEMP-UNI2")[2], "1/5 in use · 5 on roster", "the row re-read after the change");
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
  assert.deepEqual(sent(/lic-uni-1/), [
    `GET ${seatsOf(UNI)}`, `GET /v1/admin/licenses/${UNI.id}/summary`, `PATCH ${seatsOf(UNI)}/u1`,
  ],
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
