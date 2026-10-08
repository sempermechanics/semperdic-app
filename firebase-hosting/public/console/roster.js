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
import { api, esc, confirmInPage } from "./auth.js";
import { explain } from "./messages.js";
import { seatCells, inviteCells } from "./util.js";

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

/** One member's row, with what can be done to them. */
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

/** A pending invitation's row. */
export function inviteRow(invite) {
  return `
    <tr>${inviteCells(invite)}
      <td class="actions">
        <button class="danger" data-invite="${esc(invite.id)}">Withdraw</button>
      </td>
    </tr>`;
}

const ACTIONS = {
  clear: { method: "PATCH", body: { clearDeviceLock: true }, verb: "Unlocking" },
  hold: { method: "PATCH", body: { enabled: false }, verb: "Holding" },
  unhold: { method: "PATCH", body: { enabled: true }, verb: "Resuming" },
  remove: { method: "DELETE", verb: "Removing" },
};

const ACT_ERRORS = {
  seat_revoked: "That member was removed. Add their address again to restore them.",
  license_revoked: "This licence has been revoked, so its seats cannot be changed.",
  seat_busy: "That seat changed while you were acting on it. Try again.",
};

/**
 * Wire a roster table. `base()` is the open licence's path, "" when none is;
 * `report(message, isError)` shows what happened; `reload()` reads the
 * licence again after a change (the page re-renders through `render`), and
 * `retry()` (default `reload`) answers the Retry of a failed load.
 * Returns `render(seats, invites)`, which fills `rows`.
 */
export function wireRoster({ rows, email, add, base, report, reload, retry = reload }) {
  // Each action takes the roster's path before it asks, so an answer given after
  // another roster was opened (or this one closed) still goes to the one asked on.
  async function act(action, uid) {
    const spec = ACTIONS[action];
    const root = base();
    if (!root) return;
    if (action === "remove" && !(await confirmInPage({
      title: "Remove member",
      message: "Remove this member? Their saved analyses stay untouched.",
      confirm: "Remove",
    }))) {
      return;
    }
    report(`${spec.verb}…`);
    try {
      await api(`${root}/seats/${encodeURIComponent(uid)}`,
        { method: spec.method, ...(spec.body ? { body: JSON.stringify(spec.body) } : {}) });
      await reload();
    } catch (e) {
      report(explain(e, ACT_ERRORS, (text) => `Could not complete that: ${text}`), true);
    }
  }

  async function withdraw(inviteId) {
    const root = base();
    if (!root) return;
    if (!(await confirmInPage({
      title: "Withdraw invitation",
      message: "Withdraw this invitation? Nobody has claimed it yet.",
      confirm: "Withdraw",
    }))) return;
    report("Withdrawing…");
    try {
      await api(`${root}/invites/${encodeURIComponent(inviteId)}`, { method: "DELETE" });
      await reload();
    } catch (e) {
      report(`Could not withdraw: ${e.message}`, true);
    }
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
        // One licence per person. Which licence is not the roster's to say;
        // Semper support can move them.
        member_already_licensed: `${address} already has a Semper licence of their own. ` +
          "Ask Semper support to move them onto this one.",
        license_seats_exhausted: "This licence has no seats left.",
        license_seat_disabled: "That seat is on hold — resume it instead.",
      }, (text) => `Could not add ${address}: ${text}`), true);
    }
  }

  // One listener on the table body: its rows are rebuilt on every load.
  rows.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.dataset.act) act(btn.dataset.act, btn.dataset.uid);
    else if (btn.dataset.invite) withdraw(btn.dataset.invite);
    else if ("retry" in btn.dataset) retry();
  });
  add.addEventListener("click", addMember);
  email.addEventListener("keydown", (e) => { if (e.key === "Enter") addMember(); });

  return function render(seats, invites) {
    rows.innerHTML = seats.map(seatRow).concat(invites.map(inviteRow)).join("") ||
      '<tr><td colspan="5" class="muted">Nobody on this licence yet.</td></tr>';
  };
}
