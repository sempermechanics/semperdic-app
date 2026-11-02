/* A licence's audit-log panel: its events, newest first, a page at a time,
 * filtered by date, and exported as CSV or JSON (`GET …/audit`). Read on
 * demand rather than with the roster: it is a query over everything that
 * ever happened to the licence. Shared, like roster.js, by the institution
 * page (IT's routes) and the operator desk's roster card (the staff routes):
 * `base()` names the tier.
 */
import { api, apiBlob, saveBlob } from "./api.js";
import { holdFocus } from "./dialogs.js";
import { explain } from "./messages.js";
import { esc, when, auditQuery, localDayRange } from "./util.js";

/** A page in the panel; an export is one request of the backend's largest page. */
const PAGE = 50;
const EXPORT_PAGE = 5000;

/** What an export that reached the backend's largest page says. */
const CEILING = `Exported the newest ${EXPORT_PAGE.toLocaleString("en")} events — narrow the dates for the rest.`;

/**
 * The data rows in an audit CSV (`roster_io.audit_csv`): its lines less the
 * header. No cell holds a line break (`detail` is JSON, which escapes them).
 */
export function csvRecords(text) {
  return Math.max(0, String(text).split(/\r?\n/).filter((line) => line).length - 1);
}

const ERRORS = {
  invalid_audit_range: "Those dates are not a range: the start must come before the end.",
};

const EMPTY = '<tr><td colspan="5" class="muted">Choose dates, or none for everything, and press Show.</td></tr>';

/**
 * One event's row. `who(uid)` names an account (the roster's address for a
 * member, else the uid); a seat target `{licence}/{uid}` is named the same way.
 */
export function auditRow(event, who = (uid) => uid) {
  const target = event.target || {};
  const [, member] = String(target.id || "").split("/");
  const detail = Object.entries(event.detail || {})
    .filter(([, value]) => value !== "" && value != null)
    .map(([key, value]) => `${key}: ${typeof value === "object" ? JSON.stringify(value) : value}`)
    .join(", ");
  return `
    <tr>
      <td>${esc(when(event.ts))}</td>
      <td class="mono">${esc(event.action)}</td>
      <td>${esc(who(event.uid) || "—")}</td>
      <td>${esc(target.type || "")}${member ? ` ${esc(who(member))}` : ""}</td>
      <td class="muted">${esc(detail)}</td>
    </tr>`;
}

/**
 * Wire the panel. `base()` is the open licence's path ("" when none is),
 * `name()` the file name's licence part, `who(uid)` as for `auditRow`.
 * Returns `reset()`, for when another licence is opened.
 */
export function wireAuditLog({ rows, since, until, show, more, csv, json, base, name, who, report }) {
  let next = null;
  // The range the shown rows were read for: "Show more" continues that query
  // with its token, not whatever the date boxes say since. A token is a
  // cursor into one query; sent with another range it read events outside
  // the new one, or skipped some, under rows that claimed one list.
  let shown = null;
  let ticket = 0; // the latest page asked for; an older answer is dropped

  // Local days, as the rows are shown (util.js `localDayRange`).
  const range = () => localDayRange({ since: since.value, until: until.value });

  async function load(append) {
    const root = base();
    if (!root) return;
    const mine = ++ticket;
    const refocus = holdFocus(append ? more : show);
    show.disabled = true;
    more.disabled = true;
    report("Loading the audit log…");
    try {
      const asked = append ? shown : range();
      const query = auditQuery(asked, { page_size: PAGE, page_token: append ? next : "" });
      const page = await api(`${root}/audit${query}`);
      if (mine !== ticket) return;
      shown = asked;
      const events = page.events || [];
      const html = events.map((e) => auditRow(e, who)).join("");
      rows.innerHTML = append ? rows.innerHTML + html
        : html || '<tr><td colspan="5" class="muted">Nothing recorded in that range.</td></tr>';
      next = (page.page && page.page.nextPageToken) || null;
      more.hidden = !next;
      report("");
    } catch (e) {
      if (mine !== ticket) return;
      report(explain(e, ERRORS, (text) => `Could not load the audit log: ${text}`), true);
    } finally {
      if (mine === ticket) {
        show.disabled = false;
        more.disabled = false;
        refocus();
      }
    }
  }

  async function exportAs(format, button) {
    const root = base();
    if (!root) return;
    const refocus = holdFocus(button);
    button.disabled = true;
    report("Exporting the audit log…");
    try {
      if (format === "csv") {
        const query = auditQuery(range(), { format: "csv", page_size: EXPORT_PAGE });
        const blob = await apiBlob(`${root}/audit${query}`);
        saveBlob(blob, `audit-${name()}.csv`);
        // The next page's token rides in a header the browser does not expose
        // to this page, so a full page is what says the export stopped short.
        report(csvRecords(await blob.text()) >= EXPORT_PAGE ? CEILING : "Audit log exported.");
      } else {
        const query = auditQuery(range(), { page_size: EXPORT_PAGE });
        const page = await api(`${root}/audit${query}`);
        const file = { licenseId: page.licenseId, ...range(), events: page.events || [] };
        saveBlob(new Blob([JSON.stringify(file, null, 2)], { type: "application/json" }),
          `audit-${name()}.json`);
        report(page.page && page.page.hasMore ? CEILING : "Audit log exported.");
      }
    } catch (e) {
      report(explain(e, ERRORS, (text) => `Could not export: ${text}`), true);
    } finally {
      button.disabled = false;
      refocus();
    }
  }

  show.addEventListener("click", () => load(false));
  more.addEventListener("click", () => load(true));
  csv.addEventListener("click", () => exportAs("csv", csv));
  json.addEventListener("click", () => exportAs("json", json));

  return function reset() {
    ticket += 1;
    next = null;
    shown = null;
    rows.innerHTML = EMPTY;
    more.hidden = true;
    show.disabled = false;
    more.disabled = false;
  };
}
