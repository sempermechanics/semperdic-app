/* What the consoles' tables share past the roster: sortable headings, a row
 * of filter chips, and the line that says how many rows are shown. Each page
 * keeps its own rows and what a row means; this module only orders, narrows
 * and counts them, so the licence table, the test accounts, the phones and
 * the saved analyses all sort and filter the same way the roster does.
 *
 * DOM only through the elements handed in; no Firebase, no API.
 */
import { esc } from "./util.js";

/**
 * Make each `button.sort[data-sort]` in `head` sort its column. A click on
 * the column already sorted turns it round; a click on another sorts that one
 * up. The heading's <th> says which way (aria-sort), as the roster's do.
 * `onChange()` redraws. Returns `{ order, reset }`: `order()` is
 * `{ key, dir }` (key "" while unsorted, dir 1 or -1), `reset()` unsorts.
 */
export function wireSort(head, onChange, start = { key: "", dir: 1 }) {
  let order = { ...start };
  const buttons = head ? Array.from(head.querySelectorAll("button[data-sort]")) : [];
  function mark() {
    for (const button of buttons) {
      const key = button.dataset.sort;
      const way = order.key !== key ? "none" : order.dir > 0 ? "ascending" : "descending";
      button.closest("th").setAttribute("aria-sort", way);
    }
  }
  for (const button of buttons) {
    button.addEventListener("click", () => {
      const key = button.dataset.sort;
      order = { key, dir: order.key === key ? -order.dir : 1 };
      mark();
      onChange();
    });
  }
  mark();
  return {
    order: () => ({ ...order }),
    reset() {
      order = { key: "", dir: 1 };
      mark();
    },
  };
}

/**
 * `list` in `order` by `by[order.key](a, b)`, as a new array; the list as it
 * came while unsorted or sorted by a key `by` does not know. A tie keeps the
 * order the rows came in, so a second sort refines rather than shuffles.
 */
export function sortList(list, order, by) {
  const compare = order && by[order.key];
  if (!compare) return list.slice();
  return list
    .map((item, at) => ({ item, at }))
    .sort((a, b) => compare(a.item, b.item) * order.dir || a.at - b.at)
    .map((x) => x.item);
}

/** Compare two strings as people read them: case and accents aside, "item 2" before "item 10". */
export const byText = (a, b) => String(a ?? "").localeCompare(String(b ?? ""), undefined,
  { numeric: true, sensitivity: "base" });

/** Compare two numbers; a missing one (null, NaN) sorts after every number. */
export function byNumber(a, b) {
  const x = Number.isFinite(a) ? a : Infinity;
  const y = Number.isFinite(b) ? b : Infinity;
  return x === y ? 0 : x < y ? -1 : 1;
}

/** Whether any of `texts` holds `q` (already trimmed and lower-cased); everything matches "". */
export function matchesText(texts, q) {
  if (!q) return true;
  return texts.some((t) => String(t ?? "").toLowerCase().includes(q));
}

/**
 * A row of filter chips in `box`: one `button[data-chip]` per choice, the
 * chosen one pressed (aria-pressed), each with its count. `choices` is
 * `[{ value, label, count }]`; a choice with `count: 0` is left out unless it
 * is the one chosen, so a chip never offers an empty view. `chosen` is the
 * value pressed.
 */
export function drawChips(box, choices, chosen) {
  box.innerHTML = choices
    .filter((c) => c.count || c.value === chosen || c.always)
    .map((c) => `<button type="button" class="chip" data-chip="${esc(c.value)}" aria-pressed="${c.value === chosen}">` +
      `${esc(c.label)} <span class="chip-count">${esc(c.count)}</span></button>`)
    .join("");
}

/** Call `onPick(value)` when a chip in `box` is pressed. */
export function onChip(box, onPick) {
  box.addEventListener("click", (ev) => {
    const chip = ev.target.closest("button[data-chip]");
    if (chip) onPick(chip.dataset.chip);
  });
}

/**
 * Where a row's More menu goes, as { top, left } in viewport pixels: under
 * its button, right edges lined up, or above it when there is no room
 * below; never past a `gap` from the window's edges. `button` is the
 * button's rectangle, `menu` the menu's { width, height }, `view` the
 * window's { width, height }.
 */
export function menuPlace(button, menu, view, gap = 8) {
  const left = Math.max(gap, Math.min(button.right - menu.width, view.width - menu.width - gap));
  const below = button.bottom + 4;
  const above = button.top - menu.height - 4;
  const top = below + menu.height > view.height - gap && above >= gap ? above : below;
  return { top: Math.round(top), left: Math.round(left) };
}

/**
 * Make every `details.more` under `root` a menu that floats over the rows
 * instead of opening in the row and pushing the rows under it down. The
 * panel is fixed to the viewport, so the table's sideways scrolling cannot
 * clip it, and is placed by `menuPlace` when it opens. One menu is open at a
 * time; a click elsewhere, Escape (focus back on More), a scroll or a resize
 * closes it, since a fixed panel would otherwise stay put while its row moved.
 */
export function floatMenus(root, win = window) {
  let open = null;
  const close = () => {
    if (open) open.open = false;
    open = null;
  };
  function place(details) {
    const menu = details.querySelector(".menu");
    const summary = details.querySelector("summary");
    if (!menu || !summary) return;
    const at = menuPlace(summary.getBoundingClientRect(),
      { width: menu.offsetWidth, height: menu.offsetHeight },
      { width: win.innerWidth, height: win.innerHeight });
    menu.style.top = `${at.top}px`;
    menu.style.left = `${at.left}px`;
  }
  // `toggle` does not bubble; it is caught on its way down instead.
  root.addEventListener("toggle", (ev) => {
    const details = ev.target;
    if (!details.matches || !details.matches("details.more")) return;
    if (details.open) {
      if (open && open !== details) open.open = false;
      open = details;
      place(details);
    } else if (open === details) {
      open = null;
    }
  }, true);
  root.addEventListener("click", (ev) => {
    if (open && !open.contains(ev.target)) close();
  });
  root.addEventListener("keydown", (ev) => {
    if (ev.key !== "Escape" || !open) return;
    const summary = open.querySelector("summary");
    close();
    if (summary) summary.focus();
  });
  // Captured, so the table's own sideways scroll closes it too. (The
  // tests' small window has no events.)
  if (win.addEventListener) {
    win.addEventListener("resize", close);
    win.addEventListener("scroll", close, true);
  }
}

/**
 * The line under a list's toolbar: "Showing 12 of 40", or, while a filter
 * narrows it, "12 of 40 match". `noun` names the rows in the plural
 * ("licences"); `more` adds that the server holds rows not read yet.
 */
export function countLine({ shown, total, filtered = false, noun = "", more = false }) {
  if (!total && !more) return "";
  const of = `${shown} of ${total}${noun ? ` ${noun}` : ""}${more ? " loaded" : ""}`;
  return filtered ? `${of} match.` : `Showing ${of}.`;
}
