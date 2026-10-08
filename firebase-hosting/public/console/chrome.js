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
