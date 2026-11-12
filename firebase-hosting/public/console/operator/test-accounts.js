/* The Test accounts card (ADR-027): sign-ins for QC staff and CI, created
 * here with a generated password, listed, and per account extended, given a
 * new password, one phone released (TD-314: its place is free, and that
 * phone cannot register for the account again), or ended now.
 *
 * Every change is held to the revoke window (`attested_or_mfa_admin_fresh`),
 * so each one first steps up with `stepUpFresh`, carrying what to finish on
 * the return leg of a Google re-authentication (`resumeTestAccount`), as a
 * revoke does. The password is shown once, in the dialog, and nowhere else:
 * not in the status line, not in storage, never logged, and taken out of the
 * page as the dialog closes. The resume carries no password either: a new
 * one is asked for only after the step-up.
 */
import { stepUpFresh } from "../auth.js";
import { api } from "../api.js";
import { askInPage, confirmInPage, confirmByTyping, ERR_CANCELLED } from "../dialogs.js";
import { setStatus } from "../status.js";
import {
  esc, day, when, placeholderRows, retryRow, reauthFailureText,
  TEST_ACCOUNT, testAccountBody, testAccountEnds, extendDays, APP_NAMES,
} from "../util.js";
import { explain } from "../messages.js";
import { wireSort, sortList, byText, byNumber, matchesText, drawChips, onChip, countLine } from "../list-tools.js";
import { $ } from "./state.js";

/** Sentences for the refusals particular to test accounts. */
const SENTENCES = {
  test_accounts_disabled: "Test accounts are switched off on this server, or that address is " +
    "not on one of its test domains (TEST_ACCOUNT_DOMAINS).",
  email_in_use: "That address already has a semper account or sign-in. A test account " +
    "needs an address nobody has used.",
  device_not_found: "That phone is no longer one of this account's active phones. " +
    "Refresh the list.",
};

const PURPOSES = { qc: "QC", ci: "CI" };

/** A phone as staff know it: its model, else its device id. */
const phoneName = (d) => (d.model ? `${d.model} (${d.deviceId})` : d.deviceId);

/** The accounts last listed, for the email a row's buttons act on. */
let accounts = [];
let loads = 0; // the latest list load; an older answer is not drawn over it
let resumed = false; // one return leg per page load

const emailOf = (uid) => (accounts.find((a) => a.uid === uid) || {}).email || uid;

/* -------------------------------------------------------------- form */

/**
 * CI may run without an end: its days field says so, and starts blank. The
 * licence fields are QC's alone (ADR-033): a CI account keeps one licence
 * for every app that no phone binds, so Tier 3 can rotate emulators.
 */
function syncPurpose() {
  const ci = $("taPurpose").value === "ci";
  $("taDaysHint").textContent = ci ? "(1–90, or blank: never ends)" : "(1–90)";
  $("taDays").placeholder = ci ? "never ends" : "";
  const days = $("taDays").value;
  if (ci && days === String(TEST_ACCOUNT.defaultDays)) $("taDays").value = "";
  if (!ci && !days) $("taDays").value = String(TEST_ACCOUNT.defaultDays);
  $("taQcFields").hidden = ci;
}
$("taPurpose").addEventListener("change", syncPurpose);

/** A Demo QC account has no licence, so no apps and no licensed phones to choose. */
function syncLicence() {
  const none = $("taLicence").value === "none";
  $("taApps").disabled = none;
  $("taLicenceMaxDevices").disabled = none;
}
$("taLicence").addEventListener("change", syncLicence);

/**
 * A QC account's licence fields, added to the body `testAccountBody` built,
 * or `{ error }`: which apps it holds a licence for, or none (Demo), and how
 * many phones each licence may have activated (empty: 1, as the backend).
 */
function qcLicence(body) {
  if (body.purpose !== "qc") return { body };
  const out = { ...body, licence: $("taLicence").value === "none" ? "none" : "individual" };
  if (out.licence === "none") return { body: out };
  out.apps = $("taApps").value;
  const phones = $("taLicenceMaxDevices").value.trim();
  if (phones) {
    const n = Number(phones);
    if (!/^\d+$/.test(phones) || n < 1 || n > TEST_ACCOUNT.maxDevices) {
      return { error: "Licensed phones is 1 to 10." };
    }
    out.licenceMaxDevices = n;
  }
  return { body: out };
}

/** What a created account's licence is, for the status line. */
function licenceSaid(body) {
  if (body.purpose !== "qc") return "approved and Licensed";
  if (body.licence === "none") return "approved, on Demo with no licence";
  const apps = body.apps === "both"
    ? `${APP_NAMES.semper} and ${APP_NAMES.semperclassroom}, a licence each`
    : APP_NAMES[body.apps] || body.apps;
  const phones = body.licenceMaxDevices ?? 1;
  return `approved and Licensed for ${apps} (${phones} activated phone${phones === 1 ? "" : "s"} a licence)`;
}

$("taCreate").addEventListener("click", async () => {
  const { body, error } = testAccountBody({
    email: $("taEmail").value,
    purpose: $("taPurpose").value,
    days: $("taDays").value,
    maxDevices: $("taMaxDevices").value,
    note: $("taNote").value,
  });
  const qc = error ? { error } : qcLicence(body);
  if (qc.error) {
    setStatus(qc.error, true);
    return;
  }
  $("taCreate").disabled = true;
  try {
    await sendCreate(qc.body);
  } finally {
    $("taCreate").disabled = false;
  }
});

async function sendCreate(body) {
  try {
    await stepUpFresh({ action: "testAccountCreate", body }, "create this test account");
    setStatus("Creating the test account…");
    const out = await api("/v1/admin/test-accounts", {
      method: "POST", body: JSON.stringify(body),
    });
    const email = out.email || body.email;
    const ends = out.expiresAt ? `ends ${day(out.expiresAt)}` : "never ends";
    const phones = out.maxDevices ?? body.maxDevices ?? TEST_ACCOUNT.defaultDevices;
    $("taEmail").value = "";
    $("taNote").value = "";
    setStatus(`Test account ${email} created (${PURPOSES[out.purpose || body.purpose]}): ` +
      `${licenceSaid(body)}, ${ends}; up to ${phones} phone${phones === 1 ? "" : "s"} may sign in, for each app. ` +
      "Copy its password now.");
    showPassword(email, out.password);
    loadTestAccounts();
  } catch (e) {
    refused(e, `create ${body.email}`);
  }
}

/* ------------------------------------------------------------ password */

/** Open the shown-once dialog on `password`, for `email`. */
function showPassword(email, password) {
  $("taPasswordEmail").textContent = email;
  $("taPassword").textContent = password || "";
  $("taPasswordDialog").showModal();
}

/** Take the password out of the page: the dialog is its only place. */
function forgetPassword() {
  $("taPassword").textContent = "";
  $("taPasswordEmail").textContent = "";
}

$("taPasswordClose").addEventListener("click", () => {
  forgetPassword();
  $("taPasswordDialog").close();
});
// Escape closes a modal dialog without the button.
$("taPasswordDialog").addEventListener("close", forgetPassword);

$("taCopyPassword").addEventListener("click", () => {
  navigator.clipboard.writeText($("taPassword").textContent)
    .then(() => setStatus("Password copied."))
    .catch(() => setStatus("Copy failed — select and copy it by hand.", true));
});

/* ---------------------------------------------------------------- list */

// Show list / Hide list at the right of the card's heading, where the desk
// keeps what a card does to its whole list. Show reads it afresh; Hide only
// puts it away. Never disabled mid-read, so Hide always works.
$("loadTestAccounts").addEventListener("click", () => {
  if ($("testAccountsWrap").hidden) loadTestAccounts();
  else showList(false);
});

/** Show or hide the list, the button naming what its next click does. */
function showList(shown) {
  $("testAccountsWrap").hidden = !shown;
  $("loadTestAccounts").textContent = shown ? "Hide list" : "Show list";
  $("loadTestAccounts").setAttribute("aria-expanded", String(shown));
}

/** Show the list and read the test accounts (on demand: most visits never look). */
export async function loadTestAccounts() {
  const ticket = ++loads;
  showList(true);
  $("testAccountRows").innerHTML = placeholderRows(5, 2);
  try {
    const data = await api("/v1/admin/test-accounts");
    if (ticket !== loads) return;
    accounts = data.accounts || [];
    drawAccounts();
  } catch (e) {
    if (ticket !== loads) return;
    $("testAccountRows").innerHTML = retryRow(5,
      explain(e, SENTENCES, (text) => `Could not load: ${text}`));
  }
}

/* The list's chips, filter and sort: live accounts first, an ended one
 * (suspended, its data erased, kept for its audit rows) under Ended. */
const VIEWS = [
  { value: "live", label: "Live", test: (a) => a.status === "active", always: true },
  { value: "ended", label: "Ended", test: (a) => a.status !== "active" },
  { value: "all", label: "All", test: () => true },
];
let view = "live";
const SORTS = {
  account: (a, b) => byText(a.email || a.uid, b.email || b.uid),
  // Soonest end first: the accounts about to be erased. One that never ends, last.
  ends: (a, b) => byNumber(Date.parse(a.expiresAt || ""), Date.parse(b.expiresAt || "")),
  status: (a, b) => byText(a.status, b.status),
};
const sort = wireSort($("taHead"), drawAccounts);

$("taFilter").addEventListener("input", drawAccounts);
onChip($("taChips"), (value) => {
  view = value;
  drawAccounts();
  const pressed = $("taChips").querySelector(`button[data-chip="${value}"]`);
  if (pressed) pressed.focus();
});

/** Draw the accounts the chip and the filter leave, in the sort chosen. */
function drawAccounts() {
  const q = $("taFilter").value.trim().toLowerCase();
  const pick = VIEWS.find((v) => v.value === view) || VIEWS[0];
  drawChips($("taChips"), accounts.length
    ? VIEWS.map((v) => ({ ...v, count: accounts.filter(v.test).length })) : [], view);
  const inView = accounts.filter(pick.test);
  const shown = sortList(inView.filter((a) =>
    matchesText([a.email, a.uid, PURPOSES[a.purpose], a.purpose, a.note, a.status], q)), sort.order(), SORTS);
  $("testAccountRows").innerHTML = shown.length
    ? shown.map(accountRow).join("")
    : `<tr><td colspan="5" class="muted">${emptyText(q, inView)}</td></tr>`;
  $("taCount").textContent = countLine({ shown: shown.length, total: inView.length, filtered: Boolean(q) });
}

/** What an empty list says: there are none, none match, or none in this chip. */
function emptyText(q, inView) {
  if (!accounts.length) return "No test accounts.";
  if (q && inView.length) return "No test account matches that filter.";
  return view === "live" ? "No live test accounts. Press Ended to see the others." : "None here.";
}

// The accounts whose details are open under their row, by uid; kept across
// a redraw as the phones' folds are.
const detailsOpen = new Set();

// The accounts whose phones are unfolded, by uid: a reload (after a Release,
// say) draws them unfolded again rather than closing what was being worked on.
const phonesOpen = new Set();

function accountRow(a) {
  const uid = esc(a.uid);
  const live = a.status === "active";
  const phones = a.devices || [];
  const count = `${esc(a.activeDevices ?? 0)} of ${esc(a.maxDevices ?? "—")}`;
  // Each control names the account it acts on, as the roster's do: every
  // row's End now read the same to a screen reader (AE2-106).
  const who = esc(a.email || a.uid);
  // The count folds the phones away, as a row's More menu does its actions:
  // three phones, each with a Release, made one account's row a tall stack.
  const phonesCell = phones.length
    ? `<details class="phones-fold" data-ta-phones="${uid}"${phonesOpen.has(a.uid) ? " open" : ""}>
          <summary aria-label="${count} phones for ${who}">${count}</summary>
          <ul class="phone-list">${phones.map((d) => phoneLine(uid, d)).join("")}</ul>
        </details>`
    : count;
  // The row is what is read most: who, when it ends, its phones, its
  // status. Purpose and note open under it from the address, as a licence's
  // details do from its key. Extend, done most, stays in view; New password
  // and End now, which erases the account, are under More, the destructive
  // one last, as on a licence row.
  const open = detailsOpen.has(a.uid);
  const detailsId = `ta-details-${uid}`;
  return `
    <tr>
      <td class="account"><button type="button" class="row-toggle" data-ta-details="${uid}"
          aria-expanded="${open}" aria-controls="${detailsId}">${who}</button></td>
      <td class="nowrap">${esc(testAccountEnds(a))}</td>
      <td class="phones">${phonesCell}</td>
      <td><span class="pill ${live ? "ok" : "off"}">${esc(a.status || "—")}</span></td>
      <td class="actions">
        <button class="secondary" data-ta-extend="${uid}" aria-label="Extend ${who}">Extend</button>
        <details class="more">
          <summary aria-label="More for ${who}">More</summary>
          <div class="menu">
            <button class="secondary" data-ta-password="${uid}" aria-label="New password for ${who}">New password</button>
            <button class="danger" data-ta-end="${uid}" aria-label="End now: ${who}">End now</button>
          </div>
        </details>
      </td>
    </tr>${open ? accountDetails(a, detailsId) : ""}`;
}

/** What the row leaves out: the purpose, the note, when it was made, its uid. */
function accountDetails(a, detailsId) {
  const facts = [
    ["Purpose", esc(PURPOSES[a.purpose] || a.purpose || "—")],
    ["Note", a.note ? esc(a.note) : '<span class="muted">none</span>'],
    a.createdAt ? ["Created", esc(when(a.createdAt))] : null,
    ["Account id", `<span class="mono">${esc(a.uid)}</span>`],
  ].filter(Boolean);
  return `
    <tr class="row-details" id="${detailsId}">
      <td colspan="5">
        <dl class="facts">${facts.map(([k, v]) => `<dt>${k}</dt><dd>${v}</dd>`).join("")}</dl>
      </td>
    </tr>`;
}

/**
 * One ACTIVE phone of the account (`uid` already escaped), with its Release:
 * a box of its own, the model and its app on the first line, the device id
 * under them, and the button at the end. It used to run as one sentence with
 * the button in the middle of the cell, and two phones read as one paragraph.
 */
function phoneLine(uid, d) {
  const app = `<span class="tag">${esc(APP_NAMES[d.app] || d.app || "—")}</span>`;
  const id = esc(d.deviceId);
  const name = d.model
    ? `<span class="phone-model">${esc(d.model)} ${app}</span>
            <span class="mono">${id}</span>`
    : `<span class="phone-model"><span class="mono">${id}</span> ${app}</span>`;
  return `
        <li>
          <span class="phone-name">
            ${name}
          </span>
          <button class="danger" data-ta-release="${uid}" data-ta-device="${id}"
            aria-label="Release ${esc(phoneName(d))}">Release</button>
        </li>`;
}

$("testAccountRows").addEventListener("click", (ev) => {
  // A click on a phone count fires before the fold toggles: it is about to
  // become the opposite of what it is.
  const fold = ev.target.closest("summary") && ev.target.closest("details[data-ta-phones]");
  if (fold) {
    if (fold.open) phonesOpen.delete(fold.dataset.taPhones);
    else phonesOpen.add(fold.dataset.taPhones);
    return;
  }
  const btn = ev.target.closest("button");
  if (!btn) return;
  // A choice from a row's More menu closes the menu.
  const menu = btn.closest("details.more");
  if (menu) menu.open = false;
  if ("retry" in btn.dataset) loadTestAccounts();
  if (btn.dataset.taDetails) toggleDetails(btn.dataset.taDetails);
  if (btn.dataset.taExtend) extend(btn.dataset.taExtend);
  if (btn.dataset.taPassword) newPassword(btn.dataset.taPassword);
  if (btn.dataset.taRelease) release(btn.dataset.taRelease, btn.dataset.taDevice);
  if (btn.dataset.taEnd) endNow(btn.dataset.taEnd);
});

/** Open or close an account's details, keeping focus on its address. */
function toggleDetails(uid) {
  if (detailsOpen.has(uid)) detailsOpen.delete(uid);
  else detailsOpen.add(uid);
  drawAccounts();
  const again = $("testAccountRows").querySelector(`button[data-ta-details="${uid}"]`);
  if (again) again.focus();
}

/* ------------------------------------------------------------- actions */

async function extend(uid) {
  const email = emailOf(uid);
  const typed = await askInPage({
    title: `Extend ${email}`,
    message: "Days from today, 1 to 90. The account and its licence then end " +
      "that many days from now.",
    label: "Days",
    confirm: "Extend",
  });
  if (typed == null) return;
  const days = extendDays(typed);
  if (days == null) {
    setStatus("Days is 1 to 90 — nothing was changed.", true);
    return;
  }
  await sendExtend(uid, email, days);
}

async function sendExtend(uid, email, days) {
  try {
    await stepUpFresh({ action: "testAccountExtend", uid, email, days }, "extend this test account");
    const out = await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/extend`, {
      method: "POST", body: JSON.stringify({ days }),
    });
    setStatus(out && out.expiresAt
      ? `${email} now ends ${day(out.expiresAt)}.`
      : `${email} extended to ${days} days from today.`);
    loadTestAccounts();
  } catch (e) {
    refused(e, `extend ${email}`);
  }
}

async function newPassword(uid) {
  const email = emailOf(uid);
  if (!(await confirmInPage({
    title: `New password for ${email}`,
    message: `Replace the password of ${email}?\n\n` +
      "The old one stops working at once, and every phone signed in with it " +
      "is signed out. The new one is shown once.",
    confirm: "New password",
  }))) return;
  await sendPassword(uid, email);
}

async function sendPassword(uid, email) {
  try {
    await stepUpFresh({ action: "testAccountPassword", uid, email },
      "reset this test account's password");
    const out = await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/password`, {
      method: "POST",
    });
    setStatus(`New password for ${email}; its phones are signed out. Copy it now.`);
    showPassword(email, out.password);
  } catch (e) {
    refused(e, `reset the password of ${email}`);
  }
}

async function release(uid, deviceId) {
  const email = emailOf(uid);
  const account = accounts.find((a) => a.uid === uid) || {};
  const phone = (account.devices || []).find((d) => d.deviceId === deviceId) || { deviceId };
  const name = phoneName(phone);
  if (!(await confirmInPage({
    title: `Release a phone of ${email}`,
    message: `Release ${name} from ${email}?\n\n` +
      "Its place is free for another phone at once. This phone stops working " +
      "with the account and cannot register for it again; its analyses stay.",
    confirm: "Release",
  }))) return;
  await sendRelease(uid, email, deviceId, phone.app || "");
}

async function sendRelease(uid, email, deviceId, app) {
  try {
    await stepUpFresh({ action: "testAccountDeviceRelease", uid, email, deviceId, app },
      "release this phone");
    const phone = encodeURIComponent(deviceId);
    await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}/devices/${phone}/release`, {
      method: "POST",
    });
    setStatus(`Phone ${deviceId} released from ${email}: its place is free, and that phone ` +
      "cannot register for this account again.");
    loadTestAccounts();
  } catch (e) {
    refused(e, `release phone ${deviceId} from ${email}`);
  }
}

async function endNow(uid) {
  const email = emailOf(uid);
  if (!(await confirmInPage({
    title: `End ${email}`,
    message: `End ${email} now?\n\n` +
      "It is suspended and its phones signed out, its analyses and cloud " +
      "backups are erased, and its sign-in is deleted. Its audit rows are kept.",
  }))) return;
  if (!(await confirmByTyping(email, "end this test account", { title: `End ${email}`, boxLabel: "Test account's email" }))) {
    setStatus("Not ended — the address did not match.");
    return;
  }
  await sendEnd(uid, email);
}

async function sendEnd(uid, email) {
  try {
    await stepUpFresh({ action: "testAccountEnd", uid, email }, "end this test account");
    await api(`/v1/admin/test-accounts/${encodeURIComponent(uid)}`, { method: "DELETE" });
    setStatus(`${email} ended: suspended, its data erased and its sign-in deleted.`);
    loadTestAccounts();
  } catch (e) {
    refused(e, `end ${email}`);
  }
}

/** Say why a change was not made; a cancelled step-up sent nothing. */
function refused(e, what) {
  if (e.message === ERR_CANCELLED) {
    setStatus("Cancelled — nothing was sent.");
    return;
  }
  setStatus(explain(e, SENTENCES, (text) => `Could not ${what}: ${text}`), true);
}

/* --------------------------------------------------------- return leg */

/** What each stashed action does, in words, and how to finish it. */
const RESUMES = {
  testAccountCreate: {
    what: (r) => `create the test account ${r.body.email}`,
    run: (r) => sendCreate(r.body),
    valid: (r) => Boolean(r.body && testAccountBody({
      ...r.body,
      days: r.body.days == null ? "" : String(r.body.days),
      maxDevices: r.body.maxDevices == null ? "" : String(r.body.maxDevices),
    }).body),
  },
  testAccountExtend: {
    what: (r) => `extend ${r.email} to ${r.days} days from today`,
    run: (r) => sendExtend(r.uid, r.email, r.days),
    valid: (r) => Boolean(r.uid && r.email && extendDays(String(r.days))),
  },
  testAccountPassword: {
    what: (r) => `give ${r.email} a new password`,
    run: (r) => sendPassword(r.uid, r.email),
    valid: (r) => Boolean(r.uid && r.email),
  },
  testAccountDeviceRelease: {
    what: (r) => `release phone ${r.deviceId} from ${r.email}`,
    run: (r) => sendRelease(r.uid, r.email, r.deviceId, r.app || ""),
    valid: (r) => Boolean(r.uid && r.email && r.deviceId),
  },
  testAccountEnd: {
    what: (r) => `end ${r.email}`,
    run: (r) => sendEnd(r.uid, r.email),
    valid: (r) => Boolean(r.uid && r.email),
  },
};

/** Whether `resume` (from `requireSignIn`) is a test-account change. */
export function isTestAccountResume(resume) {
  return Boolean(resume && Object.hasOwn(RESUMES, resume.action));
}

/**
 * The return leg of a test-account change that went to Google for a fresh
 * sign-in. The confirmations happened on the way out; one plain one here
 * says what is about to be sent, because a page acting on load without a
 * gesture would act on a stale tab the browser restored. A leg that came
 * back without a fresh sign-in sent nothing, and says so.
 */
export async function resumeTestAccount(resume) {
  if (resumed || !isTestAccountResume(resume)) return;
  resumed = true;
  const step = RESUMES[resume.action];
  if (!step.valid(resume)) return;
  const what = step.what(resume);
  if (resume.reauthFailed) {
    setStatus(`Did not ${what}: ${reauthFailureText(resume.reauthFailed)}. ` +
      "Try it again.", true);
    return;
  }
  if (!(await confirmInPage({
    title: "Re-authenticated",
    message: `Re-authenticated. ${what[0].toUpperCase()}${what.slice(1)} now?`,
    confirm: "Continue",
  }))) {
    setStatus("Cancelled — nothing was sent.");
    return;
  }
  await step.run(resume);
}
