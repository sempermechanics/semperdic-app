import { requireSignIn, api, setStatus, esc } from "../auth.js";
import { seatCells, inviteCells, day, licenceStatePill } from "../util.js";

let licenseId = "";

const $ = (id) => document.getElementById(id);

requireSignIn(async (user) => {
  $("signedOut").hidden = true;
  if (!(await administersSomething(user))) return;
  // Deep-link support: ?license=... so IT can bookmark their own licence
  // rather than pasting the id every time.
  const fromUrl = new URLSearchParams(location.search).get("license");
  if (fromUrl) {
    $("licenseId").value = fromUrl;
    load();
  }
});

/**
 * Whether any institution licence names this address as an administrator.
 * With none, the id box is a form that can only ever answer "not found",
 * so the page says that instead and points at the account page. A fault
 * in the check leaves the page usable: the backend still decides.
 */
async function administersSomething(user) {
  let licenses;
  try {
    licenses = (await api("/v1/institutions/licenses")).licenses || [];
  } catch (e) {
    if (e.message !== "email_not_verified") {
      setStatus(`Could not list your institution licences: ${e.message}`, true);
      return true;
    }
    licenses = [];
  }
  if (licenses.length) return true;
  $("app").hidden = true;
  $("notAdminWho").textContent = user.email;
  $("notAdmin").hidden = false;
  setStatus("");
  return false;
}

$("load").addEventListener("click", load);
$("licenseId").addEventListener("keydown", (e) => { if (e.key === "Enter") load(); });
$("add").addEventListener("click", addMember);
$("addEmail").addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });

async function load() {
  licenseId = $("licenseId").value.trim();
  if (!licenseId) return;
  setStatus("Loading…");
  try {
    const data = await api(`/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats`);
    render(data);
    setStatus("");
  } catch (e) {
    $("rosterCard").hidden = true;
    $("summary").innerHTML = "";
    // The backend answers 404 identically for a licence that does not
    // exist and one you do not administer, so that probing ids tells you
    // nothing about other institutions. Say so rather than implying the id
    // was simply mistyped.
    setStatus(
      e.message === "license_not_found"
        ? "No licence with that id that you administer."
        : `Could not load: ${e.message}`,
      true,
    );
  }
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
    ? "Everyone here may use Semper, but only the number of seats above at " +
      "the same time. Someone without a seat keeps their saved work and " +
      "gets one as soon as a colleague finishes."
    : "Everyone here is licensed. Removing someone frees their seat for " +
      "another member.";

  // Invites belong in the same list: to whoever manages the roster this
  // is one question — "who is on this licence" — even though only a seat
  // holds a uid and counts against the cap.
  $("rows").innerHTML =
    data.seats.map(seatRow).concat((data.invites || []).map(inviteRow)).join("") ||
    '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  $("rosterCard").hidden = false;
  for (const el of document.querySelectorAll("[data-act]")) {
    el.addEventListener("click", () => act(el.dataset.act, el.dataset.uid));
  }
  for (const el of document.querySelectorAll("[data-invite]")) {
    el.addEventListener("click", () => withdraw(el.dataset.invite));
  }
}

function seatRow(seat) {
  // A removed member holds nothing to hold, resume or unlock. Resume used to
  // be offered here and reactivated the seat without taking a slot back;
  // adding the address again is how someone comes back.
  if (seat.status === "revoked") {
    return `
    <tr>${seatCells(seat)}
      <td class="actions muted">add again to restore</td>
    </tr>`;
  }
  return `
    <tr>${seatCells(seat)}
      <td class="actions">
        ${seat.deviceIdLock || seat.deviceIdLockMaterialTesting
          ? `<button class="secondary" data-act="clear" data-uid="${esc(seat.uid)}">New device</button>`
          : ""}
        ${seat.status === "active"
          ? `<button class="secondary" data-act="hold" data-uid="${esc(seat.uid)}">Hold</button>`
          : `<button class="secondary" data-act="unhold" data-uid="${esc(seat.uid)}">Resume</button>`}
        <button class="danger" data-act="remove" data-uid="${esc(seat.uid)}">Remove</button>
      </td>
    </tr>`;
}

function inviteRow(invite) {
  return `
    <tr>${inviteCells(invite)}
      <td class="actions">
        <button class="danger" data-invite="${esc(invite.id)}">Withdraw</button>
      </td>
    </tr>`;
}

async function withdraw(inviteId) {
  if (!confirm("Withdraw this invitation? Nobody has claimed it yet.")) return;
  setStatus("Withdrawing…");
  try {
    await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/invites/${encodeURIComponent(inviteId)}`,
      { method: "DELETE" },
    );
    await load();
  } catch (e) {
    setStatus(`Could not withdraw: ${e.message}`, true);
  }
}

async function addMember() {
  const email = $("addEmail").value.trim();
  if (!email) return;
  setStatus("Adding…");
  try {
    const out = await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats`,
      { method: "POST", body: JSON.stringify({ email }) },
    );
    $("addEmail").value = "";
    await load();
    // Someone who has never opened Semper is invited rather than refused,
    // so say which of the two happened — "added" and "invited" mean
    // different things to whoever is chasing them.
    setStatus(
      out.seat
        ? `${email} is on the licence now.`
        : `${email} has not signed in yet — invited. They join automatically ` +
          "the first time they do.",
    );
  } catch (e) {
    setStatus(
      {
        invite_exists: `${email} is already promised a place on a different licence.`,
        // One licence per person. Which licence is not IT's to know; Semper
        // support can move them.
        member_already_licensed: `${email} already has a Semper licence of their own. ` +
          "Ask Semper support to move them onto this one.",
        license_seats_exhausted: "This licence has no seats left.",
        // Another request was claiming on this licence at the same moment.
        // Nothing is wrong with it, and adding again succeeds.
        claim_contended: "Busy just now — try again.",
      }[e.message] || `Could not add ${email}: ${e.message}`,
      true,
    );
  }
}

const ACTIONS = {
  clear: { method: "PATCH", body: { clearDeviceLock: true }, verb: "Unlocking" },
  hold: { method: "PATCH", body: { enabled: false }, verb: "Holding" },
  unhold: { method: "PATCH", body: { enabled: true }, verb: "Resuming" },
  remove: { method: "DELETE", verb: "Removing" },
};

async function act(action, uid) {
  const spec = ACTIONS[action];
  if (action === "remove" && !confirm("Remove this member? Their saved analyses stay untouched.")) {
    return;
  }
  setStatus(`${spec.verb}…`);
  try {
    await api(
      `/v1/institutions/licenses/${encodeURIComponent(licenseId)}/seats/${encodeURIComponent(uid)}`,
      { method: spec.method, ...(spec.body ? { body: JSON.stringify(spec.body) } : {}) },
    );
    await load();
  } catch (e) {
    setStatus(ACT_ERRORS[e.message] || `Could not complete that: ${e.message}`, true);
  }
}

const ACT_ERRORS = {
  seat_revoked: "That member was removed. Add their address again to restore them.",
  license_revoked: "This licence has been revoked, so its seats cannot be changed.",
  seat_busy: "That seat changed while you were acting on it. Try again.",
};
