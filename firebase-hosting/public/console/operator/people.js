/* People and their devices: a licence unbound from its phone, the device
 * moves on record, accounts waiting for approval, and a phone released from
 * an unlicensed account.
 */
import { api, setStatus, esc, confirmInPage, tellInPage } from "../auth.js";
import { seatDevices } from "../util.js";
import { explain } from "../messages.js";
import { $, labelOf } from "./state.js";
import { showLicence } from "./licences.js";

/* ------------------------------------------------------------ licence */

export async function clearLicenceDevice(id) {
  // The support answer to "my phone died". Emptying the lock is the whole
  // change: the licence binds to whichever device signs in next, so
  // nothing is re-issued and nothing is typed at the customer's end.
  if (!(await confirmInPage({
    title: `Unbind ${labelOf(id)}`,
    message: `Unbind ${labelOf(id)} from the device it is on?\n\n` +
      "The next device they sign in on takes it. Their entitlement and " +
      "their analyses are untouched — this is not a revoke.",
    confirm: "Unbind",
  }))) return;
  try {
    const out = await api(`/v1/admin/licenses/${encodeURIComponent(id)}`, {
      method: "PATCH",
      body: JSON.stringify({ clearDeviceLock: true }),
    });
    setStatus(`${labelOf(id)} unbound — the next device to sign in takes it.`);
    showLicence(out);
  } catch (e) {
    setStatus(`Could not unbind: ${e.message}`, true);
  }
}

export async function showDeviceHistory(id) {
  try {
    const data = await api(
      `/v1/admin/licenses/${encodeURIComponent(id)}/device-history?limit=30`,
    );
    const lines = (data.events || []).map((e) => {
      const prev = (e.detail && e.detail.previousDeviceId) || "";
      // The registered phone the clear signed out, when it is not the lock's.
      const released = (e.detail && e.detail.releasedDeviceId) || "";
      const next = (e.detail && e.detail.deviceId) || "";
      // Material Testing's half of the same clear, and which app a bind was for.
      const prevMt = (e.detail && e.detail.previousDeviceIdMaterialTesting) || "";
      const app = (e.detail && e.detail.app) || "";
      const who = e.uid || "—";
      return `${e.ts || "?"}  ${e.action}  by ${who}` +
        (app ? ` (${app})` : "") +
        (prev ? `  left ${prev}` : "") +
        (released && released !== prev ? `  signed out ${released}` : "") +
        (prevMt ? `  left ${prevMt} (Material Testing)` : "") +
        (next ? `  → ${next}` : "");
    });
    // One move a line: the card keeps single line breaks.
    await tellInPage({
      title: "Device history",
      message: lines.length
        ? `Device history for ${labelOf(id)}\n\n${lines.join("\n")}`
        : `No device moves recorded for ${labelOf(id)} yet.`,
    });
  } catch (e) {
    setStatus(`Could not load device history: ${e.message}`, true);
  }
}

/* ---------------------------------------------------------- accounts */

$("reloadUsers").addEventListener("click", loadUsers);

export async function loadUsers() {
  try {
    const data = await api("/v1/admin/users?status=PENDING&limit=50");
    const rows = data.users || [];
    $("userRows").innerHTML = rows.length
      ? rows.map((u) => `
          <tr>
            <td>${esc(u.email || u.uid)}</td>
            <td>${esc(u.displayName || "—")}</td>
            <td class="muted">${seatDevices({
              deviceIdLock: u.activeDeviceId,
              deviceIdLockMaterialTesting: u.activeDeviceIdMaterialTesting,
            }) || "—"}</td>
            <td class="actions">
              <button data-approve="${esc(u.uid)}">Approve</button>
            </td>
          </tr>`).join("")
      : '<tr><td colspan="4" class="muted">Nobody waiting.</td></tr>';
  } catch (e) {
    $("userRows").innerHTML =
      `<tr><td colspan="4" class="err">Could not load: ${esc(e.message)}</td></tr>`;
  }
}

$("releasePhone").addEventListener("click", async () => {
  const email = $("releaseEmail").value.trim();
  if (!email) return setStatus("Enter the account's email.", true);
  const btn = $("releasePhone");
  btn.disabled = true;
  try {
    const out = await api("/v1/admin/device-releases", {
      method: "POST",
      body: JSON.stringify({ email }),
    });
    // One phone per app (ADR-010); a release frees both.
    const released = [
      out.releasedDeviceId,
      out.releasedDeviceIdMaterialTesting && `${out.releasedDeviceIdMaterialTesting} (Material Testing)`,
    ].filter(Boolean);
    setStatus(released.length
      ? `Released ${released.join(" and ")} for ${out.email || email}; the new phone can sign in.`
      : `${out.email || email} had no phone registered; any phone can sign in.`);
    $("releaseEmail").value = "";
  } catch (e) {
    setStatus(`Could not release: ${explain(e, {
      license_device_clear_required: "That account is licensed: use New device on its licence.",
    })}`, true);
  } finally {
    btn.disabled = false;
  }
});

$("userRows").addEventListener("click", async (ev) => {
  const btn = ev.target.closest("button[data-approve]");
  if (!btn) return;
  btn.disabled = true;
  try {
    await api(`/v1/admin/users/${encodeURIComponent(btn.dataset.approve)}/approve`,
              { method: "POST" });
    setStatus("Approved.");
    loadUsers();
  } catch (e) {
    setStatus(`Could not approve: ${e.message}`, true);
    btn.disabled = false;
  }
});
