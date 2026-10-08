/* The operator desk. This module signs in, decides whether the account may
 * see the desk, and routes each licence row's buttons to the card or dialog
 * that handles them; the rest is in the modules imported below.
 */
import { requireSignIn, api, setStatus, whileWaiting } from "../auth.js";
import { unfinishedStepUpText } from "../util.js";
import { mountSwitcher } from "../switcher.js";
import { licencesAdministered } from "../roles.js";
import { $, labelOf } from "./state.js";
import { loadLicences, startLicenceLoad } from "./licences.js";
import "./mint.js";
import { openEdit, openConvert } from "./edit.js";
import {
  clearLicenceDevice, showDeviceHistory, loadUsers, startUsersLoad,
} from "./people.js";
import { openVerified } from "./verify.js";
import { revokeLicence, resumeRevoke, deleteLicence, resumeDelete } from "./lifecycle.js";
import { openRoster } from "./roster-card.js";

requireSignIn(async (user, resume) => {
  $("signedOut").hidden = true;
  // Every read the desk needs goes out together, the role check first:
  // waiting for the role before asking for the licences doubled the time to
  // a filled table. Only staff are shown what comes back; for anyone else
  // the backend refuses the desk's reads, and the refusals are dropped.
  const role = api("/v1/me");
  role.catch(() => {}); // read by operatorMe
  const licences = startLicenceLoad();
  const users = startUsersLoad();
  const administered = licencesAdministered().then((out) => out.licenses);
  const me = await operatorMe(user, whileWaiting(role, "Checking your access…"));
  if (!me) return;
  $("app").hidden = false;
  mountSwitcher("operator", { me, licenses: administered });
  showFactorPill();
  loadUsers(users);
  await loadLicences(licences);
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
}, { showApp: false });

/**
 * The /v1/me answer if this account may see the desk at all, else null.
 * Anyone can be sent here by a link, and the backend refuses every call from
 * a non-operator, so the page used to show a full mint form under a one-line
 * refusal. Now the desk stays hidden and the account is told where it can go
 * instead.
 */
async function operatorMe(user, role) {
  let me;
  try {
    me = await role;
  } catch (e) {
    $("app").hidden = true;
    setStatus(`Could not check whether ${user.email} is an operator: ${e.message}`, true);
    return null;
  }
  if (me.role === "admin") return me;
  $("app").hidden = true;
  $("notOperatorWho").textContent = user.email;
  $("notOperator").hidden = false;
  setStatus("");
  return null;
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

$("licenceRows").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button");
  if (!btn) return;
  if ("retry" in btn.dataset) loadLicences();
  // A choice from a row's More menu closes the menu.
  const menu = btn.closest("details");
  if (menu) menu.open = false;
  if (btn.dataset.edit) openEdit(btn.dataset.edit);
  if (btn.dataset.delete) deleteLicence(btn.dataset.delete);
  if (btn.dataset.convert) openConvert(btn.dataset.convert);
  if (btn.dataset.revoke) revokeLicence(btn.dataset.revoke);
  if (btn.dataset.roster) openRoster(btn.dataset.roster);
  if (btn.dataset.device) clearLicenceDevice(btn.dataset.device);
  if (btn.dataset.history) showDeviceHistory(btn.dataset.history);
  if (btn.dataset.verify) openVerified(btn.dataset.verify);
});
