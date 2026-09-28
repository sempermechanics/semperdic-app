/* The end user's own page: what they hold, and what they have stored.
 *
 * Two calls answer the whole of the first card — `/v1/me` carries the
 * licence summary including seating and the lease, so nothing here has to
 * fetch limits to find out whether a seat is held. The analyses come from
 * `/v1/sessions`, which carries the quota alongside the page.
 */
import {
  requireSignIn, api, apiBlob, saveBlob, setStatus, esc, when, day,
} from "../auth.js";
import { errorDetail } from "../util.js";

const $ = (id) => document.getElementById(id);

let licence = {};        // the `license` block of /v1/me
let sessions = [];       // every page loaded so far
let nextToken = "";
let quota = null;        // the `quota` block of /v1/sessions

requireSignIn(() => {
  $("signedOut").hidden = true;
  showFactorPill();
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

// A failed /v1/me, kept so the analyses load (which runs alongside and
// clears the status line when it succeeds) puts it back rather than
// wiping it when it answers second.
let accountError = "";

async function loadAccount() {
  try {
    const me = await api("/v1/me");
    licence = me.license || {};
    accountError = "";
    renderLicence();
  } catch (e) {
    accountError = `Could not read your account: ${e.message}`;
    setStatus(accountError, true);
  }
}

function renderLicence() {
  const licensed = licence.mode === "licensed";
  const floating = licence.seating === "floating";
  const holdsSeat = licence.leaseExpiresAt &&
    new Date(licence.leaseExpiresAt) > new Date();
  // Whether a real licence is attached. A Demo key and a licence revoked out
  // from under the account both have a kind and a prefix, and used to show
  // them as if they were one. A backend without `held` is answered from mode.
  const held = licence.held ?? licensed;
  const lapsed = Date.parse(licence.graceEndsAt || licence.expiresAt || "") <= Date.now();

  const pills = [
    `<span class="pill ${licensed ? "ok" : "off"}">${licensed ? "licensed" : "demo"}</span>`,
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
  $("explain").textContent = explain(licensed, floating, holdsSeat, held, lapsed);

  // A seat can only be given back by whoever holds it, so the button
  // appears only when there is something to give back.
  $("release").hidden = !(floating && holdsSeat);
  // Nothing to move without a licence. A Demo key has a kind too, so this
  // used to offer to move one; the backend then moved nothing worth having.
  $("unbind").hidden = !held;
  // Each app holds its own device (ADR-010), so each moves on its own.
  $("unbindMt").hidden = !held;
  renderQuota();
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
    return `Your licence ended on ${day(licence.expiresAt)} and its grace period ` +
           "is over, so this account is on the demo. Your saved work is " +
           "untouched. Ask your Semper contact or your IT department to renew it.";
  }
  // Only a seat you still hold on a live licence is waiting on a colleague.
  // A seat on hold or removed reads as demo too, and was told to wait for a
  // seat that was never coming.
  if (!licensed && held && floating) {
    return "Every seat on your institution's licence is in use just now. " +
           "Your saved work is untouched, and Semper becomes licensed again " +
           "on this account as soon as a colleague finishes.";
  }
  if (!licensed) {
    return "You are on the demo. Analyses are capped and cloud backup is off. " +
           "Ask your Semper contact or your IT department for a licence — it " +
           "attaches to this address by itself, with nothing to type.";
  }
  if (licence.inGrace) {
    return "Your licence has passed its end date. Nothing is restricted yet, " +
           `but full access ends ${day(licence.graceEndsAt)} unless it is renewed.`;
  }
  if (floating) {
    return holdsSeat
      ? "You hold one of your institution's shared seats. It renews itself " +
        "while you are working, and returns to the pool when you stop."
      : "Your institution's seats are shared.";
  }
  return "Your licence is active on this account.";
}

$("release").addEventListener("click", async () => {
  if (!confirm(
    "Give up your seat? Your saved work stays exactly as it is, and you " +
    "take another seat the next time you use Semper — if one is free.",
  )) return;
  setStatus("Returning your seat…");
  try {
    await api("/v1/licenses/release", { method: "POST" });
    await loadAccount();
    setStatus("Seat returned.");
  } catch (e) {
    setStatus(`Could not return your seat: ${releaseError(e.message)}`, true);
  }
});

function releaseError(code) {
  return {
    seating_not_floating: "your licence does not use shared seats.",
    no_license: "there is no licence on this account.",
    rate_limited: "too many requests just now — wait a moment.",
  }[code] || code;
}

$("unbind").addEventListener("click", () => unbind("", "Semper"));
$("unbindMt").addEventListener("click", () => unbind("materialtesting", "Material Testing"));

/**
 * Move one app's device. A browser cannot send `X-App-Id`, so the app is
 * named in the query; the backend reads no app as Semper.
 */
async function unbind(app, name) {
  if (!confirm(
    `Move ${name} to a different device?\n\n` +
    `Nothing is deleted and nothing is cancelled. ${name} stops being ` +
    "licensed on your current device, and attaches to the next device you " +
    "sign in on. Your analyses come with you once it has.",
  )) return;
  setStatus("Unlocking…");
  try {
    const path = app ? `/v1/licenses/unbind?app=${encodeURIComponent(app)}` : "/v1/licenses/unbind";
    const out = await api(path, { method: "POST" });
    setStatus(
      `Done. Sign in on the new device, open ${name} once so the licence ` +
      "attaches, and then restore your analyses." +
      (out.nextChangeAllowedAt
        ? ` You can do this again after ${when(out.nextChangeAllowedAt)}.`
        : ""),
    );
  } catch (e) {
    setStatus(unbindError(e.message), true);
  }
}

function unbindError(detail) {
  // The cooldown refusal carries the instant it ends after the code.
  const { code, rest } = errorDetail(detail);
  return {
    // Not a refusal of entitlement: a second factor proves who is asking,
    // not how often, so the cooldown is what stops one licence being
    // passed round a lab.
    device_change_too_soon:
      "You have moved this licence recently" +
      // Local date and time, as the success message gives it: the cooldown
      // ends at the instant of the last move, not at a day boundary.
      (rest ? `, so you can move it yourself again after ${when(rest)}` : "") +
      ". Ask your IT contact or Semper support if you need to move it now.",
    mfa_required:
      "Set up two-factor authentication first — moving a licence needs it.",
    no_license: "There is no licence on this account to move.",
    seat_not_found: "Your seat is no longer on that licence — ask your IT contact.",
    license_not_found: "That licence no longer exists — ask your Semper contact.",
  }[code] || `Could not move your licence: ${code}`;
}

/* ----------------------------------------------------------- analyses */

$("more").addEventListener("click", () => loadSessions({ reset: false }));

async function loadSessions({ reset }) {
  if (reset) { sessions = []; nextToken = ""; }
  setStatus("Loading your analyses…");
  try {
    const page = nextToken ? `?page_token=${encodeURIComponent(nextToken)}` : "";
    const data = await api(`/v1/sessions${page}`);
    sessions = sessions.concat(data.sessions || []);
    nextToken = (data.page || {}).nextPageToken || "";
    $("more").hidden = !nextToken;
    quota = data.quota || {};
    renderQuota();
    renderSessions();
    setStatus(accountError, Boolean(accountError));
  } catch (e) {
    setStatus(`Could not list your analyses: ${e.message}`, true);
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
        `the demo limit of ${quota.max} applies, so new analyses sync again ` +
        "once it is active."
      : `${used} of ${quota.max} analyses stored.`;
}

function renderSessions() {
  $("rows").innerHTML = sessions.map(sessionRow).join("") ||
    '<tr><td colspan="5" class="muted">Nothing backed up yet.</td></tr>';
}

function sessionRow(s) {
  const done = s.status === "COMPLETED";
  const state = {
    COMPLETED: '<span class="pill ok">saved</span>',
    UPLOADING: '<span class="pill warn">uploading</span>',
    PROVISIONING: '<span class="pill warn">starting</span>',
    PROVISION_FAILED: '<span class="pill off">failed</span>',
  }[s.status] || `<span class="pill off">${esc(s.status || "")}</span>`;
  // An analysis with nothing stored yet has no bundle to build, and the
  // backend answers 409 rather than sending an empty zip.
  const canDownload = (s.completedCount || 0) > 0;
  return `
    <tr>
      <td>${esc(s.specimen || s.localSessionId || s.sessionId)}
          <div class="muted mono">${esc(s.sessionId)}</div></td>
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
    setStatus(downloadError(e.message), true);
  } finally {
    button.disabled = false;
  }
}

function downloadError(detail) {
  // The backend may suffix a code with ": <sentence>" (feature_not_licensed
  // does, for pre-licensing phones); the map is keyed on the code alone.
  const code = String(detail).split(":")[0].trim();
  return {
    mfa_required:
      "Set up two-factor authentication first — downloads from a browser need it.",
    feature_not_licensed:
      "Downloading needs a licence. Demo analyses stay on the device that made them.",
    file_not_uploaded: "Nothing has finished uploading in that analysis yet.",
    session_not_found: "That analysis is no longer stored.",
    rate_limited: "Too many downloads just now — wait a moment and try again.",
    drive_download_failed:
      "Storage did not answer. The analysis is intact; try again shortly.",
  }[code] || `Could not download: ${code}`;
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
