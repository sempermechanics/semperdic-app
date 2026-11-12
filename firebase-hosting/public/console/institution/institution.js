import { requireSignIn, sessionOver } from "../auth.js";
import { api } from "../api.js";
import { setStatus, markFirstData, whileWaiting } from "../status.js";
import { esc, day, licenceStatePill, placeholderRows, retryRow, sessionEnded, APP_NAMES } from "../util.js";
import { explain } from "../messages.js";
import { fetchRoster, wireRoster } from "../roster.js";
import { mountSwitcher } from "../switcher.js";
import { loadTiles, wireExport, wireImport } from "../roster-tools.js";
import { wireAuditLog } from "../audit-log.js";
import { wirePhones } from "./phones.js";

let licenseId = "";
let shownId = null; // the licence the import results and audit log are for
let keyPrefix = ""; // the open licence's, for file names
let domainLock = ""; // the open licence's domain: IT may add only addresses on it
let members = new Map(); // uid → address, from the last roster read

const $ = (id) => document.getElementById(id);

/** The open licence's roster routes, IT's tier; "" when none is open. */
const base = () => (licenseId ? `/v1/institutions/licenses/${encodeURIComponent(licenseId)}` : "");
/** A download's licence part: the key prefix people recognise, else the id. */
const fileName = () => (keyPrefix || licenseId).replace(/[^\w.-]+/g, "_");

const renderRows = wireRoster({
  rows: $("rows"), email: $("addEmail"), add: $("add"), base, report: setStatus, reload: load,
  filter: $("rowFilter"), count: $("rowCount"), head: $("rosterHead"), chips: $("rowChips"),
  domain: () => domainLock,
});

wireExport({ button: $("exportRoster"), base, name: fileName, report: setStatus });
const clearImport = wireImport({
  text: $("importText"), file: $("importFile"), preview: $("importPreview"),
  submit: $("importSubmit"), summary: $("importSummary"), results: $("importResults"),
  rows: $("importRows"), base, report: setStatus, reload: load, domain: () => domainLock,
});
const resetAudit = wireAuditLog({
  rows: $("auditRows"), since: $("auditSince"), until: $("auditUntil"), show: $("auditLoad"),
  more: $("auditMore"), csv: $("auditCsv"), json: $("auditJson"), base, name: fileName,
  who: (uid) => members.get(uid) || uid, report: setStatus,
});
// The licence's phone pool (ADR-033), on a licence that names its app.
const phones = wirePhones({
  card: $("phonesCard"), pool: $("phonesPool"), help: $("phonesHelp"), rows: $("phoneRows"),
  licenseId: () => licenseId, report: setStatus, reload: load, domain: () => domainLock,
  filter: $("phoneFilter"), count: $("phoneCount"), head: $("phoneHead"),
});

requireSignIn(async (user) => {
  $("signedOut").hidden = true;
  // Deep-link support: ?license=... so IT can bookmark their own licence
  // rather than pasting the id every time. Its roster is read alongside the
  // list of licences this address administers, not after it; the backend
  // answers 404 to anyone who does not administer it, and for them the
  // page shows the "no licence" card and never this answer.
  const fromUrl = (new URLSearchParams(location.search).get("license") || "").trim();
  const roster = fromUrl ? fetchRoster(`/v1/institutions/licenses/${encodeURIComponent(fromUrl)}`) : null;
  if (roster) roster.catch(() => {}); // read, or discarded, below
  if (!(await administersSomething(user))) return;
  $("app").hidden = false;
  if (fromUrl) {
    $("licenseId").value = fromUrl;
    load(roster);
  }
}, { showApp: false });

/**
 * Whether any institution licence names this address as an administrator.
 * With none, the id box is a form that can only ever answer "not found",
 * so the page says that instead and points at the account page. A fault
 * in the check leaves the page usable: the backend still decides.
 */
async function administersSomething(user) {
  let licenses;
  try {
    licenses = (await whileWaiting(api("/v1/institutions/licenses"), "Checking your access…"))
      .licenses || [];
    // The roster's read, sent alongside, may have ended the session first:
    // its line stays, and nothing shows the dashboard again (AE2-105).
    if (sessionOver()) return false;
    setStatus("");
  } catch (e) {
    // Signed out (api.js ended the session and said so), by this read or by
    // the roster's sent alongside: whatever this one failed with, the line
    // that says to sign in again stays and nothing more is shown (AE2-105).
    if (sessionEnded(e) || sessionOver()) return false;
    if (e.code !== "email_not_verified") {
      setStatus(`Could not list your institution licences: ${explain(e)}`, true);
      return true;
    }
    licenses = [];
  }
  if (licenses.length) {
    offerLicences(licenses);
    markFirstData();
    mountSwitcher("institution", { licenses });
    return true;
  }
  $("app").hidden = true;
  $("notAdminWho").textContent = user.email;
  $("notAdmin").hidden = false;
  setStatus("");
  return false;
}

/**
 * The licences this address administers, one click each, beside the id box.
 * The listing already said which they are; asking IT to paste an id from an
 * email it was sent months ago was the box's only purpose.
 */
function offerLicences(licenses) {
  $("licenceChoices").innerHTML = licenses.map((lic) => `
    <button class="secondary" data-pick="${esc(lic.id)}">${esc(lic.keyPrefix || lic.id.slice(0, 10))}${
      lic.domainLock ? ` · ${esc(lic.domainLock)}` : ""}</button>`).join("");
}

$("licenceChoices").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button[data-pick]");
  if (!btn) return;
  $("licenseId").value = btn.dataset.pick;
  load();
});
$("load").addEventListener("click", () => load());
$("licenseId").addEventListener("keydown", (e) => { if (e.key === "Enter") load(); });

let rosterLoad = 0; // the latest roster asked for; an older answer is dropped

/** Read and show the roster of the licence in the id box, or show the read already `started` for it. */
async function load(started) {
  licenseId = $("licenseId").value.trim();
  if (!licenseId) return;
  const ticket = ++rosterLoad;
  if (licenseId !== shownId) {
    // Another licence: nothing of the last one's may stay on screen under it.
    shownId = licenseId;
    keyPrefix = "";
    domainLock = "";
    members = new Map();
    $("tiles").innerHTML = "";
    clearImport();
    resetAudit();
    phones.hide();
  }
  // The card shows its shape at once, and never the last licence's members
  // under this one's id.
  $("summary").innerHTML = '<p><span class="skeleton pill"></span> <span class="skeleton pill"></span></p>' +
    '<p><span class="skeleton line"></span></p>';
  $("rosterHelp").textContent = "";
  $("rows").innerHTML = placeholderRows(5, 3);
  $("rosterCard").hidden = false;
  $("load").disabled = true;
  let phonesRead = null;
  try {
    const data = await whileWaiting(started || fetchRoster(base()), "Loading the roster…");
    // Another read (an export, a Remove) may have ended the session while
    // this one was out: its line stays, and no counts are read (AE2-105).
    if (ticket !== rosterLoad || sessionOver()) return;
    render(data);
    markFirstData();
    setStatus("");
    // The counts come after the roster, and again after every change to it.
    loadTiles($("tiles"), base(), () => ticket === rosterLoad);
    // So do the phones. Open is free again meanwhile, but this load ends
    // once they are drawn: a Deactivate waits for them before it says so.
    phonesRead = phones.show(data.license, () => ticket === rosterLoad && !sessionOver());
  } catch (e) {
    if (ticket !== rosterLoad || sessionOver()) return;
    $("summary").innerHTML = "";
    if (e.code !== "license_not_found") {
      $("rows").innerHTML = retryRow(5, `Could not load: ${explain(e)}`);
    } else {
      $("rosterCard").hidden = true;
      $("importCard").hidden = true;
      $("auditCard").hidden = true;
      $("tiles").innerHTML = "";
      phones.hide();
    }
    // The backend answers 404 identically for a licence that does not
    // exist and one you do not administer, so that probing ids tells you
    // nothing about other institutions. Say so rather than implying the id
    // was simply mistyped.
    setStatus(
      e.code === "license_not_found"
        ? "No licence with that id that you administer."
        : `Could not load: ${explain(e)}`,
      true,
    );
  } finally {
    if (ticket === rosterLoad) $("load").disabled = false;
  }
  await phonesRead;
}

function render(data) {
  const lic = data.license || {};
  const floating = lic.seating === "floating";
  const cap = lic.maxSeats == null ? "unlimited" : lic.maxSeats;
  const inUse = floating ? (lic.leasesActive ?? 0) : (lic.seatsUsed ?? 0);
  // Invites hold no seat until claimed, so they are counted apart rather
  // than left out: a roster of pending invites used to read "0 of 10 taken"
  // with nothing to say ten people were already promised a place.
  const invited = (data.invites || []).length;
  const invitedNote = invited
    ? ` ${invited} more ${invited === 1 ? "is" : "are"} invited and ` +
      (floating
        ? "join the roster when they first sign in."
        : `take a seat when they first sign in, if one is free.`)
    : "";

  $("summary").innerHTML = `
    <p>
      <span class="pill">${esc(lic.keyPrefix || "licence")}</span>
      ${lic.app ? `<span class="pill">${esc(APP_NAMES[lic.app] || lic.app)}</span>` : ""}
      <span class="pill">${floating ? "shared seats" : "one seat each"}</span>
      ${licenceStatePill(lic)}
      ${lic.expiresAt ? `<span class="pill warn">ends ${esc(day(lic.expiresAt))}</span>` : ""}
    </p>
    <p class="muted">
      ${floating
        ? `<strong>${inUse} of ${cap}</strong> seats in use right now, across
           ${data.seats.length} people on the roster.${invitedNote}`
        : `<strong>${inUse} of ${cap}</strong> seats taken.${invitedNote}`}
    </p>`;

  $("rosterHelp").textContent = floating
    ? "Everyone here may use semper, but only the number of seats above at " +
      "the same time. Someone without a seat keeps their saved work and " +
      "gets one as soon as a colleague finishes."
    : "Everyone here is Licensed. Removing someone frees their seat for " +
      "another member.";

  // Invites belong in the same list: to whoever manages the roster this
  // is one question — "who is on this licence" — even though only a seat
  // holds a uid and counts against the cap.
  renderRows(data.seats, data.invites);
  $("rosterCard").hidden = false;
  keyPrefix = lic.keyPrefix || "";
  domainLock = lic.domainLock || "";
  // The audit log names members by address, as the roster does.
  members = new Map(data.seats.filter((s) => s.email).map((s) => [s.uid, s.email]));
  $("importCard").hidden = false;
  $("auditCard").hidden = false;
}
