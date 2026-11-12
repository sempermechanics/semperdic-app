/* The end user's own page: what they hold, and what they have stored.
 *
 * Two calls answer the whole of the first card — `/v1/me` carries the
 * licence summary including seating and the lease, so nothing here has to
 * fetch limits to find out whether a seat is held. The analyses come from
 * `/v1/sessions`, which carries the quota alongside the page. Each app's
 * own licence and phones (ADR-033) are phones.js's sections, drawn from
 * `/v1/me`'s `licences`.
 */
import { requireSignIn, sessionOver } from "../auth.js";
import { api, apiBlob, saveBlob } from "../api.js";
import { confirmInPage } from "../dialogs.js";
import { setStatus, markFirstData, whileWaiting } from "../status.js";
import {
  esc, when, day, holdsLicence, placeholderRows, retryRow, APP_NAMES,
} from "../util.js";
import { explain as refusalText, ACCOUNT } from "../messages.js";
import { mountSwitcher } from "../switcher.js";
import { licencesAdministered } from "../roles.js";
import { wireSort, sortList, byText, byNumber, matchesText, countLine } from "../list-tools.js";
import { showClaim, onClaimed } from "./claim.js";
import { mountPhones } from "./phones.js";

const $ = (id) => document.getElementById(id);

// Said whatever the licence: deleting is done in the app, and takes the
// sign-in to both apps with it.
$("deleteAccount").textContent = ACCOUNT.deleteAccount;

let licence = {};        // the `license` block of /v1/me
let perApp = false;      // whether /v1/me carried each app's licence (`licences`)
let licences = {};       // /v1/me's `licences`, each app's licence (ADR-033)
let sessions = [];       // every page loaded so far
let nextToken = "";
let quota = null;        // the `quota` block of /v1/sessions
// The institution licences naming this address, for the dashboard switch:
// read once, alongside /v1/me rather than after it, and kept for the
// switch's redraw when the account is read again.
let administered;

requireSignIn(() => {
  $("signedOut").hidden = true;
  showFactorPill();
  administered = licencesAdministered().then((out) => out.licenses);
  loadAccount();
  loadSessions({ reset: true });
});

/* ------------------------------------------------------ second factor */

// Page code only runs once `requireSignIn` has enrolled a second factor
// and confirmed it for this session (`ensureDashboardMfa`), so the factor is
// always on here; the pill just says so.
function showFactorPill() {
  const pill = $("mfaPill");
  pill.hidden = false;
  pill.textContent = "2FA on";
  pill.className = "pill ok";
}

/* ------------------------------------------------------------ licence */

// Each app's Move button. On a licence that runs activations (ADR-033) each
// phone is deactivated on its own in the app's section, so Move is offered
// only on a licence from before the split, which holds one phone by a lock.
const MOVE = { semper: "unbind", semperclassroom: "unbindMt" };
const phones = mountPhones({
  onModel: (app, model) => { $(MOVE[app]).hidden = model !== "lock"; },
});

// A failed /v1/me, kept so the analyses load (which runs alongside and
// clears the status line when it succeeds) puts it back rather than
// wiping it when it answers second.
let accountError = "";

// A redeemed claim code re-reads the account, so the licence it attached is
// shown at once.
onClaimed(loadAccount);

/** Read /v1/me and draw the licence card; resolves to the error reported, or "". */
async function loadAccount() {
  try {
    const me = await api("/v1/me");
    licence = me.license || {};
    perApp = Boolean(me.licences);
    licences = me.licences || {};
    accountError = "";
    renderLicence();
    if (perApp) phones.show(me.licences);
    else phones.hide();
    markFirstData();
    mountSwitcher("account", { me, licenses: administered });
  } catch (e) {
    // Signed out, by this read or the analyses' sent alongside: the line that
    // says to sign in again stays (AE2-105).
    if (sessionOver()) return accountError;
    accountError = `Could not read your account: ${refusalText(e)}`;
    setStatus(accountError, true);
    // The placeholders would otherwise read as still loading.
    $("pills").innerHTML = "";
    $("facts").innerHTML = "";
    $("offline").hidden = true;
    $("explain").textContent = "The licence could not be read. Reload the page to try again.";
    // Whether there is a licence is not known, so nothing is offered.
    showClaim(false);
  }
  return accountError;
}

function renderLicence() {
  const licensed = licence.mode === "licensed";
  const floating = licence.seating === "floating";
  const holdsSeat = licence.leaseExpiresAt &&
    new Date(licence.leaseExpiresAt) > new Date();
  // Whether a real licence is attached: a Demo key used to show as if it
  // were one.
  const held = holdsLicence(licence);
  const lapsed = Date.parse(licence.graceEndsAt || licence.expiresAt || "") <= Date.now();

  const pills = [
    `<span class="pill ${licensed ? "ok" : "off"}">${licensed ? ACCOUNT.modes.licensed : ACCOUNT.modes.demo}</span>`,
  ];
  if (held) {
    if (licence.kind) pills.push(`<span class="pill">${esc(licence.kind)}</span>`);
    if (licence.prefix) pills.push(`<span class="pill mono">${esc(licence.prefix)}</span>`);
    if (licence.duration === "perpetual") pills.push('<span class="pill">no end date</span>');
    if (licence.expiresAt) pills.push(endPill(lapsed));
    if (floating && !lapsed) {
      pills.push(
        holdsSeat
          ? `<span class="pill ok">seat held until ${esc(when(licence.leaseExpiresAt))}</span>`
          : '<span class="pill warn">no seat right now</span>',
      );
    }
  }
  $("pills").innerHTML = `<p>${pills.join(" ")}</p>`;
  // `license` is semper's alone: with each app's licence in its own card
  // below, this one says whose it is, so a semper Classroom licence is not
  // read as the account being on Demo.
  $("licenceTitle").textContent = perApp ? `${appName("semper")} licence` : "Licence";
  $("explain").textContent = explain(licensed, floating, holdsSeat, held, lapsed);
  $("facts").innerHTML = facts(held, floating, holdsSeat)
    .map(([term, value]) => `<dt>${esc(term)}</dt><dd>${esc(value)}</dd>`).join("");
  // Only a licence has anything to keep working offline.
  $("offline").textContent = ACCOUNT.offline;
  $("offline").hidden = !held;

  // A seat can only be given back by whoever holds it, so the button
  // appears only when there is something to give back.
  $("release").hidden = !(floating && holdsSeat);
  // Nothing to move without a licence. A Demo key has a kind too, so this
  // used to offer to move one; the backend then moved nothing worth having.
  // With each app's licence on /v1/me, each app's phones decide (MOVE).
  $("unbind").hidden = perApp || !held;
  // Each app holds its own device (ADR-010), so each moves on its own.
  $("unbindMt").hidden = perApp || !held;
  // A claim code is for an account with nothing attached yet.
  showClaim(!held);
  renderQuota();
}

/**
 * The licence field by field, as [label, value] pairs: everything `/v1/me`
 * says about it, so nobody has to read it out of the pills and the sentence.
 * Mode always; without a licence attached, only that there is none.
 */
function facts(held, floating, holdsSeat) {
  const { labels, modes, kinds, seating } = ACCOUNT;
  const rows = [[labels.mode, modes[licence.mode] || licence.mode || modes.demo]];
  if (!held) return rows.concat([[labels.licence, ACCOUNT.noLicence]]);
  rows.push([labels.licence,
    [kinds[licence.kind] || licence.kind, licence.prefix].filter(Boolean).join(" · ") || "—"]);
  // An individual licence has one seat by definition; only an institution's
  // says how its seats are given out.
  if (licence.kind === "institution" && licence.seating) {
    rows.push([labels.seating, seating[licence.seating] || licence.seating]);
  }
  rows.push([labels.ends, licence.expiresAt ? day(licence.expiresAt) : ACCOUNT.noEnd]);
  if (licence.graceEndsAt) rows.push([labels.graceEnds, day(licence.graceEndsAt)]);
  if (floating) {
    rows.push([labels.lease, holdsSeat ? ACCOUNT.leaseHeld(when(licence.leaseExpiresAt)) : ACCOUNT.leaseNone]);
  }
  return rows;
}

/**
 * The end-date pill. "expired" used to mean the grace period, when the
 * licence still works in full, and a licence past its grace said "expires"
 * with a date already gone.
 */
function endPill(lapsed) {
  const ends = esc(day(licence.expiresAt));
  if (lapsed) return `<span class="pill off">expired ${ends}</span>`;
  if (licence.inGrace) return `<span class="pill warn">ended ${ends}</span>`;
  return `<span class="pill">ends ${ends}</span>`;
}

function explain(licensed, floating, holdsSeat, held, lapsed) {
  if (!licensed && held && lapsed) {
    return `Your licence ended on ${day(licence.expiresAt)} and its after-expiry ` +
           "grace is over, so this account is on Demo. Your saved work is " +
           "untouched. Ask your institution's IT or semper support to renew it.";
  }
  // Only a seat you still hold on a live licence is waiting on a colleague.
  // A seat on hold or removed reads as demo too, and was told to wait for a
  // seat that was never coming.
  if (!licensed && held && floating) {
    return "Every seat on your institution's licence is in use just now. " +
           "Your saved work is untouched, and semper becomes Licensed again " +
           "on this account as soon as a colleague finishes.";
  }
  const others = otherAppsLicensed();
  if (!licensed && !held && others.length) {
    return `${appName("semper")} is on Demo on this account. Your ${others.join(" and ")} ` +
           "licence is in its card below. Ask your institution's IT or semper support if you " +
           `need a licence for ${appName("semper")} too.`;
  }
  if (!licensed) {
    return "You are on Demo. Analyses are capped and cloud backup is off. " +
           "Ask your institution's IT or semper support for a licence. Most " +
           "attach to this address by themselves; if you were given a claim " +
           "code, redeem it below.";
  }
  if (licence.inGrace) {
    return "Your licence has passed its end date. Nothing is restricted yet, " +
           `but full access ends ${day(licence.graceEndsAt)} unless it is renewed.`;
  }
  if (floating) {
    return holdsSeat
      ? "You have one of your institution's shared seats. It renews itself " +
        "while you are working, and returns to the pool when you stop."
      : "Your institution's seats are shared.";
  }
  return "Your licence is active on this account.";
}

/** The other apps this account is Licensed for, by name (`/v1/me`'s `licences`). */
function otherAppsLicensed() {
  return Object.entries(licences)
    .filter(([app, lic]) => app !== "semper" && lic?.mode === "licensed")
    .map(([app]) => appName(app));
}

$("release").addEventListener("click", async () => {
  if (!(await confirmInPage({
    title: "Give up your seat",
    message: "Give up your seat? Your saved work stays exactly as it is, and you " +
      "take another seat the next time you use semper — if one is free.",
    confirm: "Give up my seat",
  }))) return;
  setStatus("Returning your seat…");
  try {
    await api("/v1/licenses/release", { method: "POST" });
    await loadAccount();
    setStatus("Seat returned.");
  } catch (e) {
    setStatus(`Could not return your seat: ${releaseError(e)}`, true);
  }
});

function releaseError(e) {
  return refusalText(e, {
    seating_not_floating: "your licence does not use shared seats.",
    no_license: "there is no licence on this account.",
    rate_limited: "too many requests just now — wait a moment.",
  });
}

$("unbind").addEventListener("click", () => unbind("", "semper", "semper"));
// Semper Classroom's old short name: a backend from before ADR-030 knows only
// it, and every backend since reads it as Semper Classroom (TD-330).
$("unbindMt").addEventListener("click", () =>
  unbind("materialtesting", "semper Classroom", "semperclassroom"));

/**
 * Move one app's device. A browser cannot send `X-App-Id`, so the app is
 * named in the query; the backend reads no app as Semper.
 */
async function unbind(app, name, section) {
  if (!(await confirmInPage({
    title: `Move ${name}`,
    message: `Move ${name} to another phone?\n\n` +
      `Nothing is deleted and nothing is cancelled. ${name} stops being ` +
      "Licensed on your current phone, and attaches to the next phone you " +
      "sign in on. Your analyses come with you once it has.",
    confirm: "Move",
  }))) return;
  setStatus("Unlocking…");
  try {
    const path = app ? `/v1/licenses/unbind?app=${encodeURIComponent(app)}` : "/v1/licenses/unbind";
    await api(path, { method: "POST" });
    setStatus(
      `Done. Sign in on the new phone, open ${name} once so the licence ` +
      "attaches, and then restore your analyses.",
    );
    // The phone that held the licence holds it no longer.
    if (perApp) phones.reload(section);
  } catch (e) {
    setStatus(unbindError(e), true);
  }
}

function unbindError(e) {
  // No cooldown since ADR-033: the licence's device count is the limit.
  return refusalText(e, {
    mfa_required:
      "Set up two-factor authentication first — moving a licence needs it.",
    no_license: "There is no licence on this account to move.",
    seat_not_found: "Your seat is no longer on that licence — ask your institution's IT.",
    license_not_found: "That licence no longer exists — ask semper support.",
  }, (text) => `Could not move your licence: ${text}`);
}

/* ----------------------------------------------------------- analyses */

$("more").addEventListener("click", () => loadSessions({ reset: false }));

let sessionsLoad = 0; // the latest list load; an older answer is dropped

async function loadSessions({ reset }) {
  const ticket = ++sessionsLoad;
  if (reset) {
    sessions = [];
    nextToken = "";
    $("rows").innerHTML = placeholderRows(6, 3);
  }
  // One page at a time: a second click on Show more while the first was in
  // flight asked for the same page again and listed it twice.
  $("more").disabled = true;
  try {
    // Every app's analyses: a browser sends no X-App-Id, and without
    // `app=all` the backend would list only Semper's (ADR-014).
    const page = nextToken ? `&page_token=${encodeURIComponent(nextToken)}` : "";
    const data = await whileWaiting(api(`/v1/sessions?app=all${page}`), "Loading your analyses…");
    // The account read may have ended the session meanwhile: its line stays.
    if (ticket !== sessionsLoad || sessionOver()) return;
    sessions = sessions.concat(data.sessions || []);
    nextToken = (data.page || {}).nextPageToken || "";
    $("more").hidden = !nextToken;
    quota = data.quota || {};
    renderQuota();
    renderSessions();
    setStatus(accountError, Boolean(accountError));
  } catch (e) {
    if (ticket !== sessionsLoad || sessionOver()) return;
    const message = `Could not list your analyses: ${refusalText(e)}`;
    setStatus(message, true);
    if (!sessions.length) $("rows").innerHTML = retryRow(6, message);
  } finally {
    if (ticket === sessionsLoad) $("more").disabled = false;
  }
}

// Also called from renderLicence: the two loads race, and whether the
// licence is lapsed changes what an over-cap count means.
function renderQuota() {
  if (!quota) return;
  const used = quota.used ?? sessions.length;
  // A licence past its grace, or a shared seat not held, drops the cap to
  // the demo's and keeps everything stored — so "120 of 25" is not a count
  // to delete down from, and must not read like one. Same reading as
  // renderLicence: a held licence that is not in licensed mode is inactive.
  // A backend without `held` is answered from the seating and the end date.
  const lapsedByDate =
    Date.parse(licence.graceEndsAt || licence.expiresAt || "") <= Date.now();
  const held = licence.held ?? (licence.seating === "floating" || lapsedByDate);
  const inactive = Boolean(held && licence.mode && licence.mode !== "licensed");
  $("quota").textContent = quota.max == null
    ? `${used} analyses stored.`
    : used > quota.max && inactive
      ? `${used} analyses stored, all kept. While your licence is inactive ` +
        `the Demo limit of ${quota.max} applies, so new analyses are backed up again ` +
        "once it is active."
      : `${used} of ${quota.max} analyses stored.`;
}

/* The list's search, app filter and sort, over the analyses loaded so far. */
const STATE_WORDS = { COMPLETED: "saved", UPLOADING: "uploading", PROVISIONING: "starting", PROVISION_FAILED: "failed" };
const stateWord = (s) => STATE_WORDS[s.status] || String(s.status || "");
const nameOf = (s) => s.name || s.specimen || s.localSessionId || s.sessionId;
const SESSION_SORTS = {
  name: (a, b) => byText(nameOf(a), nameOf(b)),
  app: (a, b) => byText(appName(a.app), appName(b.app)),
  state: (a, b) => byText(stateWord(a), stateWord(b)),
  files: (a, b) => byNumber(Number(a.completedCount) || 0, Number(b.completedCount) || 0),
  size: (a, b) => byNumber(Number(a.totalBytes) || 0, Number(b.totalBytes) || 0),
};
const sessionSort = wireSort($("analysisHead"), renderSessions);
$("analysisFilter").addEventListener("input", renderSessions);
$("analysisApp").addEventListener("change", renderSessions);

function renderSessions() {
  const q = $("analysisFilter").value.trim().toLowerCase();
  const app = $("analysisApp").value;
  // The id is searched but not shown: support may quote one, and nobody
  // else reads it.
  const shown = sortList(sessions.filter((s) =>
    (!app || appName(s.app) === APP_NAMES[app]) &&
    matchesText([nameOf(s), s.specimen, s.localSessionId, s.sessionId, appName(s.app), stateWord(s)], q)),
  sessionSort.order(), SESSION_SORTS);
  $("rows").innerHTML = shown.map(sessionRow).join("") || `<tr><td colspan="6" class="muted">${sessions.length
    ? "No analysis matches. Clear the search, or choose Any app." : "Nothing backed up yet."}</td></tr>`;
  $("analysisCount").textContent = countLine({
    shown: shown.length, total: sessions.length, filtered: Boolean(q || app), more: Boolean(nextToken),
  });
}

// The app that backed an analysis up (ADR-014). A backend without the tag
// answers nothing, and every analysis then was Semper's.

function appName(app) {
  return APP_NAMES[app || "semper"] || app;
}

function sessionRow(s) {
  const done = s.status === "COMPLETED";
  const tone = { COMPLETED: "ok", UPLOADING: "warn", PROVISIONING: "warn" }[s.status] || "off";
  const state = `<span class="pill ${tone}">${esc(stateWord(s))}</span>`;
  // An analysis with nothing stored yet has no bundle to build, and the
  // backend answers 409 rather than sending an empty zip.
  const canDownload = (s.completedCount || 0) > 0;
  return `
    <tr>
      <td>${esc(nameOf(s))}</td>
      <td>${esc(appName(s.app))}</td>
      <td>${state}</td>
      <td>${done ? s.completedCount : `${s.completedCount || 0} of ${s.fileCount || 0}`}</td>
      <td>${storedSize(s)}</td>
      <td class="actions">
        <button class="secondary" data-download="${esc(s.sessionId)}"
                ${canDownload ? "" : "disabled"}>Download</button>
      </td>
    </tr>`;
}

// One listener on the body rather than one per row: the table is rebuilt
// on every page load, and re-binding each time leaks handlers.
$("rows").addEventListener("click", (e) => {
  if (e.target.closest("button[data-retry]")) return loadSessions({ reset: true });
  const sid = e.target?.dataset?.download;
  if (sid) download(sid, e.target);
});

async function download(sid, button) {
  button.disabled = true;
  setStatus("Building your download… this can take a minute for a large analysis.");
  try {
    const blob = await apiBlob(`/v1/sessions/${encodeURIComponent(sid)}/bundle`);
    saveBlob(blob, `semper-analysis-${sid}.zip`);
    setStatus("Downloaded.");
  } catch (e) {
    setStatus(downloadError(e), true);
  } finally {
    button.disabled = false;
  }
}

function downloadError(e) {
  // The backend may suffix a code with ": <sentence>" (feature_not_licensed
  // does, for pre-licensing phones); `refusalText` matches the code alone.
  return refusalText(e, {
    mfa_required:
      "Set up two-factor authentication first — downloads from a browser need it.",
    feature_not_licensed:
      "Downloading needs a licence. Demo analyses stay on the phone that made them.",
    file_not_uploaded: "Nothing has finished uploading in that analysis yet.",
    session_not_found: "That analysis is no longer stored.",
    rate_limited: "Too many downloads just now — wait a moment and try again.",
    drive_download_failed:
      "Storage did not answer. The analysis is intact; try again shortly.",
  }, (text) => `Could not download: ${text}`);
}

/**
 * The Size cell. `totalBytes` is the size the phone declared when the upload
 * began, not what is stored: a failed upload stores nothing and one still
 * running stores part of it, and both used to read as the full size.
 */
function storedSize(s) {
  if (s.status === "COMPLETED") return esc(size(s.totalBytes));
  if (s.status === "PROVISION_FAILED" || !Number(s.totalBytes)) return "—";
  return `<span class="muted">${esc(size(s.totalBytes))} when done</span>`;
}

/** Bytes as something a person reads, matching the app's own rounding. */
function size(bytes) {
  const n = Number(bytes || 0);
  if (!n) return "—";
  const units = ["B", "KB", "MB", "GB"];
  let i = 0;
  let v = n;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i += 1; }
  return `${v < 10 && i ? v.toFixed(1) : Math.round(v)} ${units[i]}`;
}
