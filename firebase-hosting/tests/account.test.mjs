// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The account page (console/account/account.js): what a person is told about
// their licence, quota and saved analyses, and the three things they can do
// there — give a seat back, move the licence, download an analysis. Each case
// opens a fresh copy of the page on the fake SDK and DOM (harness.mjs).
import { beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, sent, page, $, loadAuth, deferred,
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

/** The page, with /v1/me answering `license` and /v1/sessions answering `sessions`. */
async function open({ license = {}, sessions = { sessions: [], quota: { used: 0, max: 25 } }, routes = {} } = {}) {
  await openPage("account", {
    routes: {
      "GET /v1/me": () => json(200, { email: "user@example.com", license }),
      "GET /v1/sessions": () => json(200, sessions),
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      ...routes,
    },
  });
}

const pills = () => $("pills").querySelectorAll(".pill").map((p) => [p.textContent, p.className]);
const status = () => [$("status").textContent, $("status").className];

/* ------------------------------------------------------------- licence */

test("the page starts once signed in: 2FA pill, account and analyses", async () => {
  await open({ license: { mode: "licensed", held: true } });
  assert.equal($("signedOut").hidden, true);
  assert.equal($("mfaPill").hidden, false);
  assert.equal($("mfaPill").textContent, "2FA on");
  assert.deepEqual(sent().sort(), ["GET /v1/institutions/licenses", "GET /v1/me", "GET /v1/sessions?app=all"]);
});

const tabs = () => $("switch").querySelectorAll("a").map((a) =>
  [a.textContent, a.getAttribute("href"), a.getAttribute("aria-current") ?? ""]);

test("staff get the switch with Your account current, even on a Demo key", async () => {
  await open({ routes: { "GET /v1/me": () => json(200, {
    role: "admin", email: "staff@semper.test", license: { mode: "demo", held: false },
  }) } });
  assert.equal($("switch").hidden, false);
  assert.deepEqual(tabs(), [["Operator", "../operator/", ""], ["Your account", "../account/", "page"]]);
});

test("an IT contact gets Institution seats | Your account", async () => {
  await open({ routes: { "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }, { id: "L2" }] }) } });
  assert.deepEqual(tabs(), [["Institution seats", "../institution/", ""], ["Your account", "../account/", "page"]]);
});

test("the switch's licence list is read alongside /v1/me, and once", async () => {
  const me = deferred();
  await open({ routes: {
    "GET /v1/me": () => me.promise,
    "GET /v1/institutions/licenses": () => json(200, { licenses: [{ id: "L1" }] }),
  } });
  assert.deepEqual(sent(/institutions/), ["GET /v1/institutions/licenses"], "sent before /v1/me answers");
  me.resolve(json(200, { email: "user@example.com", license: {} }));
  await settle();
  assert.equal($("switch").hidden, false);
  assert.equal(sent(/institutions/).length, 1);
});

test("an account with only this dashboard sees no switch", async () => {
  await open({ license: { mode: "licensed", held: true } });
  assert.equal($("switch").hidden, true);
});

test("when /v1/me fails there is no switch, and the analyses still load", async () => {
  await open({ routes: { "GET /v1/me": () => json(503, { detail: "unavailable" }) } });
  assert.equal($("switch").hidden, true);
  // The switch's own read went out alongside /v1/me; without a role it shows nothing.
  assert.deepEqual(sent().sort(), ["GET /v1/institutions/licenses", "GET /v1/me", "GET /v1/sessions?app=all"]);
});

test("a live licence shows its kind, key, end date, and may be moved", async () => {
  const expiresAt = iso(30 * DAY);
  await open({ license: {
    mode: "licensed", held: true, kind: "individual", prefix: "SEMP-4K2P", expiresAt,
  } });
  assert.deepEqual(pills(), [
    ["Licensed", "pill ok"], ["individual", "pill"], ["SEMP-4K2P", "pill mono"],
    [`ends ${day(expiresAt)}`, "pill"],
  ]);
  assert.equal($("explain").textContent, "Your licence is active on this account.");
  assert.equal($("unbind").hidden, false);
  assert.equal($("release").hidden, true, "no shared seat to give back");
});

test("a Demo key shows only 'demo', and there is nothing to move", async () => {
  await open({ license: { mode: "demo", held: false, kind: "individual", prefix: "SEMP-DEMO" } });
  assert.deepEqual(pills(), [["Demo", "pill off"]]);
  assert.match($("explain").textContent, /^You are on Demo\. Analyses are capped/);
  assert.equal($("unbind").hidden, true);
  assert.equal($("release").hidden, true);
});

test("a perpetual licence says it has no end date", async () => {
  await open({ license: { mode: "licensed", held: true, duration: "perpetual" } });
  assert.deepEqual(pills(), [["Licensed", "pill ok"], ["no end date", "pill"]]);
});

test("a licence past its grace reads expired, and the account is on the demo", async () => {
  const expiresAt = iso(-40 * DAY);
  await open({ license: {
    mode: "demo", held: true, kind: "individual", expiresAt, graceEndsAt: iso(-10 * DAY),
  } });
  assert.deepEqual(pills(), [["Demo", "pill off"], ["individual", "pill"], [`expired ${day(expiresAt)}`, "pill off"]]);
  // Plain text, not a RegExp: day() ends "(UTC)".
  const ended = `Your licence ended on ${day(expiresAt)} and its after-expiry grace is over`;
  assert.ok($("explain").textContent.startsWith(ended), $("explain").textContent);
  assert.equal($("unbind").hidden, false, "a held licence can still be moved");
});

test("a licence in its grace period reads ended, and says when access ends", async () => {
  const expiresAt = iso(-2 * DAY);
  const graceEndsAt = iso(12 * DAY);
  await open({ license: { mode: "licensed", held: true, inGrace: true, expiresAt, graceEndsAt } });
  assert.deepEqual(pills(), [["Licensed", "pill ok"], [`ended ${day(expiresAt)}`, "pill warn"]]);
  const ends = `full access ends ${day(graceEndsAt)} unless it is renewed`;
  assert.ok($("explain").textContent.includes(ends), $("explain").textContent);
});

test("a shared seat held shows until when, and can be given back", async () => {
  const leaseExpiresAt = iso(10 * 60e3);
  await open({ license: { mode: "licensed", held: true, seating: "floating", leaseExpiresAt } });
  assert.deepEqual(pills().at(-1), [`seat held until ${when(leaseExpiresAt)}`, "pill ok"]);
  assert.match($("explain").textContent, /^You have one of your institution's shared seats/);
  assert.equal($("release").hidden, false);
});

test("no shared seat free: told to wait for a colleague, nothing to give back", async () => {
  await open({ license: { mode: "demo", held: true, seating: "floating", leaseExpiresAt: iso(-60e3) } });
  assert.deepEqual(pills().at(-1), ["no seat right now", "pill warn"]);
  assert.match($("explain").textContent, /^Every seat on your institution's licence is in use/);
  assert.equal($("release").hidden, true);
});

test("a backend without `held` counts a licensed account as holding its licence", async () => {
  await open({ license: { mode: "licensed", kind: "individual" } });
  assert.deepEqual(pills(), [["Licensed", "pill ok"], ["individual", "pill"]]);
  assert.equal($("unbind").hidden, false);
});

test("a failed account read is reported", async () => {
  // Answered after the analyses.
  let answerMe;
  const me = new Promise((resolve) => { answerMe = resolve; });
  await open({ routes: { "GET /v1/me": async () => { await me; return json(500, { detail: "boom" }); } } });
  answerMe();
  await settle();
  assert.deepEqual(status(), ["Could not read your account: boom", "muted err"]);
});

test("a failed account read survives the analyses answering second", async () => {
  // The analyses load clears the status line when it succeeds; it must put
  // the account failure back rather than wipe it.
  let answerSessions;
  const later = new Promise((resolve) => { answerSessions = resolve; });
  await open({
    routes: {
      "GET /v1/me": () => json(500, { detail: "boom" }),
      "GET /v1/sessions": async () => { await later; return json(200, { sessions: [], quota: { used: 0, max: 25 } }); },
    },
  });
  answerSessions();
  await settle();
  assert.deepEqual(status(), ["Could not read your account: boom", "muted err"]);
});

/* -------------------------------------------- licence facts and delete */

const facts = () => {
  const terms = $("facts").querySelectorAll("dt").map((el) => el.textContent);
  const values = $("facts").querySelectorAll("dd").map((el) => el.textContent);
  return terms.map((term, i) => [term, values[i]]);
};
const OFFLINE = "On a phone, Licensed features keep working offline until the offline allowance " +
  "runs out; then the app falls back to Demo limits until it reconnects. Analyses you already have " +
  "stay available either way.";

test("an institution licence is set out field by field: mode, licence, seats, end, grace, lease", async () => {
  const expiresAt = iso(-2 * DAY);
  const graceEndsAt = iso(12 * DAY);
  const leaseExpiresAt = iso(20 * 60e3);
  await open({ license: {
    mode: "licensed", held: true, kind: "institution", prefix: "SEMP-UNI1", seating: "floating",
    duration: "timed", expiresAt, graceEndsAt, inGrace: true, leaseExpiresAt,
  } });
  assert.deepEqual(facts(), [
    ["Mode", "Licensed"],
    ["Licence", "Institution · SEMP-UNI1"],
    ["Seats", "Shared across the institution"],
    ["Ends", day(expiresAt)],
    ["After-expiry grace ends", day(graceEndsAt)],
    ["Shared seat", `Held until ${when(leaseExpiresAt)}`],
  ]);
  assert.equal($("offline").hidden, false);
  assert.equal($("offline").textContent, OFFLINE);
});

test("an individual licence with no end has no seat lines; an institution seat not held says so", async () => {
  await open({ license: {
    mode: "licensed", held: true, kind: "individual", prefix: "SEMP-4K2P", duration: "perpetual",
    seating: "assigned",
  } });
  assert.deepEqual(facts(), [["Mode", "Licensed"], ["Licence", "Individual · SEMP-4K2P"], ["Ends", "No end date"]]);
  reset();
  await open({ license: { mode: "demo", held: true, kind: "institution", seating: "floating" } });
  assert.deepEqual(facts(), [
    ["Mode", "Demo"], ["Licence", "Institution"], ["Seats", "Shared across the institution"],
    ["Ends", "No end date"], ["Shared seat", "Not held right now"],
  ]);
  assert.equal($("offline").hidden, false, "an attached licence, even one not active now");
});

test("without a licence the facts say so and the offline line is not shown", async () => {
  await open({ license: { mode: "demo", held: false, kind: "individual", prefix: "SEMP-DEMO" } });
  assert.deepEqual(facts(), [["Mode", "Demo"], ["Licence", "None — this account is on Demo"]]);
  assert.equal($("offline").hidden, true);
});

test("values the page has no word for are shown as they came", async () => {
  await open({ license: { mode: "suspended", held: true, kind: "site", seating: "pooled" } });
  assert.deepEqual(facts().slice(0, 2), [["Mode", "suspended"], ["Licence", "site"]]);
  reset();
  await open({ license: { held: true, kind: "institution", seating: "pooled" } });
  assert.deepEqual(facts().slice(0, 3), [["Mode", "Demo"], ["Licence", "Institution"], ["Seats", "pooled"]]);
  reset();
  await open({ license: { mode: "licensed", held: true } });
  assert.deepEqual(facts()[1], ["Licence", "—"]);
});

test("a failed /v1/me leaves no facts and no offline line", async () => {
  await open({ routes: { "GET /v1/me": () => json(503, { detail: "unavailable" }) } });
  assert.equal($("facts").innerHTML, "");
  assert.equal($("offline").hidden, true);
});

test("the delete card says it is done in the app and removes the sign-in for both apps", async () => {
  await open({ license: { mode: "demo", held: false } });
  const said = $("deleteAccount").textContent;
  assert.match(said, /Settings → Your data → Delete account/);
  assert.match(said, /removes the sign-in itself for both apps, semper and semper Classroom/);
  assert.match(said, /cannot be undone/);
});

/* --------------------------------------------------------------- quota */

test("the quota reads 'N of M'", async () => {
  await open({ license: { mode: "licensed", held: true }, sessions: { sessions: [], quota: { used: 7, max: 999 } } });
  assert.equal($("quota").textContent, "7 of 999 analyses stored.");
});

test("with no cap the quota is a count", async () => {
  await open({ sessions: { sessions: [], quota: { used: 3, max: null } } });
  assert.equal($("quota").textContent, "3 analyses stored.");
});

test("over the demo cap on an inactive licence: everything kept, not a count to delete down from", async () => {
  await open({
    license: { mode: "demo", held: true, seating: "floating" },
    sessions: { sessions: [], quota: { used: 120, max: 25 } },
  });
  assert.equal($("quota").textContent,
    "120 analyses stored, all kept. While your licence is inactive the Demo limit " +
    "of 25 applies, so new analyses are backed up again once it is active.");
});

test("over the cap on a plain Demo account is still 'N of M'", async () => {
  await open({ license: { mode: "demo", held: false }, sessions: { sessions: [], quota: { used: 30, max: 25 } } });
  assert.equal($("quota").textContent, "30 of 25 analyses stored.");
});

test("the inactive wording appears even when the analyses answer before the licence", async () => {
  let answerMe;
  const me = new Promise((resolve) => { answerMe = resolve; });
  await open({
    sessions: { sessions: [], quota: { used: 120, max: 25 } },
    routes: { "GET /v1/me": async () => { await me; return json(200, { license: { mode: "demo", held: true } }); } },
  });
  assert.equal($("quota").textContent, "120 of 25 analyses stored.");
  answerMe();
  await settle();
  assert.match($("quota").textContent, /^120 analyses stored, all kept\./);
});

/* ------------------------------------------------------------ analyses */

const rowTexts = () => $("rows").querySelectorAll("tr").map((tr) => tr.cells.map((td) => td.textContent.replace(/\s+/g, " ").trim()));

test("each analysis row says what is stored, not what was declared", async () => {
  await open({ sessions: { quota: { used: 5, max: 25 }, sessions: [
    { sessionId: "s1", specimen: "Steel A", status: "COMPLETED", completedCount: 4, fileCount: 4, totalBytes: 1572864 },
    { sessionId: "s2", specimen: "Steel B", status: "UPLOADING", completedCount: 3, fileCount: 10, totalBytes: 2097152 },
    { sessionId: "s3", localSessionId: "local-3", status: "PROVISION_FAILED", completedCount: 0, fileCount: 2, totalBytes: 999999 },
    { sessionId: "s4", specimen: "Nothing yet", status: "UPLOADING", totalBytes: 0 },
    { sessionId: "s5", specimen: "<img src=x>", status: "WEIRD" },
  ] } });
  // The analysis's id is not shown under its name any more: it is searched, not read.
  assert.deepEqual(rowTexts(), [
    ["Steel A", "semper", "saved", "4", "1.5 MB", "Download"],
    ["Steel B", "semper", "uploading", "3 of 10", "2.0 MB when done", "Download"],
    ["local-3", "semper", "failed", "0 of 2", "—", "Download"],
    ["Nothing yet", "semper", "uploading", "0 of 0", "—", "Download"],
    ["<img src=x>", "semper", "WEIRD", "0 of 0", "—", "Download"],
  ]);
  assert.equal($("rows").querySelectorAll("img").length, 0, "a specimen name is text, never markup");
  const buttons = $("rows").querySelectorAll("button");
  assert.deepEqual(buttons.map((b) => b.disabled), [false, false, true, true, true],
    "nothing to download until something finished uploading");
});

test("every app's analyses are listed, each with the app that backed it up", async () => {
  // A browser sends no X-App-Id, so without app=all the backend would answer
  // only Semper's (ADR-014).
  await open({ sessions: { quota: { used: 3, max: 25 }, sessions: [
    { sessionId: "s1", specimen: "Steel A", app: "semper", status: "COMPLETED", completedCount: 1 },
    { sessionId: "s2", specimen: "Beam B", app: "semperclassroom", status: "COMPLETED", completedCount: 1 },
    // Backed up before ADR-030: the record keeps Material Testing's short name.
    { sessionId: "s5", specimen: "Rod E", app: "materialtesting", status: "COMPLETED", completedCount: 1 },
    { sessionId: "s3", specimen: "Old C", status: "COMPLETED", completedCount: 1 },
    { sessionId: "s4", specimen: "Next D", app: "<b>future</b>", status: "COMPLETED", completedCount: 1 },
  ] } });
  assert.deepEqual(sent(/sessions/), ["GET /v1/sessions?app=all"]);
  assert.deepEqual(rowTexts().map((r) => r[1]), ["semper", "semper Classroom", "semper Classroom", "semper", "<b>future</b>"]);
  assert.equal($("rows").querySelectorAll("b").length, 0, "an unknown app name is text, never markup");
});

test("the analyses search by name, specimen or id, filter by app, sort by column, and count what is loaded", async () => {
  await open({ sessions: { quota: { used: 4, max: 25 }, page: { nextPageToken: "t1" }, sessions: [
    { sessionId: "s1", name: "Tensile 3", specimen: "Steel A", app: "semper", status: "COMPLETED", completedCount: 4, totalBytes: 300 },
    { sessionId: "s2", specimen: "Beam B", app: "semperclassroom", status: "UPLOADING", completedCount: 1, fileCount: 5, totalBytes: 900 },
    { sessionId: "s3", specimen: "Rod E", app: "materialtesting", status: "COMPLETED", completedCount: 2, totalBytes: 100 },
    { sessionId: "zz-id-77", name: "Tensile 10", app: "semper", status: "COMPLETED", completedCount: 1, totalBytes: 200 },
  ] } });
  assert.equal($("analysisCount").textContent, "Showing 4 of 4 loaded.", "Show more has more: it says so");
  const search = (text) => {
    $("analysisFilter").value = text;
    $("analysisFilter").dispatch("input");
  };
  search("steel");
  assert.deepEqual(rowTexts().map((r) => r[0]), ["Tensile 3"], "the specimen behind a named analysis is searched");
  search("zz-id");
  assert.deepEqual(rowTexts().map((r) => r[0]), ["Tensile 10"], "and the id support may quote");
  assert.equal($("analysisCount").textContent, "1 of 4 loaded match.");
  search("");
  $("analysisApp").value = "semperclassroom";
  $("analysisApp").dispatch("change");
  assert.deepEqual(rowTexts().map((r) => r[0]), ["Beam B", "Rod E"], "Classroom, under either of its names");
  $("analysisApp").value = "";
  $("analysisApp").dispatch("change");
  const head = (key) => $("analysisHead").querySelector(`button[data-sort="${key}"]`);
  head("name").click();
  assert.deepEqual(rowTexts().map((r) => r[0]), ["Beam B", "Rod E", "Tensile 3", "Tensile 10"], "as people count: 3 before 10");
  head("size").click();
  head("size").click();
  assert.equal(head("size").closest("th").getAttribute("aria-sort"), "descending");
  assert.deepEqual(rowTexts().map((r) => r[0]), ["Beam B", "Tensile 3", "Tensile 10", "Rod E"]);
  head("state").click();
  assert.deepEqual(rowTexts().map((r) => r[2]), ["saved", "saved", "saved", "uploading"]);
  search("nothing like this");
  assert.deepEqual(rowTexts(), [["No analysis matches. Clear the search, or choose Any app."]]);
});

test("no analyses says so", async () => {
  await open();
  assert.equal($("rows").textContent.trim(), "Nothing backed up yet.");
  assert.equal($("more").hidden, true);
});

test("Show more fetches the next page and appends it", async () => {
  await open({
    sessions: { sessions: [{ sessionId: "a", status: "COMPLETED", completedCount: 1, totalBytes: 10 }],
      page: { nextPageToken: "t/1" }, quota: { used: 2, max: 25 } },
    routes: { "GET /v1/sessions?app=all&page_token=t%2F1": () => json(200, {
      sessions: [{ sessionId: "b", status: "COMPLETED", completedCount: 1, totalBytes: 10 }],
      page: {}, quota: { used: 2, max: 25 },
    }) },
  });
  assert.equal($("more").hidden, false);
  $("more").click();
  await settle();
  assert.deepEqual(rowTexts().map((r) => r[0]), ["a", "b"]);
  assert.equal($("more").hidden, true);
});

test("Show more is held while a page loads, so no page is listed twice", async () => {
  const next = deferred();
  await open({
    sessions: { sessions: [{ sessionId: "a", status: "COMPLETED", completedCount: 1, totalBytes: 10 }],
      page: { nextPageToken: "t/1" }, quota: { used: 2, max: 25 } },
    routes: { "GET /v1/sessions?app=all&page_token=t%2F1": () => next.promise },
  });
  $("more").click();
  $("more").click();
  await settle();
  assert.equal(sent(/page_token/).length, 1, "the second click did nothing");
  next.resolve(json(200, {
    sessions: [{ sessionId: "b", status: "COMPLETED", completedCount: 1, totalBytes: 10 }],
    page: {}, quota: { used: 2, max: 25 },
  }));
  await settle();
  assert.deepEqual(rowTexts().map((r) => r[0]), ["a", "b"]);
});

test("the licence card and the analyses show placeholders until they load", async () => {
  const me = deferred();
  const list = deferred();
  await open({ routes: { "GET /v1/me": () => me.promise, "GET /v1/sessions": () => list.promise } });
  assert.equal($("pills").querySelectorAll(".skeleton").length, 2);
  assert.equal($("rows").querySelectorAll("tr.placeholder").length, 3);
  assert.equal($("status").textContent, "Loading your analyses…");
  me.resolve(json(200, { email: "user@example.com", license: { mode: "licensed", held: true } }));
  list.resolve(json(200, { sessions: [], quota: { used: 0, max: 25 } }));
  await settle();
  assert.equal($("pills").querySelectorAll(".skeleton").length, 0);
  assert.equal($("rows").textContent.trim(), "Nothing backed up yet.");
});

test("a failed analyses list offers Retry", async () => {
  let fail = true;
  await open({ routes: { "GET /v1/sessions": () => (fail
    ? json(503, { detail: "unavailable" })
    : json(200, { sessions: [], quota: { used: 0, max: 25 } })) } });
  assert.match($("rows").textContent, /Could not list your analyses: unavailable/);
  fail = false;
  $("rows").querySelector("button[data-retry]").click();
  await settle();
  assert.equal($("rows").textContent.trim(), "Nothing backed up yet.");
});

test("a failed analyses list is reported", async () => {
  await open({ routes: { "GET /v1/sessions": () => json(503, { detail: "unavailable" }) } });
  assert.deepEqual(status(), ["Could not list your analyses: unavailable", "muted err"]);
});

// AE2-105, rework 2: one of the page's two reads answered 401 and signed the
// page out; the other answered after (200, 503, or no network) and wrote its
// own line over the one that says to sign in again.
for (const [how, ending, late, answer] of [
  ["the analyses answering 200 after /v1/me's 401", "GET /v1/me", "GET /v1/sessions",
    (held) => held.resolve(json(200, { sessions: [], quota: { used: 0, max: 25 } }))],
  ["the analyses answering 503 after /v1/me's 401", "GET /v1/me", "GET /v1/sessions",
    (held) => held.resolve(json(503, { detail: "unavailable" }))],
  ["/v1/me answering 503 after the analyses' 401", "GET /v1/sessions", "GET /v1/me",
    (held) => held.resolve(json(503, { detail: "unavailable" }))],
  ["/v1/me failing with no network after the analyses' 401", "GET /v1/sessions", "GET /v1/me",
    (held) => held.fail(new TypeError("Failed to fetch"))],
]) {
  test(`${how} keeps the session-ended line`, async () => {
    let fail;
    const held = deferred();
    const promise = new Promise((resolve, reject) => { held.promise.then(resolve); fail = reject; });
    held.fail = fail;
    await open({ routes: { [ending]: () => json(401, { detail: "invalid_token" }), [late]: () => promise } });
    await settle();
    answer(held);
    await settle();
    assert.deepEqual(status(), ["Your sign-in has expired. Sign in again.", "muted err"]);
    assert.equal($("app").hidden, true);
    assert.equal($("signIn").hidden, false);
  });
}

/* ------------------------------------------------------------- release */

async function openHoldingSeat(extraRoutes = {}) {
  await open({
    license: { mode: "licensed", held: true, seating: "floating", leaseExpiresAt: iso(10 * 60e3) },
    routes: extraRoutes,
  });
}

test("giving a seat back asks first, then posts the release and re-reads the licence", async () => {
  await openHoldingSeat({ "POST /v1/licenses/release": () => json(200, {}) });
  confirms.answer(false);
  $("release").click();
  await settle();
  assert.deepEqual(sent(/release/), []);

  confirms.answer(true);
  $("release").click();
  await settle();
  assert.deepEqual(sent(/release|me/), ["GET /v1/me", "POST /v1/licenses/release", "GET /v1/me"]);
  assert.deepEqual(status(), ["Seat returned.", "muted"]);
});

test("a refused release is explained", async () => {
  await openHoldingSeat({ "POST /v1/licenses/release": () => json(409, { detail: "seating_not_floating" }) });
  confirms.answer(true);
  $("release").click();
  await settle();
  assert.deepEqual(status(), ["Could not return your seat: your licence does not use shared seats.", "muted err"]);
});

/* -------------------------------------------------------------- unbind */

async function unbind(response) {
  await open({
    license: { mode: "licensed", held: true, kind: "individual" },
    routes: { "POST /v1/licenses/unbind": () => response },
  });
  confirms.answer(true);
  $("unbind").click();
  await settle();
}

test("moving the licence asks first, and names no wait (no cooldown, ADR-033)", async () => {
  await open({ license: { mode: "licensed", held: true }, routes: {} });
  confirms.answer(false);
  $("unbind").click();
  await settle();
  assert.deepEqual(sent(/unbind/), []);

  reset();
  // A backend from before ADR-033 may still send an instant; it is not shown.
  await unbind(json(200, { nextChangeAllowedAt: iso(7 * DAY) }));
  assert.deepEqual(sent(/unbind/), ["POST /v1/licenses/unbind"]);
  assert.equal($("status").textContent,
    "Done. Sign in on the new phone, open semper once so the licence attaches, and then " +
    "restore your analyses.");
});

test("semper Classroom moves on its own, named in the query", async () => {
  // Each app holds its own device (ADR-010). A browser cannot send X-App-Id,
  // so the page names the app; Semper's button still names none. Semper
  // Classroom is named by its old short name: a backend from before ADR-030
  // knows only it, and every backend since reads it as Semper Classroom.
  await open({
    license: { mode: "licensed", held: true, kind: "individual" },
    routes: { "POST /v1/licenses/unbind": () => json(200, { nextChangeAllowedAt: "" }) },
  });
  assert.equal($("unbindMt").hidden, false);
  confirms.answer(true);
  $("unbindMt").click();
  await settle();
  assert.deepEqual(sent(/unbind/), ["POST /v1/licenses/unbind?app=materialtesting"]);
  assert.equal($("status").textContent,
    "Done. Sign in on the new phone, open semper Classroom once so the licence attaches, " +
    "and then restore your analyses.");
});

test("the other move refusals read as sentences, and an unknown one keeps its code", async () => {
  await unbind(json(403, { detail: "mfa_required" }));
  assert.equal($("status").textContent, "Set up two-factor authentication first — moving a licence needs it.");
  reset();
  await unbind(json(409, { detail: "strange_thing" }));
  assert.equal($("status").textContent, "Could not move your licence: the server refused it (ref: strange_thing)");
});

/* ------------------------------------------------------------ download */

async function openWithDownload(bundle) {
  await open({
    sessions: { quota: { used: 1, max: 25 }, sessions: [
      { sessionId: "s 1", specimen: "Steel", status: "COMPLETED", completedCount: 2, totalBytes: 2048 },
    ] },
    routes: { "GET /v1/sessions/s%201/bundle": bundle },
  });
  return $("rows").querySelector("button");
}

test("Download fetches the bundle and saves it under the analysis id", async () => {
  mock.timers.enable({ apis: ["setTimeout"] }); // saveBlob revokes its URL after 30 s
  try {
    const button = await openWithDownload(() => new Response("PK\x03\x04", { status: 200 }));
    const appended = [];
    const append = page.body.appendChild.bind(page.body);
    page.body.appendChild = (el) => { appended.push(el); return append(el); };
    button.click();
    assert.equal(button.disabled, true, "one download at a time");
    await settle();
    assert.equal(appended.length, 1);
    const [anchor] = appended;
    assert.equal(anchor.download, "semper-analysis-s 1.zip");
    assert.match(anchor.href, /^blob:/);
    assert.ok(anchor.removed, "the link is removed once clicked");
    assert.deepEqual(sent(/bundle/), ["GET /v1/sessions/s%201/bundle"]);
    assert.deepEqual(status(), ["Downloaded.", "muted"]);
    assert.equal(button.disabled, false);
  } finally {
    mock.timers.reset();
  }
});

test("a refused download is explained, whatever follows the code", async () => {
  const button = await openWithDownload(() =>
    json(403, { detail: "feature_not_licensed: this phone predates licensing" }));
  button.click();
  await settle();
  assert.deepEqual(status(), [
    "Downloading needs a licence. Demo analyses stay on the phone that made them.", "muted err",
  ]);
  assert.equal(button.disabled, false);
});
