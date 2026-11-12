/* The licence table: its pages, the filters, the sort and the search, the
 * details under a row, and one row redrawn when one licence changes. What
 * each row's buttons do lives with the card or dialog the button opens; the
 * entry module routes the clicks.
 */
import { api } from "../api.js";
import { setStatus, markFirstData, whileWaiting } from "../status.js";
import {
  esc, day, when, licenceState, licenceStatePill, licenceListPath, licenceSearchBody, searchableLicenceText,
  upsertLicence, placeholderRows, retryRow,
} from "../util.js";
import { ACCOUNT, explain } from "../messages.js";
import { wireSort, sortList, byText, byNumber, matchesText } from "../list-tools.js";
import { $, desk, demoCapNote, labelOf, runsActivations, appName, devicesCap, capText } from "./state.js";

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
// Load all reads the biggest pages the backend serves, up to this many of
// them: 10,000 licences, far past the desk's today, and a stop if a token
// ever repeats.
const ALL_PAGE = 200;
const ALL_PAGES = 50;
// The most pages one search reads. A search is an exact match on an address,
// a domain or a key prefix, so the backend answers it in one page today.
const SEARCH_PAGES = 10;
const COLUMNS = 5;
const DAY = 86400e3;
// "Ends within 30 days": the renewals to chase this month.
const ENDING_DAYS = 30;
let searchTimer = 0;
// Which table load is the latest: a toggle flipped twice sends two, and the
// first to answer must not be drawn over the second.
let licenceLoad = 0;
// The licences whose details are open under their row, by id. A redraw (a
// change to one licence, a filter) draws them open again.
const expanded = new Set();

/* --------------------------------------------------------- the view */

// What the operator chose to see, kept for this tab (sessionStorage) so a
// reload, or the way back from a Google re-authentication, keeps the view.
// The filter text is not kept: it is often somebody's address.
const VIEW_KEY = "semper.desk.licenceView";
const SELECTS = ["kindFilter", "appFilter", "stateFilter"];
const TOGGLES = ["showRevoked", "showDemo", "showTest"];

function readView() {
  try {
    return JSON.parse(window.sessionStorage.getItem(VIEW_KEY) || "{}") || {};
  } catch {
    return {};
  }
}

function saveView() {
  const view = { sort: sort.order() };
  for (const id of SELECTS) view[id] = $(id).value;
  for (const id of TOGGLES) view[id] = $(id).checked;
  try {
    window.sessionStorage.setItem(VIEW_KEY, JSON.stringify(view));
  } catch {
    // Storage refused (a private window): the view lasts this page only.
  }
}

const saved = readView();
for (const id of SELECTS) {
  const want = saved[id];
  if (typeof want === "string" && Array.from($(id).querySelectorAll("option")).some((o) => o.getAttribute("value") === want)) {
    $(id).value = want;
  }
}
for (const id of TOGGLES) if (typeof saved[id] === "boolean") $(id).checked = saved[id];

const SORTS = {
  key: (a, b) => byText(labelText(a), labelText(b)),
  kind: (a, b) => byText(a.kind, b.kind) || byText(appName(a.app), appName(b.app)),
  // An institution by the seats it uses now; an individual licence has none, and goes last.
  seats: (a, b) => byNumber(seatsInUse(a), seatsInUse(b)),
  state: (a, b) => byText(licenceState(a), licenceState(b)),
};
const savedSort = saved.sort && SORTS[saved.sort.key] ? saved.sort : undefined;
const sort = wireSort($("licenceHead"), () => {
  saveView();
  renderLicences();
}, savedSort && { key: savedSort.key, dir: savedSort.dir < 0 ? -1 : 1 });

$("reload").addEventListener("click", () => loadLicences());
$("filter").addEventListener("input", onFilterInput);
for (const id of SELECTS) {
  $(id).addEventListener("change", () => {
    saveView();
    renderLicences();
  });
}
// The backend leaves Demo, revoked and test-account licences out, so showing
// them is a fetch, not a redraw.
for (const id of TOGGLES) {
  $(id).addEventListener("change", () => {
    saveView();
    loadLicences();
  });
}
$("clearFilters").addEventListener("click", clearFilters);
$("loadMore").addEventListener("click", () => loadMoreLicences());
$("loadAll").addEventListener("click", () => loadAllLicences());
$("licenceRows").addEventListener("click", (ev) => {
  const toggle = ev.target.closest("button[data-details]");
  if (toggle) toggleDetails(toggle.dataset.details);
});
// The roster and seat-check cards belong to one licence: its row stays
// marked while either is open (markCurrent), and loses the mark on Close.
for (const id of ["rosterClose", "verifyClose"]) {
  $(id).addEventListener("click", () => markCurrent(null));
}

// "/" anywhere on the desk jumps to the filter, as in most tools that list
// things; not while typing in a box or working in a dialog.
document.addEventListener("keydown", (ev) => {
  if (ev.key !== "/" || ev.ctrlKey || ev.metaKey || ev.altKey) return;
  const at = ev.target;
  const typing = at && ["input", "textarea", "select"].includes(at.localName);
  if (typing || (at && at.isContentEditable) || (at && at.closest && at.closest("dialog"))) return;
  if ($("app").hidden) return;
  ev.preventDefault();
  $("filter").focus();
});

const listPath = (extra = {}) => licenceListPath({
  limit: LICENCE_PAGE,
  showDemo: $("showDemo").checked,
  showRevoked: $("showRevoked").checked,
  showTest: $("showTest").checked,
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
  const ticket = ++licenceLoad;
  desk.verified = {};
  desk.searchHits = null;
  $("licenceRows").innerHTML = placeholderRows(COLUMNS, 5);
  $("licencePaging").textContent = "";
  $("loadMore").hidden = true;
  $("loadAll").hidden = true;
  $("reload").disabled = true;
  try {
    const path = listPath();
    const data = await whileWaiting(
      started && started.path === path ? started.data : api(path),
      "Loading licences…",
    );
    if (ticket !== licenceLoad) return;
    desk.licences = data.licenses || [];
    desk.licencePage = data.page || {};
    desk.demoAllowance = Number.isInteger(data.demoMaxAnalyses) ? data.demoMaxAnalyses : null;
    renderLicences();
    markFirstData();
    setStatus("");
    for (const fn of readers) fn(null);
    if (searchableLicenceText($("filter").value)) searchLicences();
  } catch (e) {
    if (ticket !== licenceLoad) return;
    const message = e.code === "not_admin"
      ? "That account is not a semper operator."
      : `Could not load licences: ${explain(e)}`;
    setStatus(message, true);
    $("licenceRows").innerHTML = retryRow(COLUMNS, message);
  } finally {
    if (ticket === licenceLoad) $("reload").disabled = false;
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
    const why = explain(e);
    setStatus(`Changed, but the row could not be re-read: ${why}${/[.!?]$/.test(why) ? "" : "."} ` +
      "Refresh to see it.", true);
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
 * Mark licence `id`'s row as the one a card below is open for (the roster,
 * the seat check), or no row for null: with the card far down the page, the
 * row it came from was easy to lose.
 */
export function markCurrent(id) {
  desk.current = id || null;
  renderLicences();
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

/** Empty the text box and the three filters; the toggles, which are fetches, stay. */
function clearFilters() {
  clearTimeout(searchTimer);
  desk.searchHits = null;
  $("filter").value = "";
  for (const id of SELECTS) $(id).value = "";
  saveView();
  renderLicences();
  $("filter").focus();
}

/**
 * Look the filter text up on the backend. A POST with the text in the body
 * (TD-244): sent as a `q` query parameter, the address searched for was
 * written into every request log between here and Cloud Run. A search
 * answers in the list's shape; a further page is asked for with its token
 * in the body too, up to `SEARCH_PAGES` pages. The Demo, revoked and test
 * boxes go in the body as the list's query sends them, so a ticked box finds
 * those licences too; the table still drops any it is not showing.
 */
export async function searchLicences() {
  const q = $("filter").value.trim();
  const showDemo = $("showDemo").checked;
  const showRevoked = $("showRevoked").checked;
  const showTest = $("showTest").checked;
  try {
    let hits = [];
    let pageToken = "";
    for (let pages = 0; pages < SEARCH_PAGES; pages += 1) {
      const data = await api("/v1/admin/licenses/search", {
        method: "POST",
        body: licenceSearchBody({ q, limit: LICENCE_PAGE, pageToken, showDemo, showRevoked, showTest }),
      });
      hits = hits.concat(data.licenses || []);
      const next = (data.page && data.page.nextPageToken) || "";
      if (!next || next === pageToken) break;
      pageToken = next;
    }
    // Typing moved on while this was in flight: its answer is for old text.
    if ($("filter").value.trim() !== q) return;
    desk.searchHits = hits;
    renderLicences();
  } catch (e) {
    setStatus(`Could not search: ${explain(e)}`, true);
  }
}

/** Append one page of `data` to the table, leaving out any licence already on it. */
function appendPage(data) {
  const loaded = new Set(desk.licences.map((l) => l.id));
  desk.licences = desk.licences.concat((data.licenses || []).filter((l) => !loaded.has(l.id)));
  desk.licencePage = data.page || {};
}

/**
 * The next page of licences, appended. The desk used to stop at the first
 * page and say only "more exist", so any licence past it could not be
 * found, filtered for, or acted on from here.
 *
 * It holds the ticket of the table it extends: a Refresh or a flipped toggle
 * while the page is in flight starts a new table, and the late page (or its
 * failure) belongs to the old one, so it is dropped.
 */
async function loadMoreLicences() {
  const token = desk.licencePage.nextPageToken;
  if (!token) return;
  const ticket = licenceLoad;
  $("loadMore").disabled = true;
  try {
    const data = await api(listPath({ pageToken: token }));
    if (ticket !== licenceLoad) return;
    appendPage(data);
    renderLicences();
  } catch (e) {
    if (ticket !== licenceLoad) return;
    setStatus(`Could not load more licences: ${explain(e)}`, true);
  } finally {
    $("loadMore").disabled = false;
  }
}

/**
 * Every page still on the server, appended, so a sort or a filter covers
 * every licence and not just the newest. The table is redrawn after each
 * page, so a failure part-way keeps what was read, and Load all carries on
 * from there when pressed again.
 */
async function loadAllLicences() {
  const ticket = licenceLoad;
  $("loadAll").disabled = true;
  $("loadMore").disabled = true;
  try {
    for (let pages = 0; pages < ALL_PAGES; pages += 1) {
      const token = desk.licencePage.nextPageToken;
      if (!desk.licencePage.hasMore || !token) break;
      setStatus(`Loading every licence… ${desk.licences.length} so far.`);
      const data = await api(listPath({ limit: ALL_PAGE, pageToken: token }));
      if (ticket !== licenceLoad) return;
      appendPage(data);
      renderLicences();
      if ((data.page || {}).nextPageToken === token) break;
    }
    setStatus(`All ${desk.licences.length} licences loaded.`);
  } catch (e) {
    if (ticket !== licenceLoad) return;
    setStatus(`Could not load every licence: ${explain(e)}. The ${desk.licences.length} read so far are shown.`, true);
  } finally {
    $("loadAll").disabled = false;
    $("loadMore").disabled = false;
  }
}

/* --------------------------------------------------------- filtering */

const labelText = (lic) => lic.keyPrefix || lic.id.slice(0, 10);
const lockOf = (lic) => lic.domainLock || lic.emailLock || "";

/** The seats an institution licence uses now (in use on a floating one); NaN for an individual licence. */
function seatsInUse(lic) {
  if (lic.kind !== "institution") return NaN;
  return Number(lic.seating === "floating" ? lic.leasesActive : lic.seatsUsed) || 0;
}

/** Whether `lic` is the State filter's `want`, at `now`. */
function inState(lic, want, now) {
  const state = licenceState(lic, now);
  if (want === "active") return !["revoked", "expired", "in grace"].includes(state);
  if (want === "grace") return state === "in grace";
  if (want === "expired") return state === "expired";
  if (want === "upgrade") return Boolean(lic.upgradePendingId);
  if (want === "ending") {
    const ends = Date.parse(lic.expiresAt || "");
    return state !== "revoked" && Number.isFinite(ends) && ends > now && ends - now <= ENDING_DAYS * DAY;
  }
  return true;
}

/** What the text box is matched against: the words the row shows, and the note. */
function searchText(lic) {
  return [
    labelText(lic), lic.id, lic.domainLock, lic.emailLock, lic.note, lic.kind,
    lic.seating === "floating" ? "shared floating" : "", appName(lic.app),
    lic.mode === "demo" ? "demo" : "", lic.testAccount ? "test" : "", licenceState(lic),
    lic.duration === "timed" ? "timed" : "perpetual", ...(lic.adminEmails || []),
  ];
}

/** The rows the toggles, the three filters and the text box leave, in the chosen order. */
function visibleLicences(q) {
  const now = Date.now();
  const showRevoked = $("showRevoked").checked;
  const showDemo = $("showDemo").checked;
  const kind = $("kindFilter").value;
  const app = $("appFilter").value;
  const state = $("stateFilter").value;
  const found = new Set((desk.searchHits || []).map((l) => l.id));
  const pool = (desk.searchHits || []).concat(desk.licences.filter((l) => !found.has(l.id)));
  const rows = pool.filter((l) =>
    (showRevoked || l.status !== "revoked") &&
    (showDemo || l.mode !== "demo") &&
    (!kind || l.kind === kind) &&
    (!app || (app === "none" ? !l.app : l.app === app)) &&
    inState(l, state, now) &&
    (found.has(l.id) || matchesText(searchText(l), q)));
  return sortList(rows, sort.order(), SORTS);
}

// Revoked licences are kept — the record is the audit trail, and a revoked
// key can still be looked up — but out of the way by default: a revoke that
// left its row in place with only the pill changed read as a revoke that had
// not happened. Demo keys likewise: one is minted for every account, so they
// outnumbered the licences anyone sold and read as live customer keys. The
// backend leaves both out of the list; a row revoked since it loaded is left
// out here.
export function renderLicences() {
  const focus = focusedAction();
  const q = $("filter").value.trim().toLowerCase();
  const showRevoked = $("showRevoked").checked;
  const showDemo = $("showDemo").checked;
  const narrowed = SELECTS.some((id) => $(id).value);
  const rows = visibleLicences(q);
  const hidden = [!showRevoked ? "revoked" : "", !showDemo ? "Demo" : ""].filter(Boolean);
  $("licenceRows").innerHTML = rows.length
    ? rows.map((l) => licenceRow(l, q)).join("")
    : `<tr><td colspan="${COLUMNS}" class="muted">${esc(q && !desk.searchHits && searchableLicenceText(q)
      ? "Searching…" : emptyText(q, hidden, narrowed))}</td></tr>`;
  const note = hidden.length ? ` (${hidden.join(" and ")} hidden)` : "";
  const more = Boolean(desk.licencePage.hasMore);
  const matched = q || narrowed ? `${rows.length} match. ` : "";
  const sorted = more && sort.order().key ? " Sorted within those; Load all to sort every licence." : "";
  $("licencePaging").textContent = matched + (more
    ? `Newest ${desk.licences.length} shown${note}; more exist.${sorted}`
    : `${desk.licences.length} licence(s)${note}.`);
  $("loadMore").hidden = !more;
  $("loadAll").hidden = !more;
  $("clearFilters").hidden = !(q || narrowed);
  // What a Demo row's "Demo limit (25)" means, under the table while one is
  // shown: it was a hover-only title, out of reach from a keyboard or a finger.
  $("demoNote").hidden = !rows.some((l) => l.mode === "demo");
  $("demoNote").textContent = $("demoNote").hidden ? "" : demoCapNote();
  refocus(focus);
}

/**
 * Which row button has focus, as { key, id } (its data key and licence id;
 * key "" for a row's More menu), or null. The table is rebuilt on every
 * change, which drops focus to the top of the page; `refocus` puts it back.
 */
function focusedAction() {
  const at = document.activeElement;
  const row = at && at.closest && at.closest("tr");
  if (!row || row.parentNode !== $("licenceRows")) return null;
  const button = at.localName === "button" ? at : row.querySelector("button");
  const key = button && Object.keys(button.dataset).find((k) => k !== "retry");
  if (!key) return null;
  return { key: at.localName === "summary" ? "" : key, id: button.dataset[key] };
}

/**
 * Focus the same button on the licence's new row; when it sits in the More
 * menu (hidden once the menu closes), or the action is gone, the row's menu,
 * else its first button. Nothing when the row has gone.
 */
function refocus(focus) {
  if (!focus) return;
  const buttons = Array.from($("licenceRows").querySelectorAll("button"));
  const same = focus.key && buttons.find((b) => b.dataset[focus.key] === focus.id);
  if (same && !same.closest("details")) {
    same.focus();
    return;
  }
  const mine = same || buttons.find((b) => Object.values(b.dataset).includes(focus.id));
  if (!mine) return;
  (mine.closest("tr").querySelector("summary") || mine).focus();
}

/** What an empty table says, with what to try next. */
function emptyText(q, hidden, narrowed) {
  const show = hidden.length
    ? `tick ${hidden.map((h) => `Show ${h === "Demo" ? "Demo keys" : "revoked"}`).join(" or ")}`
    : "";
  if (narrowed) return `No licences match. Clear filters${show ? `, or ${show}` : ""}.`;
  if (q) return `No licences match. Clear the filter${show ? `, or ${show}` : ""}.`;
  // Revoked and Demo licences are not fetched until asked for, so an empty
  // table does not mean there are none.
  return show
    ? `No licences to show. ${show[0].toUpperCase()}${show.slice(1)} to include those, ` +
      "or issue one with Issue a licence."
    : "No licences to show. Issue one with Issue a licence.";
}

/** Open or close licence `id`'s details. */
function toggleDetails(id) {
  if (expanded.has(id)) expanded.delete(id);
  else expanded.add(id);
  renderLicences();
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
    ? ` · <span class="err">${outstanding} removal${outstanding === 1 ? "" : "s"}` +
      ` not landed</span>`
    : ` · <span class="ok">${report.entitled} verified</span>`;
}

/**
 * The cloud analyses each holder may keep: a Demo key shows the Demo limit
 * (a number stored on one never applied), a licence without its own number
 * the Licensed default. It was a column; nearly every row read "default".
 */
function capHtml(lic) {
  if (lic.mode === "demo") {
    return `Demo limit${desk.demoAllowance == null ? "" : ` (${esc(desk.demoAllowance)})`}`;
  }
  return lic.maxAnalyses == null ? "the Licensed default" : esc(lic.maxAnalyses);
}

function licenceRow(lic, q) {
  const label = labelText(lic);
  const term = lic.duration === "timed"
    ? `until ${esc(day(lic.expiresAt))}${lic.graceDays ? ` +${lic.graceDays}d` : ""}`
    : "perpetual";
  const revoked = lic.status === "revoked";
  const demo = lic.mode === "demo";
  // A licence for one app (ADR-033) moves by Issue upgrade: a new licence the
  // holder activates, which revokes this one. One from before the split still
  // converts in place (To institution) and frees its one phone (New phone);
  // the backend refuses an upgrade there. A Demo key is not a licence anyone
  // bought, so there is nothing to carry over.
  const perApp = runsActivations(lic);
  const individual = lic.kind !== "institution";
  const convertButton = individual && !demo && !perApp
    ? `<button class="secondary" data-convert="${esc(lic.id)}">To institution</button>`
    : "";
  // While an upgrade waits for its holder another cannot be issued; staff can
  // apply the waiting one instead, for an app that has no Activate.
  const upgradeButton = !(individual && perApp) ? ""
    : lic.upgradePendingId
      ? `<button class="secondary" data-apply="${esc(lic.id)}">Apply upgrade</button>`
      : `<button class="secondary" data-upgrade="${esc(lic.id)}">Issue upgrade</button>`;
  // The phones activated on it, against its count. A shared (floating)
  // licence counts its seats in use instead: the lease is the activation.
  const phonesButton = perApp && lic.seating !== "floating" ? phonesControl(lic, label) : "";
  // Revoked licences are what most deletes are for: the record of one is
  // the audit trail until nobody needs it. A system Demo key is not deleted —
  // the account would only be issued another.
  const deleteButton = demo && lic.createdByUid === "system"
    ? ""
    : `<button class="danger" data-delete="${esc(lic.id)}">Delete</button>`;
  // Six buttons a row made the table wider than any screen. What is done
  // most stays in view; the rest is under More, the irreversible last.
  const actions = revoked ? deleteButton : `
    <button class="secondary" data-edit="${esc(lic.id)}">Edit</button>
    ${phonesButton}
    ${lic.kind === "institution"
      ? `<button class="secondary" data-roster="${esc(lic.id)}">Roster</button>` : ""}
    <details class="more">
      <summary>More</summary>
      <div class="menu">
        ${lic.kind === "institution"
          ? `<button class="secondary" data-verify="${esc(lic.id)}">Verify</button>`
          : perApp ? "" : `<button class="secondary" data-device="${esc(lic.id)}">New phone</button>`}
        ${upgradeButton}
        ${convertButton}
        <button class="secondary" data-history="${esc(lic.id)}">Phone history</button>
        <button class="danger" data-revoke="${esc(lic.id)}">Revoke</button>
        ${deleteButton}
      </div>
    </details>`;
  const open = expanded.has(lic.id);
  const detailsId = `licence-details-${esc(lic.id)}`;
  // The row shows four things; a filter that matched something else (the
  // address or domain, the note) says what, under the kind, so a match never
  // looks like a stray row.
  const lock = lockOf(lic);
  const hit = !q ? ""
    : lock && lock.toLowerCase().includes(q) ? `Locked to ${esc(lock)}`
      : lic.note && lic.note.toLowerCase().includes(q) ? `Note: ${esc(lic.note)}` : "";
  const hitLine = hit ? `<br><span class="muted">${hit}</span>` : "";
  const current = desk.current === lic.id ? ' class="current"' : "";
  return `
    <tr${current}>
      <td class="mono"><button type="button" class="row-toggle" data-details="${esc(lic.id)}"
          aria-expanded="${open}" aria-controls="${detailsId}">${esc(label)}</button></td>
      <td><span>${esc(lic.kind)}${lic.seating === "floating" ? " · shared" : ""}${kindTags(lic)}${upgradeNote(lic)}${hitLine}</span></td>
      <td>${seatSummary(lic)}</td>
      <td>${licenceStatePill(lic)}</td>
      <td class="actions">${actions}</td>
    </tr>${open ? detailsRow(lic, detailsId, term) : ""}`;
}

/**
 * The rest of a licence, opened from its key. The row keeps what is read
 * most (key, kind, seats, status); here are what were columns (its term,
 * who or what it is locked to, its mode, its analyses limit) and what was
 * only in Edit (its note, IT contacts, support date), with when it was
 * issued and claimed.
 */
function detailsRow(lic, detailsId, term) {
  const facts = [
    ["Term", term],
    ["Locked to", lockOf(lic) ? esc(lockOf(lic)) : '<span class="muted">nobody yet</span>'],
    ["Licence id", `<span class="mono">${esc(lic.id)}</span>`],
    ["Mode", esc(ACCOUNT.modes[lic.mode] || lic.mode)],
    lic.app ? ["App", esc(appName(lic.app))] : ["App", "none named (from before the split)"],
    lic.kind === "institution"
      ? ["Seating", lic.seating === "floating" ? "floating — a shared pool of seats" : "assigned — everyone on the roster"]
      : null,
    runsActivations(lic)
      ? ["Phones", `${esc(Number(lic.devicesActive) || 0)} of ${esc(capText(devicesCap(lic)))} active`] : null,
    ["Cloud analyses per person", capHtml(lic)],
    lic.adminEmails && lic.adminEmails.length ? ["IT contacts", esc(lic.adminEmails.join(", "))] : null,
    lic.createdAt ? ["Issued", esc(when(lic.createdAt))] : null,
    lic.redeemedAt ? ["Claimed", esc(when(lic.redeemedAt))] : null,
    lic.graceDays ? ["After-expiry grace", `${esc(lic.graceDays)} days${lic.graceEndsAt ? `, until ${esc(day(lic.graceEndsAt))}` : ""}`] : null,
    lic.supportUntil ? ["Support until", esc(day(lic.supportUntil))] : null,
    lic.testAccount ? ["Test account", "yes — a staff test account's licence"] : null,
    ["Note", lic.note ? esc(lic.note) : '<span class="muted">none</span>'],
  ].filter(Boolean);
  return `
    <tr class="row-details" id="${detailsId}">
      <td colspan="${COLUMNS}">
        <dl class="facts">${facts.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("")}</dl>
      </td>
    </tr>`;
}

/**
 * Where a licence stands in an upgrade (ADR-033): replaced, waiting for its
 * holder to activate the new one, or issued to replace another.
 */
function upgradeNote(lic) {
  const note = (text) => `<br><span class="muted">${text}</span>`;
  if (lic.supersededBy) {
    return note(lic.app ? "replaced by an upgrade" : "replaced by an institution licence");
  }
  if (lic.upgradePendingId) {
    return note(`upgrade issued (${esc(labelOf(lic.upgradePendingId))}), waiting for the holder`);
  }
  if (lic.supersedes && lic.status !== "revoked") return note(`upgrade of ${esc(labelOf(lic.supersedes))}`);
  return "";
}

/**
 * The tags after a licence's kind: the app it is for (none on one from before
 * the split), Demo or test when it is one, and its analyses limit when one was
 * set at issue — the rare value the old Analyses / person column existed for.
 */
function kindTags(lic) {
  const tags = [];
  if (lic.app) tags.push(esc(appName(lic.app)));
  if (lic.mode === "demo") tags.push("Demo");
  if (lic.testAccount) tags.push("test");
  if (lic.mode !== "demo" && lic.maxAnalyses != null) tags.push(`${esc(lic.maxAnalyses)} analyses each`);
  return tags.map((t) => ` <span class="tag">${t}</span>`).join("");
}

/** "Phones n/m": opens the licence's phones (phones.js). */
function phonesControl(lic, label) {
  const active = Number(lic.devicesActive) || 0;
  const cap = capText(devicesCap(lic));
  return `<button class="secondary" data-phones="${esc(lic.id)}"
      aria-label="Phones on ${esc(label)}: ${esc(active)} of ${esc(cap)} active">Phones ${esc(active)}/${esc(cap)}</button>`;
}
