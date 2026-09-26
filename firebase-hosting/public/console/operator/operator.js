import {
  requireSignIn, api, setStatus, esc, when, confirmByTyping,
  stepUpForRevoke, ERR_CANCELLED,
} from "../auth.js";
import {
  seatCells, inviteCells, day, licenceStatePill,
  licenceListPath, searchableLicenceText, upsertLicence, alreadyLicensedId,
  isoDay, emailList, licenceEditPatch, daysLeft, unfinishedStepUpText,
} from "../util.js";

const $ = (id) => document.getElementById(id);
let licences = [];
let roster = null;      // { id, label } of the licence whose roster is open
// Reconciliation reports, keyed by licence id, kept only for this page
// load. Filled on demand: the read costs one user lookup per seat, so it
// is never run for the whole table at once.
let verified = {};
// One pending revoke per page load, whatever calls `onReady`.
let resumed = false;

requireSignIn(async (user, resume) => {
  $("signedOut").hidden = true;
  if (!(await isOperator(user))) return;
  showFactorPill();
  loadUsers();
  await loadLicences();
  // Back from the Google re-authentication a revoke asked for: finish it
  // now, while the fresh sign-in is inside the backend's window. If the
  // round trip failed, say so here — after the list load, whose own status
  // line used to wipe the failure and leave a delete silently unsent.
  if (resume && resume.reauthFailed) {
    setStatus(unfinishedStepUpText(resume, labelOf(resume.id)), true);
    return;
  }
  if (resume && resume.action === "revoke") resumeRevoke(resume.id);
  if (resume && resume.action === "delete") resumeDelete(resume.id);
});

/**
 * Whether this account may see the desk at all. Anyone can be sent here by
 * a link, and the backend refuses every call from a non-operator, so the
 * page used to show a full mint form under a one-line refusal. Now the desk
 * stays hidden and the account is told where it can go instead.
 */
async function isOperator(user) {
  let me;
  try {
    me = await api("/v1/me");
  } catch (e) {
    $("app").hidden = true;
    setStatus(`Could not check whether ${user.email} is an operator: ${e.message}`, true);
    return false;
  }
  if (me.role === "admin") return true;
  $("app").hidden = true;
  $("notOperatorWho").textContent = user.email;
  $("notOperator").hidden = false;
  setStatus("");
  return false;
}

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

/* --------------------------------------------------------------- mint */

const kindRadios = () => document.querySelectorAll('input[name="kind"]');

/** Show the fields of whichever kind is ticked. */
function syncKind() {
  const institution = [...kindRadios()].some((r) => r.value === "institution" && r.checked);
  $("individualFields").hidden = institution;
  $("institutionFields").hidden = !institution;
}

for (const radio of kindRadios()) radio.addEventListener("change", syncKind);

$("duration").addEventListener("change", () => {
  const timed = $("duration").value === "timed";
  $("expiresAt").disabled = !timed;
  $("graceDays").disabled = !timed;
  if (!timed) { $("expiresAt").value = ""; $("graceDays").value = ""; }
});

const num = (id) => ($(id).value ? Number($(id).value) : null);
const str = (id) => ($(id).value.trim() || null);

$("mint").addEventListener("click", async () => {
  const kind = document.querySelector('input[name="kind"]:checked').value;
  const timed = $("duration").value === "timed";
  if (timed && !$("expiresAt").value) {
    setStatus("A time-limited licence needs an expiry date.", true);
    return;
  }
  const body = {
    kind,
    duration: $("duration").value,
    // End of the chosen day, UTC — a licence bought "until the 31st"
    // should not stop working on the morning of the 31st.
    expiresAt: timed ? `${$("expiresAt").value}T23:59:59Z` : null,
    graceDays: timed ? num("graceDays") : null,
    maxAnalyses: num("maxAnalyses"),
    note: str("note"),
    ...(kind === "institution"
      ? {
          domainLock: str("domainLock"),
          adminEmails: ($("adminEmails").value || "")
            .split(",").map((s) => s.trim()).filter(Boolean),
          maxSeats: num("maxSeats"),
          seating: $("seating").value,
        }
      : { emailLock: str("emailLock") }),
  };
  for (const k of Object.keys(body)) if (body[k] === null) delete body[k];

  $("mint").disabled = true;
  setStatus("Issuing…");
  try {
    const out = await api("/v1/admin/licenses", {
      method: "POST", body: JSON.stringify(body),
    });
    // Both kinds return the plaintext key, once. An individual one is for
    // support recovery only; delivery is the sign-in. An institution one is
    // what members on the domain can redeem, besides IT's roster.
    if (out.key) {
      $("mintedKey").textContent = out.key;
      $("mintedBox").hidden = false;
    }
    setStatus(...mintOutcome(out, body));
    // Read back rather than show the mint's answer: attaching to an account
    // that already signed in happens after the licence is written.
    if (out.license) refreshLicence(out.license.id);
  } catch (e) {
    const held = alreadyLicensedId(e.message);
    if (held) {
      showHeldLicence(body.emailLock, held);
    } else {
      setStatus(mintError(e.message), true);
    }
  } finally {
    $("mint").disabled = false;
  }
});

/**
 * What an individual mint actually did, as [message, isError]. The licence
 * exists in every case; what varies is whether it reached the person, and
 * each way it did not is something the operator has to act on.
 */
function mintOutcome(out, body) {
  if (body.kind === "institution") {
    return [`Institution licence issued. People with a verified ${body.domainLock || "domain"} ` +
      "address can redeem the key below, or IT adds them from the roster."];
  }
  const who = body.emailLock || "that address";
  if (out.inviteError === "invite_exists") {
    return [`Issued, but ${who} is already promised another licence — this ` +
      "one will not attach. Revoke whichever of the two is not wanted.", true];
  }
  if (out.inviteError) {
    return [`Issued, but the invite for ${who} failed (${out.inviteError}) — ` +
      "it will not attach at sign-in. The key below still redeems it.", true];
  }
  if (out.claimError === "holder_already_licensed") {
    return [`Issued, but ${who} already holds a live licence, so this one was ` +
      "not attached. Revoke or extend the other one.", true];
  }
  if (out.claimError) {
    return [`Issued, but it could not be attached to ${who} (${out.claimError}). ` +
      "The key below redeems it.", true];
  }
  if (out.claimedByUid) {
    return [`Issued and attached to ${who} — licensed from their next request.`];
  }
  return [`Issued. It attaches when ${who} first signs in.`];
}

/**
 * One licence per person: the backend refused the mint because the address
 * holds or is promised a live licence, and named it. Put that one in front
 * of the operator — renewal is Edit on it, replacing it is revoke first.
 * It may be an institution seat, so the filter is the address, not the id.
 */
async function showHeldLicence(email, id) {
  setStatus(`Not issued: ${email} already has a live licence (shown below). ` +
    "To renew it, use Edit. To replace it, revoke it first, then issue again.", true);
  $("filter").value = email;
  await refreshLicence(id);
  searchLicences();
}

function mintError(code) {
  return {
    mfa_required: "Enrol a second factor before issuing licences.",
    not_admin: "That account is not a Semper operator.",
    rate_limited: "Too many admin calls just now — wait a moment.",
  }[code] || `Could not issue: ${code}`;
}

$("copyKey").addEventListener("click", () => {
  navigator.clipboard.writeText($("mintedKey").textContent)
    .then(() => setStatus("Key copied."))
    .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
});

/* ----------------------------------------------------------- licences */

$("reload").addEventListener("click", () => loadLicences());
$("filter").addEventListener("input", onFilterInput);
// The backend leaves Demo and revoked licences out, so showing them is a
// fetch, not a redraw.
$("showRevoked").addEventListener("change", () => loadLicences());
$("showDemo").addEventListener("change", () => loadLicences());
$("loadMore").addEventListener("click", () => loadMoreLicences());

const listPath = (extra = {}) => licenceListPath({
  limit: LICENCE_PAGE,
  showDemo: $("showDemo").checked,
  showRevoked: $("showRevoked").checked,
  ...extra,
});

/**
 * Fetch and redraw the licence table, from its first page.
 *
 * A change to one licence does not come here: it refreshes that row
 * (`refreshLicence`). Reloading the first page after every change was the
 * desk's slowness, and it dropped every page loaded with "Load more".
 */
async function loadLicences() {
  setStatus("Loading…");
  const wasOpen = $("verifyCard").hidden ? "" : $("verifyCard").dataset.licence;
  verified = {};
  searchHits = null;
  try {
    const data = await api(listPath());
    licences = data.licenses || [];
    licencePage = data.page || {};
    demoAllowance = Number.isInteger(data.demoMaxAnalyses) ? data.demoMaxAnalyses : null;
    renderLicences();
    setStatus("");
    if (wasOpen) loadVerified(wasOpen);
    if (searchableLicenceText($("filter").value)) searchLicences();
  } catch (e) {
    setStatus(
      e.message === "not_admin"
        ? "That account is not a Semper operator."
        : `Could not load licences: ${e.message}`,
      true,
    );
  }
}

/**
 * Re-read one licence and redraw its row, after something changed it. The
 * status line keeps what the change said; any seat check on screen for it
 * is re-run, because a change is exactly when to ask again.
 */
async function refreshLicence(id) {
  try {
    showLicence(await api(`/v1/admin/licenses/${encodeURIComponent(id)}`));
  } catch (e) {
    setStatus(`Changed, but the row could not be re-read: ${e.message}. Refresh to see it.`, true);
  }
}

/** Put a licence the backend just returned into the table. */
function showLicence(lic) {
  if (!lic || !lic.id) return;
  licences = upsertLicence(licences, lic);
  if (searchHits) searchHits = upsertLicence(searchHits, lic);
  delete verified[lic.id];
  renderLicences();
  if (!$("verifyCard").hidden && $("verifyCard").dataset.licence === lic.id) loadVerified(lic.id);
}

let licencePage = {};
// Newest first, and without Demo keys, so the first page is the one wanted.
const LICENCE_PAGE = 50;
// Licences the backend found for the filter text, or null when the filter
// is not a search. They join the loaded rows, since a match may be on a page
// nobody has loaded.
let searchHits = null;
let searchTimer = 0;
// DEMO_MAX_ANALYSES as the backend has it, from the licence list. Null until
// a backend that sends it answers; the wording then leaves the number out.
let demoAllowance = null;

/**
 * Why a demo key has no Cap. A demo holder gets the demo allowance whatever
 * the key stores, and the backend refuses the edit (`cap_on_demo_key`). It
 * used to answer 200 and change nothing, and the app kept showing "N of 25".
 */
function demoCapNote() {
  const allowance = demoAllowance == null ? "the demo allowance" : `the demo allowance of ${demoAllowance}`;
  return `Demo keys use ${allowance}; issue a licensed key to raise it.`;
}

/**
 * The filter narrows the loaded rows at once. An address, a domain or a key
 * prefix is also looked up on the backend, after a pause in typing, so a
 * licence past the loaded pages is found too.
 */
function onFilterInput() {
  clearTimeout(searchTimer);
  searchHits = null;
  renderLicences();
  if (searchableLicenceText($("filter").value)) searchTimer = setTimeout(searchLicences, 300);
}

async function searchLicences() {
  const q = $("filter").value.trim();
  try {
    const data = await api(listPath({ q }));
    // Typing moved on while this was in flight: its answer is for old text.
    if ($("filter").value.trim() !== q) return;
    searchHits = data.licenses || [];
    renderLicences();
  } catch (e) {
    setStatus(`Could not search: ${e.message}`, true);
  }
}

/**
 * The next page of licences, appended. The desk used to stop at the first
 * page and say only "more exist", so any licence past it could not be
 * found, filtered for, or acted on from here.
 */
async function loadMoreLicences() {
  const token = licencePage.nextPageToken;
  if (!token) return;
  $("loadMore").disabled = true;
  try {
    const data = await api(listPath({ pageToken: token }));
    const loaded = new Set(licences.map((l) => l.id));
    licences = licences.concat((data.licenses || []).filter((l) => !loaded.has(l.id)));
    licencePage = data.page || {};
    renderLicences();
  } catch (e) {
    setStatus(`Could not load more licences: ${e.message}`, true);
  } finally {
    $("loadMore").disabled = false;
  }
}

// Revoked licences are kept — the record is the audit trail, and a revoked
// key can still be looked up — but out of the way by default: a revoke that
// left its row in place with only the pill changed read as a revoke that had
// not happened. Demo keys likewise: one is minted for every account, so they
// outnumbered the licences anyone sold and read as live customer keys. The
// backend leaves both out of the list; a row revoked since it loaded is left
// out here.
function renderLicences() {
  const q = $("filter").value.trim().toLowerCase();
  const showRevoked = $("showRevoked").checked;
  const showDemo = $("showDemo").checked;
  const matches = (l) => !q || [l.keyPrefix, l.domainLock, l.emailLock, l.note]
    .some((v) => (v || "").toLowerCase().includes(q));
  const found = new Set((searchHits || []).map((l) => l.id));
  const pool = (searchHits || []).concat(licences.filter((l) => !found.has(l.id)));
  const rows = pool.filter((l) =>
    (showRevoked || l.status !== "revoked") &&
    (showDemo || l.mode !== "demo") &&
    (found.has(l.id) || matches(l)));
  $("licenceRows").innerHTML = rows.length
    ? rows.map(licenceRow).join("")
    : `<tr><td colspan="9" class="muted">${q && !searchHits && searchableLicenceText(q)
      ? "Searching…" : "Nothing matches."}</td></tr>`;
  const hidden = [!showRevoked ? "revoked" : "", !showDemo ? "Demo" : ""].filter(Boolean);
  const note = hidden.length ? ` (${hidden.join(" and ")} hidden)` : "";
  $("licencePaging").textContent = licencePage.hasMore
    ? `Newest ${licences.length} shown${note}; more exist.`
    : `${licences.length} licence(s)${note}.`;
  $("loadMore").hidden = !licencePage.hasMore;
}

function seatSummary(lic) {
  // An individual licence has no seat count to show; a bare "1" here read as
  // a number someone had chosen, next to a cap column that also held numbers.
  if (lic.kind !== "institution") return "—";
  const cap = lic.maxSeats == null ? "∞" : lic.maxSeats;
  // The two counts mean different things, and conflating them is the
  // easiest mistake to make when reading this table: on a floating licence
  // maxSeats caps concurrent use, not roster size.
  const intended = lic.seating === "floating"
    ? `${lic.leasesActive ?? 0}/${cap} in use · ${lic.seatsUsed ?? 0} on roster`
    : `${lic.seatsUsed ?? 0}/${cap}`;
  return intended + verifiedNote(lic.id);
}

/** The second count, once someone has asked for it. */
function verifiedNote(id) {
  const report = verified[id];
  if (!report) return "";
  const outstanding = report.counts.revokedStillRunning;
  return outstanding
    ? ` · <span class="err">${outstanding} revoke${outstanding === 1 ? "" : "s"}` +
      ` not landed</span>`
    : ` · <span class="ok">${report.entitled} verified</span>`;
}

function licenceRow(lic) {
  const label = lic.keyPrefix || lic.id.slice(0, 10);
  const term = lic.duration === "timed"
    ? `until ${esc(day(lic.expiresAt))}${lic.graceDays ? ` +${lic.graceDays}d` : ""}`
    : "perpetual";
  const revoked = lic.status === "revoked";
  // Shown because it used to be invisible after mint: a cap typed at issue
  // time reached every holder with no trace of it on this desk. A demo key
  // shows the demo allowance instead: a number stored on one never applied.
  const demo = lic.mode === "demo";
  const cap = demo
    ? `<span class="muted" title="${esc(demoCapNote())}">demo${demoAllowance == null ? "" : ` (${esc(demoAllowance)})`}</span>`
    : lic.maxAnalyses == null ? '<span class="muted">default</span>' : esc(lic.maxAnalyses);
  // An individual licensed key can become an institution one; a Demo key is
  // not a licence anyone bought, so there is nothing to carry over.
  const convertButton = lic.kind !== "institution" && !demo
    ? `<button class="secondary" data-convert="${esc(lic.id)}">To institution</button>`
    : "";
  // Revoked licences are what most deletes are for: the record of one is
  // the audit trail until nobody needs it. A system Demo key is not deleted —
  // the account would only be issued another.
  const deleteButton = demo && lic.createdByUid === "system"
    ? ""
    : `<button class="danger" data-delete="${esc(lic.id)}">Delete</button>`;
  const actions = revoked ? deleteButton : `
    <button class="secondary" data-edit="${esc(lic.id)}">Edit</button>
    ${lic.kind === "institution"
      ? `<button class="secondary" data-roster="${esc(lic.id)}">Roster</button>
         <button class="secondary" data-verify="${esc(lic.id)}">Verify</button>`
      : `<button class="secondary" data-device="${esc(lic.id)}">New device</button>`}
    ${convertButton}
    <button class="secondary" data-history="${esc(lic.id)}">Devices</button>
    <button class="danger" data-revoke="${esc(lic.id)}">Revoke</button>
    ${deleteButton}`;
  return `
    <tr>
      <td class="mono">${esc(label)}</td>
      <td>${esc(lic.kind)}${lic.seating === "floating" ? " · shared" : ""}${lic.supersededBy
        ? '<br><span class="muted">replaced by an institution licence</span>' : ""}</td>
      <td>${esc(lic.mode)}</td>
      <td>${seatSummary(lic)}</td>
      <td>${term}</td>
      <td>${cap}</td>
      <td>${licenceStatePill(lic)}</td>
      <td class="muted">${esc(lic.domainLock || lic.emailLock || "—")}</td>
      <td class="actions">${actions}</td>
    </tr>`;
}

$("licenceRows").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  if (btn.dataset.edit) openEdit(btn.dataset.edit);
  if (btn.dataset.delete) deleteLicence(btn.dataset.delete);
  if (btn.dataset.convert) openConvert(btn.dataset.convert);
  if (btn.dataset.revoke) revokeLicence(btn.dataset.revoke);
  if (btn.dataset.roster) openRoster(btn.dataset.roster);
  if (btn.dataset.device) clearLicenceDevice(btn.dataset.device);
  if (btn.dataset.history) showDeviceHistory(btn.dataset.history);
  if (btn.dataset.verify) openVerified(btn.dataset.verify);
});

const findLicence = (id) =>
  licences.find((l) => l.id === id) || (searchHits || []).find((l) => l.id === id);

const labelOf = (id) => {
  const lic = findLicence(id);
  return lic ? (lic.keyPrefix || lic.id.slice(0, 10)) : id.slice(0, 10);
};

// ------------------------------------------------------------------ edit

let editing = null;

function openEdit(id) {
  const lic = findLicence(id);
  if (!lic) return;
  editing = lic;
  const institution = lic.kind === "institution";
  const demo = lic.mode === "demo";
  $("editName").textContent = labelOf(id);
  $("editExpiry").value = isoDay(lic.expiresAt);
  $("editPerpetual").checked = lic.duration !== "timed";
  $("editGrace").value = lic.graceDays ?? "";
  $("editSupport").value = isoDay(lic.supportUntil);
  $("editCap").value = lic.maxAnalyses ?? "";
  // A demo holder gets the demo allowance whatever the key says, so a cap on
  // one is refused; say why instead of offering it.
  $("editCap").disabled = demo;
  $("editCap").title = demo ? demoCapNote() : "";
  // Nothing in this dialog licenses a Demo account; say what does.
  $("editDemo").hidden = !demo;
  $("editDemoEmail").textContent = lic.emailLock || "the account's address";
  $("editDemoIssue").hidden = !lic.emailLock;
  $("editInstitution").hidden = !institution;
  $("editSeats").value = lic.maxSeats ?? "";
  $("editSeating").value = lic.seating || "assigned";
  $("editAdmins").value = (lic.adminEmails || []).join(", ");
  $("editNote").value = lic.note || "";
  syncEditTerm();
  $("editHint").textContent = "";
  $("editHint").className = "muted";
  // The dialog reports its own outcome; a line left from the last action
  // ("… saved") read as this edit's result beside the dialog's error.
  setStatus("");
  $("editDialog").showModal();
}

/** Never expires and a date are one choice, not two. */
function syncEditTerm() {
  const perpetual = $("editPerpetual").checked;
  $("editExpiry").disabled = perpetual;
  $("editGrace").disabled = perpetual;
}

function editHint(message, isError = true) {
  $("editHint").textContent = message;
  $("editHint").className = isError ? "err" : "muted";
}

$("editPerpetual").addEventListener("change", syncEditTerm);
// Licensing a Demo account is an issue, not an edit: hand its address to the
// Issue form rather than have the operator retype it. The term is still
// theirs to choose, so nothing is sent from here.
$("editDemoIssue").addEventListener("click", () => {
  const email = editing?.emailLock;
  if (!email) return;
  $("editDialog").close();
  for (const radio of kindRadios()) radio.checked = radio.value === "individual";
  syncKind();
  $("emailLock").value = email;
  $("emailLock").focus();
  setStatus(`Choose the term, then Issue licence: it attaches to ${email} and replaces the Demo key.`);
});
$("editCancel").addEventListener("click", () => $("editDialog").close());
$("editForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const lic = editing;
  if (!lic) return;
  const { patch, shortens, error } = licenceEditPatch(lic, {
    expiry: $("editExpiry").value,
    perpetual: $("editPerpetual").checked,
    graceDays: $("editGrace").value,
    supportUntil: $("editSupport").value,
    maxAnalyses: $("editCap").value,
    capLocked: $("editCap").disabled,
    maxSeats: $("editSeats").value,
    seating: $("editSeating").value,
    adminEmails: $("editAdmins").value,
    note: $("editNote").value,
  });
  if (error) {
    editHint(error);
    return;
  }
  const label = labelOf(lic.id);
  // A downgrade is agreed with the customer, not clicked through: the key
  // has to be typed, as for a revoke.
  if (shortens) {
    const typed = window.prompt(
      `This shortens ${label}: its term ends ${patch.expiresAt.slice(0, 10)}` +
      `${lic.duration === "timed" ? ` instead of ${isoDay(lic.expiresAt)}` : " (it was perpetual)"}` +
      ", for everyone on it." +
      `\n\nType ${label} to confirm:`,
    );
    if (typed == null || typed.trim() !== label) {
      editHint("Not saved: the key was not typed.");
      return;
    }
  }
  $("editSave").disabled = true;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(lic.id)}`, {
      method: "PATCH", body: JSON.stringify(patch),
    });
    $("editDialog").close();
    showLicence(out);
    // Say what the server stored, not what was typed.
    setStatus(`${label} saved — ${out.duration === "timed"
      ? `ends ${day(out.expiresAt)}` : "perpetual"}. Everyone on it has the change.`);
  } catch (e) {
    editHint(editError(e.message));
  } finally {
    $("editSave").disabled = false;
  }
});

function editError(code) {
  return {
    expiry_in_past: "That date has already passed. Ending a licence now is Revoke.",
    expiry_before_current: "That is earlier than the current expiry.",
    license_perpetual: "This licence is perpetual.",
    cap_on_demo_key: demoCapNote(),
    max_seats_below_used:
      "More people are on the roster than that many seats. Remove members first, " +
      "or raise Seats.",
    floating_needs_max_seats: "A floating licence needs a number of seats.",
    institution_only: "Seats, seating and IT contacts are for institution licences.",
  }[code] || `Not saved: ${code}`;
}

// --------------------------------------------------------------- convert

let converting = null;

function openConvert(id) {
  const lic = findLicence(id);
  if (!lic) return;
  converting = lic;
  $("convertName").textContent = labelOf(id);
  const email = lic.emailLock || "";
  $("convertDomain").value = email.includes("@") ? email.split("@")[1] : "";
  $("convertAdmins").value = "";
  $("convertSeats").value = "";
  $("convertSeating").value = "assigned";
  $("convertFields").hidden = false;
  $("convertedBox").hidden = true;
  $("convertSave").hidden = false;
  $("convertCancel").textContent = "Cancel";
  $("convertHint").textContent = "";
  $("convertHint").className = "muted";
  $("convertDialog").showModal();
}

$("convertCancel").addEventListener("click", () => $("convertDialog").close());
$("convertForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const lic = converting;
  if (!lic) return;
  const hint = (message) => {
    $("convertHint").textContent = message;
    $("convertHint").className = "err";
  };
  const seats = $("convertSeats").value.trim();
  const body = {
    domainLock: $("convertDomain").value.trim().toLowerCase(),
    adminEmails: emailList($("convertAdmins").value),
    seating: $("convertSeating").value,
    ...(seats ? { maxSeats: Number(seats) } : {}),
  };
  if (!body.domainLock || !body.adminEmails.length) {
    hint("A domain and at least one IT contact are needed.");
    return;
  }
  if (body.seating === "floating" && !seats) {
    hint("A floating licence needs a number of seats.");
    return;
  }
  $("convertSave").disabled = true;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(lic.id)}/convert`, {
      method: "POST", body: JSON.stringify(body),
    });
    $("convertedKey").textContent = out.key;
    $("convertFields").hidden = true;
    $("convertedBox").hidden = false;
    $("convertSave").hidden = true;
    $("convertCancel").textContent = "Done";
    $("convertHint").className = "muted";
    $("convertHint").textContent = out.claimedByUid
      ? "The holder is on the new roster, on the same device."
      : "Nobody had signed in yet: the invitation moved to the new licence.";
    showLicence(out.license);
    refreshLicence(lic.id);
    setStatus(`${labelOf(lic.id)} is now institution licence ${out.license.keyPrefix}.`);
  } catch (e) {
    hint({
      convert_domain_mismatch: "The holder's address is not on that domain.",
      license_not_convertible: "Only an individual licensed key converts.",
      license_revoked: "This licence is revoked.",
      claim_contended: "Busy just now — try again.",
    }[e.message] || `Not converted: ${e.message}`);
  } finally {
    $("convertSave").disabled = false;
  }
});

async function clearLicenceDevice(id) {
  // The support answer to "my phone died". Emptying the lock is the whole
  // change: the licence binds to whichever device signs in next, so
  // nothing is re-issued and nothing is typed at the customer's end.
  if (!window.confirm(
    `Unbind ${labelOf(id)} from the device it is on?\n\n` +
    "The next device they sign in on takes it. Their entitlement and " +
    "their analyses are untouched — this is not a revoke.",
  )) return;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ clearDeviceLock: true }),
    });
    setStatus(`${labelOf(id)} unbound — the next device to sign in takes it.`);
    showLicence(out);
  } catch (e) {
    setStatus(`Could not unbind: ${e.message}`, true);
  }
}

async function showDeviceHistory(id) {
  try {
    const data = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/device-history?limit=30`,
    );
    const lines = (data.events || []).map((e) => {
      const prev = (e.detail && e.detail.previousDeviceId) || "";
      // The registered phone the clear signed out, when it is not the lock's.
      const released = (e.detail && e.detail.releasedDeviceId) || "";
      const next = (e.detail && e.detail.deviceId) || "";
      const who = e.uid || "—";
      return `${e.ts || "?"}  ${e.action}  by ${who}` +
        (prev ? `  left ${prev}` : "") +
        (released && released !== prev ? `  signed out ${released}` : "") +
        (next ? `  → ${next}` : "");
    });
    window.alert(
      lines.length
        ? `Device history for ${labelOf(id)}\n\n${lines.join("\n")}`
        : `No device moves recorded for ${labelOf(id)} yet.`,
    );
  } catch (e) {
    setStatus(`Could not load device history: ${e.message}`, true);
  }
}

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

async function openVerified(id) {
  $("verifyName").textContent = labelOf(id);
  $("verifyCard").hidden = false;
  $("verifyCard").dataset.licence = id;
  await loadVerified(id);
}

async function loadVerified(id) {
  $("verifySummary").textContent = "Checking every seat against its holder…";
  $("verifyRows").innerHTML = "";
  try {
    const report = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/reconcile`,
    );
    verified[id] = report;
    $("verifySummary").innerHTML = verifiedSummary(report);
    $("verifyRows").innerHTML = (report.seats || []).map(verifiedRow).join("") ||
      '<tr><td colspan="4" class="muted">No seats on this licence yet.</td></tr>';
    // The Seats column now has a second number to show for this licence.
    renderLicences();
  } catch (e) {
    const msg = e.message === "kind_not_institution"
      ? "This is an individual licence: one holder, no roster, so there " +
        "are no two counts to compare."
      : `Could not check the seats: ${e.message}`;
    $("verifySummary").innerHTML = `<span class="err">${esc(msg)}</span>`;
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

$("verifyReload").addEventListener("click", () => {
  const id = $("verifyCard").dataset.licence;
  if (id) loadVerified(id);
});

async function revokeLicence(id) {
  const label = labelOf(id);
  const lic = findLicence(id) || {};
  const who = lic.kind === "institution"
    ? `every one of the ${lic.seatsUsed ?? 0} people on its roster`
    : "the person holding it";
  if (!window.confirm(
    `Revoking ${label} drops ${who} to demo immediately.\n\n` +
    "Their saved analyses are untouched — this withdraws entitlement, " +
    "it does not delete anything.\n\nContinue?",
  )) return;
  if (!confirmByTyping(label, "revoke this licence")) {
    setStatus("Revoke cancelled — the key did not match.");
    return;
  }
  await sendRevoke(id, label);
}

/**
 * The return leg of a revoke that went to Google for a fresh sign-in. The
 * operator already named who is affected and typed the key on the way out;
 * one plain confirmation here says which licence this page is about to
 * revoke, because a page acting on load without any gesture is a page that
 * revokes on a stale tab restored by the browser.
 */
async function resumeRevoke(id) {
  if (resumed) return;
  resumed = true;
  const label = labelOf(id);
  // The first page may not hold it; ask for the licence itself.
  let lic = findLicence(id);
  if (!lic) {
    try {
      lic = await api(`/v1/admin/licenses/${encodeURIComponent(id)}`);
    } catch {
      lic = null;
    }
  }
  if (!lic) {
    setStatus(`Re-authenticated, but ${label} no longer exists — nothing revoked.`, true);
    return;
  }
  if (lic.status === "revoked") {
    setStatus(`${label} is already revoked.`);
    return;
  }
  if (!window.confirm(`Re-authenticated. Revoke ${label} now?`)) {
    setStatus("Revoke cancelled.");
    return;
  }
  await sendRevoke(id, label);
}

async function sendRevoke(id, label) {
  try {
    await stepUpForRevoke({ action: "revoke", id });
    const out = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/revoke`, { method: "POST" },
    );
    // The answer is the revoked licence; there is nothing to reload.
    showLicence({ ...out, status: "revoked" });
    setStatus(
      `${label} revoked — its holder is on demo from their next request.` +
      ($("showRevoked").checked ? "" : " Tick “Show revoked” to see it."),
    );
    if (roster && roster.id === id) closeRoster();
  } catch (e) {
    if (e.message === ERR_CANCELLED) {
      setStatus("Revoke cancelled.");
      return;
    }
    setStatus(`Could not revoke: ${e.message}`, true);
  }
}

/* ------------------------------------------------------------- delete */

async function deleteLicence(id) {
  const label = labelOf(id);
  const lic = findLicence(id) || {};
  const live = lic.status !== "revoked";
  const who = lic.kind === "institution"
    ? `every one of the ${lic.seatsUsed ?? 0} people on its roster`
    : "the person holding it";
  if (!window.confirm(
    `Delete ${label}?\n\n` +
    (live ? `It is revoked first: ${who} drops to demo immediately. ` : "") +
    "It leaves this list and is held under Recently deleted for 30 days, " +
    "then purged. Nobody's saved analyses are touched.",
  )) return;
  const typed = window.prompt(`Type ${label} to delete this licence:`);
  if (typed == null || typed.trim() !== label) {
    setStatus("Delete cancelled — the key did not match.");
    return;
  }
  await sendDelete(id, label);
}

/** The return leg of a delete that went to Google for a fresh sign-in. */
async function resumeDelete(id) {
  if (resumed) return;
  resumed = true;
  const label = labelOf(id);
  if (!window.confirm(`Re-authenticated. Delete ${label} now?`)) {
    setStatus("Delete cancelled.");
    return;
  }
  await sendDelete(id, label);
}

async function sendDelete(id, label) {
  try {
    await stepUpForRevoke({ action: "delete", id });
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, { method: "DELETE" });
    licences = licences.filter((l) => l.id !== id);
    if (searchHits) searchHits = searchHits.filter((l) => l.id !== id);
    renderLicences();
    if (roster && roster.id === id) closeRoster();
    setStatus(`${label} deleted — restorable under Recently deleted until ${day(out.purgeAt)}.`);
    if (!$("deletedWrap").hidden) loadDeleted();
  } catch (e) {
    if (e.message === ERR_CANCELLED) {
      setStatus("Delete cancelled.");
      return;
    }
    setStatus({
      license_not_found: `${label} no longer exists.`,
      demo_key_not_deletable: "A system Demo key is not deleted; the account would only get another.",
    }[e.message] || `Could not delete: ${e.message}`, true);
  }
}

$("loadDeleted").addEventListener("click", loadDeleted);

async function loadDeleted() {
  $("loadDeleted").textContent = "Refresh";
  $("deletedWrap").hidden = false;
  try {
    const data = await api("/v1/admin/deleted-licenses?limit=50");
    const rows = data.licenses || [];
    $("deletedRows").innerHTML = rows.length
      ? rows.map(deletedRow).join("")
      : '<tr><td colspan="7" class="muted">Nothing deleted in the last 30 days.</td></tr>';
  } catch (e) {
    $("deletedRows").innerHTML =
      `<tr><td colspan="7" class="err">Could not load: ${esc(e.message)}</td></tr>`;
  }
}

function deletedRow(lic) {
  const left = daysLeft(lic.purgeAt);
  return `
    <tr>
      <td class="mono">${esc(lic.keyPrefix || lic.id.slice(0, 10))}</td>
      <td>${esc(lic.kind)}</td>
      <td class="muted">${esc(lic.domainLock || lic.emailLock || "—")}</td>
      <td>${esc(lic.priorStatus || "—")}</td>
      <td>${esc(day(lic.deletedAt))}</td>
      <td>${left ? `${left} day${left === 1 ? "" : "s"}` : "due"}</td>
      <td class="actions">${left
        ? `<button class="secondary" data-restore="${esc(lic.id)}">Restore</button>` : ""}</td>
    </tr>`;
}

$("deletedRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-restore]");
  if (!btn) return;
  const id = btn.dataset.restore;
  btn.disabled = true;
  try {
    const lic = await api(
      `/v1/admin/deleted-licenses/${encodeURIComponent(id)}/restore`, { method: "POST" },
    );
    showLicence(lic);
    loadDeleted();
    const label = lic.keyPrefix || id.slice(0, 10);
    setStatus(lic.status === "revoked"
      ? `${label} restored, revoked as it was.`
      : `${label} restored — its holders are back on it, except anyone who took ` +
        "another licence meanwhile.");
  } catch (e) {
    btn.disabled = false;
    setStatus({
      deleted_license_purged: "Too late: the 30 days have passed.",
      deleted_license_not_found: "Already restored or purged.",
      license_exists: "A licence with that key exists again.",
    }[e.message] || `Could not restore: ${e.message}`, true);
  }
});

/* ------------------------------------------------------------- roster */

$("rosterClose").addEventListener("click", closeRoster);
function closeRoster() {
  roster = null;
  $("rosterCard").hidden = true;
}

async function openRoster(id) {
  roster = { id, label: labelOf(id) };
  $("rosterName").textContent = roster.label;
  $("rosterCard").hidden = false;
  await loadRoster();
}

async function loadRoster() {
  if (!roster) return;
  try {
    const data = await api(
      `/v1/institutions/licenses/${encodeURIComponent(roster.id)}/seats`,
    );
    const seats = (data.seats || []).map(seatRow);
    const invites = (data.invites || []).map(inviteRow);
    $("rosterRows").innerHTML = seats.concat(invites).join("") ||
      '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  } catch (e) {
    // Semper staff are not automatically institution admins: the seat
    // routes check adminEmails, not role=admin. Say so plainly rather
    // than showing a bare 404.
    const msg = e.message === "license_not_found"
      ? "Your account is not listed as an IT contact on this licence, " +
        "so its roster is not visible here."
      : `Could not load the roster: ${e.message}`;
    $("rosterRows").innerHTML =
      `<tr><td colspan="5" class="err">${esc(msg)}</td></tr>`;
  }
}

function seatRow(s) {
  return `
    <tr>${seatCells(s)}
      <td class="actions">
        <button class="secondary" data-device-seat="${esc(s.uid)}">New device</button>
        <button class="danger" data-seat="${esc(s.uid)}">Remove</button>
      </td>
    </tr>`;
}

function inviteRow(i) {
  return `
    <tr>${inviteCells(i)}
      <td class="actions">
        <button class="danger" data-invite="${esc(i.id)}">Withdraw</button>
      </td>
    </tr>`;
}

$("addMember").addEventListener("click", async () => {
  const email = $("memberEmail").value.trim();
  if (!roster || !email) return;
  $("rosterHint").textContent = "";
  try {
    const out = await api(
      `/v1/institutions/licenses/${encodeURIComponent(roster.id)}/seats`,
      { method: "POST", body: JSON.stringify({ email }) },
    );
    $("memberEmail").value = "";
    $("rosterHint").textContent = out.seat
      ? "Added — they are entitled now."
      : "Invited — they join the moment they first sign in.";
    loadRoster();
    refreshLicence(roster.id);
  } catch (e) {
    $("rosterHint").textContent = {
      invite_exists: "That address is already promised to a different licence.",
      member_already_licensed: "That person already has a live licence. " +
        "One licence per person: revoke the other one first.",
      license_seats_exhausted: "This licence has no seats left.",
      license_seat_disabled: "That seat is on hold — re-enable it instead.",
      claim_contended: "Busy just now — try again.",
    }[e.message] || `Could not add: ${e.message}`;
  }
});

$("rosterRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button");
  if (!btn || !roster) return;
  const base = `/v1/institutions/licenses/${encodeURIComponent(roster.id)}`;
  try {
    if (btn.dataset.seat) {
      const row = btn.closest("tr");
      const label = row.cells[0].textContent;
      if (!window.confirm(
        `Remove ${label} from ${roster.label}?\n\n` +
        "They drop to demo and their seat is freed. Their analyses stay.",
      )) return;
      await api(`${base}/seats/${encodeURIComponent(btn.dataset.seat)}`, { method: "DELETE" });
    } else if (btn.dataset.deviceSeat) {
      // The staff-tier seat unbind, not the institution one this card
      // reads from: Semper is not in every licence's adminEmails, and a
      // support request must not depend on that.
      await api(
        `/v1/admin/licenses/${encodeURIComponent(roster.id)}` +
        `/seats/${encodeURIComponent(btn.dataset.deviceSeat)}/device`,
        { method: "PATCH" },
      );
    } else if (btn.dataset.invite) {
      await api(`${base}/invites/${encodeURIComponent(btn.dataset.invite)}`, { method: "DELETE" });
    } else return;
    loadRoster();
    refreshLicence(roster.id);
  } catch (e) {
    $("rosterHint").textContent = `Could not update: ${e.message}`;
  }
});

/* ------------------------------------------------------------- people */

$("reloadUsers").addEventListener("click", loadUsers);

async function loadUsers() {
  try {
    const data = await api("/v1/admin/users?status=PENDING&limit=50");
    const rows = data.users || [];
    $("userRows").innerHTML = rows.length
      ? rows.map((u) => `
          <tr>
            <td>${esc(u.email || u.uid)}</td>
            <td>${esc(u.displayName || "—")}</td>
            <td class="muted">${u.activeDeviceId ? esc(u.activeDeviceId.slice(0, 10)) + "…" : "—"}</td>
            <td class="actions">
              <button data-approve="${esc(u.uid)}">Approve</button>
            </td>
          </tr>`).join("")
      : '<tr><td colspan="4" class="muted">Nobody waiting.</td></tr>';
  } catch (e) {
    $("userRows").innerHTML =
      `<tr><td colspan="4" class="err">Could not load: ${esc(e.message)}</td></tr>`;
  }
}

$("releasePhone").addEventListener("click", async () => {
  const email = $("releaseEmail").value.trim();
  if (!email) return setStatus("Enter the account's email.", true);
  const btn = $("releasePhone");
  btn.disabled = true;
  try {
    const out = await api("/v1/admin/device-releases", {
      method: "POST",
      body: JSON.stringify({ email }),
    });
    setStatus(out.releasedDeviceId
      ? `Released ${out.releasedDeviceId} for ${out.email || email}; the new phone can sign in.`
      : `${out.email || email} had no phone registered; any phone can sign in.`);
    $("releaseEmail").value = "";
  } catch (e) {
    const msg = /license_device_clear_required/.test(e.message)
      ? "That account is licensed: use New device on its licence."
      : e.message;
    setStatus(`Could not release: ${msg}`, true);
  } finally {
    btn.disabled = false;
  }
});

$("userRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-approve]");
  if (!btn) return;
  btn.disabled = true;
  try {
    await api(`/v1/admin/users/${encodeURIComponent(btn.dataset.approve)}/approve`,
              { method: "POST" });
    setStatus("Approved.");
    loadUsers();
  } catch (e) {
    setStatus(`Could not approve: ${e.message}`, true);
    btn.disabled = false;
  }
});
