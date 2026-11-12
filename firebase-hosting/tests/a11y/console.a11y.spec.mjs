// The consoles in a real browser (Chromium), against a stub API and the
// fake Firebase SDK (stub-site.mjs): axe-core finds no serious or critical
// WCAG 2.1 A/AA violation on any page, at phone, tablet and desktop widths,
// and no page scrolls sideways. AE2-001 (readiness 2026-10-24); the hosted
// pages outside the consoles and the dialog, list and error states, AE2-111
// (readiness 2026-10-28).
//
// Run from firebase-hosting/: npm ci && npx playwright install chromium &&
// npm run test:a11y
import { test, expect } from "@playwright/test";
import AxeBuilder from "@axe-core/playwright";
import { serveSite, stubContext, PERSONAS } from "./stub-site.mjs";

let site;
test.beforeAll(async () => { site = await serveSite(); });
test.afterAll(async () => { await site.close(); });

const WIDTHS = { phone: 360, tablet: 768, desktop: 1280 };

/** Each console page in a state worth reading, and what says it has settled. */
const PAGES = [
  { name: "sign-in, signed out", path: "/console/", persona: "out", ready: 'body[data-auth="out"]' },
  { name: "front door, a choice of dashboards", path: "/console/", persona: "staff", ready: "#choices:not([hidden]) a" },
  { name: "account, licensed", path: "/console/account/", persona: "holder", ready: "#rows tr td" },
  { name: "account, Demo with the claim card", path: "/console/account/", persona: "demo", ready: "#claimCard:not([hidden])" },
  { name: "institution roster", path: "/console/institution/?license=L1", persona: "it", ready: "#rows button[data-act]" },
  { name: "operator desk", path: "/console/operator/", persona: "staff", ready: "#licenceRows button[data-edit]" },
];

/**
 * The hosted pages outside the consoles, with no API behind them: each in
 * the light and the dark scheme, since the two landing pages follow the
 * system's (AE2-101).
 */
const STATIC = [
  { name: "password-reset landing page", path: "/finishReset/", persona: "out", ready: "#continueLink" },
  { name: "email-link sign-in landing page", path: "/finishSignIn/", persona: "out", ready: "h1" },
  // Hosting's answer for any address with no file (AE2-102: no timed redirect).
  { name: "the 404 page", path: "/404.html", persona: "out", ready: "h1" },
  // The legal pages (generated from docs/legal/, held for counsel). The
  // privacy policy's wide table cannot be scrolled by keyboard at 360 px
  // until AE2-103's generator fix is cleared with the text (TD-277).
  { name: "terms of service", path: "/terms/", persona: "out", ready: "h1" },
  { name: "privacy policy", path: "/privacy/", persona: "out", ready: "h1", fixme: { 360: "AE2-103" } },
];

/** Open `path` as `persona` at `width`, and wait until `ready` is on the page. */
async function open(browser, { path, persona, ready }, width, colorScheme = "light") {
  const context = await browser.newContext({ viewport: { width, height: 900 }, colorScheme });
  await stubContext(context, PERSONAS[persona]);
  const page = await context.newPage();
  const errors = [];
  page.on("pageerror", (e) => errors.push(String(e)));
  await page.goto(site.origin + path);
  await page.locator(ready).first().waitFor({ state: "attached", timeout: 10_000 });
  // Status lines and late reads (tiles, approvals) settle.
  await page.waitForLoadState("networkidle");
  return { context, page, errors };
}

/** The serious and critical violations axe finds, one line each. */
async function violations(page) {
  const result = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
    .analyze();
  return result.violations
    .filter((v) => v.impact === "serious" || v.impact === "critical")
    .flatMap((v) => v.nodes.map((n) => `${v.impact} ${v.id}: ${n.target.join(" ")} — ${v.help}`));
}

for (const target of PAGES) {
  for (const [label, width] of Object.entries(WIDTHS)) {
    test(`${target.name} at ${width} px (${label}): no serious or critical axe violation, no sideways scroll`, async ({ browser }) => {
      const { context, page, errors } = await open(browser, target, width);
      expect(await violations(page)).toEqual([]);
      const scroll = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth]);
      expect(scroll[0], "the page does not scroll sideways").toBeLessThanOrEqual(scroll[1]);
      expect(errors, "no uncaught page error").toEqual([]);
      await context.close();
    });
  }
}

for (const target of STATIC) {
  for (const scheme of ["light", "dark"]) {
    for (const [label, width] of Object.entries(WIDTHS)) {
      test(`${target.name}, ${scheme}, at ${width} px (${label}): no serious or critical axe violation, no sideways scroll`, async ({ browser }) => {
        test.fixme(Boolean(target.fixme?.[width]), `open finding ${target.fixme?.[width]}`);
        const { context, page, errors } = await open(browser, target, width, scheme);
        expect(await violations(page)).toEqual([]);
        const scroll = await page.evaluate(() => [document.documentElement.scrollWidth, document.documentElement.clientWidth]);
        expect(scroll[0], "the page does not scroll sideways").toBeLessThanOrEqual(scroll[1]);
        expect(errors, "no uncaught page error").toEqual([]);
        await context.close();
      });
    }
  }
}

test("an in-page confirmation card passes axe and takes focus (institution, Remove)", async ({ browser }) => {
  const { context, page } = await open(browser, PAGES[4], WIDTHS.desktop);
  await page.locator('#rows button[data-act="remove"]').first().click();
  const card = page.getByRole("alertdialog", { name: /^Remove member000@uni\.edu$/ });
  await expect(card).toBeVisible();
  await expect(card.getByRole("button", { name: "Remove" })).toBeFocused();
  expect(await violations(page)).toEqual([]);
  await page.keyboard.press("Escape");
  await expect(card).toHaveCount(0);
  await expect(page.locator('#rows button[data-act="remove"]').first()).toBeFocused();
  await context.close();
});

// AE2V-001: at tablet width the roster's Actions cell ran past its scrolling
// wrapper, so Pause (then "Hold") and Remove were out of sight until the table
// was scrolled. Measured on a row with all three buttons and a held lease.
test("every roster action is in view at 768 px, without scrolling the table", async ({ browser }) => {
  const { context, page } = await open(browser, PAGES[4], WIDTHS.tablet);
  const outside = await page.evaluate(() => {
    const wrap = document.querySelector("#rosterCard .wrap").getBoundingClientRect();
    // A seat on a phone: the row with the most buttons (New phone, Pause, Remove).
    const row = document.querySelector('#rows button[data-act="clear"]').closest("tr");
    return [...row.querySelectorAll("td.actions button")]
      .map((b) => [b.textContent.trim(), b.getBoundingClientRect()])
      .filter(([, r]) => r.left < wrap.left || r.right > wrap.right + 0.5)
      .map(([name, r]) => `${name} at ${Math.round(r.left)}–${Math.round(r.right)}, wrapper ${Math.round(wrap.left)}–${Math.round(wrap.right)}`);
  });
  expect(outside).toEqual([]);
  await context.close();
});

// AE2-104: at 600 px and below the table heading was clipped to 1 px, and
// the roster's two sort buttons with it: Tab landed on controls nobody could
// see, and a tap could not reach them. They show as a "Sort by" line.
// Tab reaches them straight after the filter chips (the last control above
// the table).
test("the roster's sort buttons are on screen and sort at 360 px", async ({ browser }) => {
  const { context, page } = await open(browser, PAGES[4], WIDTHS.phone);
  const member = page.locator('#rosterHead button[data-sort="member"]');
  await page.locator("#rowChips button[data-chip]").last().focus();
  await page.keyboard.press("Tab");
  await expect(member).toBeFocused();
  const box = await member.boundingBox();
  expect(box.width, "the focused button is drawn, not clipped").toBeGreaterThan(20);
  expect(box.height).toBeGreaterThan(10);
  expect(await member.evaluate((b) => {
    const r = b.getBoundingClientRect();
    return document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) === b;
  }), "nothing covers it").toBe(true);
  await page.locator('#rosterHead button[data-sort="status"]').click();
  await expect(page.locator('#rosterHead th:has(button[data-sort="status"])')).toHaveAttribute("aria-sort", "ascending");
  expect(await violations(page)).toEqual([]);
  await context.close();
});

/** The operator desk with its Test accounts list shown. */
async function openTestAccounts(browser, width) {
  const opened = await open(browser, PAGES[5], width);
  await opened.page.locator("#loadTestAccounts").click();
  await opened.page.locator("#testAccountRows details[data-ta-phones] summary").first().waitFor();
  return opened;
}

// AE2-107: at 600 px and below a test account's phone count sat beside its
// "Phones" label while folded, and dropped to the line under it unfolded.
test("a test account's phone count stays beside its label when unfolded, at 360 px", async ({ browser }) => {
  const { context, page } = await openTestAccounts(browser, WIDTHS.phone);
  const summary = page.locator("#testAccountRows details[data-ta-phones] summary").first();
  const cell = page.locator("#testAccountRows td.phones").first();
  const top = async () => (await summary.boundingBox()).y - (await cell.boundingBox()).y;
  const left = async () => (await summary.boundingBox()).x;
  const folded = [await top(), await left()];
  await summary.click();
  await expect(page.locator("#testAccountRows details[data-ta-phones]").first()).toHaveAttribute("open", "");
  expect([await top(), await left()], "the same place, folded or not").toEqual(folded);
  const outside = await page.evaluate(() => {
    const row = document.querySelector("#testAccountRows tr").getBoundingClientRect();
    return [...document.querySelectorAll("#testAccountRows tr:first-child button[data-ta-release]")]
      .map((b) => b.getBoundingClientRect()).filter((r) => r.left < row.left || r.right > row.right + 0.5).length;
  });
  expect(outside, "every phone's Release is inside the row's card").toBe(0);
  await context.close();
});

// AE2-109: Import, the audit log's Show and its exports disable their button
// while they work, and a disabled button drops focus to <body>; focus comes
// back to the button once it is usable again.
test("focus stays on Import, Show and Export JSON after they finish", async ({ browser }) => {
  const { context, page } = await open(browser, PAGES[4], WIDTHS.desktop);
  await page.locator("#importText").fill("email\nmember000@uni.edu\n");
  await page.locator("#importSubmit").focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#importSummary")).toHaveText("1 already on the roster.");
  await expect(page.locator("#importSubmit")).toBeFocused();

  await page.locator("#auditLoad").focus();
  await page.keyboard.press("Enter");
  await expect(page.locator("#auditRows tr")).toHaveCount(1);
  await expect(page.locator("#auditLoad")).toBeFocused();

  const download = page.waitForEvent("download");
  await page.locator("#auditJson").focus();
  await page.keyboard.press("Enter");
  await download;
  await expect(page.locator("#status")).toHaveText("Audit log exported.");
  await expect(page.locator("#auditJson")).toBeFocused();
  await context.close();
});

// AE2-111: the states the page-by-page runs do not reach. Each passes axe.

test("the operator desk's Test accounts list, phones unfolded, passes axe at 360 and 1280 px", async ({ browser }) => {
  for (const width of [WIDTHS.phone, WIDTHS.desktop]) {
    const { context, page } = await openTestAccounts(browser, width);
    await page.locator("#testAccountRows details[data-ta-phones] summary").first().click();
    await expect(page.locator("#testAccountRows button[data-ta-release]").first()).toBeVisible();
    expect(await violations(page)).toEqual([]);
    await context.close();
  }
});

test("an error on the status line passes axe (institution, the licence list refused)", async ({ browser }) => {
  const context = await browser.newContext({ viewport: { width: WIDTHS.phone, height: 900 } });
  await stubContext(context, PERSONAS.it);
  // Registered last, so answered first: the list fails, the rest is the stub's.
  await context.route("**/__API_BASE_URL__/v1/institutions/licenses", (route) =>
    route.fulfill({ status: 503, contentType: "application/json", body: JSON.stringify({ detail: "firestore_unreachable" }) }));
  const page = await context.newPage();
  await page.goto(`${site.origin}/console/institution/`);
  await expect(page.locator("#status.err")).toContainText("could not reach its database");
  expect(await violations(page)).toEqual([]);
  await context.close();
});

test("the authenticator-code and enrolment cards are named alertdialogs and pass axe", async ({ browser }) => {
  const { context, page } = await open(browser, PAGES[0], WIDTHS.phone);
  // auth.js shows these during a step-up and a first sign-in; drawn here
  // directly, as the stub's personas have their second factor done.
  await page.evaluate(async () => {
    const dialogs = await import("/console/dialogs.js");
    dialogs.codeCard().next();
    dialogs.enrolInPage({ qrUrl: "otpauth://totp/x", secret: "JBSWY3DPEHPK3PXP", finish: async () => {} },
      "pat@lab.org", null).catch(() => {});
  });
  await expect(page.getByRole("alertdialog", { name: "Enter your authenticator code" })).toBeVisible();
  await expect(page.getByRole("alertdialog", { name: "Set up two-factor authentication" })).toBeVisible();
  expect(await violations(page)).toEqual([]);
  await context.close();
});
