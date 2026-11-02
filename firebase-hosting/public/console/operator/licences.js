/* The licence table: its pages, the filter and search, and one row redrawn
 * when one licence changes. What each row's buttons do lives with the card
 * or dialog the button opens; the entry module routes the clicks.
 */
import { api } from "../api.js";
import { setStatus, markFirstData, whileWaiting } from "../status.js";
import {
  esc, day, licenceStatePill, licenceListPath, licenceSearchBody, searchableLicenceText, upsertLicence,
  placeholderRows, retryRow,
} from "../util.js";
import { ACCOUNT, explain } from "../messages.js";
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
// The most pages one search reads. A search is an exact match on an address,
// a domain or a key prefix, so the backend answers it in one page today.
const SEARCH_PAGES = 10;
const COLUMNS = 9;
let searchTimer = 0;
// Which table load is the latest: a toggle flipped twice sends two, and the
// first to answer must not be drawn over the second.
let licenceLoad = 0;

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
  const ticket = ++licenceLoad;
  desk.verified = {};
  desk.searchHits = null;
  $("licenceRows").innerHTML = placeholderRows(COLUMNS, 5);
  $("licencePaging").textContent = "";
  $("loadMore").hidden = true;
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

/**
 * Look the filter text up on the backend. A POST with the text in the body
 * (TD-244): sent as a `q` query parameter, the address searched for was
 * written into every request log between here and Cloud Run. A search
 * answers in the list's shape; a further page is asked for with its token
 * in the body too, up to `SEARCH_PAGES` pages. The Demo and revoked boxes
 * go in the body as the list's query sends them, so a ticked box finds those
 * licences too; the table still drops any it is not showing.
 */
export async function searchLicences() {
  const q = $("filter").value.trim();
  const showDemo = $("showDemo").checked;
  const showRevoked = $("showRevoked").checked;
  try {
    let hits = [];
    let pageToken = "";
    for (let pages = 0; pages < SEARCH_PAGES; pages += 1) {
      const data = await api("/v1/admin/licenses/search", {
        method: "POST",
        body: licenceSearchBody({ q, limit: LICENCE_PAGE, pageToken, showDemo, showRevoked }),
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
    const loaded = new Set(desk.licences.map((l) => l.id));
    desk.licences = desk.licences.concat((data.licenses || []).filter((l) => !loaded.has(l.id)));
    desk.licencePage = data.page || {};
    renderLicences();
  } catch (e) {
    if (ticket !== licenceLoad) return;
    setStatus(`Could not load more licences: ${explain(e)}`, true);
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
  const focus = focusedAction();
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
  const hidden = [!showRevoked ? "revoked" : "", !showDemo ? "Demo" : ""].filter(Boolean);
  $("licenceRows").innerHTML = rows.length
    ? rows.map(licenceRow).join("")
    : `<tr><td colspan="${COLUMNS}" class="muted">${esc(q && !desk.searchHits && searchableLicenceText(q)
      ? "Searching…" : emptyText(q, hidden))}</td></tr>`;
  const note = hidden.length ? ` (${hidden.join(" and ")} hidden)` : "";
  $("licencePaging").textContent = desk.licencePage.hasMore
    ? `Newest ${desk.licences.length} shown${note}; more exist.`
    : `${desk.licences.length} licence(s)${note}.`;
  $("loadMore").hidden = !desk.licencePage.hasMore;
  // What a Demo row's "demo (25)" means, under the table while one is shown:
  // it was a hover-only title, out of reach from a keyboard or a finger.
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
function emptyText(q, hidden) {
  const show = hidden.length
    ? `tick ${hidden.map((h) => `Show ${h === "Demo" ? "Demo keys" : "revoked"}`).join(" or ")}`
    : "";
  if (q) return `No licences match. Clear the filter${show ? `, or ${show}` : ""}.`;
  // Revoked and Demo licences are not fetched until asked for, so an empty
  // table does not mean there are none.
  return show
    ? `No licences to show. ${show[0].toUpperCase()}${show.slice(1)} to include those, ` +
      "or issue one with Issue a licence."
    : "No licences to show. Issue one with Issue a licence.";
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

function licenceRow(lic) {
  const label = lic.keyPrefix || lic.id.slice(0, 10);
  const term = lic.duration === "timed"
    ? `until ${esc(day(lic.expiresAt))}${lic.graceDays ? ` +${lic.graceDays}d` : ""}`
    : "perpetual";
  const revoked = lic.status === "revoked";
  // Shown because it used to be invisible after mint: a cap typed at issue
  // time reached every holder with no trace of it on this desk. A Demo key
  // shows the Demo limit instead: a number stored on one never applied.
  const demo = lic.mode === "demo";
  const cap = demo
    ? `<span class="muted">Demo limit${desk.demoAllowance == null ? "" : ` (${esc(desk.demoAllowance)})`}</span>`
    : lic.maxAnalyses == null ? '<span class="muted">default</span>' : esc(lic.maxAnalyses);
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
  return `
    <tr>
      <td class="mono">${esc(label)}</td>
      <td><span>${esc(lic.kind)}${lic.seating === "floating" ? " · shared" : ""}${appBadge(lic)}${upgradeNote(lic)}</span></td>
      <td>${esc(ACCOUNT.modes[lic.mode] || lic.mode)}</td>
      <td>${seatSummary(lic)}</td>
      <td>${term}</td>
      <td>${cap}</td>
      <td>${licenceStatePill(lic)}</td>
      <td class="muted">${esc(lic.domainLock || lic.emailLock || "—")}</td>
      <td class="actions">${actions}</td>
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

/** The app a licence is for, as a tag; none on one from before the split. */
function appBadge(lic) {
  return lic.app ? ` <span class="tag">${esc(appName(lic.app))}</span>` : "";
}

/** "Phones n/m": opens the licence's phones (phones.js). */
function phonesControl(lic, label) {
  const active = Number(lic.devicesActive) || 0;
  const cap = capText(devicesCap(lic));
  return `<button class="secondary" data-phones="${esc(lic.id)}"
      aria-label="Phones on ${esc(label)}: ${esc(active)} of ${esc(cap)} active">Phones ${esc(active)}/${esc(cap)}</button>`;
}
