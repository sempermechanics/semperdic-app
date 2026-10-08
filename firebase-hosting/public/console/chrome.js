// Page chrome that needs no sign-in. Its own file because the console CSP is
// script-src 'self' — an inline one-liner would be the only script on the
// page that never ran.
import { API_BASE_URL } from "./config.js";

// The footer year.
document.getElementById("year").textContent = String(new Date().getFullYear());

// Open the connection to the API while sign-in is still being checked, so
// the first call does not also wait for DNS and TLS. The address is in
// config.js, substituted at deploy, so it is added here rather than written
// into every page; before substitution it is a placeholder and nothing is
// added. `anonymous` is the mode api() fetches in (no cookies), so the
// browser reuses this connection.
if (API_BASE_URL.startsWith("https://") && document.head) {
  const link = document.createElement("link");
  link.rel = "preconnect";
  link.href = new URL(API_BASE_URL).origin;
  link.crossOrigin = "anonymous";
  document.head.appendChild(link);
}

// On a phone each table row is shown as a card (console.css), and each cell
// is labelled with its column's heading, which CSS cannot look up for
// itself. Rows are redrawn on every load, so each table's body is watched.
function labelCells(table) {
  const heads = [...(table.tHead?.rows[0]?.cells || [])].map((th) => th.textContent.trim());
  for (const body of table.tBodies) {
    for (const row of body.rows) {
      [...row.cells].forEach((cell, i) => {
        if (heads[i] && cell.colSpan === 1) cell.dataset.label = heads[i];
      });
    }
  }
}
if (typeof MutationObserver === "function") {
  for (const table of document.querySelectorAll(".wrap table")) {
    labelCells(table);
    const watch = new MutationObserver(() => labelCells(table));
    for (const body of table.tBodies) watch.observe(body, { childList: true, subtree: true });
  }
}
