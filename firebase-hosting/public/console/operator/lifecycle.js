/* Revoke, delete and restore: the changes that need the key typed and a
 * fresh sign-in, and the 30 days in which a delete can be undone.
 */
import {
  api, setStatus, esc, askInPage, confirmInPage, confirmByTyping, stepUpForRevoke, ERR_CANCELLED,
} from "../auth.js";
import { day, daysLeft, placeholderRows, retryRow } from "../util.js";
import { explain } from "../messages.js";
import { $, desk, findLicence, labelOf } from "./state.js";
import { showLicence, renderLicences } from "./licences.js";
import { closeRosterOf } from "./roster-card.js";

// One pending revoke per page load, whatever calls `onReady`.
let resumed = false;

/* ------------------------------------------------------------- revoke */

export async function revokeLicence(id) {
  const label = labelOf(id);
  const lic = findLicence(id) || {};
  const who = lic.kind === "institution"
    ? `every one of the ${lic.seatsUsed ?? 0} people on its roster`
    : "the person holding it";
  if (!(await confirmInPage({
    title: `Revoke ${label}`,
    message: `Revoking ${label} drops ${who} to demo immediately.\n\n` +
      "Their saved analyses are untouched — this withdraws entitlement, " +
      "it does not delete anything.\n\nContinue?",
  }))) return;
  if (!(await confirmByTyping(label, "revoke this licence", { title: `Revoke ${label}` }))) {
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
export async function resumeRevoke(id) {
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
  if (!(await confirmInPage({
    title: `Revoke ${label}`, message: `Re-authenticated. Revoke ${label} now?`, confirm: "Revoke",
  }))) {
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
    closeRosterOf(id);
  } catch (e) {
    if (e.message === ERR_CANCELLED) {
      setStatus("Revoke cancelled.");
      return;
    }
    setStatus(`Could not revoke: ${e.message}`, true);
  }
}

/* ------------------------------------------------------------- delete */

export async function deleteLicence(id) {
  const label = labelOf(id);
  const lic = findLicence(id) || {};
  const live = lic.status !== "revoked";
  const who = lic.kind === "institution"
    ? `every one of the ${lic.seatsUsed ?? 0} people on its roster`
    : "the person holding it";
  if (!(await confirmInPage({
    title: `Delete ${label}`,
    message: `Delete ${label}?\n\n` +
      (live ? `It is revoked first: ${who} drops to demo immediately. ` : "") +
      "It leaves this list and is held under Recently deleted for 30 days, " +
      "then purged. Nobody's saved analyses are touched.",
  }))) return;
  const typed = await askInPage({
    title: `Delete ${label}`,
    message: `Type ${label} to delete this licence:`,
    confirm: "Delete",
  });
  if (typed !== label) {
    setStatus("Delete cancelled — the key did not match.");
    return;
  }
  await sendDelete(id, label);
}

/** The return leg of a delete that went to Google for a fresh sign-in. */
export async function resumeDelete(id) {
  if (resumed) return;
  resumed = true;
  const label = labelOf(id);
  if (!(await confirmInPage({
    title: `Delete ${label}`, message: `Re-authenticated. Delete ${label} now?`, confirm: "Delete",
  }))) {
    setStatus("Delete cancelled.");
    return;
  }
  await sendDelete(id, label);
}

async function sendDelete(id, label) {
  try {
    await stepUpForRevoke({ action: "delete", id });
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, { method: "DELETE" });
    desk.licences = desk.licences.filter((l) => l.id !== id);
    if (desk.searchHits) desk.searchHits = desk.searchHits.filter((l) => l.id !== id);
    renderLicences();
    closeRosterOf(id);
    setStatus(`${label} deleted — restorable under Recently deleted until ${day(out.purgeAt)}.`);
    if (!$("deletedWrap").hidden) loadDeleted();
  } catch (e) {
    if (e.message === ERR_CANCELLED) {
      setStatus("Delete cancelled.");
      return;
    }
    setStatus(explain(e, {
      license_not_found: `${label} no longer exists.`,
      demo_key_not_deletable: "A system Demo key is not deleted; the account would only get another.",
    }, (text) => `Could not delete: ${text}`), true);
  }
}

$("loadDeleted").addEventListener("click", loadDeleted);

async function loadDeleted() {
  $("loadDeleted").textContent = "Refresh";
  $("deletedWrap").hidden = false;
  $("deletedRows").innerHTML = placeholderRows(7, 2);
  $("loadDeleted").disabled = true;
  try {
    const data = await api("/v1/admin/deleted-licenses?limit=50");
    const rows = data.licenses || [];
    $("deletedRows").innerHTML = rows.length
      ? rows.map(deletedRow).join("")
      : '<tr><td colspan="7" class="muted">Nothing deleted in the last 30 days.</td></tr>';
  } catch (e) {
    $("deletedRows").innerHTML = retryRow(7, `Could not load: ${e.message}`);
  } finally {
    $("loadDeleted").disabled = false;
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
  if (ev.target.closest("button[data-retry]")) return loadDeleted();
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
    setStatus(explain(e, {
      deleted_license_purged: "Too late: the 30 days have passed.",
      deleted_license_not_found: "Already restored or purged.",
      license_exists: "A licence with that key exists again.",
    }, (text) => `Could not restore: ${text}`), true);
  }
});
