/* One institution licence's roster, as a table with its actions — the same on
 * the institution page (IT, `/v1/institutions/licenses/{id}`) and on the
 * operator desk (Semper staff, `/v1/admin/licenses/{id}`). The two used to
 * carry their own copies, and they drifted: the desk offered fewer actions
 * and called routes that refused staff (TD-191).
 *
 * The page owns the elements and what goes around the table (the licence
 * summary, where messages land); this module owns the rows, what each button
 * sends, and what a refusal says.
 */
import { api } from "./api.js";
import { confirmInPage } from "./dialogs.js";
import { explain } from "./messages.js";
import { esc, seatCells, inviteCells, classroomField, seatDevices } from "./util.js";
import { byText, byNumber, drawChips, onChip } from "./list-tools.js";

/**
 * The roster of the licence at `base`: its summary, every seat and the
 * outstanding invites. The listing is paged; this follows `nextPageToken`
 * to the end, and invites come with the first page.
 */
export async function fetchRoster(base) {
  const first = await api(`${base}/seats`);
  let seats = first.seats || [];
  let token = first.page && first.page.nextPageToken;
  while (token) {
    const next = await api(`${base}/seats?page_token=${encodeURIComponent(token)}`);
    seats = seats.concat(next.seats || []);
    token = next.page && next.page.nextPageToken;
  }
  return { license: first.license || {}, seats, invites: first.invites || [] };
}

/**
 * One member's row, with what can be done to them. Each button carries the
 * member's address, so its confirmation and its name for a screen reader say
 * who it acts on: on a long roster every row's button reads "Remove".
 */
export function seatRow(seat) {
  // A removed member holds nothing to hold, resume or unlock. Resume used to
  // be offered here and reactivated the seat without taking a slot back;
  // adding the address again is how someone comes back.
  if (seat.status === "revoked") {
    return `
    <tr>${seatCells(seat)}
      <td class="actions muted">add again to restore</td>
    </tr>`;
  }
  const who = esc(seat.email || seat.uid);
  const on = `data-uid="${esc(seat.uid)}" data-email="${who}"`;
  return `
    <tr>${seatCells(seat)}
      <td class="actions">
        ${seat.deviceIdLock || classroomField(seat, "deviceIdLock")
          ? `<button class="secondary" data-act="clear" ${on} aria-label="New phone for ${who}">New phone</button>`
          : ""}
        ${seat.status === "active"
          ? `<button class="secondary" data-act="hold" ${on} aria-label="Pause ${who}">Pause</button>`
          : `<button class="secondary" data-act="unhold" ${on} aria-label="Resume ${who}">Resume</button>`}
        <button class="danger" data-act="remove" ${on} aria-label="Remove ${who}">Remove</button>
      </td>
    </tr>`;
}

/** A pending invitation's row. */
export function inviteRow(invite) {
  return `
    <tr>${inviteCells(invite)}
      <td class="actions">
        <button class="danger" data-invite="${esc(invite.id)}" data-email="${esc(invite.email)}"
                aria-label="Withdraw the invitation to ${esc(invite.email)}">Withdraw</button>
      </td>
    </tr>`;
}

const ACTIONS = {
  clear: { method: "PATCH", body: { clearDeviceLock: true }, verb: "Unlocking" },
  hold: { method: "PATCH", body: { enabled: false }, verb: "Pausing" },
  unhold: { method: "PATCH", body: { enabled: true }, verb: "Resuming" },
  remove: { method: "DELETE", verb: "Removing" },
};

/** The confirmation an action asks for first, naming the member, or none. */
const ASK = {
  clear: (who) => ({
    title: `New phone for ${who}`,
    message: `Free ${who} for a new phone?

` +
      "The next phone they sign in on takes the seat. Their seat and their " +
      "saved analyses are untouched.",
    confirm: "New phone",
  }),
  remove: (who) => ({
    title: `Remove ${who}`,
    message: `Remove ${who} from this licence?

` +
      "They drop to Demo the next time their phone checks in. Their " +
      "saved analyses stay untouched.",
    confirm: "Remove",
  }),
};

/** Rows drawn at a time: a 500-seat roster is one table, but not one page of it. */
export const PAGE_ROWS = 100;

/** A seat's status as the Status cell words it, for the filter and the sort. */
const STATUS_WORDS = { active: "active", disabled: "paused", revoked: "removed" };

/** One roster line, seat or invite, with what the filter, the chips and the sort read. */
function entry(kind, item) {
  const email = String((kind === "seat" ? item.email || item.uid : item.email) || "");
  const status = kind === "seat" ? STATUS_WORDS[item.status] || String(item.status || "") : "invited";
  const text = [email, status, item.deviceIdLock, classroomField(item, "deviceIdLock")]
    .filter(Boolean).join(" ").toLowerCase();
  const phone = kind === "seat" ? seatDevices(item) || "" : "";
  const lease = kind === "seat" ? Date.parse(item.leaseExpiresAt || "") : NaN;
  return { kind, item, email: email.toLowerCase(), status, text, phone, lease };
}

const BY = {
  member: (a, b) => a.email.localeCompare(b.email),
  status: (a, b) => a.status.localeCompare(b.status) || a.email.localeCompare(b.email),
  // A floating seat by when its lease runs out, soonest first; nobody holding one, last.
  seat: (a, b) => byNumber(a.lease, b.lease) || a.email.localeCompare(b.email),
  // By the phone's id; nobody's phone yet, last.
  phone: (a, b) => (!a.phone - !b.phone) || byText(a.phone, b.phone) || a.email.localeCompare(b.email),
};

/**
 * The roster's views, as chips over the table: everyone but the removed (the
 * one shown first: a removed member holds nothing, and a long roster's
 * removals buried the people on it), or one status.
 */
const VIEWS = [
  { value: "current", label: "Current", test: (e) => e.status !== "removed", always: true },
  { value: "active", label: "Active", test: (e) => e.status === "active" },
  { value: "paused", label: "Paused", test: (e) => e.status === "paused" },
  { value: "invited", label: "Invited", test: (e) => e.status === "invited" },
  { value: "removed", label: "Removed", test: (e) => e.status === "removed" },
];

const ACT_ERRORS = {
  seat_revoked: "That member was removed. Add their address again to restore them.",
  license_revoked: "This licence has been revoked, so its seats cannot be changed.",
  seat_busy: "That seat changed while you were acting on it. Try again.",
  seat_not_found: "That member is no longer on this licence. Reload to see the roster.",
};

/**
 * Wire a roster table. `base()` is the open licence's path, "" when none is;
 * `report(message, isError)` shows what happened; `reload()` reads the
 * licence again after a change (the page re-renders through `render`), and
 * `retry()` (default `reload`) answers the Retry of a failed load.
 * Returns `render(seats, invites)`, which fills `rows`.
 *
 * A roster runs to hundreds of rows, so it is drawn `PAGE_ROWS` at a time
 * with Show more; `filter` (a text box) narrows it by address, status or
 * device; each `button[data-sort]` in `head` sorts by its column, its <th>
 * saying which way (aria-sort); `count` says how many are shown of how many.
 * All three work on the rows already read; another licence opened starts
 * them afresh.
 */
export function wireRoster({
  rows, email, add, base, report, reload, retry = reload, filter = null, count = null, head = null,
  chips = null, domain = () => "",
}) {
  let all = [];
  let shown = PAGE_ROWS;
  // The chip pressed; without chips every line is shown, removed ones too.
  let view = chips ? "current" : "";
  let order = { key: "", dir: 1 };
  let drawnFor = null; // the licence the rows are for
  const sorters = head ? Array.from(head.querySelectorAll("button[data-sort]")) : [];
  // Each action takes the roster's path before it asks, so an answer given after
  // another roster was opened (or this one closed) still goes to the one asked on.
  async function act(action, uid, who) {
    const spec = ACTIONS[action];
    const root = base();
    if (!root) return;
    if (ASK[action] && !(await confirmInPage(ASK[action](who || uid)))) return;
    report(`${spec.verb}…`);
    try {
      await api(`${root}/seats/${encodeURIComponent(uid)}`,
        { method: spec.method, ...(spec.body ? { body: JSON.stringify(spec.body) } : {}) });
      await reload();
      refocus((b) => b.dataset.uid === uid);
    } catch (e) {
      report(explain(e, ACT_ERRORS, (text) => `Could not complete that: ${text}`), true);
    }
  }

  async function withdraw(inviteId, who) {
    const root = base();
    if (!root) return;
    if (!(await confirmInPage({
      title: "Withdraw invitation",
      message: `Withdraw the invitation to ${who || "this address"}?

Nobody has claimed it yet.`,
      confirm: "Withdraw",
    }))) return;
    report("Withdrawing…");
    try {
      await api(`${root}/invites/${encodeURIComponent(inviteId)}`, { method: "DELETE" });
      await reload();
      refocus(() => false);
    } catch (e) {
      report(explain(e, {}, (text) => `Could not withdraw: ${text}`), true);
    }
  }

  /**
   * After a change the rows are rebuilt and the button that had focus is
   * gone: put focus on the same member's row again (`mine` picks its
   * button), or on the Add box when they left the roster, so a keyboard user
   * carries on where they were rather than from the top of the page.
   */
  function refocus(mine) {
    const again = Array.from(rows.querySelectorAll("button")).find(mine);
    (again || email).focus();
  }

  async function addMember() {
    const address = email.value.trim();
    if (!address || !base()) return;
    report("Adding…");
    try {
      const out = await api(`${base()}/seats`,
        { method: "POST", body: JSON.stringify({ email: address }) });
      email.value = "";
      await reload();
      // Someone who has never opened Semper is invited rather than refused,
      // so say which of the two happened — "added" and "invited" mean
      // different things to whoever is chasing them.
      report(out.seat
        ? `${address} is on the licence now.`
        : `${address} has not signed in yet — invited. They join automatically ` +
          "the first time they do.");
    } catch (e) {
      report(explain(e, {
        invite_exists: `${address} is already promised a place on a different licence.`,
        // IT may add only addresses on the licence's domain (TE3-102).
        member_off_domain: `${address} is outside this licence's domain` +
          `${domain() ? ` (${domain()})` : ""}. Only addresses on it can be added.`,
        // One licence per person, told to staff only since TE3-102 (IT gets a
        // pending invite instead); semper support can move them.
        member_already_licensed: `${address} already has a semper licence of their own. ` +
          "Ask semper support to move them onto this one.",
        license_seats_exhausted: "This licence has no seats left.",
        license_seat_disabled: "That seat is paused — resume it instead.",
      }, (text) => `Could not add ${address}: ${text}`), true);
    }
  }

  /** Draw the rows the chips, the filter, the sort and Show more leave. */
  function draw() {
    const q = filter ? filter.value.trim().toLowerCase() : "";
    const pick = VIEWS.find((v) => v.value === view);
    const inView = pick ? all.filter(pick.test) : all;
    if (chips) {
      drawChips(chips, VIEWS.map((v) => ({ ...v, count: all.filter(v.test).length })), view);
    }
    const matching = q ? inView.filter((e) => e.text.includes(q)) : inView.slice();
    if (order.key) matching.sort((a, b) => BY[order.key](a, b) * order.dir);
    const visible = matching.slice(0, shown);
    const more = matching.length - visible.length;
    rows.innerHTML = visible.map((e) => (e.kind === "seat" ? seatRow(e.item) : inviteRow(e.item))).join("") +
      (more ? `<tr><td colspan="5"><button class="secondary" data-more>Show ${Math.min(more, PAGE_ROWS)} more</button></td></tr>` : "") ||
      `<tr><td colspan="5" class="muted">${emptyText(q)}</td></tr>`;
    if (count) {
      count.textContent = !all.length ? ""
        : q ? `Showing ${visible.length} of ${matching.length} matching, ${all.length} on the roster`
          : `Showing ${visible.length} of ${inView.length}${inView.length < all.length ? `, ${all.length} on the roster` : ""}`;
    }
    for (const button of sorters) {
      const key = button.dataset.sort;
      const way = order.key !== key ? "none" : order.dir > 0 ? "ascending" : "descending";
      button.closest("th").setAttribute("aria-sort", way);
    }
  }

  /** What an empty table says: the roster is empty, or this view or filter is. */
  function emptyText(q) {
    if (!all.length) return "Nobody on this licence yet.";
    if (q) return "Nobody on the roster matches that filter.";
    if (view === "current") return "Everyone on this licence has been removed. Press Removed to see them.";
    return "Nobody here. Press Current to see the roster.";
  }

  if (chips) {
    onChip(chips, (value) => {
      view = value;
      shown = PAGE_ROWS;
      draw();
      // The chips are drawn again with their counts: focus stays on the one pressed.
      const pressed = chips.querySelector(`button[data-chip="${value}"]`);
      if (pressed) pressed.focus();
    });
  }

  // One listener on the table body: its rows are rebuilt on every load.
  rows.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.dataset.act) act(btn.dataset.act, btn.dataset.uid, btn.dataset.email);
    else if (btn.dataset.invite) withdraw(btn.dataset.invite, btn.dataset.email);
    else if ("retry" in btn.dataset) retry();
    else if ("more" in btn.dataset) {
      const first = shown;
      shown += PAGE_ROWS;
      draw();
      // Carry on from the first row just shown, not the top of the page.
      const row = rows.querySelectorAll("tr")[first];
      const next = row && row.querySelector("button");
      if (next) next.focus();
    }
  });
  add.addEventListener("click", addMember);
  email.addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });
  if (filter) {
    filter.addEventListener("input", () => {
      shown = PAGE_ROWS;
      draw();
    });
  }
  for (const button of sorters) {
    button.addEventListener("click", () => {
      const key = button.dataset.sort;
      order = { key, dir: order.key === key ? -order.dir : 1 };
      draw();
    });
  }

  return function render(seats, invites) {
    // Another licence: its own rows, unfiltered and unsorted, from the top.
    const root = base();
    if (root !== drawnFor) {
      drawnFor = root;
      shown = PAGE_ROWS;
      order = { key: "", dir: 1 };
      if (chips) view = "current";
      if (filter) filter.value = "";
    }
    all = seats.map((s) => entry("seat", s)).concat(invites.map((i) => entry("invite", i)));
    draw();
  };
}
