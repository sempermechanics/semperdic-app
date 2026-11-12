// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The licence table's list tools (console/operator/licences.js, with
// list-tools.js): the Kind, App and State filters, the text box matching what
// a row shows, Clear filters, the sortable headings, Load all, a row's
// details, Show test accounts, the view kept for the tab, the / key, and the
// row a card below is open for.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, sent, storage, $, loadAuth } from "./harness.mjs";
import { IND, UNI, APP_IND, APP_UNI, at, openDesk, status, rows, labels, tableRows } from "./operator-desk.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

async function choose(id, value) {
  $(id).value = value;
  $(id).dispatch("change");
  await settle();
}

async function click(button) {
  button.click();
  await settle();
}

const sortBy = (key) => $("licenceHead").querySelector(`button[data-sort="${key}"]`);
const sortOf = (key) => sortBy(key).closest("th").getAttribute("aria-sort");

// Ends in 10 days: "Ends within 30 days". In grace: ended, grace running.
const ENDING = { ...APP_IND, id: "lic-ending", keyPrefix: "SEMP-END1", duration: "timed", expiresAt: at(10) };
const GRACE = {
  ...IND, id: "lic-grace", keyPrefix: "SEMP-GRC1", maxAnalyses: null, note: "", expiresAt: at(-2), graceEndsAt: at(5),
};
const ALL = [IND, UNI, APP_IND, APP_UNI, ENDING, GRACE];

test("Kind, App and State narrow the rows, say how many match, and Clear filters undoes them", async () => {
  await openDesk({ licenses: ALL });
  assert.equal($("clearFilters").hidden, true, "nothing to clear yet");
  await choose("kindFilter", "institution");
  assert.deepEqual(labels(), ["SEMP-UNI1", "SEMP-AUN1"]);
  assert.equal($("licencePaging").textContent, "2 match. 6 licence(s) (revoked and Demo hidden).");
  assert.equal($("clearFilters").hidden, false);
  await choose("appFilter", "semperclassroom");
  assert.deepEqual(labels(), ["SEMP-AUN1"]);
  await choose("appFilter", "none");
  assert.deepEqual(labels(), ["SEMP-UNI1"], "From before the split: no app named");
  await choose("kindFilter", "");
  await choose("appFilter", "");
  await choose("stateFilter", "ending");
  assert.deepEqual(labels(), ["SEMP-END1"]);
  await choose("stateFilter", "grace");
  assert.deepEqual(labels(), ["SEMP-GRC1"]);
  await choose("stateFilter", "active");
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-UNI1", "SEMP-APP1", "SEMP-AUN1", "SEMP-END1"]);
  await choose("stateFilter", "expired");
  assert.deepEqual(tableRows("licenceRows"), [["No licences match. Clear filters, or tick Show revoked or Show Demo keys."]]);
  await click($("clearFilters"));
  assert.equal(labels().length, 6);
  assert.equal($("stateFilter").value, "");
  assert.equal($("clearFilters").hidden, true);
  assert.equal($("filter").focused, true, "back in the box, ready to type");
});

test("an upgrade waiting for its holder is a State of its own", async () => {
  const waiting = { ...APP_IND, upgradePendingId: "lic-up-9" };
  await openDesk({ licenses: [IND, waiting] });
  await choose("stateFilter", "upgrade");
  assert.deepEqual(labels(), ["SEMP-APP1"]);
});

test("the text box matches the words a row shows, and a note it matched is shown on the row", async () => {
  await openDesk({ licenses: ALL });
  $("filter").value = "shared";
  $("filter").dispatch("input");
  assert.deepEqual(labels(), ["SEMP-UNI1"], "a floating licence reads 'shared'");
  $("filter").value = "classroom";
  $("filter").dispatch("input");
  assert.deepEqual(labels(), ["SEMP-AUN1"], "the app's name");
  $("filter").value = "in grace";
  $("filter").dispatch("input");
  assert.deepEqual(labels(), ["SEMP-GRC1"], "the status pill's words");
  $("filter").value = "po 4471";
  $("filter").dispatch("input");
  assert.deepEqual(labels(), ["SEMP-IND1"]);
  assert.match(rows()[0][1], /Note: Prof\. Chen, PO 4471$/, "why it matched, under the kind");
  assert.equal($("licencePaging").textContent, "1 match. 6 licence(s) (revoked and Demo hidden).");
  $("filter").value = "pat@lab";
  $("filter").dispatch("input");
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-GRC1"]);
  assert.match(rows()[0][1], /Locked to pat@lab\.org$/, "the address, which the row no longer shows");
});

test("a heading sorts its column, again the other way, and says which way", async () => {
  await openDesk({ licenses: ALL });
  assert.deepEqual($("licenceHead").querySelectorAll("button").map((b) => b.dataset.sort),
    ["key", "kind", "seats", "state"], "the four columns a row keeps");
  assert.equal(sortOf("seats"), "none");
  await click(sortBy("seats"));
  assert.equal(sortOf("seats"), "ascending");
  assert.deepEqual(labels().slice(0, 2), ["SEMP-UNI1", "SEMP-AUN1"],
    "1 in use, then 3 on the roster; an individual licence has no seats, so last");
  await click(sortBy("seats"));
  assert.equal(sortOf("seats"), "descending");
  assert.deepEqual(labels().slice(-2), ["SEMP-AUN1", "SEMP-UNI1"]);
  await click(sortBy("key"));
  assert.equal(sortOf("seats"), "none", "one column sorts at a time");
  assert.deepEqual(labels(), ["SEMP-APP1", "SEMP-AUN1", "SEMP-END1", "SEMP-GRC1", "SEMP-IND1", "SEMP-UNI1"]);
  await click(sortBy("kind"));
  assert.deepEqual(labels().slice(-2), ["SEMP-UNI1", "SEMP-AUN1"], "institutions after individuals, by app");
  await click(sortBy("state"));
  assert.equal(labels()[0], "SEMP-GRC1", "'in grace' before 'redeemed'");
});

test("a sort over part of the list says so, and Load all reads every page, 200 at a time", async () => {
  const later = { ...UNI, id: "lic-uni-old", keyPrefix: "SEMP-OLD1" };
  const oldest = { ...IND, id: "lic-ind-old", keyPrefix: "SEMP-OLD2" };
  await openDesk({
    licenses: [IND],
    page: { hasMore: true, nextPageToken: "t1" },
    routes: {
      "GET /v1/admin/licenses?limit=200&include_revoked=false&page_token=t1":
        () => json(200, { licenses: [later], page: { hasMore: true, nextPageToken: "t2" } }),
      "GET /v1/admin/licenses?limit=200&include_revoked=false&page_token=t2":
        () => json(200, { licenses: [oldest, IND], page: {} }),
    },
  });
  assert.equal($("loadAll").hidden, false);
  await click(sortBy("key"));
  assert.equal($("licencePaging").textContent,
    "Newest 1 shown (revoked and Demo hidden); more exist. Sorted within those; Load all to sort every licence.");
  await click($("loadAll"));
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-OLD1", "SEMP-OLD2"], "every page, sorted, none twice");
  assert.deepEqual(status(), ["All 3 licences loaded.", "muted"]);
  assert.equal($("loadAll").hidden, true);
  assert.equal($("loadMore").hidden, true);
  assert.equal($("licencePaging").textContent, "3 licence(s) (revoked and Demo hidden).");
});

test("Load all that fails part-way keeps what it read and says so", async () => {
  const later = { ...UNI, id: "lic-uni-old", keyPrefix: "SEMP-OLD1" };
  await openDesk({
    licenses: [IND],
    page: { hasMore: true, nextPageToken: "t1" },
    routes: {
      "GET /v1/admin/licenses?limit=200&include_revoked=false&page_token=t1":
        () => json(200, { licenses: [later], page: { hasMore: true, nextPageToken: "t2" } }),
      "GET /v1/admin/licenses?limit=200&include_revoked=false&page_token=t2": () => json(503, { detail: "unavailable" }),
    },
  });
  await click($("loadAll"));
  assert.deepEqual(labels(), ["SEMP-IND1", "SEMP-OLD1"]);
  assert.deepEqual(status(), ["Could not load every licence: unavailable. The 2 read so far are shown.", "muted err"]);
  assert.equal($("loadAll").hidden, false, "pressed again, it carries on from the page that failed");
  assert.equal($("loadAll").disabled, false);
});

test("the key opens the licence's details under its row, and a redraw keeps them open", async () => {
  const lic = {
    ...UNI, createdAt: "2026-03-01T10:00:00Z", supportUntil: "2027-03-01T23:59:59Z",
    note: "Physics dept", adminEmails: ["it@uni.edu", "ops@uni.edu"],
  };
  await openDesk({ licenses: [IND, lic] });
  const toggle = () => $("licenceRows").querySelector(`button[data-details="${UNI.id}"]`);
  assert.equal(toggle().getAttribute("aria-expanded"), "false");
  assert.equal($(`licence-details-${UNI.id}`), null);
  await click(toggle());
  assert.equal(toggle().getAttribute("aria-expanded"), "true");
  const facts = $(`licence-details-${UNI.id}`).querySelectorAll("dt").map((dt) => dt.textContent);
  assert.deepEqual(facts, ["Term", "Locked to", "Licence id", "Mode", "App", "Seating", "Cloud analyses per person",
    "IT contacts", "Issued", "Support until", "Note"], "what left the row comes first");
  const text = $(`licence-details-${UNI.id}`).textContent;
  assert.match(text, /IT contactsit@uni\.edu, ops@uni\.edu/);
  assert.match(text, /Seatingfloating — a shared pool of seats/);
  assert.match(text, /NotePhysics dept/);
  assert.equal(rows().length, 3, "one more row, under the licence");
  $("filter").value = "uni";
  $("filter").dispatch("input");
  assert.notEqual($(`licence-details-${UNI.id}`), null, "still open after the filter redrew the table");
  await click(toggle());
  assert.equal($(`licence-details-${UNI.id}`), null);
});

test("Show test accounts asks the server for their licences, which say so", async () => {
  const testLic = { ...APP_IND, id: "lic-ta", keyPrefix: "SEMP-TA01", testAccount: true };
  await openDesk({ routes: {
    "GET /v1/admin/licenses?limit=50&include_revoked=false&include_test=true":
      () => json(200, { licenses: [IND, testLic], page: {} }),
  } });
  $("showTest").checked = true;
  $("showTest").dispatch("change");
  await settle();
  assert.equal(sent(/admin\/licenses/).at(-1), "GET /v1/admin/licenses?limit=50&include_revoked=false&include_test=true");
  assert.equal(rows().find((r) => r[0] === "SEMP-TA01")[1], "individual semper test");
});

test("a search sends the test-account box with it, only when ticked", async () => {
  await openDesk({ routes: {
    "GET /v1/admin/licenses?limit=50&include_revoked=false&include_test=true": () => json(200, { licenses: [], page: {} }),
    "POST /v1/admin/licenses/search": () => json(200, { licenses: [], page: {} }),
  } });
  $("showTest").checked = true;
  $("showTest").dispatch("change");
  await settle();
  $("filter").value = "far@else.org";
  $("filter").dispatch("input");
  await new Promise((r) => setTimeout(r, 320));
  await settle();
  const search = net.requests.find((r) => r.method === "POST");
  assert.equal(JSON.parse(search.body).include_test, true);
});

test("the filters, toggles and sort are kept for the tab; the text typed is not", async () => {
  await openDesk({ licenses: ALL });
  await choose("kindFilter", "individual");
  await click(sortBy("key"));
  $("filter").value = "chen";
  $("filter").dispatch("input");
  const view = JSON.parse(storage.getItem("semper.desk.licenceView"));
  assert.equal(view.kindFilter, "individual");
  assert.deepEqual(view.sort, { key: "key", dir: 1 });
  assert.equal(JSON.stringify(view).includes("chen"), false, "nothing typed in storage: it is often an address");

  // The page again in the same tab, as after a reload.
  await openDesk({ licenses: ALL });
  assert.equal($("kindFilter").value, "individual");
  assert.equal($("filter").value, "", "the text box starts empty");
  assert.equal(sortOf("key"), "ascending");
  assert.deepEqual(labels(), ["SEMP-APP1", "SEMP-END1", "SEMP-GRC1", "SEMP-IND1"]);
});

test("a kept view naming an option the page no longer has is ignored", async () => {
  // "term" was a sortable column before the row kept only four.
  storage.setItem("semper.desk.licenceView", JSON.stringify({ kindFilter: "gone", sort: { key: "term", dir: 1 } }));
  await openDesk();
  assert.equal($("kindFilter").value, "");
  assert.equal(sortOf("key"), "none");
  storage.setItem("semper.desk.licenceView", "not json");
  await openDesk();
  assert.equal(labels().length, 2);
});

test("a kept Show revoked asks for revoked licences on the first read", async () => {
  storage.setItem("semper.desk.licenceView", JSON.stringify({ showRevoked: true }));
  await openDesk({ routes: { "GET /v1/admin/licenses?limit=50": () => json(200, { licenses: [IND], page: {} }) } });
  assert.equal($("showRevoked").checked, true);
  assert.equal(sent(/admin\/licenses/)[0], "GET /v1/admin/licenses?limit=50");
});

test("/ jumps to the filter, except while typing or in a dialog", async () => {
  await openDesk();
  const press = (target) => {
    const ev = { key: "/", target, prevented: false, preventDefault() { this.prevented = true; } };
    for (const listener of globalThis.document.listeners.keydown || []) listener(ev);
    return ev;
  };
  assert.equal(press($("memberEmail")).prevented, false, "a / typed in a box stays in the box");
  assert.equal($("filter").focused, false);
  assert.equal(press($("editCap")).prevented, false, "nor inside a dialog");
  assert.equal(press(globalThis.document.body).prevented, true);
  assert.equal($("filter").focused, true);
});

test("the row a Roster or Verify card is open for is marked until Close", async () => {
  const seats = `/v1/admin/licenses/${UNI.id}/seats`;
  await openDesk({ routes: {
    [`GET ${seats}`]: () => json(200, { license: { keyPrefix: "SEMP-UNI1" }, seats: [], invites: [] }),
  } });
  const marked = () => $("licenceRows").querySelectorAll("tr").filter((tr) => tr.className === "current")
    .map((tr) => tr.cells[0].textContent.trim());
  assert.deepEqual(marked(), []);
  await click($("licenceRows").querySelector(`button[data-roster="${UNI.id}"]`));
  assert.deepEqual(marked(), ["SEMP-UNI1"]);
  await click($("rosterClose"));
  assert.deepEqual(marked(), []);
});
