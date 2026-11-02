/* In-page cards in place of the browser's own dialogs: ask, confirm, tell,
 * confirm-by-typing, and the two second-factor cards (the authenticator code
 * and TOTP enrolment).
 *
 * DOM only. Nothing here loads Firebase or calls the API: `auth.js` drives the
 * second-factor cards, and the pages drive the rest. Not `window.prompt` /
 * `confirm` / `alert`, because some browsers show none of them at all (the
 * Claude desktop app's browser pane answers "prompt() is not supported").
 */
import { statusAnchor } from "./status.js";
import { authErrorText } from "./util.js";

/** The person closed a card instead of answering it. */
export const ERR_CANCELLED = "console_cancelled";

/**
 * A card in the page in place of the browser's own dialogs: a heading, the
 * message (paragraphs split on blank lines; a single line break is kept), an
 * optional text or password box, the confirm button and, unless `cancel` is
 * null, a Cancel button. Resolves what `answer` makes of the confirm (given
 * the box's trimmed text), or null on Cancel.
 *
 * Not `window.prompt` / `confirm` / `alert`, for the reason `codeCard` gives:
 * some browsers show none of them at all. The card goes after `anchor`
 * (default: the status line). The Edit dialog passes an element inside
 * itself, because a modal dialog makes everything outside it unreachable.
 * Every piece of text goes in as a text node, so a licence label or address
 * is never markup. The box is named `label` ("Password", "Licence key"), or
 * the card's title, for a screen reader: it has no visible label of its own.
 *
 * For a keyboard and a screen reader the card is an alertdialog named by its
 * heading and described by its message; Escape answers it as Cancel (a tell
 * card: as Close); and once answered, focus goes back to what had it when the
 * card opened (`restoreFocus`), not to the top of the page.
 */
let cards = 0; // ids for each card's heading and message

function pageCard(kind, {
  title, message, input = false, password = false, label, confirm, cancel = "Cancel", anchor, answer,
}) {
  const card = document.createElement("section");
  card.className = `card ${kind}`;
  card.innerHTML = `
    <h2></h2>
    <div class="lines"></div>
    <div class="row">
      <input size="24" />
      <button type="button" class="confirm"></button>
      <button type="button" class="secondary cancel"></button>
    </div>`;
  const heading = card.querySelector("h2");
  heading.textContent = title;
  const lines = card.querySelector(".lines");
  cards += 1;
  heading.id = `card-title-${cards}`;
  lines.id = `card-message-${cards}`;
  card.setAttribute("role", "alertdialog");
  card.setAttribute("aria-labelledby", heading.id);
  card.setAttribute("aria-describedby", lines.id);
  for (const text of String(message).split("\n\n")) {
    const p = document.createElement("p");
    p.className = "muted line";
    p.textContent = text;
    lines.appendChild(p);
  }
  const box = card.querySelector("input");
  if (input) {
    box.type = password ? "password" : "text";
    box.autocomplete = password ? "current-password" : "off";
    box.setAttribute("aria-label", label || title);
  } else {
    box.remove();
  }
  const ok = card.querySelector(".confirm");
  ok.textContent = confirm;
  const no = card.querySelector(".cancel");
  if (cancel == null) no.remove();
  else no.textContent = cancel;

  const opener = document.activeElement;
  const at = anchor || statusAnchor() || document.querySelector("main");
  at.insertAdjacentElement("afterend", card);
  // The desk's status line sits above a long table; bring the card into view.
  if (card.scrollIntoView) card.scrollIntoView({ block: "center" });
  (input ? box : ok).focus();

  return new Promise((resolve) => {
    const done = (value) => { card.remove(); restoreFocus(opener); resolve(value); };
    const yes = () => done(answer(input ? box.value.trim() : ""));
    card.addEventListener("keydown", (e) => {
      if (e.key !== "Escape") return;
      // Inside the Edit dialog, Escape must close the card, not the dialog.
      if (e.preventDefault) e.preventDefault();
      if (e.stopPropagation) e.stopPropagation();
      done(cancel == null ? answer("") : null);
    });
    ok.addEventListener("click", yes);
    if (input) {
      // Enter answers the card; inside the Edit form it must not also submit it.
      box.addEventListener("keydown", (e) => {
        if (e.key !== "Enter") return;
        if (e.preventDefault) e.preventDefault();
        yes();
      });
    }
    if (cancel != null) no.addEventListener("click", () => done(null));
  });
}

/**
 * Give focus back to `opener`, the control that had it when a card opened.
 * Not when it has left the page (its row was redrawn: the page puts focus on
 * the new row itself). A button in a row's More menu, which closed when the
 * button was chosen, cannot take focus; its menu's summary does.
 */
export function restoreFocus(opener) {
  if (!opener || !opener.isConnected || !opener.focus) return;
  const menu = opener.closest && opener.closest("details");
  const target = menu && !menu.open ? menu.querySelector("summary") : opener;
  if (target) target.focus();
}

/**
 * For a button that disables itself while its work runs: a disabled button
 * drops focus to <body>, and a screen reader's place with it (AE2-109).
 * Call before disabling; the function returned, called once the button is
 * usable again, gives focus back if the button had it and nothing has
 * taken it since.
 */
export function holdFocus(button) {
  const had = document.activeElement === button;
  return () => {
    const now = document.activeElement;
    if (had && !button.disabled && (!now || now === document.body)) button.focus();
  };
}

/**
 * Ask for one line of text in the page. Resolves the trimmed answer ("" if
 * left blank), or null on Cancel. `label` names the box (default: `title`).
 */
export function askInPage({ title, message, password = false, label, confirm = "Confirm", anchor } = {}) {
  return pageCard("ask", {
    title, message, input: true, password, label, confirm, anchor, answer: (text) => text,
  });
}

/** A yes / no question in the page. Resolves true on `confirm`, false on Cancel. */
export async function confirmInPage({ title, message, confirm = "Continue", anchor } = {}) {
  const yes = await pageCard("confirm", { title, message, confirm, anchor, answer: () => true });
  return yes === true;
}

/** Something to read, in the page, with one Close button. Resolves once closed. */
export async function tellInPage({ title, message, anchor } = {}) {
  await pageCard("tell", { title, message, confirm: "Close", cancel: null, anchor, answer: () => true });
}

/**
 * Confirm a destructive act by making the operator type the thing's name.
 *
 * A yes/no dialog is muscle memory by the third licence of the afternoon;
 * typing `SEMP-4K2P` is not something a hand does absent-mindedly. Revoking
 * the wrong licence drops a whole institution to demo, so the friction is the
 * point.
 */
export async function confirmByTyping(label, what, { title = "Confirm", anchor, boxLabel } = {}) {
  const typed = await askInPage({
    title,
    message: `This cannot be undone.\n\nType ${label} to ${what}:`,
    label: boxLabel || `Type ${label}`,
    confirm: "Confirm",
    anchor,
  });
  return typed != null && typed === label;
}

/**
 * Give `feedback` (a code card's "not accepted" line, an alert) an id and
 * point each control at it, so the box and Try again are read with why.
 */
function describeBy(feedback, ...controls) {
  cards += 1;
  feedback.id = `card-feedback-${cards}`;
  for (const control of controls) control.setAttribute("aria-describedby", feedback.id);
}

/**
 * Make a second-factor card a dialog as `pageCard`'s are (AE2-108): an
 * alertdialog named by its heading, and Escape calls `cancel`, as its Cancel
 * button would.
 */
function asDialog(card, cancel) {
  const heading = card.querySelector("h2");
  cards += 1;
  heading.id = `card-title-${cards}`;
  card.setAttribute("role", "alertdialog");
  card.setAttribute("aria-labelledby", heading.id);
  card.addEventListener("keydown", (e) => {
    if (e.key !== "Escape") return;
    if (e.preventDefault) e.preventDefault();
    if (e.stopPropagation) e.stopPropagation();
    cancel();
  });
}

/**
 * The authenticator-code card: a code box, Confirm and Cancel under the
 * status line, and after a rejected code a Try again button.
 *
 * Not `window.prompt`: some browsers show no prompt at all (the Claude
 * desktop app's browser pane answers "prompt() is not supported"), and there
 * this was the one step of sign-in nobody could complete. It sits where the
 * enrolment card does and looks like it, so first sign-in and every later one
 * ask for the code the same way.
 *
 * `next()` resolves the trimmed code, or null on Cancel. `rejected()` says the
 * code was not accepted and resolves true on Try again (the box cleared for a
 * fresh code), false on Cancel. Escape answers as Cancel. `close()` removes
 * the card.
 */
export function codeCard() {
  const card = document.createElement("section");
  card.className = "card code";
  card.innerHTML = `
    <h2>Enter your authenticator code</h2>
    <p class="muted">The 6-digit code your authenticator app shows for semper.</p>
    <div class="row">
      <input inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" aria-label="Authenticator code"
             placeholder="6-digit code" size="12" />
      <button class="confirm">Confirm</button>
      <button class="retry" hidden>Try again</button>
      <button class="secondary cancel">Cancel</button>
    </div>
    <p class="muted err feedback" role="alert"></p>`;
  const anchor = statusAnchor() || document.querySelector("main");
  anchor.insertAdjacentElement("afterend", card);
  const input = card.querySelector("input");
  const confirmBtn = card.querySelector(".confirm");
  const retryBtn = card.querySelector(".retry");
  const feedback = card.querySelector(".feedback");
  describeBy(feedback, input, retryBtn);

  // Whoever is waiting on the card, next() or rejected(), and what Cancel
  // answers it with.
  let waiting = null;
  let onCancel = null;
  const answer = (value) => {
    const settle = waiting;
    waiting = null;
    if (settle) settle(value);
  };
  const confirm = () => {
    const code = input.value.trim();
    if (!code || !retryBtn.hidden) return;
    // Being checked: no second submit of the same code.
    input.disabled = true;
    confirmBtn.disabled = true;
    answer(code);
  };
  confirmBtn.addEventListener("click", confirm);
  input.addEventListener("keydown", (e) => { if (e.key === "Enter") confirm(); });
  retryBtn.addEventListener("click", () => answer(true));
  card.querySelector(".cancel").addEventListener("click", () => answer(onCancel));
  asDialog(card, () => answer(onCancel));

  return {
    next() {
      feedback.textContent = "";
      input.value = "";
      input.disabled = false;
      confirmBtn.disabled = false;
      confirmBtn.hidden = false;
      retryBtn.hidden = true;
      input.focus();
      onCancel = null;
      return new Promise((resolve) => { waiting = resolve; });
    },
    rejected() {
      feedback.textContent = "That code was not accepted. Codes change every 30 " +
        "seconds: wait for a fresh one, from the semper entry for this account.";
      confirmBtn.hidden = true;
      retryBtn.hidden = false;
      retryBtn.focus();
      onCancel = false;
      return new Promise((resolve) => { waiting = resolve; });
    },
    close() {
      card.remove();
    },
  };
}

/**
 * The enrolment card: a QR code to scan, the key for anyone who cannot, and
 * the code box. Built here rather than in each page's HTML because every
 * console needs it and the account page is the only one with a place of its
 * own for it. Resolves once Firebase accepts a code; rejects ERR_CANCELLED
 * on Cancel or Escape.
 *
 * The secret and the account go in as text nodes — never through innerHTML —
 * and the SVG is the library's own output for a URI we built, so the one
 * innerHTML below carries nothing a user typed. Without `qrSvg` (qr.js could
 * not be fetched) the key is shown open in place of the picture.
 */
export function enrolInPage(enrolment, account, qrSvg) {
  const card = document.createElement("section");
  card.className = "card enrol";
  card.innerHTML = `
    <h2>Set up two-factor authentication</h2>
    <p class="muted">Scan this with Google Authenticator, Microsoft
      Authenticator, Authy, 1Password or any authenticator app, then enter
      the 6-digit code it shows.</p>
    <div class="qr"></div>
    <details>
      <summary>Can't scan? Enter the key by hand</summary>
      <p class="mono key"></p>
      <p class="muted">Account: <span class="mono account"></span> · Issuer:
        semper · Time-based, 6 digits, 30 seconds</p>
    </details>
    <div class="row">
      <input inputmode="numeric" autocomplete="one-time-code" pattern="[0-9]*" aria-label="Authenticator code"
             placeholder="6-digit code" size="12" />
      <button class="confirm">Confirm</button>
      <button class="secondary cancel">Cancel</button>
    </div>
    <p class="muted err feedback" role="alert"></p>`;
  if (qrSvg) {
    card.querySelector(".qr").innerHTML = qrSvg(enrolment.qrUrl);
  } else {
    card.querySelector(".qr").hidden = true;
    card.querySelector("details").open = true;
  }
  card.querySelector(".key").textContent = enrolment.secret;
  card.querySelector(".account").textContent = account;

  const anchor = statusAnchor() || document.querySelector("main");
  anchor.insertAdjacentElement("afterend", card);
  const input = card.querySelector("input");
  const feedback = card.querySelector(".feedback");
  describeBy(feedback, input);
  input.focus();

  return new Promise((resolve, reject) => {
    const done = (fn, value) => { card.remove(); fn(value); };
    const confirmBtn = card.querySelector(".confirm");
    const confirm = async () => {
      const code = input.value.trim();
      // Being checked: Enter in the box must not send the code again while
      // the first finish() runs (the button alone was disabled).
      if (!code || confirmBtn.disabled) return;
      feedback.textContent = "";
      input.disabled = true;
      confirmBtn.disabled = true;
      try {
        await enrolment.finish(code);
        done(resolve);
      } catch (e) {
        input.disabled = false;
        confirmBtn.disabled = false;
        input.focus();
        feedback.textContent = e.code === "auth/invalid-verification-code"
          ? "That code was not accepted. Check the phone's clock is set automatically, " +
            "then try the next code."
          : `That did not work: ${authErrorText(e)}. Try the next code.`;
      }
    };
    confirmBtn.addEventListener("click", confirm);
    input.addEventListener("keydown", (e) => { if (e.key === "Enter") confirm(); });
    const cancel = () => done(reject, new Error(ERR_CANCELLED));
    card.querySelector(".cancel").addEventListener("click", cancel);
    asDialog(card, cancel);
  });
}
