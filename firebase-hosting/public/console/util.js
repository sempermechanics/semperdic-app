/* Pure helpers shared by the console pages: no DOM, no Firebase, so
 * `node --test` can run them (see util.test.mjs).
 *
 * The roster cells live here because the operator desk and the institution
 * page each had their own copy, and the copies drifted: the desk showed an
 * expired floating lease as "until <past time>" (TD-50). Both pages now render
 * the same four cells and add only their own action buttons.
 */

/** Escape text before it reaches innerHTML. Emails and names come from users. */
export function esc(value) {
  return String(value ?? "").replace(
    /[&<>"']/g,
    (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c],
  );
}

/** A short, readable rendering of an ISO instant, or an em dash for null. */
export function when(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString();
}

/**
 * A licence end as the calendar day it was set to, or an em dash for null.
 *
 * Licence ends are stored as the last second of the chosen day in UTC
 * (`YYYY-MM-DDT23:59:59Z`), so they are read back in UTC. `when` renders the
 * local time instead, and east of UTC that moved the day picked: an expiry set
 * for 30 June showed as 1 July, 05:29.
 */
export function day(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : d.toLocaleDateString(undefined, {
      timeZone: "UTC", year: "numeric", month: "short", day: "numeric",
    });
}

/**
 * Where a licence is in its life: "revoked", "expired" (past its grace too,
 * so it grants nothing), "in grace" (past its end, still fully usable), or
 * its stored status. The stored status never changes when a licence runs out,
 * so a lapsed licence used to show a green "redeemed" pill.
 */
export function licenceState(lic, now = Date.now()) {
  if (lic.status === "revoked") return "revoked";
  const ends = Date.parse(lic.expiresAt || "");
  const graceEnds = Date.parse(lic.graceEndsAt || lic.expiresAt || "");
  if (Number.isFinite(graceEnds) && graceEnds <= now) return "expired";
  if (Number.isFinite(ends) && ends <= now) return "in grace";
  return lic.status || "unused";
}

/** The pill for `licenceState`. */
export function licenceStatePill(lic, now = Date.now()) {
  const state = licenceState(lic, now);
  const tone = { revoked: "off", expired: "off", "in grace": "warn" }[state] || "ok";
  return `<span class="pill ${tone}">${esc(state)}</span>`;
}

/** Whether a floating lease ending at `iso` is still held at `now` (ms). */
export function leaseHeld(iso, now = Date.now()) {
  const end = Date.parse(iso || "");
  return Number.isFinite(end) && end > now;
}

const SEAT_STATUS = {
  active: '<span class="pill ok">active</span>',
  disabled: '<span class="pill warn">on hold</span>',
  revoked: '<span class="pill off">removed</span>',
};

/**
 * The devices a seat or licence is bound to, one per app (ADR-010), as short
 * escaped HTML: "abc123…" for Semper alone, with "Material Testing def456…"
 * after it when that app is bound too. "" when neither is.
 */
export function seatDevices(seat) {
  const short = (id) => `${esc(id.slice(0, 10))}…`;
  const parts = [];
  if (seat.deviceIdLock) parts.push(short(seat.deviceIdLock));
  if (seat.deviceIdLockMaterialTesting) {
    parts.push(`Material Testing ${short(seat.deviceIdLockMaterialTesting)}`);
  }
  return parts.join("; ");
}

/**
 * Member, Status, Seat and Device cells for one seat. The caller appends its
 * own actions cell, which is the only part the two pages do differently.
 */
export function seatCells(seat, now = Date.now()) {
  const status = SEAT_STATUS[seat.status] || esc(seat.status);
  const lease = leaseHeld(seat.leaseExpiresAt, now)
    ? `<span class="pill ok">until ${esc(when(seat.leaseExpiresAt))}</span>`
    : '<span class="pill off">—</span>';
  const device = seatDevices(seat) || "not yet";
  return `
      <td>${esc(seat.email || seat.uid)}</td>
      <td>${status}</td>
      <td>${lease}</td>
      <td class="muted">${device}</td>`;
}

/** The same four cells for a pending invite: nobody holds anything yet. */
export function inviteCells(invite) {
  return `
      <td>${esc(invite.email)}</td>
      <td><span class="pill warn">invited</span></td>
      <td><span class="pill off">—</span></td>
      <td class="muted">joins at first sign-in</td>`;
}

/**
 * A backend error detail split into its code and what follows the code.
 *
 * Some codes carry a value after a colon — `session_quota_exceeded: 25/25 …`,
 * `device_change_too_soon: <ISO instant>` — and an instant has colons of its
 * own, so only the first one separates. Pages match on `code`.
 */
export function errorDetail(detail) {
  const text = String(detail ?? "");
  const at = text.indexOf(":");
  return at < 0
    ? { code: text.trim(), rest: "" }
    : { code: text.slice(0, at).trim(), rest: text.slice(at + 1).trim() };
}

/**
 * The staff desk's licence-list URL. Demo keys and revoked licences are
 * left out by the backend unless asked for, so a page holds the licences
 * anyone sold instead of one Demo key per account.
 */
export function licenceListPath({ limit, showDemo = false, showRevoked = false, pageToken = "", q = "" }) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (showDemo) params.set("include_demo", "true");
  if (!showRevoked) params.set("include_revoked", "false");
  if (q) params.set("q", q);
  else if (pageToken) params.set("page_token", pageToken);
  return `/v1/admin/licenses?${params}`;
}

/**
 * Whether the filter text is something the backend can look up: an email
 * address, a domain, or a key prefix. Those are exact matches on any page;
 * anything else only filters the rows already loaded.
 */
export function searchableLicenceText(text) {
  const t = String(text ?? "").trim();
  if (/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(t)) return true;
  if (/^semp-[a-z0-9]{4}/i.test(t)) return true;
  return /^[a-z0-9-]+(\.[a-z0-9-]+)+$/i.test(t);
}

/**
 * The licence a refused mint names, or "". One licence per person: the
 * backend answers `email_already_licensed: <licence id>` when the address
 * already holds or is promised a live one, so the desk can show it.
 */
export function alreadyLicensedId(code) {
  const m = /^email_already_licensed:\s*([A-Za-z0-9_-]+)$/.exec(String(code ?? "").trim());
  return m ? m[1] : "";
}

/**
 * `list` with `lic` in place of the row with its id, or at the top when it
 * is new. A change refreshes its own row this way; the desk used to reload
 * the first page after every change and drop every page loaded after it.
 */
export function upsertLicence(list, lic) {
  if (!lic || !lic.id) return list;
  const at = list.findIndex((l) => l.id === lic.id);
  if (at < 0) return [lic, ...list];
  const next = list.slice();
  next[at] = { ...list[at], ...lic };
  return next;
}

/**
 * Whole days until a deleted licence is purged, rounded up; 0 once due.
 * The purge itself runs up to a day after this (Firestore TTL), but restore
 * is refused from the date, so the date is what the desk counts to.
 */
export function daysLeft(purgeAt, now = Date.now()) {
  const t = Date.parse(purgeAt ?? "");
  return Number.isNaN(t) ? 0 : Math.max(0, Math.ceil((t - now) / 86400e3));
}

/**
 * How an account can re-authenticate, from Firebase `user.providerData`.
 * Only an account with a password provider can answer a password prompt;
 * asking a Google-only operator for one sent them into a credential error.
 */
export function reauthMethods(providerData) {
  const ids = new Set((providerData || []).map((p) => p && p.providerId));
  return { password: ids.has("password"), google: ids.has("google.com") };
}

// Why the return leg of a step-up redirect did not finish, in the operator's
// words. Keys are the `reauthFailed` values `requireSignIn` hands back.
const REAUTH_FAILURES = {
  cancelled: "the authenticator code was not entered",
  incomplete: "the Google sign-in did not finish",
  "auth/invalid-verification-code": "the authenticator code was not accepted",
  "auth/totp-challenge-timeout": "the authenticator code came too late",
};

/**
 * The status line for a revoke or delete whose Google re-authentication came
 * back without a fresh sign-in. The action was never sent; the page's own
 * loading must not be allowed to leave the operator thinking it was.
 */
export function unfinishedStepUpText(resume, label) {
  const deleting = resume.action === "delete";
  const why = REAUTH_FAILURES[resume.reauthFailed] ||
    `re-authentication failed (${resume.reauthFailed})`;
  return `${label} was not ${deleting ? "deleted" : "revoked"}: ${why}. ` +
    `${deleting ? "Delete" : "Revoke"} it again to retry.`;
}

/** The UTC calendar day (YYYY-MM-DD) of a stored instant, or "". */
export function isoDay(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toISOString().slice(0, 10);
}

/** An address list typed as "a@x, b@x", normalised as the backend stores it. */
export function emailList(text) {
  return [...new Set(String(text ?? "").split(",")
    .map((s) => s.trim().toLowerCase()).filter(Boolean))];
}

/**
 * The PATCH body for what the Edit dialog changed — only the fields that
 * differ from `lic`, since every field sent is a change pushed to every
 * holder. Returns `{ patch, shortens, error }`.
 *
 * `shortens` is an earlier end, or an end put on a perpetual licence: a
 * downgrade. The desk confirms it by typing the key, and the backend takes
 * it only with `allowShorten`, which this sets.
 *
 * A new end already past is refused here, as the backend refuses it
 * (`expiry_in_past`), so the desk does not ask for the typed key first.
 */
export function licenceEditPatch(lic, form, now = Date.now()) {
  const fail = (error) => ({ patch: {}, shortens: false, error });
  const patch = {};
  let shortens = false;
  const timed = lic.duration === "timed";
  const current = isoDay(lic.expiresAt);
  if (form.perpetual) {
    if (timed) patch.perpetual = true;
  } else if (form.expiry) {
    if (!/^\d{4}-\d{2}-\d{2}$/.test(form.expiry)) return fail("Enter the expiry as a date.");
    if (!timed || form.expiry !== current) {
      patch.expiresAt = `${form.expiry}T23:59:59Z`;
      if (Date.parse(patch.expiresAt) <= now) {
        return fail("That date has already passed. Ending a licence now is Revoke.");
      }
      shortens = !timed || form.expiry < current;
      if (shortens) patch.allowShorten = true;
    }
  } else if (timed) {
    return fail("A time-limited licence needs an expiry date, or tick Never expires.");
  }
  const endsAfter = (timed && !patch.perpetual) || Boolean(patch.expiresAt);
  const grace = String(form.graceDays ?? "").trim();
  if (endsAfter && grace !== "" && grace !== String(lic.graceDays ?? "")) {
    if (!/^\d+$/.test(grace) || Number(grace) > 365) return fail("Grace is 0 to 365 days.");
    patch.graceDays = Number(grace);
  }
  const support = String(form.supportUntil ?? "").trim();
  if (support && support !== isoDay(lic.supportUntil)) {
    patch.supportUntil = `${support}T00:00:00Z`;
  }
  if (!form.capLocked) {
    const cap = String(form.maxAnalyses ?? "").trim();
    if (cap !== String(lic.maxAnalyses ?? "")) {
      if (cap === "") patch.clearMaxAnalyses = true;
      else if (!/^\d+$/.test(cap) || Number(cap) < 1) return fail("Analyses per person is a whole number.");
      else patch.maxAnalyses = Number(cap);
    }
  }
  if (lic.kind === "institution") {
    const seats = String(form.maxSeats ?? "").trim();
    if (seats !== String(lic.maxSeats ?? "")) {
      if (!/^\d+$/.test(seats) || Number(seats) < 1) {
        return fail("Seats is a whole number; an existing cap cannot be removed here.");
      }
      patch.maxSeats = Number(seats);
    }
    if (form.seating && form.seating !== (lic.seating || "assigned")) patch.seating = form.seating;
    const admins = emailList(form.adminEmails);
    if (admins.join(",") !== (lic.adminEmails || []).join(",")) {
      if (!admins.length) return fail("An institution licence needs at least one IT contact.");
      patch.adminEmails = admins;
    }
  }
  if ((form.note ?? "") !== (lic.note ?? "")) patch.note = form.note ?? "";
  if (!Object.keys(patch).length) return fail("Nothing changed.");
  return { patch, shortens, error: "" };
}
