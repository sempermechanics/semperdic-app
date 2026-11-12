// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The operator desk's roster card has the institution page's bulk tools
// (console/roster-tools.js and audit-log.js, shared), pointed at the staff
// routes (`/v1/admin/licenses/{id}/…`): the seat-count tiles, the roster
// export, the bulk import (and its step-up) and the audit-log panel. The
// last case opens both pages on the same answers and compares what each
// draws, which is what keeps the two from drifting apart.
import { beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import { resolveObjectURL } from "node:buffer";
import {
  test, reset, settle, openPage, json, net, sent, $, page, loadAuth, deferred, fake, storage,
} from "./harness.mjs";
import { IND, UNI, openDesk, rowButton, rows, tableRows } from "./operator-desk.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

const STAFF = `/v1/admin/licenses/${UNI.id}`;
const UNI2 = { ...UNI, id: "lic-uni-2", keyPrefix: "SEMP-UNI2" };
const STAFF2 = `/v1/admin/licenses/${UNI2.id}`;
const ROSTER = {
  license: { keyPrefix: "SEMP-UNI1" },
  seats: [
    { uid: "u1", email: "ana@uni.edu", status: "active" },
    { uid: "u2", email: "ben@uni.edu", status: "active" },
  ],
  invites: [],
};
const counts = { assigned: 4, activeLast7Days: 2, onHold: 1, invited: 2, revocationPending: 1, expiringWithin30Days: 3 };

const hint = () => [$("rosterHint").textContent, $("rosterHint").className];
const text = (el) => el.textContent.replace(/\s+/g, " ").trim();
const tiles = () => $("rosterTiles").querySelectorAll(".tile").map((t) => [text(t), t.classList.includes("warn")]);

async function click(button) {
  button.click();
  await settle();
}

/** The desk with UNI's roster card open on the staff routes. */
async function openCard(routes = {}) {
  await openDesk({ licenses: [IND, UNI, UNI2], routes: {
    [`GET ${STAFF}/seats`]: () => json(200, ROSTER),
    [`GET ${STAFF}/summary`]: () => json(200, { counts }),
    [`GET ${STAFF2}/seats`]: () => json(200, { ...ROSTER, license: { keyPrefix: "SEMP-UNI2" }, seats: [] }),
    [`GET ${STAFF2}/summary`]: () => json(200, { counts: { assigned: 9 } }),
    ...routes,
  } });
  await click(rowButton("roster", UNI.id));
}

/** Run `fn` while capturing the downloads `saveBlob` starts: [{ name, blob }]. */
async function downloads(fn) {
  mock.timers.enable({ apis: ["setTimeout"] }); // saveBlob revokes its URL after 30 s
  const saved = [];
  const append = page.body.appendChild.bind(page.body);
  page.body.appendChild = (el) => {
    saved.push({ name: el.download, blob: resolveObjectURL(el.href) });
    return append(el);
  };
  try {
    await fn();
  } finally {
    mock.timers.reset();
  }
  return saved;
}

function typeImport(value, id = "rosterImportText") {
  $(id).value = value;
  $(id).dispatch("input");
}

/* ---------------------------------------------------------------- tiles */

test("the roster card shows the seat counts from the staff route", async () => {
  await openCard();
  assert.deepEqual(tiles(), [
    ["4 assigned", false], ["2 active in the last 7 days", false], ["1 paused", false],
    ["2 invited", false], ["1 removed, phone not told yet", true], ["3 seats ending within 30 days", true],
  ]);
  assert.deepEqual(sent(/summary/), [`GET ${STAFF}/summary`]);
});

test("counts that fail say so in the card; another licence's late counts are dropped", async () => {
  const slow = deferred();
  await openCard({ [`GET ${STAFF}/summary`]: () => slow.promise });
  await click(rowButton("roster", UNI2.id));
  assert.equal(tiles()[0][0], "9 assigned");
  slow.resolve(json(500, { detail: "late" }));
  await settle();
  assert.equal(tiles()[0][0], "9 assigned");

  net.routes[`GET ${STAFF}/summary`] = () => json(500, { detail: "boom" });
  await click(rowButton("roster", UNI.id));
  assert.equal(text($("rosterTiles")), "Could not count seats: boom");
  assert.equal(tableRows("rosterRows").length, 2, "the roster is unaffected");
});

/* --------------------------------------------------------------- export */

test("Export roster CSV saves the staff route's file under the key prefix", async () => {
  await openCard({ [`GET ${STAFF}/seats/export`]: () => new Response("email\r\nana@uni.edu\r\n", { status: 200 }) });
  const saved = await downloads(async () => {
    $("rosterExport").click();
    assert.equal($("rosterExport").disabled, true, "one export at a time");
    await settle();
  });
  assert.deepEqual(saved.map((s) => s.name), ["roster-SEMP-UNI1.csv"]);
  assert.equal(await saved[0].blob.text(), "email\r\nana@uni.edu\r\n");
  assert.deepEqual(hint(), ["Roster exported.", "muted"]);
  assert.equal($("rosterExport").disabled, false);
});

test("a refused export is said in the card", async () => {
  await openCard({ [`GET ${STAFF}/seats/export`]: () => json(403, { detail: "not_admin" }) });
  await click($("rosterExport"));
  assert.deepEqual(hint(), ["That account is not a semper operator.", "muted err"]);
});

test("with the card closed the tools send nothing", async () => {
  await openCard();
  await click($("rosterClose"));
  for (const id of ["rosterExport", "rosterAuditLoad", "rosterAuditMore", "rosterAuditCsv", "rosterAuditJson"]) {
    $(id).disabled = false;
    $(id).click();
  }
  typeImport("a@uni.edu");
  $("rosterImportSubmit").click();
  await settle();
  assert.deepEqual(sent(/export|import|audit/), []);
});

/* --------------------------------------------------------------- import */

const imported = {
  rows: 3,
  counts: { seated: 1, exists: 1, refused: 1 },
  results: [
    { row: 2, email: "new@uni.edu", outcome: "seated", error: "" },
    { row: 3, email: "ana@uni.edu", outcome: "exists", error: "" },
    { row: 4, email: "full@uni.edu", outcome: "refused", error: "license_seats_exhausted" },
  ],
};

test("an import previews its rows, posts to the staff route, answers every row and re-reads the licence", async () => {
  await openCard({
    [`POST ${STAFF}/seats/import`]: () => json(200, imported),
    [`GET ${STAFF}`]: () => json(200, { ...UNI, seatsUsed: 5 }),
  });
  assert.equal($("rosterImportSubmit").disabled, true, "nothing to send yet");
  const file = "email\nnew@uni.edu\nana@uni.edu\nfull@uni.edu\n";
  typeImport(file);
  assert.equal($("rosterImportPreview").textContent, "3 rows ready");
  typeImport(Array.from({ length: 501 }, (_, i) => `m${i}@uni.edu`).join("\n"));
  assert.deepEqual([$("rosterImportPreview").textContent, $("rosterImportSubmit").disabled],
    ["501 rows — at most 500 at a time", true]);
  typeImport(file);
  $("rosterImportSubmit").click();
  assert.equal($("rosterImportSubmit").disabled, true, "one import at a time");
  await settle();
  const post = net.requests.find((r) => r.method === "POST");
  assert.deepEqual(JSON.parse(post.body), { csv: file });
  assert.equal($("rosterImportSummary").textContent, "1 added, 1 already on the roster, 1 not added.");
  assert.equal($("rosterImportResults").hidden, false);
  assert.deepEqual(tableRows("rosterImportRows"), [
    ["2", "new@uni.edu", "on the licence now"],
    ["3", "ana@uni.edu", "already on the roster"],
    ["4", "full@uni.edu", "no seats left on this licence"],
  ]);
  assert.deepEqual(sent(/lic-uni-1/), [
    `GET ${STAFF}/seats`, `GET ${STAFF}/summary`, `POST ${STAFF}/seats/import`,
    `GET ${STAFF}/seats`, `GET ${STAFF}/summary`, `GET ${STAFF}`,
  ]);
  assert.equal(rows().find((r) => r[0] === "SEMP-UNI1")[2], "1/5 in use · 5 on roster", "the licence row re-read");
  // The card's own message line, live, says the outcome too (AE2-009).
  assert.deepEqual(hint(), ["1 added, 1 already on the roster, 1 not added.", "muted"]);
  assert.equal($("rosterHint").getAttribute("aria-live"), "polite");
});

test("a chosen file fills the import; a refused one is said in the card", async () => {
  await openCard({ [`POST ${STAFF}/seats/import`]: () => json(400, { detail: "roster_import_malformed" }) });
  $("rosterImportFile").files = [new Blob(["email\nana@uni.edu\n"])];
  $("rosterImportFile").dispatch("change");
  await settle();
  assert.equal($("rosterImportText").value, "email\nana@uni.edu\n");
  await click($("rosterImportSubmit"));
  assert.deepEqual(hint(), ["That file could not be read as CSV — check its quotes.", "muted err"]);
  assert.equal($("rosterImportResults").hidden, true);
});

test("a staff import on a stale sign-in steps up through Google and sends nothing else", async () => {
  // The import is a change (admin-stepup); a sign-in older than the window
  // answers reauth_required, and api() re-authenticates as for every other
  // change the desk makes: the page leaves for Google, and the request is
  // not repeated with the stale token.
  await openCard({ [`POST ${STAFF}/seats/import`]: () => json(403, { detail: "reauth_required" }) });
  typeImport("ana@uni.edu");
  $("rosterImportSubmit").click();
  await settle();
  assert.deepEqual(fake.callsTo("reauthenticateWithRedirect").length, 1);
  assert.match(storage.getItem("semper.afterReauth"), /Repeat what you were doing/);
  assert.deepEqual(sent(/import/), [`POST ${STAFF}/seats/import`]);
});

test("opening another licence clears the last import's results; reopening the same keeps them", async () => {
  await openCard({ [`POST ${STAFF}/seats/import`]: () => json(200, { ...imported, counts: { exists: 1 } }) });
  typeImport("ana@uni.edu");
  await click($("rosterImportSubmit"));
  assert.equal($("rosterImportResults").hidden, false);
  await click($("rosterClose"));
  await click(rowButton("roster", UNI.id));
  assert.equal($("rosterImportResults").hidden, false, "the same licence: its results stay");
  await click(rowButton("roster", UNI2.id));
  assert.equal($("rosterImportResults").hidden, true);
  assert.equal($("rosterImportSummary").textContent, "");
  assert.equal($("rosterImportRows").innerHTML, "");
});

/* ------------------------------------------------------------ audit log */

const events = [
  { id: "e2", ts: "2026-10-02T09:00:00Z", uid: "staff-1", action: "INSTITUTION_SEAT_PATCH",
    target: { type: "seat", id: `${UNI.id}/u2` }, detail: { enabled: false } },
  { id: "e1", ts: "2026-10-01T09:00:00Z", uid: "u1", action: "LICENSE_DEVICE_BIND",
    target: { type: "license", id: UNI.id }, detail: {} },
];

test("the audit log reads the staff route on demand, by date, a page at a time", async () => {
  // From / To are local days: local midnights, the To day included.
  const query = `${new URLSearchParams({
    since: new Date(2026, 9, 1).toISOString(), until: new Date(2026, 9, 3).toISOString(),
  })}&page_size=50`;
  await openCard({
    [`GET ${STAFF}/audit?${query}`]: () => json(200, { events, page: { nextPageToken: "e1" } }),
    [`GET ${STAFF}/audit?${query}&page_token=e1`]: () => json(200, {
      events: [{ id: "e0", ts: "2026-10-01T08:00:00Z", uid: "u2", action: "INSTITUTION_ROSTER_IMPORT", target: {}, detail: { rows: 3 } }],
      page: {},
    }),
  });
  assert.equal(text($("rosterAuditRows")), "Choose dates, or none for everything, and press Show.");
  assert.deepEqual(sent(/audit/), [], "read on demand only");
  $("rosterAuditSince").value = "2026-10-01";
  $("rosterAuditUntil").value = "2026-10-02";
  await click($("rosterAuditLoad"));
  assert.deepEqual(tableRows("rosterAuditRows").map((r) => r.slice(1, 4)), [
    ["INSTITUTION_SEAT_PATCH", "staff-1", "seat ben@uni.edu"],
    ["LICENSE_DEVICE_BIND", "ana@uni.edu", "license"],
  ]);
  assert.equal($("rosterAuditMore").hidden, false);
  await click($("rosterAuditMore"));
  assert.deepEqual(tableRows("rosterAuditRows").map((r) => r[2]), ["staff-1", "ana@uni.edu", "ben@uni.edu"]);
  assert.equal($("rosterAuditMore").hidden, true);

  net.routes[`GET ${STAFF}/audit?${query}`] = () => json(400, { detail: "invalid_audit_range" });
  await click($("rosterAuditLoad"));
  assert.deepEqual(hint(), ["Those dates are not a range: the start must come before the end.", "muted err"]);
});

test("the audit log exports as CSV and JSON, and says when it reached the 5,000-event cap", async () => {
  const full = "ts,action\r\n" + "2026-10-01T00:00:00Z,X\r\n".repeat(5000);
  await openCard({
    [`GET ${STAFF}/audit?format=csv&page_size=5000`]: () => new Response("ts,action\r\n", { status: 200 }),
    [`GET ${STAFF}/audit?page_size=5000`]: () =>
      json(200, { licenseId: UNI.id, events, page: { hasMore: true } }),
  });
  const saved = await downloads(async () => {
    await click($("rosterAuditCsv"));
    assert.deepEqual(hint(), ["Audit log exported.", "muted"]);
    await click($("rosterAuditJson"));
    assert.deepEqual(hint(), ["Exported the newest 5,000 events — narrow the dates for the rest.", "muted"]);
    net.routes[`GET ${STAFF}/audit?format=csv&page_size=5000`] = () => new Response(full, { status: 200 });
    await click($("rosterAuditCsv"));
    assert.deepEqual(hint(), ["Exported the newest 5,000 events — narrow the dates for the rest.", "muted"]);
  });
  assert.deepEqual(saved.map((s) => s.name), ["audit-SEMP-UNI1.csv", "audit-SEMP-UNI1.json", "audit-SEMP-UNI1.csv"]);
  assert.deepEqual(JSON.parse(await saved[1].blob.text()),
    { licenseId: UNI.id, since: "", until: "", events });
});

test("a file is named by the licence id until the roster has said its key prefix", async () => {
  const roster = deferred();
  await openDesk({ licenses: [IND, UNI], routes: {
    [`GET ${STAFF}/seats`]: () => roster.promise,
    [`GET ${STAFF}/seats/export`]: () => new Response("email\r\n", { status: 200 }),
  } });
  const saved = await downloads(async () => {
    await click(rowButton("roster", UNI.id));
    await click($("rosterExport"));
  });
  assert.deepEqual(saved.map((s) => s.name), ["roster-lic-uni-1.csv"]);
  roster.resolve(json(200, ROSTER));
});

test("another licence opened resets the audit log", async () => {
  await openCard({ [`GET ${STAFF}/audit?page_size=50`]: () => json(200, { events }) });
  await click($("rosterAuditLoad"));
  assert.equal(tableRows("rosterAuditRows").length, 2);
  await click(rowButton("roster", UNI2.id));
  assert.equal(text($("rosterAuditRows")), "Choose dates, or none for everything, and press Show.");
});

test("the desk's roster filters, sorts and counts as the institution page's does", async () => {
  await openCard();
  assert.equal($("rosterCount").textContent, "Showing 2 of 2");
  $("rosterFilter").value = "ben";
  $("rosterFilter").dispatch("input");
  assert.deepEqual(tableRows("rosterRows").map((r) => r[0]), ["ben@uni.edu"]);
  $("rosterFilter").value = "";
  $("rosterFilter").dispatch("input");
  $("rosterHead").querySelector('button[data-sort="member"]').click();
  $("rosterHead").querySelector('button[data-sort="member"]').click();
  assert.deepEqual(tableRows("rosterRows").map((r) => r[0]), ["ben@uni.edu", "ana@uni.edu"]);
  // Another licence's roster starts unfiltered and unsorted.
  $("rosterFilter").value = "ana";
  await click(rowButton("roster", UNI2.id));
  assert.equal($("rosterFilter").value, "");
  assert.equal($("rosterHead").querySelector("th").getAttribute("aria-sort"), "none");
});

/* --------------------------------------------------------------- parity */

test("the desk's tools draw exactly what the institution page draws for the same answers", async () => {
  // One module serves both pages, so this holds by construction; it is
  // pinned so that a tier-specific branch added to either cannot pass
  // unnoticed. Only the route prefix differs.
  const answers = (root) => ({
    [`GET ${root}/summary`]: () => json(200, { counts }),
    [`POST ${root}/seats/import`]: () => json(200, imported),
    [`GET ${root}/audit?page_size=50`]: () => json(200, { events, page: { nextPageToken: "x" } }),
  });
  const drawn = async (ids) => {
    typeImport("email\nnew@uni.edu\nana@uni.edu\nfull@uni.edu\n", ids.text);
    await click($(ids.submit));
    await click($(ids.show));
    return [ids.tiles, ids.summary, ids.results, ids.audit].map((id) => $(id).innerHTML)
      .concat([$(ids.preview).textContent, $(ids.more).hidden]);
  };

  const IT = "/v1/institutions/licenses/L1";
  await openPage("institution", {
    search: "?license=L1",
    routes: {
      "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }] }),
      "GET /v1/me": () => json(200, { role: "user" }),
      [`GET ${IT}/seats`]: () => json(200, { ...ROSTER, seats: ROSTER.seats.map((s) => ({ ...s })) }),
      ...answers(IT),
    },
  });
  const institution = await drawn({
    text: "importText", submit: "importSubmit", show: "auditLoad", tiles: "tiles",
    summary: "importSummary", results: "importRows", audit: "auditRows",
    preview: "importPreview", more: "auditMore",
  });
  const itSent = sent(/L1/).map((line) => line.replace(IT, "{root}"));

  reset();
  await openCard({ ...answers(STAFF), [`GET ${STAFF}`]: () => json(200, UNI) });
  const desk = await drawn({
    text: "rosterImportText", submit: "rosterImportSubmit", show: "rosterAuditLoad", tiles: "rosterTiles",
    summary: "rosterImportSummary", results: "rosterImportRows", audit: "rosterAuditRows",
    preview: "rosterImportPreview", more: "rosterAuditMore",
  });
  // The desk also re-reads the licence row after the import added someone.
  const staffSent = sent(/lic-uni-1/).map((line) => line.replace(STAFF, "{root}"))
    .filter((line) => line !== "GET {root}");

  assert.match(institution[0], /removed, phone not told yet/);
  assert.match(institution[2], /no seats left on this licence/);
  assert.match(institution[3], /INSTITUTION_SEAT_PATCH/);
  assert.deepEqual(desk, institution);
  assert.ok(itSent.includes("POST {root}/seats/import"));
  assert.deepEqual(staffSent, itSent);
});
