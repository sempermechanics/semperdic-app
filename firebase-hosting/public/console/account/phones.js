/* Each app's section on the account page (ADR-033): semper and semper
 * Classroom each hold a licence of their own, and each licence covers the
 * phones activated on it, up to its count.
 *
 * A section shows that app's licence from `/v1/me`'s `licences`, how many
 * phones it covers (an institution's is a pool across the whole licence), a
 * new licence waiting to be activated, and the holder's own phones for the
 * app from `GET /v1/me/devices?app=` (a browser sends no X-App-Id, so the app
 * is named). Activating is done in the app, on the phone; here an activated
 * phone can be deactivated, which frees its slot at once and leaves its
 * analyses on it.
 *
 * Not bound at import: the page module calls `mountPhones` on each load, so
 * the listeners and the load tickets belong to the page that is open.
 */
import { api } from "../api.js";
import { sessionOver } from "../auth.js";
import { confirmInPage, holdFocus } from "../dialogs.js";
import { setStatus, whileWaiting } from "../status.js";
import { esc, when, day, holdsLicence, APP_NAMES } from "../util.js";
import { explain as refusalText, ACCOUNT } from "../messages.js";

const $ = (id) => document.getElementById(id);
const SAID = ACCOUNT.phones;

// Each app's section, keyed by the name `?app=` takes.
const SECTIONS = {
  semper: {
    card: "semperCard", facts: "semperFacts", pending: "semperPending",
    count: "semperCount", list: "semperPhones",
  },
  semperclassroom: {
    card: "classroomCard", facts: "classroomFacts", pending: "classroomPending",
    count: "classroomCount", list: "classroomPhones",
  },
};

/**
 * Wire both sections to the open page. `onModel(app, model)` hears how each
 * app's licence holds its phones once that is known: "activation", "lock",
 * "none", or "" while it is being read (the page's Move buttons follow it).
 * Returns `show(licences)`, which draws each app `/v1/me` names and reads its
 * phones; `reload(app)`, which reads one app's phones again; and `hide()`,
 * for an answer with no `licences` at all.
 */
export function mountPhones({ onModel = () => {} } = {}) {
  let licences = {};
  const loads = {}; // the latest phones load per app; an older answer is dropped

  for (const app of Object.keys(SECTIONS)) {
    // One listener per list: the list is redrawn on every load.
    $(SECTIONS[app].list).addEventListener("click", (e) => {
      if (e.target.closest("button[data-retry]")) return load(app);
      const button = e.target.closest("button[data-phone]");
      if (button) deactivate(app, button);
    });
  }

  function show(all) {
    licences = all || {};
    for (const [app, ids] of Object.entries(SECTIONS)) {
      const lic = licences[app];
      $(ids.card).hidden = !lic;
      if (!lic) {
        onModel(app, "");
        continue;
      }
      renderLicence(app, lic);
      load(app);
    }
  }

  function hide() {
    for (const ids of Object.values(SECTIONS)) $(ids.card).hidden = true;
  }

  async function load(app) {
    const ticket = (loads[app] = (loads[app] || 0) + 1);
    const ids = SECTIONS[app];
    onModel(app, "");
    $(ids.count).textContent = "";
    $(ids.list).innerHTML = `<li class="muted">${esc(SAID.loading)}</li>`;
    try {
      const out = await api(`/v1/me/devices?app=${app}`);
      if (ticket !== loads[app] || sessionOver()) return;
      renderPhones(app, out || {}, licences[app] || {});
      onModel(app, out?.model || "none");
    } catch (e) {
      if (ticket !== loads[app] || sessionOver()) return;
      $(ids.list).innerHTML = `
        <li>
          <span class="phone-name">${esc(SAID.failed(refusalText(e)))}</span>
          <button class="secondary" data-retry>Retry</button>
        </li>`;
    }
  }

  // The phones whose Deactivate is under way, from the first click until the
  // DELETE has answered. A second click opens no second card and sends no
  // second DELETE, whose "no longer activated" would land on the first one's
  // success line.
  const busy = new Set();

  async function deactivate(app, button) {
    const name = APP_NAMES[app];
    const model = button.dataset.model || SAID.unnamed;
    const key = `${app}/${button.dataset.phone}`;
    if (busy.has(key)) return;
    busy.add(key);
    const refocus = holdFocus(button);
    button.disabled = true;
    try {
      if (!(await confirmInPage({
        title: SAID.deactivateLabel(model),
        message: SAID.confirm(name, model),
        confirm: SAID.deactivate,
      }))) {
        button.disabled = false;
        refocus();
        return;
      }
      const phone = encodeURIComponent(button.dataset.phone);
      try {
        await whileWaiting(
          api(`/v1/me/devices/${phone}?app=${app}`, { method: "DELETE" }),
          SAID.working(model),
        );
        setStatus(SAID.done(name, model));
      } catch (e) {
        setStatus(refusalText(e, { activation_not_found: SAID.gone }, SAID.refused), true);
        // Anything but a phone already gone leaves the list as it was.
        if (e?.code !== "activation_not_found") {
          button.disabled = false;
          refocus();
          return;
        }
      }
      load(app);
    } finally {
      busy.delete(key);
    }
  }

  return { show, hide, reload: load };
}

/** The app's licence field by field, and a new licence waiting, if any. */
function renderLicence(app, lic) {
  const ids = SECTIONS[app];
  $(ids.facts).innerHTML = facts(lic, APP_NAMES[app])
    .map(([term, value]) => `<dt>${esc(term)}</dt><dd>${esc(value)}</dd>`).join("");
  const pending = lic.pendingLicence;
  $(ids.pending).hidden = !pending;
  $(ids.pending).textContent = pending ? SAID.pending(pending.prefix || "") : "";
}

/** As the account's own facts (account.js), in the same words, for one app. */
function facts(lic, name) {
  const { labels, modes, kinds, seating } = ACCOUNT;
  const rows = [[labels.mode, modes[lic.mode] || lic.mode || modes.demo]];
  if (!holdsLicence(lic)) return rows.concat([[labels.licence, SAID.noLicence(name)]]);
  rows.push([labels.licence,
    [kinds[lic.kind] || lic.kind, lic.prefix].filter(Boolean).join(" · ") || "—"]);
  if (lic.kind === "institution" && lic.seating) {
    rows.push([labels.seating, seating[lic.seating] || lic.seating]);
  }
  const ends = lic.expiresAt && day(lic.expiresAt);
  rows.push([labels.ends, !ends ? ACCOUNT.noEnd : lic.inGrace ? SAID.endedInGrace(ends) : ends]);
  return rows;
}

/** How many phones the licence covers, then each of the holder's phones. */
function renderPhones(app, out, lic) {
  const ids = SECTIONS[app];
  const name = APP_NAMES[app];
  const active = Number(out.devicesActive) || 0;
  let count;
  if (out.model === "activation") {
    const max = Number(out.maxDevices) || 0;
    count = out.pool ? SAID.pool(active, max) : SAID.count(active, max);
  } else if (out.model === "lock") {
    count = SAID.count(active, 1);
  } else {
    // No phone model: a shared seat is the licence's own way to share it,
    // and anything else is Demo.
    count = lic.seating === "floating" && holdsLicence(lic) ? SAID.shared : SAID.demo(name);
  }
  $(ids.count).textContent = count;
  const phones = Array.isArray(out.devices) ? out.devices : [];
  // Only a licence that runs activations deactivates one phone; a lock
  // licence moves with Move instead.
  const canDeactivate = out.model === "activation";
  $(ids.list).innerHTML = phones.map((d) => phoneLine(d, canDeactivate)).join("") ||
    `<li class="muted">${esc(SAID.none(name))}</li>`;
}

function phoneLine(d, canDeactivate) {
  const model = d.model || SAID.unnamed;
  const state = d.activated
    ? `<span class="pill ok">${esc(SAID.activated(d.activatedAt ? when(d.activatedAt) : ""))}</span>`
    : `<span class="pill off">${esc(SAID.notActivated)}</span>`;
  const button = canDeactivate && d.activated
    ? `<button class="secondary" data-phone="${esc(d.deviceId)}" data-model="${esc(model)}"
              aria-label="${esc(SAID.deactivateLabel(model))}">${esc(SAID.deactivate)}</button>`
    : "";
  return `
    <li>
      <span class="phone-name">
        <span class="phone-model">${esc(model)}</span>
        <span>${state}</span>
      </span>
      ${button}
    </li>`;
}
