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

/**
 * A short, readable rendering of an ISO instant in the browser's time zone,
 * with that zone named ("Oct 18, 2026, 05:30 PM GMT+5:30"), or an em dash for
 * null. Every instant the consoles show goes through here, so one page never
 * mixes formats or zones; a calendar day stored in UTC is `day`.
 */
export function when(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "—" : d.toLocaleString(undefined, {
    year: "numeric", month: "short", day: "numeric", hour: "2-digit", minute: "2-digit",
    timeZoneName: "short",
  });
}

/**
 * A licence end as the calendar day it was set to, or an em dash for null.
 *
 * Licence ends are stored as the last second of the chosen day in UTC
 * (`YYYY-MM-DDT23:59:59Z`), so they are read back in UTC. `when` renders the
 * local time instead, and east of UTC that moved the day picked: an expiry set
 * for 30 June showed as 1 July, 05:29. It says "(UTC)", so beside a `when`
 * time in the reader's own zone the two are not read as one clock.
 */
export function day(iso) {
  if (!iso) return "—";
  const d = new Date(iso);
  return Number.isNaN(d.getTime())
    ? "—"
    : `${d.toLocaleDateString(undefined, {
      timeZone: "UTC", year: "numeric", month: "short", day: "numeric",
    })} (UTC)`;
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

/**
 * Whether the `license` block of /v1/me is a real licence attached to the
 * account. A Demo key and a licence revoked out from under the account both
 * have a kind and a prefix, so neither is enough; a backend without `held`
 * is answered from mode.
 */
export function holdsLicence(license) {
  const lic = license || {};
  return lic.held ?? lic.mode === "licensed";
}

/** Whether a floating lease ending at `iso` is still held at `now` (ms). */
export function leaseHeld(iso, now = Date.now()) {
  const end = Date.parse(iso || "");
  return Number.isFinite(end) && end > now;
}

const SEAT_STATUS = {
  active: '<span class="pill ok">active</span>',
  disabled: '<span class="pill warn">paused</span>',
  revoked: '<span class="pill off">removed</span>',
};

/**
 * Each app by its short name. Semper Classroom was Material Testing until
 * 2026-10 (ADR-030), and devices, analyses and audit rows still say
 * `materialtesting`, so both name it.
 */
export const APP_NAMES = {
  semper: "semper",
  semperclassroom: "semper Classroom",
  materialtesting: "semper Classroom",
};

/**
 * Semper Classroom's `name` field from a record or response: the
 * `…MaterialTesting` one it had before ADR-030, and only that one, as the
 * backend reads it (`apps.read_field`). Every backend writes and deletes the
 * old field, and every response this console reads carries it, so it is the
 * current one until TD-330; a `…SemperClassroom` field on its own may be one
 * a backend from before the rename left behind, stale. An audit row keeps
 * the old name for good. Undefined when the old field is unset or empty.
 */
export function classroomField(doc, name) {
  return doc?.[`${name}MaterialTesting`] || undefined;
}

/**
 * The devices a seat or licence is bound to, one per app (ADR-010), as short
 * escaped HTML: "abc123…" for Semper alone, with "Semper Classroom def456…"
 * after it when that app is bound too. "" when neither is.
 */
export function seatDevices(seat) {
  const short = (id) => `${esc(id.slice(0, 10))}…`;
  const parts = [];
  if (seat.deviceIdLock) parts.push(short(seat.deviceIdLock));
  const classroom = classroomField(seat, "deviceIdLock");
  if (classroom) parts.push(`semper Classroom ${short(classroom)}`);
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
 * `device_limit_reached: 1/1` — and a suffix may hold colons of its own (an
 * ISO instant does), so only the first one separates. Pages match on `code`.
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
 * anyone sold instead of one Demo key per account. A search is not a list:
 * it is `licenceSearchBody`, sent as a POST, so the address searched for is
 * never in a URL (TD-244).
 */
export function licenceListPath({ limit, showDemo = false, showRevoked = false, showTest = false, pageToken = "" }) {
  const params = new URLSearchParams({ limit: String(limit) });
  if (showDemo) params.set("include_demo", "true");
  if (!showRevoked) params.set("include_revoked", "false");
  // A staff test account's licences (ADR-027) only when asked for.
  if (showTest) params.set("include_test", "true");
  if (pageToken) params.set("page_token", pageToken);
  return `/v1/admin/licenses?${params}`;
}

/**
 * The JSON body of a licence search (`POST /v1/admin/licenses/search`):
 * `{q, limit?, page_token?, include_demo, include_revoked}`. The text
 * searched for is an address often enough that it belongs in a body, which
 * no access log records, rather than in `?q=`, which every one does. The two
 * flags are the table's toggles, as `licenceListPath` sends them: the
 * backend leaves Demo keys out of a search unless asked, so a search with
 * "Show Demo keys" ticked has to ask.
 */
export function licenceSearchBody({ q, limit, pageToken = "", showDemo = false, showRevoked = false, showTest = false }) {
  const body = { q: String(q ?? "").trim() };
  if (Number.isInteger(limit)) body.limit = limit;
  if (pageToken) body.page_token = pageToken;
  body.include_demo = Boolean(showDemo);
  body.include_revoked = Boolean(showRevoked);
  if (showTest) body.include_test = true;
  return JSON.stringify(body);
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
  const why = reauthFailureText(resume.reauthFailed);
  return `${label} was not ${deleting ? "deleted" : "revoked"}: ${why}. ` +
    `${deleting ? "Delete" : "Revoke"} it again to retry.`;
}

/** Why a step-up's return leg did not finish (`resume.reauthFailed`), in words. */
export function reauthFailureText(reason) {
  return REAUTH_FAILURES[reason] || FIREBASE_ERRORS[reason] ||
    `re-authentication did not finish (ref: ${reason})`;
}

// The Firebase Auth codes a console sign-in, step-up or enrolment can meet,
// in words. Anything else is "an unexpected sign-in error (ref: <code>)".
const FIREBASE_ERRORS = {
  "auth/network-request-failed": "the sign-in service did not answer — check your connection",
  "auth/too-many-requests": "too many attempts — wait a few minutes",
  "auth/user-disabled": "this account has been disabled",
  "auth/invalid-credential": "that password was not accepted",
  "auth/wrong-password": "that password was not accepted",
  "auth/user-mismatch": "that was a different account from the one signed in",
  "auth/invalid-verification-code": "the authenticator code was not accepted",
  "auth/totp-challenge-timeout": "the authenticator code came too late",
  // The challenge a code answers lives a few minutes. SDK 12.4.0 says so
  // with these codes, not totp-challenge-timeout; each used to reach the
  // operator as "an unexpected sign-in error".
  "auth/invalid-multi-factor-session": "the authenticator code came too late",
  "auth/missing-multi-factor-session": "the authenticator code came too late",
  "auth/code-expired": "the authenticator code came too late",
  "auth/requires-recent-login": "your sign-in is too old for that — sign in again",
  "auth/user-token-expired": "your sign-in has expired — sign in again",
  "auth/invalid-user-token": "your sign-in has expired — sign in again",
  "auth/maximum-second-factor-count-exceeded":
    "this account already holds as many authenticators as it can — ask semper support to remove the unused ones",
  "auth/unauthorized-domain": "this address is not set up for sign-in",
  "auth/web-storage-unsupported": "this browser blocks the storage sign-in needs",
  "auth/internal-error": "the sign-in service had a problem — try again",
};

/**
 * A Firebase (or sign-in) failure in words, for "Sign-in failed: …": its
 * code's sentence, else the code as a reference. Never the bare code alone.
 */
export function authErrorText(e) {
  const code = (e && e.code) || errorDetail(e && e.message).code;
  return FIREBASE_ERRORS[code] || `an unexpected sign-in error (ref: ${code})`;
}

// What says this browser's sign-in is over, not merely refused: Firebase can
// no longer refresh the remembered session (its refresh token was revoked,
// the password changed, the account was deleted), or the backend refused the
// ID token itself (401 `invalid_token`). Repeating the action cannot help;
// signing in again does.
const SESSION_ENDED = new Set([
  "auth/user-token-expired",
  "auth/invalid-user-token",
  "auth/user-not-found",
  "invalid_token",
]);

// A lapsed second-factor challenge: it lives a few minutes after the first
// factor. SDK 12.4.0 says so with the session codes, not the
// `totp-challenge-timeout` the console was written against.
const CHALLENGE_EXPIRED = new Set([
  "auth/totp-challenge-timeout",
  "auth/invalid-multi-factor-session",
  "auth/missing-multi-factor-session",
  "auth/code-expired",
]);

/** Whether `e` is a second-factor challenge that lapsed before a code was accepted. */
export function challengeExpired(e) {
  return CHALLENGE_EXPIRED.has(e && e.code);
}

/** Whether `e` means the remembered sign-in is over and only a new one helps. */
export function sessionEnded(e) {
  const code = (e && e.code) || errorDetail(e && e.message).code;
  return SESSION_ENDED.has(code);
}

/* ------------------------------------------------------- test accounts */

/** The test-account limits the backend holds (`admin_test_accounts.py`). */
export const TEST_ACCOUNT = { maxDays: 90, defaultDays: 30, maxDevices: 10, defaultDevices: 3, maxNote: 200 };

/** A whole number in 1..`max` from a form field's text, null for anything else. */
function wholeIn(text, max) {
  const t = String(text ?? "").trim();
  if (!/^\d+$/.test(t)) return null;
  const n = Number(t);
  return n >= 1 && n <= max ? n : null;
}

/** The days an Extend asks for (1..90), or null when the text is not that. */
export function extendDays(text) {
  return wholeIn(text, TEST_ACCOUNT.maxDays);
}

/**
 * The `POST /v1/admin/test-accounts` body for the create form's fields (their
 * text, as typed), as `{ body }`, or `{ error }` saying what to fix. QC
 * accounts always end: days 1..90. A CI account may leave days blank, which
 * is sent as `days: null`, no expiry. Phones at once is 1..10, the backend's
 * default (3) when blank. An empty note is left out.
 */
export function testAccountBody({ email, purpose, days, maxDevices, note }) {
  const address = String(email ?? "").trim();
  if (!/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(address)) {
    return { error: "Enter the test account's email address." };
  }
  if (purpose !== "qc" && purpose !== "ci") return { error: "Choose QC or CI." };
  const body = { email: address, purpose };
  const dayText = String(days ?? "").trim();
  if (purpose === "ci" && !dayText) {
    body.days = null;
  } else {
    body.days = wholeIn(dayText, TEST_ACCOUNT.maxDays);
    if (body.days == null) {
      return { error: purpose === "ci"
        ? "Days is 1 to 90, or blank for a CI account that never ends."
        : "Days is 1 to 90: a QC account always ends." };
    }
  }
  if (String(maxDevices ?? "").trim()) {
    body.maxDevices = wholeIn(maxDevices, TEST_ACCOUNT.maxDevices);
    if (body.maxDevices == null) return { error: "Phones at once is 1 to 10." };
  }
  const text = String(note ?? "").trim();
  if (text.length > TEST_ACCOUNT.maxNote) return { error: "The note is at most 200 characters." };
  if (text) body.note = text;
  return { body };
}

/** When a test account ends, as the desk shows it: its UTC day, or "Never". */
export function testAccountEnds(account) {
  return account && account.expiresAt ? day(account.expiresAt) : "Never";
}

/** The UTC calendar day (YYYY-MM-DD) of a stored instant, or "". */
export function isoDay(iso) {
  if (!iso) return "";
  const d = new Date(iso);
  return Number.isNaN(d.getTime()) ? "" : d.toISOString().slice(0, 10);
}

/** The most rows one roster import may hold (`roster_io.MAX_IMPORT_ROWS`). */
export const MAX_IMPORT_ROWS = 500;

/**
 * How many rows a roster import holds, for the preview before it is sent:
 * the non-blank lines, less a header (a first line with no `@` in it), as
 * `roster_io.parse_import` counts them. A quoted address spanning lines
 * would count twice here; the backend's answer is the real count.
 */
export function importRowCount(text) {
  const lines = String(text ?? "").split(/\r?\n/).filter((line) => line.trim());
  if (!lines.length) return 0;
  return lines[0].includes("@") ? lines.length : lines.length - 1;
}

/**
 * The query string of an audit-log request: the date range when one is set,
 * plus `extra` (`format`, `page_size`, `page_token`). Starts with "?".
 */
export function auditQuery({ since = "", until = "" } = {}, extra = {}) {
  const params = new URLSearchParams();
  if (since) params.set("since", since);
  if (until) params.set("until", until);
  for (const [key, value] of Object.entries(extra)) {
    if (value !== "" && value != null) params.set(key, String(value));
  }
  const text = params.toString();
  return text ? `?${text}` : "";
}

/**
 * The audit log's From / To dates (`YYYY-MM-DD`, as a date input gives them)
 * as instants in the browser's time zone: From's local midnight, and the
 * local midnight after To, so To's day is included. The rows are shown in
 * local time (`when`); a bare date reached the backend as a UTC day, so east
 * of UTC "18 Oct to 18 Oct" listed rows shown as 18 Oct 05:30 to 19 Oct 05:29.
 * Anything not a date is passed on as typed, for the backend to refuse.
 */
export function localDayRange({ since = "", until = "" } = {}) {
  const local = (text, after) => {
    const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(text);
    return m ? new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]) + after).toISOString() : text;
  };
  return { since: local(since, 0), until: local(until, 1) };
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
  // The phone count (ADR-033), on a licence that names its app; `undefined`
  // when the field is not shown. An empty field leaves the count as it is.
  const phones = String(form.maxDevices ?? "").trim();
  if (phones && phones !== String(lic.maxDevices ?? "")) {
    if (!/^\d+$/.test(phones) || Number(phones) < 1) return fail("Phones is a whole number, 1 or more.");
    patch.maxDevices = Number(phones);
  }
  if ((form.note ?? "") !== (lic.note ?? "")) patch.note = form.note ?? "";
  if (!Object.keys(patch).length) return fail("Nothing changed.");
  return { patch, shortens, error: "" };
}

/**
 * Grey placeholder rows for a table that is loading: `count` rows of
 * `columns` cells, the shape of what is coming, so the page does not sit
 * empty or jump when the answer lands. Hidden from screen readers, which
 * hear the status line ("Loading licences…") instead.
 */
export function placeholderRows(columns, count = 3) {
  const row = '<td><span class="skeleton"></span></td>'.repeat(columns);
  return `<tr class="placeholder" aria-hidden="true">${row}</tr>`.repeat(count);
}

/**
 * The row a table shows when its load failed: what went wrong, and a Retry
 * button (`data-retry`) the page wires to that one load.
 */
export function retryRow(columns, message) {
  return `<tr><td colspan="${columns}" class="err">${esc(message)} ` +
    '<button class="secondary" data-retry>Retry</button></td></tr>';
}

/**
 * A claim code as typed, tidied: letters upper-cased, and anything but
 * letters, digits and dashes dropped (spaces, a pasted line break, a stray
 * quote). Character by character, so tidying what lies before the caret
 * says where the caret belongs afterwards. The server canonicalises again;
 * this only keeps the field readable.
 */
export function normaliseClaimCode(text) {
  return String(text ?? "").replace(/[^A-Za-z0-9-]/g, "").toUpperCase();
}

/** The longest claim code the page sends; anything longer is a paste gone wrong. */
export const MAX_CLAIM_CODE = 64;

/**
 * Whether a tidied claim code is shaped like one: groups of letters and
 * digits split by single dashes, at least eight of them in all, and no longer
 * than `MAX_CLAIM_CODE`. A light check, to catch a half-pasted code before
 * it costs an attempt against the rate limit; whether it is a real code is
 * the server's to say.
 */
export function claimCodeShaped(code) {
  const text = String(code ?? "");
  return text.length <= MAX_CLAIM_CODE &&
    /^[A-Z0-9]+(?:-[A-Z0-9]+)*$/.test(text) &&
    text.replace(/-/g, "").length >= 8;
}
