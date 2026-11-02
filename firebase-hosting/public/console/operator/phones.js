/* A licence's phones (ADR-033): the phones activated on a licence that names
 * its app, each with Deactivate, and staff's Add phone. Staff reach any
 * licence (`/v1/admin/licenses/{id}/devices`); a staff Deactivate also signs
 * the phone out of its account, and Add activates a phone the holder has
 * registered, within the licence's count or past it on purpose.
 *
 * The dialog is modal, so its confirmation card is anchored inside it, as
 * Edit's typed key is.
 */
import { api } from "../api.js";
import { confirmInPage, holdFocus } from "../dialogs.js";
import { setStatus } from "../status.js";
import { esc, when } from "../util.js";
import { explain } from "../messages.js";
import { $, findLicence, labelOf, appName, capText } from "./state.js";
import { showLicence } from "./licences.js";

/** Sentences for the refusals a licence's phones can meet. */
const SENTENCES = {
  license_not_found: "This licence no longer exists. Refresh the table.",
  license_revoked: "This licence is revoked: it licenses no phone.",
  activation_not_found: "That phone is no longer active on this licence. The list has been read again.",
  device_not_found: "No phone with that id is signed in to this licence's holder for its app. " +
    "Check the id; the holder signs in on the phone first.",
  device_in_use: "That phone is active for another account on this licence.",
  seat_not_found: "That phone's account has no seat on this licence. Add the person on the roster first.",
  license_seat_disabled: "That member's seat is paused. Resume it on the roster first.",
};

/** Who activated a phone, from the activation's `activatedBy`. */
const BY = {
  self: "the holder",
  staff: "semper staff",
  it: "IT",
  legacy: "the app itself (a build without Activate)",
};

/** A phone as staff know it: its model and id, else its id. */
const phoneName = (d) => (d.deviceModel ? `${d.deviceModel} (${d.deviceId})` : d.deviceId);

let open = ""; // the licence whose phones are shown
let phones = []; // its active phones, as last read
let reads = 0; // the latest read; an older answer is not drawn over it

/** Open the phones of licence `id`, and read them. */
export async function openPhones(id) {
  open = id;
  const lic = findLicence(id) || {};
  $("phonesName").textContent = labelOf(id);
  $("phonesApp").textContent = appName(lic.app);
  $("phonesApp").hidden = !lic.app;
  $("phonesAddId").value = "";
  $("phonesOverride").checked = false;
  hint("");
  // The dialog reports its own outcome, as Edit does.
  setStatus("");
  $("phonesDialog").showModal();
  await loadPhones();
}

function hint(message, isError = true) {
  $("phonesHint").textContent = message;
  $("phonesHint").className = message && isError ? "err" : "muted";
}

async function loadPhones() {
  const id = open;
  const ticket = ++reads;
  $("phonesCount").textContent = "Reading the phones…";
  $("phonesList").innerHTML = "";
  try {
    const data = await api(`/v1/admin/licenses/${encodeURIComponent(id)}/devices`);
    if (ticket !== reads || id !== open) return;
    phones = data.devices || [];
    $("phonesCount").textContent = countText(data);
    $("phonesList").innerHTML = phones.length
      ? phones.map(phoneLine).join("")
      : '<li class="muted">No phone is active on this licence.</li>';
    keepRowCount(id, data.devicesActive);
  } catch (e) {
    if (ticket !== reads || id !== open) return;
    phones = [];
    $("phonesCount").textContent = "";
    $("phonesList").innerHTML = `<li class="err">${esc(explain(e, SENTENCES,
      (text) => `Could not read the phones: ${text}`))}</li>`;
  }
}

/** "2 of 3 phones active", and whether the count is a pool. */
function countText(data) {
  const active = Number(data.devicesActive) || 0;
  const pool = data.pool ? ", a pool across the whole licence" : "";
  return `${active} of ${capText(data.maxDevices)} phone${active === 1 ? "" : "s"} active${pool}.`;
}

/** The row's "Phones n/m" follows what was just read. */
function keepRowCount(id, active) {
  const lic = findLicence(id);
  if (lic && Number.isInteger(active) && active !== lic.devicesActive) {
    showLicence({ ...lic, devicesActive: active });
  }
}

/**
 * One active phone: its model, the member it belongs to on an institution
 * licence, its id, when and by whom it was activated, and its Deactivate.
 * The test accounts card's phone list, in the same boxes.
 */
function phoneLine(d) {
  const id = esc(d.deviceId);
  return `
        <li>
          <span class="phone-name">
            <span class="phone-model">${esc(d.deviceModel || "Unknown model")}</span>
            ${d.email ? `<span>${esc(d.email)}</span>` : ""}
            <span class="mono">${id}</span>
            <span class="hint">Activated ${esc(when(d.activatedAt))} by ${esc(BY[d.activatedBy] || d.activatedBy || "—")}</span>
          </span>
          <button class="danger" data-deactivate="${id}"
            aria-label="Deactivate ${esc(phoneName(d))}">Deactivate</button>
        </li>`;
}

$("phonesList").addEventListener("click", (ev) => {
  const btn = ev.target.closest("button[data-deactivate]");
  if (btn) deactivate(btn);
});

// The phones whose Deactivate is under way, from the first click until the
// DELETE has answered. A second click, or a redrawn list's fresh button,
// opens no second card and sends no second DELETE, whose "no longer
// activated" would land on the first one's success line.
const busy = new Set();

async function deactivate(button) {
  const id = open;
  const deviceId = button.dataset.deactivate;
  const key = `${id}/${deviceId}`;
  if (busy.has(key)) return;
  busy.add(key);
  const refocus = holdFocus(button);
  button.disabled = true;
  try {
    await deactivatePhone(id, deviceId);
  } finally {
    busy.delete(key);
    button.disabled = false;
    refocus();
  }
}

async function deactivatePhone(id, deviceId) {
  const phone = phones.find((d) => d.deviceId === deviceId) || { deviceId };
  const name = phoneName(phone);
  if (!(await confirmInPage({
    title: `Deactivate ${name}`,
    message: `Deactivate ${name} on ${labelOf(id)}?\n\n` +
      `It is signed out of ${phone.email || "the holder's account"} and held off for 24 hours, ` +
      "and its place on the licence is free at once. Its analyses stay on the phone.",
    confirm: "Deactivate",
    anchor: $("phonesHint"),
  }))) return;
  try {
    const out = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/devices/${encodeURIComponent(deviceId)}`,
      { method: "DELETE" },
    );
    if (id !== open) return;
    hint(`${name} deactivated${out.released ? " and signed out" : ""}: its place is free. ` +
      `${out.devicesActive} of ${capText(out.maxDevices)} active.`, false);
    await loadPhones();
  } catch (e) {
    if (id !== open) return;
    hint(explain(e, SENTENCES, (text) => `Not deactivated: ${text}`));
    if (e.code === "activation_not_found") await loadPhones();
  }
}

/* ------------------------------------------------------------- add */

$("phonesAdd").addEventListener("click", addPhone);

async function addPhone() {
  const id = open;
  const deviceId = $("phonesAddId").value.trim();
  const override = $("phonesOverride").checked;
  if (!deviceId) {
    hint("Enter the phone's id.");
    return;
  }
  $("phonesAdd").disabled = true;
  try {
    const out = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/devices/${encodeURIComponent(deviceId)}`,
      { method: "POST", body: JSON.stringify({ override }) },
    );
    if (id !== open) return;
    $("phonesAddId").value = "";
    $("phonesOverride").checked = false;
    const past = out.devicesActive > out.maxDevices ? ", past the count" : "";
    hint(out.already
      ? `${deviceId} was already active on this licence.`
      : `${deviceId} added: ${out.devicesActive} of ${capText(out.maxDevices)} active${past}.`, false);
    await loadPhones();
  } catch (e) {
    if (id !== open) return;
    hint(addError(e));
  } finally {
    $("phonesAdd").disabled = false;
  }
}

/** Why a phone was not added; a full licence names its count and the way past it. */
function addError(e) {
  if (e.code === "device_limit_reached") {
    const [active, cap] = String(e.rest || "").split("/");
    const count = cap ? ` (${active} of ${capText(cap)} active)` : "";
    return `Not added: every place on this licence is taken${count}. ` +
      "Deactivate a phone first, or tick Past the count.";
  }
  return explain(e, SENTENCES, (text) => `Not added: ${text}`);
}

$("phonesClose").addEventListener("click", () => $("phonesDialog").close());
// Escape closes a modal dialog without the button: forget which was open, so
// an answer still in flight is not drawn into the next one.
$("phonesDialog").addEventListener("close", () => {
  open = "";
  phones = [];
});
