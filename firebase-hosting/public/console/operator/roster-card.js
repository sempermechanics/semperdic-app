/* The roster card: one institution licence's members, through the shared
 * roster component (`../roster.js`) on the staff routes.
 */
import { wireRoster, fetchRoster } from "../roster.js";
import { placeholderRows, retryRow } from "../util.js";
import { $, labelOf } from "./state.js";
import { refreshLicence } from "./licences.js";

let roster = null;      // { id, label } of the licence whose roster is open

// The staff-tier roster routes: any institution licence, whether or not
// this account is among its adminEmails (TD-191).
const rosterBase = () => (roster ? `/v1/admin/licenses/${encodeURIComponent(roster.id)}` : "");

function rosterReport(message, isError = false) {
  $("rosterHint").textContent = message;
  $("rosterHint").className = isError ? "muted err" : "muted";
}

const renderRoster = wireRoster({
  rows: $("rosterRows"),
  email: $("memberEmail"),
  add: $("addMember"),
  base: rosterBase,
  report: rosterReport,
  reload: async () => {
    if (!roster) return;
    const id = roster.id;
    await loadRoster();
    refreshLicence(id);
  },
  retry: () => loadRoster(),
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
  } catch (e) {
    if (roster !== open) return;
    $("rosterRows").innerHTML = retryRow(5, `Could not load the roster: ${e.message}`);
  }
}
