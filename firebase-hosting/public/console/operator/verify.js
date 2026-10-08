import { api, esc, when } from "../auth.js";
import { placeholderRows, retryRow } from "../util.js";
import { $, desk, labelOf } from "./state.js";
import { renderLicences, onLicenceRead } from "./licences.js";

/* ------------------------------------------------------- verified seats
 *
 * The roster is a statement of intent. `seatsUsed` moves the instant IT
 * revokes a seat, so the institution's own console can only ever report
 * what IT meant to happen. This asks the backend what the licence still
 * entitles, seat by seat, and names the difference.
 *
 * Two of the answers are ordinary and one is a fault, so every row says
 * which it is in words rather than leaving a code to be looked up.
 */
const SEAT_PROSE = {
  "": "On the roster and holding the licence.",
  on_hold: "On hold. Occupies a seat; entitles nobody until it is resumed.",
  no_account: "There is no account behind this seat. Occupies a seat; entitles nobody.",
  moved_on: "The account is on a different licence now. Occupies a seat here; entitles nobody.",
  demoted: "The account points here but is on Demo. Occupies a seat; entitles nobody.",
  still_licensed:
    "The revoke did not land — this account is still licensed. " +
    "Revoke the seat again to repair it.",
  no_checkin_since_revoke:
    "Revoked, but this account has not been back since. The device may " +
    "still be running on the licence it cached.",
  checked_in: "Revoked, and the account has been back since to hear it.",
};

// moved_on and no_account are reasons for both a live and a revoked seat.
const REVOKED_PROSE = {
  moved_on: "Revoked, and the account is on a different licence now.",
  no_account: "Revoked, and there is no account behind the seat.",
};

const SEAT_PILL = {
  active: "ok",
  revokedConfirmed: "off",
  revokedStillRunning: "warn",
};

export async function openVerified(id) {
  $("verifyName").textContent = labelOf(id);
  $("verifyCard").hidden = false;
  $("verifyCard").dataset.licence = id;
  await loadVerified(id);
}

let verifyLoad = 0; // the latest check; an answer for an older one is dropped

async function loadVerified(id) {
  const ticket = ++verifyLoad;
  $("verifySummary").textContent = "Checking every seat against its holder…";
  $("verifyRows").innerHTML = placeholderRows(4, 3);
  $("verifyReload").disabled = true;
  try {
    const report = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/reconcile`,
    );
    if (ticket !== verifyLoad) return;
    desk.verified[id] = report;
    $("verifySummary").innerHTML = verifiedSummary(report);
    $("verifyRows").innerHTML = (report.seats || []).map(verifiedRow).join("") ||
      '<tr><td colspan="4" class="muted">No seats on this licence yet.</td></tr>';
    // The Seats column now has a second number to show for this licence.
    renderLicences();
  } catch (e) {
    if (ticket !== verifyLoad) return;
    const individual = e.code === "kind_not_institution";
    const msg = individual
      ? "This is an individual licence: one holder, no roster, so there " +
        "are no two counts to compare."
      : `Could not check the seats: ${e.message}`;
    $("verifySummary").innerHTML = `<span class="err">${esc(msg)}</span>`;
    $("verifyRows").innerHTML = individual ? "" : retryRow(4, "The seats were not checked.");
  } finally {
    if (ticket === verifyLoad) $("verifyReload").disabled = false;
  }
}

function verifiedSummary(report) {
  const c = report.counts;
  const parts = [
    `IT's roster says <strong>${report.intended}</strong> ` +
    `seat${report.intended === 1 ? " is" : "s are"} in use.`,
    `<strong>${report.entitled}</strong> account` +
    `${report.entitled === 1 ? " is" : "s are"} actually entitled.`,
  ];
  if (c.revokedStillRunning) {
    parts.push(
      `<span class="err"><strong>${c.revokedStillRunning}</strong> ` +
      `revoke${c.revokedStillRunning === 1 ? " has" : "s have"} not ` +
      `landed yet</span> — see below for which, and why.`,
    );
  } else {
    parts.push('<span class="ok">Every revoke has landed.</span>');
  }
  // `neverClaimed` is what a backend before this change calls the same count.
  const idle = c.notEntitled ?? c.neverClaimed;
  if (idle) {
    parts.push(
      `${idle} seat${idle === 1 ? " is" : "s are"} on the roster without ` +
      "entitling anyone — see below for why.",
    );
  }
  // A counter that disagrees with its own seats is a different fault from
  // anything the buckets describe, and worth saying out loud.
  if (report.intended !== report.intendedRecounted) {
    parts.push(
      `<span class="err">seatsUsed says ${report.intended} but there ` +
      `are ${report.intendedRecounted} live seats — the counter has ` +
      "drifted.</span>",
    );
  }
  return parts.join(" ");
}

function verifiedRow(s) {
  const prose = (s.bucket !== "active" && REVOKED_PROSE[s.reason]) ||
    SEAT_PROSE[s.reason] || s.reason;
  const pill = SEAT_PILL[s.bucket] || "off";
  // Revoked seats are dated by the revoke; live ones have nothing to date.
  const seen = s.lastSeenAt ? esc(when(s.lastSeenAt)) : "never";
  return `
    <tr>
      <td>${esc(s.email || s.uid)}</td>
      <td><span class="pill ${pill}">${esc(s.status)}</span></td>
      <td>${esc(prose)}</td>
      <td class="muted">${seen}</td>
    </tr>`;
}

$("verifyClose").addEventListener("click", () => {
  $("verifyCard").hidden = true;
});

$("verifyRows").addEventListener("click", (ev) => {
  const id = $("verifyCard").dataset.licence;
  if (id && ev.target.closest("button[data-retry]")) loadVerified(id);
});

$("verifyReload").addEventListener("click", () => {
  const id = $("verifyCard").dataset.licence;
  if (id) loadVerified(id);
});

// A change to the licence on this card, or a reload of the table, asks again.
onLicenceRead((id) => {
  const open = $("verifyCard").hidden ? "" : $("verifyCard").dataset.licence;
  if (open && (id === null || id === open)) loadVerified(open);
});
