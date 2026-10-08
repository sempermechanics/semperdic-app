/* The Issue card: a new licence, individual or institution. */
import { api, setStatus } from "../auth.js";
import { alreadyLicensedId } from "../util.js";
import { explain } from "../messages.js";
import { $ } from "./state.js";
import { refreshLicence, searchLicences } from "./licences.js";

export const kindRadios = () => document.querySelectorAll('input[name="kind"]');

/** Show the fields of whichever kind is ticked. */
export function syncKind() {
  const institution = [...kindRadios()].some((r) => r.value === "institution" && r.checked);
  $("individualFields").hidden = institution;
  $("institutionFields").hidden = !institution;
}

for (const radio of kindRadios()) radio.addEventListener("change", syncKind);

/** Show the Issue card and bring it into view, the first field focused. */
export function openIssue() {
  $("issueCard").hidden = false;
  if ($("issueCard").scrollIntoView) $("issueCard").scrollIntoView({ block: "start" });
}
$("openIssue").addEventListener("click", () => {
  openIssue();
  const individual = [...kindRadios()].find((r) => r.checked)?.value !== "institution";
  $(individual ? "emailLock" : "domainLock").focus();
});
$("closeIssue").addEventListener("click", () => { $("issueCard").hidden = true; });

$("duration").addEventListener("change", () => {
  const timed = $("duration").value === "timed";
  $("expiresAt").disabled = !timed;
  $("graceDays").disabled = !timed;
  if (!timed) { $("expiresAt").value = ""; $("graceDays").value = ""; }
});

const num = (id) => ($(id).value ? Number($(id).value) : null);
const str = (id) => ($(id).value.trim() || null);

$("mint").addEventListener("click", async () => {
  const kind = document.querySelector('input[name="kind"]:checked').value;
  const timed = $("duration").value === "timed";
  if (timed && !$("expiresAt").value) {
    setStatus("A time-limited licence needs an expiry date.", true);
    return;
  }
  const body = {
    kind,
    duration: $("duration").value,
    // End of the chosen day, UTC — a licence bought "until the 31st"
    // should not stop working on the morning of the 31st.
    expiresAt: timed ? `${$("expiresAt").value}T23:59:59Z` : null,
    graceDays: timed ? num("graceDays") : null,
    maxAnalyses: num("maxAnalyses"),
    note: str("note"),
    ...(kind === "institution"
      ? {
          domainLock: str("domainLock"),
          adminEmails: ($("adminEmails").value || "")
            .split(",").map((s) => s.trim()).filter(Boolean),
          maxSeats: num("maxSeats"),
          seating: $("seating").value,
        }
      : { emailLock: str("emailLock") }),
  };
  for (const k of Object.keys(body)) if (body[k] === null) delete body[k];

  $("mint").disabled = true;
  setStatus("Issuing…");
  try {
    const out = await api("/v1/admin/licenses", {
      method: "POST", body: JSON.stringify(body),
    });
    // Both kinds return the plaintext key, once. An individual one is for
    // support recovery only; delivery is the sign-in. An institution one is
    // what members on the domain can redeem, besides IT's roster.
    if (out.key) {
      $("mintedKey").textContent = out.key;
      $("mintedBox").hidden = false;
    }
    setStatus(...mintOutcome(out, body));
    // Read back rather than show the mint's answer: attaching to an account
    // that already signed in happens after the licence is written.
    if (out.license) refreshLicence(out.license.id);
  } catch (e) {
    const held = alreadyLicensedId(e.message);
    if (held) {
      showHeldLicence(body.emailLock, held);
    } else {
      setStatus(mintError(e), true);
    }
  } finally {
    $("mint").disabled = false;
  }
});

/**
 * What an individual mint actually did, as [message, isError]. The licence
 * exists in every case; what varies is whether it reached the person, and
 * each way it did not is something the operator has to act on.
 */
function mintOutcome(out, body) {
  if (body.kind === "institution") {
    return [`Institution licence issued. People with a verified ${body.domainLock || "domain"} ` +
      "address can redeem the key below, or IT adds them from the roster."];
  }
  const who = body.emailLock || "that address";
  if (out.inviteError === "invite_exists") {
    return [`Issued, but ${who} is already promised another licence — this ` +
      "one will not attach. Revoke whichever of the two is not wanted.", true];
  }
  if (out.inviteError) {
    return [`Issued, but the invite for ${who} failed (${out.inviteError}) — ` +
      "it will not attach at sign-in. The key below still redeems it.", true];
  }
  if (out.claimError === "holder_already_licensed") {
    return [`Issued, but ${who} already holds a live licence, so this one was ` +
      "not attached. Revoke or extend the other one.", true];
  }
  if (out.claimError) {
    return [`Issued, but it could not be attached to ${who} (${out.claimError}). ` +
      "The key below redeems it.", true];
  }
  if (out.claimedByUid) {
    return [`Issued and attached to ${who} — licensed from their next request.`];
  }
  return [`Issued. It attaches when ${who} first signs in.`];
}

/**
 * One licence per person: the backend refused the mint because the address
 * holds or is promised a live licence, and named it. Put that one in front
 * of the operator — renewal is Edit on it, replacing it is revoke first.
 * It may be an institution seat, so the filter is the address, not the id.
 */
async function showHeldLicence(email, id) {
  setStatus(`Not issued: ${email} already has a live licence (shown below). ` +
    "To renew it, use Edit. To replace it, revoke it first, then issue again.", true);
  $("filter").value = email;
  await refreshLicence(id);
  searchLicences();
}

function mintError(e) {
  return explain(e, {
    mfa_required: "Enrol a second factor before issuing licences.",
    rate_limited: "Too many admin calls just now — wait a moment.",
  }, (text) => `Could not issue: ${text}`);
}

$("copyKey").addEventListener("click", () => {
  navigator.clipboard.writeText($("mintedKey").textContent)
    .then(() => setStatus("Key copied."))
    .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
});
