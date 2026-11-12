// Run: node --test "firebase-hosting/tests/*.test.mjs"
// console/list-tools.js on its own: the orderings, the text match, the chips'
// markup and the count line every list's toolbar shows.
import { test } from "node:test";
import assert from "node:assert/strict";
import "./harness.mjs";
import { FakeElement } from "./fake-dom.mjs";

const { sortList, byText, byNumber, matchesText, drawChips, onChip, countLine, wireSort, menuPlace, floatMenus } =
  await import("../public/console/list-tools.js");

test("sortList orders by the chosen key either way, keeps ties in the order read, and leaves an unknown key alone", () => {
  const list = [{ n: 2, id: "a" }, { n: 1, id: "b" }, { n: 2, id: "c" }];
  const by = { n: (a, b) => byNumber(a.n, b.n) };
  assert.deepEqual(sortList(list, { key: "n", dir: 1 }, by).map((x) => x.id), ["b", "a", "c"]);
  assert.deepEqual(sortList(list, { key: "n", dir: -1 }, by).map((x) => x.id), ["a", "c", "b"]);
  assert.deepEqual(sortList(list, { key: "gone", dir: 1 }, by).map((x) => x.id), ["a", "b", "c"]);
  assert.deepEqual(sortList(list, null, by).map((x) => x.id), ["a", "b", "c"]);
  assert.notEqual(sortList(list, null, by), list, "a new array, never the one passed in");
});

test("byText reads numbers in text as numbers and ignores case; byNumber puts a missing number last", () => {
  assert.deepEqual(["Item 10", "item 2", "Item 1"].sort(byText), ["Item 1", "item 2", "Item 10"]);
  assert.equal(byText(null, undefined), 0);
  assert.deepEqual([3, NaN, 1, null].sort(byNumber), [1, 3, NaN, null]);
});

test("matchesText finds the lower-cased text in any field; an empty search matches everything", () => {
  assert.equal(matchesText(["Ana@Uni.edu", null], "uni.edu"), true);
  assert.equal(matchesText([undefined, 42], "42"), true);
  assert.equal(matchesText(["x"], "y"), false);
  assert.equal(matchesText([], ""), true);
});

test("countLine says how many are shown, how many match, and when more are not read yet", () => {
  assert.equal(countLine({ shown: 0, total: 0 }), "");
  assert.equal(countLine({ shown: 3, total: 3 }), "Showing 3 of 3.");
  assert.equal(countLine({ shown: 1, total: 3, filtered: true, noun: "phones" }), "1 of 3 phones match.");
  assert.equal(countLine({ shown: 0, total: 0, more: true }), "Showing 0 of 0 loaded.");
});

test("drawChips leaves out an empty chip unless it is the one chosen or always shown, and escapes its words", () => {
  const box = new FakeElement("div");
  drawChips(box, [
    { value: "all", label: "All", count: 0, always: true },
    { value: "a", label: "<b>A</b>", count: 2 },
    { value: "b", label: "B", count: 0 },
    { value: "c", label: "C", count: 0 },
  ], "c");
  const chips = box.querySelectorAll("button");
  assert.deepEqual(chips.map((b) => b.dataset.chip), ["all", "a", "c"]);
  assert.deepEqual(chips.map((b) => b.getAttribute("aria-pressed")), ["false", "false", "true"]);
  assert.equal(box.querySelectorAll("b").length, 0, "a label is text");
  let picked = "";
  onChip(box, (value) => { picked = value; });
  chips[1].click();
  assert.equal(picked, "a");
  box.click();
  assert.equal(picked, "a", "a click beside the chips picks nothing");
});

test("wireSort with no heading sorts nothing and does not throw", () => {
  const sort = wireSort(null, () => {});
  assert.deepEqual(sort.order(), { key: "", dir: 1 });
  sort.reset();
  assert.deepEqual(sort.order(), { key: "", dir: 1 });
});

test("menuPlace puts a menu under its button, right edges lined up, above it near the bottom, and inside the window", () => {
  const view = { width: 1000, height: 800 };
  const menu = { width: 180, height: 150 };
  assert.deepEqual(menuPlace({ top: 100, bottom: 130, right: 900 }, menu, view), { top: 134, left: 720 });
  assert.deepEqual(menuPlace({ top: 700, bottom: 730, right: 900 }, menu, view), { top: 546, left: 720 },
    "no room below: above the button");
  assert.deepEqual(menuPlace({ top: 20, bottom: 50, right: 100 }, menu, { width: 1000, height: 120 }), { top: 54, left: 8 },
    "no room either way: below, where it can be scrolled to; never past the left edge");
  assert.deepEqual(menuPlace({ top: 100, bottom: 130, right: 1200 }, menu, view), { top: 134, left: 812 },
    "never past the right edge");
});

/** A small stand-in for the DOM floatMenus touches: listeners, a details with its summary and menu. */
function menuStub(name) {
  const target = () => ({
    listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); },
    fire(type, ev) { for (const fn of this.listeners[type] || []) fn(ev); },
  });
  const summary = {
    focused: false,
    focus() { this.focused = true; },
    getBoundingClientRect: () => ({ top: 100, bottom: 130, right: 900 }),
  };
  const menu = { offsetWidth: 180, offsetHeight: 150, style: {} };
  const details = {
    name, open: false, summary, menu,
    matches: (s) => s === "details.more",
    querySelector: (s) => (s === ".menu" ? menu : summary),
    contains: (t) => t === summary || t === menu,
  };
  return { target, details };
}

test("floatMenus places a menu as it opens, keeps one open, and closes it on a click elsewhere, Escape, scroll or resize", () => {
  const { target, details: a } = menuStub("a");
  const { details: b } = menuStub("b");
  const root = target();
  const win = Object.assign(target(), { innerWidth: 1000, innerHeight: 800 });
  floatMenus(root, win);
  const open = (d) => {
    d.open = true;
    root.fire("toggle", { target: d });
  };
  open(a);
  assert.deepEqual(a.menu.style, { top: "134px", left: "720px" });
  open(b);
  assert.equal(a.open, false, "one menu at a time");
  root.fire("click", { target: b.summary });
  assert.equal(b.open, true, "a click inside leaves it to the menu");
  root.fire("click", { target: {} });
  assert.equal(b.open, false, "a click elsewhere closes it");
  open(a);
  root.fire("keydown", { key: "Enter" });
  assert.equal(a.open, true);
  root.fire("keydown", { key: "Escape" });
  assert.equal(a.open, false);
  assert.equal(a.summary.focused, true, "Escape gives focus back to More");
  root.fire("keydown", { key: "Escape" });
  open(a);
  win.fire("scroll", {});
  assert.equal(a.open, false, "a scroll would leave it behind its row");
  open(b);
  win.fire("resize", {});
  assert.equal(b.open, false);
  a.open = false;
  root.fire("toggle", { target: a });
  root.fire("toggle", { target: { matches: () => false } });
  root.fire("toggle", { target: {} });
  b.open = true;
  root.fire("toggle", { target: b });
  b.open = false;
  root.fire("toggle", { target: b });
  root.fire("click", { target: {} });
});

test("floatMenus places nothing for a details without its parts, and runs where the window has no events", () => {
  const root = { listeners: {}, addEventListener(type, fn) { (this.listeners[type] ||= []).push(fn); } };
  floatMenus(root, { innerWidth: 100, innerHeight: 100 });
  const bare = { open: true, matches: () => true, querySelector: () => null };
  for (const fn of root.listeners.toggle) fn({ target: bare });
  assert.equal(bare.open, true);
});
