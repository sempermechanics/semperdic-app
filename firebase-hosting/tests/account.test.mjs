// Run: node --test "firebase-hosting/tests/*.test.mjs"
// The account page (console/account/account.js): what a person is told about
// their licence, quota and saved analyses, and the three things they can do
// there — give a seat back, move the licence, download an analysis. Each case
// opens a fresh copy of the page on the fake SDK and DOM (harness.mjs).
import { beforeEach, afterEach, mock } from "node:test";
import assert from "node:assert/strict";
import {
  test, reset, settle, openPage, json, net, confirms, sent, page, $, loadAuth,
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
  assert.deepEqual(sent(), ["GET /v1/me", "GET /v1/sessions"]);
});

test("a live licence shows its kind, key, end date, and may be moved", async () => {
  const expiresAt = iso(30 * DAY);
  await open({ license: {
    mode: "licensed", held: true, kind: "individual", prefix: "SEMP-4K2P", expiresAt,
  } });
  assert.deepEqual(pills(), [
    ["licensed", "pill ok"], ["individual", "pill"], ["SEMP-4K2P", "pill mono"],
    [`ends ${day(expiresAt)}`, "pill"],
  ]);
  assert.equal($("explain").textContent, "Your licence is active on this account.");
  assert.equal($("unbind").hidden, false);
  assert.equal($("release").hidden, true, "no shared seat to give back");
});

test("a Demo key shows only 'demo', and there is nothing to move", async () => {
  await open({ license: { mode: "demo", held: false, kind: "individual", prefix: "SEMP-DEMO" } });
  assert.deepEqual(pills(), [["demo", "pill off"]]);
  assert.match($("explain").textContent, /^You are on the demo\. Analyses are capped/);
  assert.equal($("unbind").hidden, true);
  assert.equal($("release").hidden, true);
});

test("a perpetual licence says it has no end date", async () => {
  await open({ license: { mode: "licensed", held: true, duration: "perpetual" } });
  assert.deepEqual(pills(), [["licensed", "pill ok"], ["no end date", "pill"]]);
});

test("a licence past its grace reads expired, and the account is on the demo", async () => {
  const expiresAt = iso(-40 * DAY);
  await open({ license: {
    mode: "demo", held: true, kind: "individual", expiresAt, graceEndsAt: iso(-10 * DAY),
  } });
  assert.deepEqual(pills(), [["demo", "pill off"], ["individual", "pill"], [`expired ${day(expiresAt)}`, "pill off"]]);
  assert.match($("explain").textContent,
    new RegExp(`^Your licence ended on ${day(expiresAt)} and its grace period is over`));
  assert.equal($("unbind").hidden, false, "a held licence can still be moved");
});

test("a licence in its grace period reads ended, and says when access ends", async () => {
  const expiresAt = iso(-2 * DAY);
  const graceEndsAt = iso(12 * DAY);
  await open({ license: { mode: "licensed", held: true, inGrace: true, expiresAt, graceEndsAt } });
  assert.deepEqual(pills(), [["licensed", "pill ok"], [`ended ${day(expiresAt)}`, "pill warn"]]);
  assert.match($("explain").textContent, new RegExp(`full access ends ${day(graceEndsAt)} unless it is renewed`));
});

test("a shared seat held shows until when, and can be given back", async () => {
  const leaseExpiresAt = iso(10 * 60e3);
  await open({ license: { mode: "licensed", held: true, seating: "floating", leaseExpiresAt } });
  assert.deepEqual(pills().at(-1), [`seat held until ${when(leaseExpiresAt)}`, "pill ok"]);
  assert.match($("explain").textContent, /^You hold one of your institution's shared seats/);
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
  assert.deepEqual(pills(), [["licensed", "pill ok"], ["individual", "pill"]]);
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
    "120 analyses stored, all kept. While your licence is inactive the demo limit " +
    "of 25 applies, so new analyses sync again once it is active.");
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
  assert.deepEqual(rowTexts(), [
    ["Steel A s1", "saved", "4", "1.5 MB", "Download"],
    ["Steel B s2", "uploading", "3 of 10", "2.0 MB when done", "Download"],
    ["local-3 s3", "failed", "0 of 2", "—", "Download"],
    ["Nothing yet s4", "uploading", "0 of 0", "—", "Download"],
    ["<img src=x> s5", "WEIRD", "0 of 0", "—", "Download"],
  ]);
  assert.equal($("rows").querySelectorAll("img").length, 0, "a specimen name is text, never markup");
  const buttons = $("rows").querySelectorAll("button");
  assert.deepEqual(buttons.map((b) => b.disabled), [false, false, true, true, true],
    "nothing to download until something finished uploading");
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
    routes: { "GET /v1/sessions?page_token=t%2F1": () => json(200, {
      sessions: [{ sessionId: "b", status: "COMPLETED", completedCount: 1, totalBytes: 10 }],
      page: {}, quota: { used: 2, max: 25 },
    }) },
  });
  assert.equal($("more").hidden, false);
  $("more").click();
  await settle();
  assert.deepEqual(rowTexts().map((r) => r[0]), ["a a", "b b"]);
  assert.equal($("more").hidden, true);
});

test("a failed analyses list is reported", async () => {
  await open({ routes: { "GET /v1/sessions": () => json(503, { detail: "unavailable" }) } });
  assert.deepEqual(status(), ["Could not list your analyses: unavailable", "muted err"]);
});

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

test("moving the licence asks first and says when it can be moved again", async () => {
  await open({ license: { mode: "licensed", held: true }, routes: {} });
  confirms.answer(false);
  $("unbind").click();
  await settle();
  assert.deepEqual(sent(/unbind/), []);

  reset();
  const next = iso(7 * DAY);
  await unbind(json(200, { nextChangeAllowedAt: next }));
  assert.deepEqual(sent(/unbind/), ["POST /v1/licenses/unbind"]);
  assert.equal($("status").textContent,
    "Done. Sign in on the new device, open Semper once so the licence attaches, and then " +
    `restore your analyses. You can do this again after ${when(next)}.`);
});

test("Material Testing moves on its own, named in the query", async () => {
  // Each app holds its own device (ADR-010). A browser cannot send X-App-Id,
  // so the page names the app; Semper's button still names none.
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
    "Done. Sign in on the new device, open Material Testing once so the licence attaches, " +
    "and then restore your analyses.");
});

test("a move inside the cooldown names the instant it ends", async () => {
  const until = "2026-10-01T10:00:00Z";
  await unbind(json(409, { detail: `device_change_too_soon: ${until}` }));
  assert.deepEqual(status(), [
    `You have moved this licence recently, so you can move it yourself again after ${when(until)}. ` +
    "Ask your IT contact or Semper support if you need to move it now.",
    "muted err",
  ]);
});

test("the other move refusals read as sentences, and an unknown one keeps its code", async () => {
  await unbind(json(409, { detail: "device_change_too_soon" }));
  assert.equal($("status").textContent,
    "You have moved this licence recently. Ask your IT contact or Semper support if you need to move it now.");
  reset();
  await unbind(json(403, { detail: "mfa_required" }));
  assert.equal($("status").textContent, "Set up two-factor authentication first — moving a licence needs it.");
  reset();
  await unbind(json(409, { detail: "strange_thing" }));
  assert.equal($("status").textContent, "Could not move your licence: strange_thing");
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
    "Downloading needs a licence. Demo analyses stay on the device that made them.", "muted err",
  ]);
  assert.equal(button.disabled, false);
});
