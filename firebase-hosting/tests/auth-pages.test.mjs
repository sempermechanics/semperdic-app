// The auth continue-URL pages (/finishReset, /finishSignIn): not consoles, so
// not through harness.mjs. finishReset's one script runs here in a bare VM
// context with just the browser surface it touches.
import { test } from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import vm from "node:vm";

const PUBLIC = new URL("../public/", import.meta.url);
const read = (path) => readFileSync(new URL(path, PUBLIC), "utf8");

function runReset(search) {
  const replaced = [];
  const listeners = {};
  const link = { href: "#" };
  const context = {
    location: { search, replace: (url) => replaced.push(url) },
    document: {
      addEventListener: (type, fn) => {
        listeners[type] = fn;
      },
      getElementById: (id) => (id === "continueLink" ? link : null),
    },
  };
  vm.runInNewContext(read("finishReset/reset.js"), context);
  return { replaced, listeners, link };
}

test("a reset link opened in a browser goes on to Firebase's action handler", () => {
  const { replaced } = runReset("?mode=resetPassword&oobCode=abc&apiKey=k");
  assert.deepEqual(replaced, ["/__/auth/action?mode=resetPassword&oobCode=abc&apiKey=k"]);
});

test("without a mode the page stays, and its button carries the same query", () => {
  const { replaced, listeners, link } = runReset("?oobCode=abc");
  assert.deepEqual(replaced, []);
  assert.equal(link.href, "#", "the link is set once the page has parsed, not before");
  listeners.DOMContentLoaded();
  assert.equal(link.href, "/__/auth/action?oobCode=abc");
});

test("no query at all still leaves a working button", () => {
  const { replaced, listeners, link } = runReset("");
  listeners.DOMContentLoaded();
  assert.deepEqual(replaced, []);
  assert.equal(link.href, "/__/auth/action");
});

test("finishReset loads its script by an absolute path, before the body", () => {
  const html = read("finishReset/index.html");
  const head = html.slice(0, html.indexOf("</head>"));
  assert.match(head, /<script src="\/finishReset\/reset\.js"><\/script>/);
  assert.doesNotMatch(html, /<script>/, "no inline script: the page needs no 'unsafe-inline'");
  assert.match(html, /id="continueLink"/);
});

test("finishSignIn is static: no script at all", () => {
  assert.doesNotMatch(read("finishSignIn/index.html"), /<script/);
});
