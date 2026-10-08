/* The dashboard switch in the header of every console page.
 *
 * An account with more than one role — Semper staff, an institution's IT
 * contact, and the account holder everyone is — has more than one
 * dashboard, and the front door forwards it to one of them. Each page used
 * to link only to the account page, and only from its "not yours" card, so
 * getting from the desk to the account meant typing the address. Every page
 * now shows one tab per dashboard the account can open, whenever there is
 * more than one.
 *
 * Decided from what the backend says on each load, the same two answers the
 * front door routes on; nothing is remembered in the browser. A page passes
 * the answer it already read, or the read it already started, and the switch
 * asks only for the other (roles.js decides what each answer opens).
 */
import { api, esc } from "./auth.js";
import { licencesAdministered, dashboardsFor } from "./roles.js";

const $ = (id) => document.getElementById(id);

const TABS = {
  operator: { href: "../operator/", title: "Operator" },
  institution: { href: "../institution/", title: "Institution seats" },
  account: { href: "../account/", title: "Your account" },
};

/**
 * Fill and show `#switch` with `current` marked, when the account has more
 * than one dashboard. `known` carries what the page already read (`me`,
 * `licenses`), each as the answer or a promise of it. A failed read hides
 * only what it would have shown; the switch is a convenience and never
 * stops a page.
 */
export async function mountSwitcher(current, known = {}) {
  let me = await known.me;
  let licenses = await known.licenses;
  if (me === undefined) {
    try {
      me = await api("/v1/me", {}, { allowStepUp: false });
    } catch {
      me = null;
    }
  }
  if (licenses === undefined) licenses = (await licencesAdministered()).licenses;

  const names = dashboardsFor(me, licenses);
  if (names.length < 2) return;
  // Deep-link the one licence an IT contact administers, as the front door
  // does, so the tab lands on the roster rather than an empty id box.
  const href = (name) => name === "institution" && licenses.length === 1
    ? `../institution/?license=${encodeURIComponent(licenses[0].id)}`
    : TABS[name].href;
  $("switch").innerHTML = names.map((name) => name === current
    ? `<a href="${esc(href(name))}" aria-current="page">${esc(TABS[name].title)}</a>`
    : `<a href="${esc(href(name))}">${esc(TABS[name].title)}</a>`).join("");
  $("switch").hidden = false;
}
