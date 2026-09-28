// Run: node --test "firebase-hosting/tests/*.test.mjs"
// DOM-free console helpers (public/console/util.js). CI runs these in the
// console-pages job.
import { test } from "node:test";
import assert from "node:assert/strict";
import {
  esc, when, day, licenceState, licenceStatePill, leaseHeld, seatCells, seatDevices, inviteCells,
  errorDetail, licenceListPath, searchableLicenceText, upsertLicence, alreadyLicensedId,
  isoDay, emailList, licenceEditPatch, daysLeft, reauthMethods, unfinishedStepUpText,
} from "../public/console/util.js";

const NOW = Date.parse("2026-09-23T12:00:00Z");

test("esc neutralises markup from user-supplied text", () => {
  assert.equal(esc(`<img src=x onerror="a('b')">&`),
    "&lt;img src=x onerror=&quot;a(&#39;b&#39;)&quot;&gt;&amp;");
  assert.equal(esc(null), "");
  assert.equal(esc(undefined), "");
});

test("when renders an em dash for nothing or garbage", () => {
  assert.equal(when(null), "—");
  assert.equal(when("not a date"), "—");
  assert.notEqual(when("2026-09-23T12:00:00Z"), "—");
});

test("a lease is held only until it ends", () => {
  assert.equal(leaseHeld("2026-09-23T12:05:00Z", NOW), true);
  assert.equal(leaseHeld("2026-09-23T11:55:00Z", NOW), false);
  assert.equal(leaseHeld("2026-09-23T12:00:00Z", NOW), false);
  assert.equal(leaseHeld(null, NOW), false);
  assert.equal(leaseHeld("", NOW), false);
});

test("an expired lease is not shown as held (TD-50)", () => {
  const html = seatCells(
    { uid: "u1", email: "a@uni.edu", status: "active", leaseExpiresAt: "2026-09-23T11:00:00Z" },
    NOW,
  );
  assert.doesNotMatch(html, /until/);
  assert.match(html, /pill off">—/);
});

test("a live lease shows its end", () => {
  const html = seatCells(
    { uid: "u1", email: "a@uni.edu", status: "active", leaseExpiresAt: "2026-09-23T13:00:00Z" },
    NOW,
  );
  assert.match(html, /pill ok">until /);
});

test("seat cells: four columns, status labels, escaped member", () => {
  const html = seatCells({ uid: "u<2>", status: "disabled", deviceIdLock: "abcdefghijklmnop" }, NOW);
  assert.equal(html.match(/<td/g).length, 4);
  assert.match(html, /on hold/);
  assert.match(html, /u&lt;2&gt;/);
  assert.match(html, /abcdefghij…/);
  assert.match(seatCells({ uid: "u", status: "revoked" }, NOW), /removed/);
  assert.match(seatCells({ uid: "u", status: "active" }, NOW), /not yet/);
});

test("a seat shows each app's device, escaped (ADR-010)", () => {
  assert.equal(seatDevices({}), "");
  assert.equal(seatDevices({ deviceIdLock: "abcdefghijklmnop" }), "abcdefghij…");
  assert.equal(
    seatDevices({ deviceIdLock: "abcdefghijklmnop", deviceIdLockMaterialTesting: "<mt>defghijklmn" }),
    "abcdefghij…; Material Testing &lt;mt&gt;defghi…",
  );
  assert.equal(seatDevices({ deviceIdLockMaterialTesting: "zyxwvutsrqpo" }),
    "Material Testing zyxwvutsrq…");
  assert.match(seatCells({ uid: "u", status: "active", deviceIdLockMaterialTesting: "zyxwvutsrqpo" }, NOW),
    /Material Testing zyxwvutsrq…/);
});

test("invite cells line up with seat cells", () => {
  const html = inviteCells({ id: "k", email: "new@uni.edu" });
  assert.equal(html.match(/<td/g).length, 4);
  assert.match(html, /invited/);
});

test("a licence end shows the day it was set to, in any time zone", () => {
  // Stored as the last second of the chosen day, UTC. Rendered locally this
  // read as the next day anywhere east of UTC.
  const shown = day("2026-06-30T23:59:59Z");
  assert.match(shown, /30/);
  assert.doesNotMatch(shown, /1|Jul/);
  assert.equal(day(null), "—");
  assert.equal(day("garbage"), "—");
});

test("a licence's state follows its end and grace, not only its status", () => {
  const lic = (over) => ({ status: "redeemed", ...over });
  assert.equal(licenceState(lic({}), NOW), "redeemed");
  assert.equal(licenceState(lic({ status: "revoked" }), NOW), "revoked");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-30T23:59:59Z", graceEndsAt: "2026-10-14T23:59:59Z",
  }), NOW), "redeemed");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-20T23:59:59Z", graceEndsAt: "2026-10-04T23:59:59Z",
  }), NOW), "in grace");
  assert.equal(licenceState(lic({
    expiresAt: "2026-09-01T23:59:59Z", graceEndsAt: "2026-09-15T23:59:59Z",
  }), NOW), "expired");
  // No grace: expiry is the cliff.
  assert.equal(licenceState(lic({ expiresAt: "2026-09-22T23:59:59Z" }), NOW), "expired");
  assert.match(licenceStatePill(lic({ expiresAt: "2026-09-22T23:59:59Z" }), NOW), /pill off/);
});

test("an error detail splits at its first colon only (TD-116)", () => {
  assert.deepEqual(errorDetail("no_license"), { code: "no_license", rest: "" });
  // An ISO instant carries colons of its own.
  assert.deepEqual(
    errorDetail("device_change_too_soon: 2026-10-25T08:00:00+00:00"),
    { code: "device_change_too_soon", rest: "2026-10-25T08:00:00+00:00" },
  );
  assert.deepEqual(errorDetail(undefined), { code: "", rest: "" });
});

test("the licence list asks the backend to leave Demo and revoked rows out", () => {
  assert.equal(licenceListPath({ limit: 50 }), "/v1/admin/licenses?limit=50&include_revoked=false");
  assert.equal(
    licenceListPath({ limit: 50, showDemo: true, showRevoked: true, pageToken: "abc" }),
    "/v1/admin/licenses?limit=50&include_demo=true&page_token=abc",
  );
  // A search is not a page: the token is dropped.
  assert.equal(
    licenceListPath({ limit: 50, showRevoked: true, pageToken: "abc", q: "a@b.com" }),
    "/v1/admin/licenses?limit=50&q=a%40b.com",
  );
});

test("only an address, a domain or a key prefix is sent as a search", () => {
  assert.equal(searchableLicenceText("a@b.com"), true);
  assert.equal(searchableLicenceText(" uni.edu "), true);
  assert.equal(searchableLicenceText("SEMP-K7QX"), true);
  assert.equal(searchableLicenceText("semp-k7qx-aaaa"), true);
  assert.equal(searchableLicenceText("renewal"), false);
  assert.equal(searchableLicenceText("a@b"), false);
  assert.equal(searchableLicenceText(""), false);
});

test("a refused mint names the licence the address already holds", () => {
  assert.equal(alreadyLicensedId("email_already_licensed: 1c2fff4d30"), "1c2fff4d30");
  assert.equal(alreadyLicensedId("email_already_licensed:abc"), "abc");
  assert.equal(alreadyLicensedId("email_already_licensed"), "");
  assert.equal(alreadyLicensedId("invite_exists"), "");
  assert.equal(alreadyLicensedId(undefined), "");
});

test("a changed licence replaces its row and a new one goes on top", () => {
  const list = [{ id: "a", note: "x", seatsUsed: 2 }, { id: "b" }];
  const changed = upsertLicence(list, { id: "a", seatsUsed: 0 });
  assert.deepEqual(changed, [{ id: "a", note: "x", seatsUsed: 0 }, { id: "b" }]);
  assert.equal(list[0].seatsUsed, 2);
  assert.deepEqual(upsertLicence(list, { id: "c" }).map((l) => l.id), ["c", "a", "b"]);
  assert.equal(upsertLicence(list, null), list);
});

const timed = {
  id: "t", kind: "individual", duration: "timed", expiresAt: "2027-03-31T23:59:59Z",
  graceDays: 14, maxAnalyses: null, note: "PO 1",
};
const form = (over = {}) => ({
  expiry: "2027-03-31", perpetual: false, graceDays: "14", supportUntil: "",
  maxAnalyses: "", note: "PO 1", ...over,
});

test("an unchanged dialog sends nothing", () => {
  assert.equal(licenceEditPatch(timed, form()).error, "Nothing changed.");
});

test("a later end is an extension, an earlier one a confirmed downgrade", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ expiry: "2028-03-31" })),
    { patch: { expiresAt: "2028-03-31T23:59:59Z" }, shortens: false, error: "" });
  assert.deepEqual(licenceEditPatch(timed, form({ expiry: "2027-01-31" })),
    { patch: { expiresAt: "2027-01-31T23:59:59Z", allowShorten: true }, shortens: true, error: "" });
});

test("never expires drops the end; giving a perpetual licence one is a downgrade", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ perpetual: true })).patch, { perpetual: true });
  const perpetual = { ...timed, duration: "perpetual", expiresAt: null, graceDays: null };
  const out = licenceEditPatch(perpetual, form({ expiry: "2027-06-30", graceDays: "" }));
  assert.deepEqual(out.patch, { expiresAt: "2027-06-30T23:59:59Z", allowShorten: true });
  assert.equal(out.shortens, true);
  assert.equal(licenceEditPatch(perpetual, form({ expiry: "", graceDays: "" })).error, "Nothing changed.");
});

test("the cap is set, cleared, or left alone on a demo key", () => {
  assert.deepEqual(licenceEditPatch(timed, form({ maxAnalyses: "500" })).patch, { maxAnalyses: 500 });
  const capped = { ...timed, maxAnalyses: 500 };
  assert.deepEqual(licenceEditPatch(capped, form({ maxAnalyses: "" })).patch, { clearMaxAnalyses: true });
  assert.equal(licenceEditPatch(timed, form({ maxAnalyses: "9", capLocked: true })).error, "Nothing changed.");
});

test("an institution's seats, seating and IT contacts", () => {
  const inst = { ...timed, kind: "institution", maxSeats: 10, seating: "assigned",
    adminEmails: ["it@uni.edu"] };
  const base = { maxSeats: "10", seating: "assigned", adminEmails: "it@uni.edu" };
  assert.deepEqual(licenceEditPatch(inst, form({ ...base, maxSeats: "25", seating: "floating",
    adminEmails: "IT@uni.edu, dean@uni.edu" })).patch,
  { maxSeats: 25, seating: "floating", adminEmails: ["it@uni.edu", "dean@uni.edu"] });
  assert.match(licenceEditPatch(inst, form({ ...base, adminEmails: " " })).error, /IT contact/);
  assert.match(licenceEditPatch(inst, form({ ...base, maxSeats: "" })).error, /Seats/);
});

test("a time-limited licence cannot lose its date by accident", () => {
  assert.match(licenceEditPatch(timed, form({ expiry: "" })).error, /Never expires/);
});

test("helpers", () => {
  assert.equal(isoDay("2027-03-31T23:59:59Z"), "2027-03-31");
  assert.equal(isoDay(null), "");
  assert.deepEqual(emailList(" A@x.org, a@x.org ,, b@x.org"), ["a@x.org", "b@x.org"]);
});

test("days left in the deleted hold", () => {
  const now = Date.parse("2026-09-25T12:00:00Z");
  assert.equal(daysLeft("2026-10-25T12:00:00Z", now), 30);
  assert.equal(daysLeft("2026-09-25T13:00:00Z", now), 1);
  assert.equal(daysLeft("2026-09-24T00:00:00Z", now), 0);
  assert.equal(daysLeft(null, now), 0);
});

test("only an account with a password is asked for one", () => {
  assert.deepEqual(reauthMethods([{ providerId: "google.com" }]),
    { password: false, google: true });
  assert.deepEqual(reauthMethods([{ providerId: "password" }, { providerId: "google.com" }]),
    { password: true, google: true });
  assert.deepEqual(reauthMethods([{ providerId: "password" }]),
    { password: true, google: false });
  assert.deepEqual(reauthMethods(null), { password: false, google: false });
});

test("a failed re-authentication says the action was not sent", () => {
  assert.equal(
    unfinishedStepUpText({ action: "delete", id: "x", reauthFailed: "incomplete" }, "SEMP-2YCY"),
    "SEMP-2YCY was not deleted: the Google sign-in did not finish. Delete it again to retry.");
  assert.equal(
    unfinishedStepUpText({ action: "revoke", id: "x", reauthFailed: "cancelled" }, "SEMP-2YCY"),
    "SEMP-2YCY was not revoked: the authenticator code was not entered. Revoke it again to retry.");
  assert.match(
    unfinishedStepUpText({ action: "delete", id: "x", reauthFailed: "auth/network-request-failed" }, "K"),
    /re-authentication failed \(auth\/network-request-failed\)/);
});

test("a past end is refused before the typed key is asked for", () => {
  const out = licenceEditPatch(timed, form({ expiry: "2026-09-22" }), NOW);
  assert.match(out.error, /already passed/);
  assert.equal(out.shortens, false);
  // Today still has hours left: the end is the last second of the day.
  assert.equal(licenceEditPatch(timed, form({ expiry: "2026-09-23" }), NOW).error, "");
  // An expired licence can still take a note without moving its end.
  const expired = { ...timed, expiresAt: "2026-09-01T23:59:59Z" };
  assert.deepEqual(
    licenceEditPatch(expired, form({ expiry: "2026-09-01", note: "renewal due" }), NOW).patch,
    { note: "renewal due" });
});
