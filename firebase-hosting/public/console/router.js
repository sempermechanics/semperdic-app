/* The front door. One address to give anybody — a customer, an IT
 * contact, a colleague on the Semper side — that lands each of them
 * somewhere useful without their having to know which of three pages is
 * theirs.
 *
 * Identity decides, and identity is what the backend already knows: the
 * `role` it returns for the caller, and whether any live institution
 * licence names their address. Nothing is inferred from the email domain
 * or remembered in the browser, so an account that changes hands routes
 * correctly the first time.
 */
import { requireSignIn, api, setStatus, esc, whileWaiting } from "./auth.js";
import { licencesAdministered } from "./roles.js";

const $ = (id) => document.getElementById(id);

const DESTINATIONS = {
  operator: {
    href: "operator/",
    title: "Operator",
    blurb: "Issue, extend and revoke licences, drive any institution's " +
           "roster, and approve accounts. Needs two-factor authentication.",
  },
  institution: {
    href: "institution/",
    title: "Institution seats",
    blurb: "Add and remove members, see who is using a seat right now, " +
           "put someone on hold, and let a member move to a new device. " +
           "Needs two-factor authentication.",
  },
  account: {
    href: "account/",
    title: "Your account",
    blurb: "Your licence, your seat, and your saved analyses. " +
           "Needs two-factor authentication.",
  },
};

requireSignIn(async () => {
  $("signedOut").hidden = true;
  try {
    const [me, administered] = await whileWaiting(
      Promise.all([api("/v1/me"), licencesAdministered()]),
      "Finding your dashboard…",
    );
    route(me, administered);
  } catch (e) {
    // /v1/me failing is the one thing this page cannot work around: with
    // no answer about the caller there is nothing to route on. Offer all
    // three rather than guessing at one.
    setStatus(`Could not tell where to send you: ${e.message}`, true);
    offer(["operator", "institution", "account"], {
      title: "Choose a dashboard",
      help: "Each one will say plainly if it is not yours.",
    });
  }
});

function route(me, administered) {
  const targets = [];
  if (me.role === "admin") targets.push("operator");
  if (administered.licenses.length) targets.push("institution");

  if (!administered.certain) {
    setStatus(
      `Could not check whether you administer an institution licence ` +
      `(${administered.why}).`,
      true,
    );
    offer([...targets, "institution", "account"], {
      title: "Choose a dashboard",
      help: "Each one will say plainly if it is not yours.",
    });
    return;
  }

  if (targets.length > 1) {
    setStatus("");
    offer([...targets, "account"], {
      title: `Signed in as ${me.email || "you"}`,
      help: "Your account can open more than one of these.",
    });
    return;
  }

  // Exactly one place to be.
  const only = targets[0] || "account";

  // `?stay=1` asks for the menu instead of the forward, so that a link
  // back here from one console does not bounce straight out of it again.
  if (new URLSearchParams(location.search).has("stay")) {
    setStatus("");
    offer([only, "account"], { title: `Signed in as ${me.email || "you"}`, help: "" });
    return;
  }

  // Deep-link the one licence an IT contact administers, so the common
  // case never asks them to paste an id they were sent months ago.
  const one = administered.licenses.length === 1 ? administered.licenses[0].id : "";
  const href = only === "institution" && one
    ? `institution/?license=${encodeURIComponent(one)}`
    : DESTINATIONS[only].href;
  // replace, not assign: the back button should leave the console, not
  // land on a page that immediately forwards again.
  location.replace(href);
}

/** Render the switcher, in the order given, each destination once. */
function offer(names, { title, help }) {
  $("choicesTitle").textContent = title;
  $("choicesHelp").textContent = help;
  $("links").innerHTML = [...new Set(names)]
    .map((name) => {
      const d = DESTINATIONS[name];
      return `
        <section class="card">
          <h2><a href="${esc(d.href)}">${esc(d.title)}</a></h2>
          <p class="muted">${esc(d.blurb)}</p>
        </section>`;
    })
    .join("");
  $("choices").hidden = false;
}
