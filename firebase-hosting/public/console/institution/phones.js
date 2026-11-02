/* The open licence's phones (ADR-033). A licence that names its app counts
 * the phones activated on it, one pool across the whole licence; this card
 * shows that count, the active phones of members on the licence's domain,
 * and Deactivate for each. IT does not activate a phone: a member does that
 * in the app, on the phone itself.
 *
 * A licence from before the split (no `app`) keeps one phone per member on
 * the roster's Phone column and New phone, so it gets no card and no read.
 */
import { sessionOver } from "../auth.js";
import { api } from "../api.js";
import { confirmInPage, holdFocus } from "../dialogs.js";
import { explain } from "../messages.js";
import { esc, when, APP_NAMES, placeholderRows, retryRow, sessionEnded } from "../util.js";

/** An institution licence with neither a device count nor a seat cap: no limit (`repo.activations._UNCOUNTED`). */
const UNCOUNTED = 1_000_000;

/** Who activated a phone, when it was not the member on it. */
const ACTIVATED_BY = {
  it: "by your institution's IT",
  staff: "by semper support",
};

/** What a refusal on this card says. */
const PHONE_ERRORS = {
  // Not there any more, deactivated meanwhile, or its member is off the
  // licence's domain: the backend answers all three alike.
  activation_not_found: "That phone is no longer activated on this licence. The list below is read again.",
  license_not_found: "No licence with that id that you administer.",
};

/** The app a licence is for, by name; the stored name when it is one this page does not know. */
function appName(app) {
  return APP_NAMES[app] || app;
}

/** The pool's count, as one line: "3 of 10 phones activated, for semper". */
export function poolHtml(answer) {
  const n = Number(answer.devicesActive ?? 0);
  const max = answer.maxDevices;
  const app = esc(appName(answer.app));
  if (max == null || max >= UNCOUNTED) {
    return `<strong>${n} ${n === 1 ? "phone" : "phones"}</strong> activated on this licence, ` +
      `for ${app}, with no limit on how many.`;
  }
  return `<strong>${n} of ${Number(max)}</strong> phones activated on this licence, for ${app}.`;
}

/** One active phone's row: the member, the phone, when it was activated, and Deactivate. */
export function phoneRow(phone) {
  const who = esc(phone.email || phone.uid);
  const model = esc(phone.deviceModel || "");
  const by = ACTIVATED_BY[phone.activatedBy];
  return `
    <tr>
      <td>${who}</td>
      <td>${model || '<span class="muted">—</span>'}</td>
      <td>${esc(when(phone.activatedAt))}${by ? ` <span class="muted">${esc(by)}</span>` : ""}</td>
      <td class="actions">
        <button class="danger" data-phone="${esc(phone.deviceId)}" data-email="${who}" data-model="${model}"
                aria-label="Deactivate ${who}'s phone${model ? ` (${model})` : ""}">Deactivate</button>
      </td>
    </tr>`;
}

/**
 * Wire the Phones card. `licenseId()` is the open licence's id ("" when none
 * is); `report(message, isError)` shows what happened; `reload()` reads the
 * whole licence again after a change (roster, counts and this card, since a
 * deactivated phone also leaves the roster's Phone column). Returns
 * `{ show(lic, current), hide() }`: `show` reads and draws the phones of the
 * licence just rendered, dropping an answer once `current()` is false.
 */
export function wirePhones({ card, pool, help, rows, licenseId, report, reload, domain = () => "" }) {
  let showing = null; // { lic, current } of the last show, for Retry

  function hide() {
    showing = null;
    card.hidden = true;
    pool.innerHTML = "";
    rows.innerHTML = "";
  }

  async function show(lic, current = () => true) {
    // A licence from before the split locks one phone per member; the
    // roster already shows it, and there is no pool to count.
    if (!lic || !lic.app) {
      hide();
      return;
    }
    showing = { lic, current };
    const id = licenseId();
    card.hidden = false;
    pool.innerHTML = '<span class="skeleton line"></span>';
    help.textContent =
      "Members activate a phone themselves, in the app. Deactivating one signs it out " +
      "of the member's account and frees its place in the count at once; every analysis " +
      "on it stays on the phone." +
      (domain() ? ` Only members at ${domain()} are listed.` : "");
    rows.innerHTML = placeholderRows(4, 2);
    try {
      // Literal paths, so check_console.py holds them to the gateway.
      const answer = await api(`/v1/institutions/licenses/${encodeURIComponent(id)}/devices`);
      if (!current()) return;
      if (answer.model === "lock") {
        hide();
        return;
      }
      pool.innerHTML = poolHtml({ app: lic.app, ...answer });
      const phones = (answer.devices || []).filter((p) => !p.status || p.status === "active");
      rows.innerHTML = phones.map(phoneRow).join("") ||
        '<tr><td colspan="4" class="muted">No phone is activated on this licence yet.</td></tr>';
    } catch (e) {
      if (!current()) return;
      pool.innerHTML = "";
      rows.innerHTML = retryRow(4, `Could not load the phones: ${explain(e, PHONE_ERRORS)}`);
    }
  }

  // The phones whose Deactivate is under way, from the first click until the
  // DELETE has answered. A second click, or a redrawn row's fresh button,
  // opens no second card and sends no second DELETE, whose "no longer
  // activated" would land on the first one's success line.
  const busy = new Set();

  async function deactivate(button) {
    const id = licenseId();
    const deviceId = button.dataset.phone;
    if (!id || busy.has(deviceId)) return;
    busy.add(deviceId);
    const refocus = holdFocus(button);
    button.disabled = true;
    try {
      await deactivatePhone(id, deviceId, button.dataset.email, button.dataset.model);
    } finally {
      busy.delete(deviceId);
      button.disabled = false;
      refocus();
    }
  }

  async function deactivatePhone(id, deviceId, who, model) {
    const phone = model || "this phone";
    if (!(await confirmInPage({
      title: `Deactivate ${who}'s phone`,
      message: `Deactivate ${phone} for ${who}?\n\n` +
        "It is signed out of their semper account, and its place in this licence's " +
        "count frees at once. Every analysis on the phone stays on it.",
      confirm: "Deactivate",
    }))) return;
    report("Deactivating…");
    try {
      await api(`/v1/institutions/licenses/${encodeURIComponent(id)}/devices/${encodeURIComponent(deviceId)}`,
        { method: "DELETE" });
    } catch (e) {
      // Signed out meanwhile: the line that says to sign in again stays.
      if (sessionEnded(e) || sessionOver()) return;
      const message = explain(e, PHONE_ERRORS, (text) => `Could not deactivate the phone: ${text}`);
      if (e.code === "activation_not_found") await reload();
      report(message, true);
      return;
    }
    await reload();
    if (sessionOver()) return;
    report(`${who}'s phone is deactivated and signed out. Its place in the count is free.`);
    // Its row is gone: carry on from the next phone, when there is one.
    const next = rows.querySelector("button[data-phone]");
    if (next) next.focus();
  }

  rows.addEventListener("click", (ev) => {
    const btn = ev.target.closest("button");
    if (!btn) return;
    if (btn.dataset.phone) deactivate(btn);
    else if ("retry" in btn.dataset && showing) show(showing.lic, showing.current);
  });

  return { show, hide };
}
