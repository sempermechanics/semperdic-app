// The password-reset continue page's only code, kept out of the HTML so the
// page needs no 'unsafe-inline' (scripts/check_console.py checks it). Loaded
// by an absolute src without defer: the page is served at /finishReset and
// /auth/finishReset, and the redirect should happen before the card paints.
"use strict";

// App-not-installed fallback: forward the same query to Firebase's default
// action handler so a browser can still complete the reset.
var query = location.search || "";
if (query.indexOf("mode=") !== -1) {
  location.replace("/__/auth/action" + query);
}

document.addEventListener("DOMContentLoaded", function () {
  document.getElementById("continueLink").href = "/__/auth/action" + query;
});
