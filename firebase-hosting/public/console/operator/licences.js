/* The licence table: its pages, the filter and search, and one row redrawn
 * when one licence changes. What each row's buttons do lives with the card
 * or dialog the button opens; the entry module routes the clicks.
 */
import { api, setStatus, esc, markFirstData } from "../auth.js";
import {
  day, licenceStatePill, licenceListPath, searchableLicenceText, upsertLicence,
} from "../util.js";
import { $, desk, demoCapNote } from "./state.js";

// Whoever shows something derived from one licence, told when it is re-read.
const readers = [];

/**
 * Call `fn(id)` after licence `id` is re-read and redrawn, and `fn(null)`
 * after the whole table is. The seat check uses it to ask again: a change is
 * exactly when to. A callback rather than an import keeps this module below
 * the cards that read the table.
 */
export function onLicenceRead(fn) {
  readers.push(fn);
}

// Newest first, and without Demo keys, so the first page is the one wanted.
const LICENCE_PAGE = 50;
let searchTimer = 0;

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
 * Start reading the first page now, for `loadLicences` to show later. The
 * desk starts it alongside its role check rather than after it; for anyone
 * but staff the backend refuses it and the refusal is never shown.
 */
export function startLicenceLoad() {
  const path = listPath();
  const data = api(path);
  data.catch(() => {}); // read, or discarded, by loadLicences
  return { path, data };
}

/**
 * Fetch and redraw the licence table, from its first page — or show the read
 * `startLicenceLoad` began, when the toggles still ask for that page.
 *
 * A change to one licence does not come here: it refreshes that row
 * (`refreshLicence`). Reloading the first page after every change was the
 * desk's slowness, and it dropped every page loaded with "Load more".
 */
export async function loadLicences(started) {
  setStatus("Loading…");
  desk.verified = {};
  desk.searchHits = null;
  try {
    const path = listPath();
    const data = await (started && started.path === path ? started.data : api(path));
    desk.licences = data.licenses || [];
    desk.licencePage = data.page || {};
    desk.demoAllowance = Number.isInteger(data.demoMaxAnalyses) ? data.demoMaxAnalyses : null;
    renderLicences();
    markFirstData();
    setStatus("");
    for (const fn of readers) fn(null);
    if (searchableLicenceText($("filter").value)) searchLicences();
  } catch (e) {
    setStatus(
      e.code === "not_admin"
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
export async function refreshLicence(id) {
  try {
    showLicence(await api(`/v1/admin/licenses/${encodeURIComponent(id)}`));
  } catch (e) {
    setStatus(`Changed, but the row could not be re-read: ${e.message}. Refresh to see it.`, true);
  }
}

/** Put a licence the backend just returned into the table. */
export function showLicence(lic) {
  if (!lic || !lic.id) return;
  desk.licences = upsertLicence(desk.licences, lic);
  if (desk.searchHits) desk.searchHits = upsertLicence(desk.searchHits, lic);
  delete desk.verified[lic.id];
  renderLicences();
  for (const fn of readers) fn(lic.id);
}



/**
 * The filter narrows the loaded rows at once. An address, a domain or a key
 * prefix is also looked up on the backend, after a pause in typing, so a
 * licence past the loaded pages is found too.
 */
function onFilterInput() {
  clearTimeout(searchTimer);
  desk.searchHits = null;
  renderLicences();
  if (searchableLicenceText($("filter").value)) searchTimer = setTimeout(searchLicences, 300);
}

export async function searchLicences() {
  const q = $("filter").value.trim();
  try {
    const data = await api(listPath({ q }));
    // Typing moved on while this was in flight: its answer is for old text.
    if ($("filter").value.trim() !== q) return;
    desk.searchHits = data.licenses || [];
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
  const token = desk.licencePage.nextPageToken;
  if (!token) return;
  $("loadMore").disabled = true;
  try {
    const data = await api(listPath({ pageToken: token }));
    const loaded = new Set(desk.licences.map((l) => l.id));
    desk.licences = desk.licences.concat((data.licenses || []).filter((l) => !loaded.has(l.id)));
    desk.licencePage = data.page || {};
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
export function renderLicences() {
  const q = $("filter").value.trim().toLowerCase();
  const showRevoked = $("showRevoked").checked;
  const showDemo = $("showDemo").checked;
  const matches = (l) => !q || [l.keyPrefix, l.domainLock, l.emailLock, l.note]
    .some((v) => (v || "").toLowerCase().includes(q));
  const found = new Set((desk.searchHits || []).map((l) => l.id));
  const pool = (desk.searchHits || []).concat(desk.licences.filter((l) => !found.has(l.id)));
  const rows = pool.filter((l) =>
    (showRevoked || l.status !== "revoked") &&
    (showDemo || l.mode !== "demo") &&
    (found.has(l.id) || matches(l)));
  $("licenceRows").innerHTML = rows.length
    ? rows.map(licenceRow).join("")
    : `<tr><td colspan="9" class="muted">${q && !desk.searchHits && searchableLicenceText(q)
      ? "Searching…" : "Nothing matches."}</td></tr>`;
  const hidden = [!showRevoked ? "revoked" : "", !showDemo ? "Demo" : ""].filter(Boolean);
  const note = hidden.length ? ` (${hidden.join(" and ")} hidden)` : "";
  $("licencePaging").textContent = desk.licencePage.hasMore
    ? `Newest ${desk.licences.length} shown${note}; more exist.`
    : `${desk.licences.length} licence(s)${note}.`;
  $("loadMore").hidden = !desk.licencePage.hasMore;
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
  const report = desk.verified[id];
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
    ? `<span class="muted" title="${esc(demoCapNote())}">demo${desk.demoAllowance == null ? "" : ` (${esc(desk.demoAllowance)})`}</span>`
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
