/* The roster card: one institution licence's members, through the shared
 * roster component (`../roster.js`) on the staff routes, with the same bulk
 * tools IT has on the institution page — the seat-count tiles, the roster
 * CSV export and import (`../roster-tools.js`) and the audit log
 * (`../audit-log.js`) — pointed at the staff tier.
 */
import { wireRoster, fetchRoster } from "../roster.js";
import { loadTiles, wireExport, wireImport } from "../roster-tools.js";
import { wireAuditLog } from "../audit-log.js";
import { placeholderRows, retryRow } from "../util.js";
import { explain } from "../messages.js";
import { $, labelOf } from "./state.js";
import { refreshLicence } from "./licences.js";

let roster = null;      // { id, label } of the licence whose roster is open
let shownId = null;     // the licence the tiles, import results and audit log are for
let keyPrefix = "";     // the open licence's, for file names
let members = new Map(); // uid → address, from the last roster read

// The staff-tier roster routes: any institution licence, whether or not
// this account is among its adminEmails (TD-191). Reads (summary, export,
// audit) are `admin`; the import changes the roster, so it is `admin-stepup`
// and a stale sign-in is re-authenticated by api() like every other change.
const rosterBase = () => (roster ? `/v1/admin/licenses/${encodeURIComponent(roster.id)}` : "");
/** A download's licence part: the key prefix people recognise, else the id. */
const fileName = () => (keyPrefix || (roster ? roster.id : "")).replace(/[^\w.-]+/g, "_");

function rosterReport(message, isError = false) {
  $("rosterHint").textContent = message;
  $("rosterHint").className = isError ? "muted err" : "muted";
}

/** Read the open roster again after a change, and the licence row its counts sit in. */
async function reloadAfterChange() {
  if (!roster) return;
  const id = roster.id;
  await loadRoster();
  refreshLicence(id);
}

const renderRoster = wireRoster({
  rows: $("rosterRows"),
  email: $("memberEmail"),
  add: $("addMember"),
  base: rosterBase,
  report: rosterReport,
  reload: reloadAfterChange,
  retry: () => loadRoster(),
  filter: $("rosterFilter"),
  count: $("rosterCount"),
  head: $("rosterHead"),
  chips: $("rosterChips"),
});

wireExport({ button: $("rosterExport"), base: rosterBase, name: fileName, report: rosterReport });
const clearImport = wireImport({
  text: $("rosterImportText"), file: $("rosterImportFile"), preview: $("rosterImportPreview"),
  submit: $("rosterImportSubmit"), summary: $("rosterImportSummary"),
  results: $("rosterImportResults"), rows: $("rosterImportRows"),
  base: rosterBase, report: rosterReport, reload: reloadAfterChange,
});
const resetAudit = wireAuditLog({
  rows: $("rosterAuditRows"), since: $("rosterAuditSince"), until: $("rosterAuditUntil"),
  show: $("rosterAuditLoad"), more: $("rosterAuditMore"), csv: $("rosterAuditCsv"),
  json: $("rosterAuditJson"), base: rosterBase, name: fileName,
  who: (uid) => members.get(uid) || uid, report: rosterReport,
});

$("rosterClose").addEventListener("click", closeRoster);
function closeRoster() {
  roster = null;
  $("rosterCard").hidden = true;
}

/** Close the card if it shows licence `id`, which was just revoked or deleted. */
export function closeRosterOf(id) {
  if (roster && roster.id === id) closeRoster();
}

export async function openRoster(id) {
  roster = { id, label: labelOf(id) };
  if (id !== shownId) {
    // Another licence: nothing of the last one's may stay on screen under it.
    shownId = id;
    keyPrefix = "";
    members = new Map();
    $("rosterTiles").innerHTML = "";
    clearImport();
    resetAudit();
  }
  $("rosterName").textContent = roster.label;
  $("rosterCard").hidden = false;
  rosterReport("");
  await loadRoster();
}

async function loadRoster() {
  if (!roster) return;
  const open = roster;
  // Another licence's members must not stay on screen under this one's name.
  $("rosterRows").innerHTML = placeholderRows(5, 3);
  try {
    const data = await fetchRoster(rosterBase());
    if (roster !== open) return; // closed, or another roster opened meanwhile
    renderRoster(data.seats, data.invites);
    keyPrefix = data.license.keyPrefix || "";
    // The audit log names members by address, as the roster does.
    members = new Map(data.seats.filter((s) => s.email).map((s) => [s.uid, s.email]));
    // The counts come after the roster, and again after every change to it.
    loadTiles($("rosterTiles"), rosterBase(), () => roster === open);
  } catch (e) {
    if (roster !== open) return;
    $("rosterRows").innerHTML = retryRow(5, `Could not load the roster: ${explain(e)}`);
  }
}
