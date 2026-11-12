/* The operator desk. This module signs in, decides whether the account may
 * see the desk, and routes each licence row's buttons to the card or dialog
 * that handles them; the rest is in the modules imported below.
 */
import { requireSignIn, sessionOver } from "../auth.js";
import { api } from "../api.js";
import { setStatus, whileWaiting } from "../status.js";
import { unfinishedStepUpText } from "../util.js";
import { explain } from "../messages.js";
import { mountSwitcher } from "../switcher.js";
import { floatMenus } from "../list-tools.js";
import { licencesAdministered } from "../roles.js";
import { $, labelOf } from "./state.js";
import { loadLicences, startLicenceLoad, markCurrent } from "./licences.js";
import "./mint.js";
import { openEdit, openConvert } from "./edit.js";
import {
  clearLicenceDevice, showDeviceHistory, loadUsers, startUsersLoad,
} from "./people.js";
import { openVerified } from "./verify.js";
import { revokeLicence, resumeRevoke, deleteLicence, resumeDelete } from "./lifecycle.js";
import { openRoster } from "./roster-card.js";
import { openPhones } from "./phones.js";
import { openUpgrade, applyFromRow } from "./upgrade.js";
import { isTestAccountResume, resumeTestAccount } from "./test-accounts.js";

// Every row's More menu (licences, test accounts) floats over the rows.
floatMenus(document);

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
  // A test-account change that stepped up through Google finishes, or says
  // it was not sent, in its own card's words.
  if (isTestAccountResume(resume)) {
    resumeTestAccount(resume);
    return;
  }
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
    // Signed out meanwhile, by this read or a desk read sent alongside: the
    // line that says to sign in again stays (AE2-105).
    if (sessionOver()) return null;
    setStatus(`Could not check whether ${user.email} is an operator: ${explain(e)}`, true);
    return null;
  }
  // A desk read sent alongside may have ended the session before the role
  // answered: its line stays, and nothing is shown behind Sign in (AE2-105).
  if (sessionOver()) return null;
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
  // The two cards that open under the table keep their row marked.
  if (btn.dataset.roster) {
    markCurrent(btn.dataset.roster);
    openRoster(btn.dataset.roster);
  }
  if (btn.dataset.device) clearLicenceDevice(btn.dataset.device);
  if (btn.dataset.history) showDeviceHistory(btn.dataset.history);
  if (btn.dataset.verify) {
    markCurrent(btn.dataset.verify);
    openVerified(btn.dataset.verify);
  }
  if (btn.dataset.phones) openPhones(btn.dataset.phones);
  if (btn.dataset.upgrade) openUpgrade(btn.dataset.upgrade);
  if (btn.dataset.apply) applyFromRow(btn.dataset.apply);
});
