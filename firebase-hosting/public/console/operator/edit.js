/* The Edit and To-institution dialogs: a licence's terms, and an individual
 * licence carried over to an institution one.
 */
import { api, setStatus, askInPage } from "../auth.js";
import { day, isoDay, emailList, licenceEditPatch } from "../util.js";
import { explain } from "../messages.js";
import { $, findLicence, labelOf, demoCapNote } from "./state.js";
import { showLicence, refreshLicence } from "./licences.js";
import { kindRadios, syncKind, openIssue } from "./mint.js";

// ------------------------------------------------------------------ edit

let editing = null;

export function openEdit(id) {
  const lic = findLicence(id);
  if (!lic) return;
  editing = lic;
  const institution = lic.kind === "institution";
  const demo = lic.mode === "demo";
  $("editName").textContent = labelOf(id);
  $("editExpiry").value = isoDay(lic.expiresAt);
  $("editPerpetual").checked = lic.duration !== "timed";
  $("editGrace").value = lic.graceDays ?? "";
  $("editSupport").value = isoDay(lic.supportUntil);
  $("editCap").value = lic.maxAnalyses ?? "";
  // A demo holder gets the demo allowance whatever the key says, so a cap on
  // one is refused; say why instead of offering it.
  $("editCap").disabled = demo;
  $("editCap").title = demo ? demoCapNote() : "";
  // Nothing in this dialog licenses a Demo account; say what does.
  $("editDemo").hidden = !demo;
  $("editDemoEmail").textContent = lic.emailLock || "the account's address";
  $("editDemoIssue").hidden = !lic.emailLock;
  $("editInstitution").hidden = !institution;
  $("editSeats").value = lic.maxSeats ?? "";
  $("editSeating").value = lic.seating || "assigned";
  $("editAdmins").value = (lic.adminEmails || []).join(", ");
  $("editNote").value = lic.note || "";
  syncEditTerm();
  $("editHint").textContent = "";
  $("editHint").className = "muted";
  // The dialog reports its own outcome; a line left from the last action
  // ("… saved") read as this edit's result beside the dialog's error.
  setStatus("");
  $("editDialog").showModal();
}

/** Never expires and a date are one choice, not two. */
function syncEditTerm() {
  const perpetual = $("editPerpetual").checked;
  $("editExpiry").disabled = perpetual;
  $("editGrace").disabled = perpetual;
}

function editHint(message, isError = true) {
  $("editHint").textContent = message;
  $("editHint").className = isError ? "err" : "muted";
}

$("editPerpetual").addEventListener("change", syncEditTerm);
// Licensing a Demo account is an issue, not an edit: hand its address to the
// Issue form rather than have the operator retype it. The term is still
// theirs to choose, so nothing is sent from here.
$("editDemoIssue").addEventListener("click", () => {
  const email = editing?.emailLock;
  if (!email) return;
  $("editDialog").close();
  for (const radio of kindRadios()) radio.checked = radio.value === "individual";
  syncKind();
  openIssue();
  $("emailLock").value = email;
  $("emailLock").focus();
  setStatus(`Choose the term, then Issue licence: it attaches to ${email} and replaces the Demo key.`);
});
$("editCancel").addEventListener("click", () => $("editDialog").close());
$("editForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const lic = editing;
  if (!lic) return;
  const { patch, shortens, error } = licenceEditPatch(lic, {
    expiry: $("editExpiry").value,
    perpetual: $("editPerpetual").checked,
    graceDays: $("editGrace").value,
    supportUntil: $("editSupport").value,
    maxAnalyses: $("editCap").value,
    capLocked: $("editCap").disabled,
    maxSeats: $("editSeats").value,
    seating: $("editSeating").value,
    adminEmails: $("editAdmins").value,
    note: $("editNote").value,
  });
  if (error) {
    editHint(error);
    return;
  }
  const label = labelOf(lic.id);
  // A downgrade is agreed with the customer, not clicked through: the key
  // has to be typed, as for a revoke.
  if (shortens) {
    // Inside the dialog: it is modal, so a card anywhere else is out of reach.
    const typed = await askInPage({
      title: `Shorten ${label}`,
      message: `This shortens ${label}: its term ends ${patch.expiresAt.slice(0, 10)}` +
        `${lic.duration === "timed" ? ` instead of ${isoDay(lic.expiresAt)}` : " (it was perpetual)"}` +
        ", for everyone on it." +
        `\n\nType ${label} to confirm:`,
      anchor: $("editHint"),
    });
    if (typed !== label) {
      editHint("Not saved: the key was not typed.");
      return;
    }
  }
  $("editSave").disabled = true;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(lic.id)}`, {
      method: "PATCH", body: JSON.stringify(patch),
    });
    $("editDialog").close();
    showLicence(out);
    // Say what the server stored, not what was typed.
    setStatus(`${label} saved — ${out.duration === "timed"
      ? `ends ${day(out.expiresAt)}` : "perpetual"}. Everyone on it has the change.`);
  } catch (e) {
    editHint(editError(e));
  } finally {
    $("editSave").disabled = false;
  }
});

function editError(e) {
  return explain(e, {
    expiry_in_past: "That date has already passed. Ending a licence now is Revoke.",
    expiry_before_current: "That is earlier than the current expiry.",
    license_perpetual: "This licence is perpetual.",
    cap_on_demo_key: demoCapNote(),
    max_seats_below_used:
      "More people are on the roster than that many seats. Remove members first, " +
      "or raise Seats.",
    floating_needs_max_seats: "A floating licence needs a number of seats.",
    institution_only: "Seats, seating and IT contacts are for institution licences.",
  }, (text) => `Not saved: ${text}`);
}

// --------------------------------------------------------------- convert

let converting = null;

export function openConvert(id) {
  const lic = findLicence(id);
  if (!lic) return;
  converting = lic;
  $("convertName").textContent = labelOf(id);
  const email = lic.emailLock || "";
  $("convertDomain").value = email.includes("@") ? email.split("@")[1] : "";
  $("convertAdmins").value = "";
  $("convertSeats").value = "";
  $("convertSeating").value = "assigned";
  $("convertFields").hidden = false;
  $("convertedBox").hidden = true;
  $("convertSave").hidden = false;
  $("convertCancel").textContent = "Cancel";
  $("convertHint").textContent = "";
  $("convertHint").className = "muted";
  $("convertDialog").showModal();
}

$("convertCancel").addEventListener("click", () => $("convertDialog").close());
$("convertForm").addEventListener("submit", async (ev) => {
  ev.preventDefault();
  const lic = converting;
  if (!lic) return;
  const hint = (message) => {
    $("convertHint").textContent = message;
    $("convertHint").className = "err";
  };
  const seats = $("convertSeats").value.trim();
  const body = {
    domainLock: $("convertDomain").value.trim().toLowerCase(),
    adminEmails: emailList($("convertAdmins").value),
    seating: $("convertSeating").value,
    ...(seats ? { maxSeats: Number(seats) } : {}),
  };
  if (!body.domainLock || !body.adminEmails.length) {
    hint("A domain and at least one IT contact are needed.");
    return;
  }
  if (body.seating === "floating" && !seats) {
    hint("A floating licence needs a number of seats.");
    return;
  }
  $("convertSave").disabled = true;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(lic.id)}/convert`, {
      method: "POST", body: JSON.stringify(body),
    });
    $("convertedKey").textContent = out.key;
    $("convertFields").hidden = true;
    $("convertedBox").hidden = false;
    $("convertSave").hidden = true;
    $("convertCancel").textContent = "Done";
    $("convertHint").className = "muted";
    $("convertHint").textContent = out.claimedByUid
      ? "The holder is on the new roster, on the same device."
      : "Nobody had signed in yet: the invitation moved to the new licence.";
    showLicence(out.license);
    refreshLicence(lic.id);
    setStatus(`${labelOf(lic.id)} is now institution licence ${out.license.keyPrefix}.`);
  } catch (e) {
    hint(explain(e, {
      convert_domain_mismatch: "The holder's address is not on that domain.",
      license_not_convertible: "Only an individual licensed key converts.",
      license_revoked: "This licence is revoked.",
    }, (text) => `Not converted: ${text}`));
  } finally {
    $("convertSave").disabled = false;
  }
});
