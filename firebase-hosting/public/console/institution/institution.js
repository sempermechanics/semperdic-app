import { requireSignIn, api, setStatus, esc, markFirstData } from "../auth.js";
import { day, licenceStatePill } from "../util.js";
import { fetchRoster, wireRoster } from "../roster.js";
import { mountSwitcher } from "../switcher.js";

let licenseId = "";

const $ = (id) => document.getElementById(id);

/** The open licence's roster routes, IT's tier; "" when none is open. */
const base = () => (licenseId ? `/v1/institutions/licenses/${encodeURIComponent(licenseId)}` : "");

const renderRows = wireRoster({
  rows: $("rows"), email: $("addEmail"), add: $("add"), base, report: setStatus, reload: load,
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
  if (fromUrl) {
    $("licenseId").value = fromUrl;
    load(roster);
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
    if (e.code !== "email_not_verified") {
      setStatus(`Could not list your institution licences: ${e.message}`, true);
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

/** Read and show the roster of the licence in the id box, or show the read already `started` for it. */
async function load(started) {
  licenseId = $("licenseId").value.trim();
  if (!licenseId) return;
  setStatus("Loading…");
  try {
    render(await (started || fetchRoster(base())));
    markFirstData();
    setStatus("");
  } catch (e) {
    $("rosterCard").hidden = true;
    $("summary").innerHTML = "";
    // The backend answers 404 identically for a licence that does not
    // exist and one you do not administer, so that probing ids tells you
    // nothing about other institutions. Say so rather than implying the id
    // was simply mistyped.
    setStatus(
      e.code === "license_not_found"
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
  renderRows(data.seats, data.invites);
  $("rosterCard").hidden = false;
}
