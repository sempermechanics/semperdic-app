// Run: node --test "firebase-hosting/tests/*.test.mjs"
// People and devices on the operator desk (console/operator/people.js), past
// what operator.test.mjs pins: a refused New device, every part of a device
// history line and an empty or unreadable history, the accounts waiting for
// approval (rows, Approve, Reload, failures), and releasing a phone from an
// unlicensed account.
import { beforeEach, afterEach } from "node:test";
import assert from "node:assert/strict";
import { test, reset, settle, json, net, confirms, alerts, sent, $, loadAuth, deferred } from "./harness.mjs";
import { IND, openDesk, status, tableRows, rowButton, offline, bodyOf, PENDING } from "./operator-desk.mjs";

await loadAuth();

beforeEach(reset);
afterEach(async () => {
  await settle();
  assert.deepEqual(net.unexpected, [], "every request the page made was scripted");
});

async function click(button) {
  button.click();
  await settle();
}

/* ------------------------------------------------------------ licence */

test("a refused New device says so", async () => {
  await openDesk({ routes: { [`PATCH /v1/admin/licenses/${IND.id}`]: () => json(409, { detail: "license_revoked" }) } });
  confirms.answer(true);
  await click(rowButton("device", IND.id));
  assert.match(confirms.asked[0], /^Unbind SEMP-IND1 from the device it is on\?\n\nThe next device they sign in on takes it\./);
  assert.deepEqual(status(), ["Could not unbind: license_revoked", "muted err"]);
});

const HISTORY = `GET /v1/admin/licenses/${IND.id}/device-history?limit=30`;

test("a device history line names what was left, signed out and bound, per app", async () => {
  await openDesk({ routes: { [HISTORY]: () => json(200, { events: [
    { ts: "2026-10-07T11:21Z", action: "clear", uid: "staff", detail: {
      previousDeviceId: "dev-1", releasedDeviceId: "dev-9", previousDeviceIdMaterialTesting: "mt-1",
    } },
    // The registered phone is the lock's own: said once, as "left".
    { action: "clear", detail: { previousDeviceId: "dev-2", releasedDeviceId: "dev-2" } },
    { ts: "2026-10-07T11:25Z", action: "bind", uid: "u1" },
  ] }) } });
  await click(rowButton("history", IND.id));
  assert.deepEqual(alerts, [
    "Device history for SEMP-IND1\n\n" +
    "2026-10-07T11:21Z  clear  by staff  left dev-1  signed out dev-9  left mt-1 (Material Testing)\n" +
    "?  clear  by —  left dev-2\n" +
    "2026-10-07T11:25Z  bind  by u1",
  ]);
});

test("no moves recorded says so; a history that cannot be read is reported", async () => {
  await openDesk({ routes: { [HISTORY]: () => json(200, {}) } });
  await click(rowButton("history", IND.id));
  assert.deepEqual(alerts, ["No device moves recorded for SEMP-IND1 yet."]);

  reset();
  await openDesk({ routes: { [HISTORY]: () => json(503, { detail: "unavailable" }) } });
  await click(rowButton("history", IND.id));
  assert.deepEqual(alerts, []);
  assert.deepEqual(status(), ["Could not load device history: unavailable", "muted err"]);
});

/* ---------------------------------------------------------- accounts */

const userRows = () => tableRows("userRows");
const WAITING = [
  { uid: "u1", email: "a@lab.org", displayName: "Ann", activeDeviceId: "device-abcdef-123",
    activeDeviceIdMaterialTesting: "mt-device-xyz-9" },
  { uid: "u2" },
];

test("accounts waiting for approval are listed with their phones, or 'Nobody waiting.'", async () => {
  await openDesk({ users: WAITING });
  assert.deepEqual(userRows(), [
    ["a@lab.org", "Ann", "device-abc…; Material Testing mt-device-…", "Approve"],
    ["u2", "—", "—", "Approve"],
  ]);

  reset();
  await openDesk({ users: [] });
  assert.deepEqual(userRows(), [["Nobody waiting."]]);
});

test("a pending list that cannot be read says so in the table; Reload asks again", async () => {
  let n = 0;
  await openDesk({ routes: {
    [PENDING]: () => (n++ ? json(200, { users: WAITING.slice(1) }) : json(503, { detail: "unavailable" })),
  } });
  assert.deepEqual(userRows(), [["Could not load: unavailable Retry"]]);
  assert.equal($("status").textContent, "", "the licence table still loaded");
  await click($("reloadUsers"));
  assert.deepEqual(sent(/users/), [PENDING, PENDING]);
  assert.deepEqual(userRows(), [["u2", "—", "—", "Approve"]]);
});

test("Approve posts for that account, says so and re-reads who is waiting", async () => {
  let n = 0;
  const answer = deferred();
  await openDesk({ routes: {
    [PENDING]: () => json(200, { users: n++ ? WAITING.slice(1) : WAITING }),
    "POST /v1/admin/users/u1/approve": () => answer.promise,
  } });
  const approve = $("userRows").querySelector('button[data-approve="u1"]');
  await click(approve);
  assert.equal(approve.disabled, true, "one approval per click");
  answer.resolve(json(200, {}));
  await settle();
  assert.deepEqual(sent(/approve|users/), [PENDING, "POST /v1/admin/users/u1/approve", PENDING]);
  assert.deepEqual(status(), ["Approved.", "muted"]);
  assert.deepEqual(userRows().map((r) => r[0]), ["u2"]);
});

test("a refused approval says why and leaves the button to press again", async () => {
  await openDesk({ users: WAITING, routes: {
    "POST /v1/admin/users/u2/approve": () => json(404, { detail: "user_not_found" }),
  } });
  const approve = $("userRows").querySelector('button[data-approve="u2"]');
  await click(approve);
  assert.deepEqual(status(), ["Could not approve: user_not_found", "muted err"]);
  assert.equal(approve.disabled, false);
  assert.deepEqual(sent(/users/), [PENDING, "POST /v1/admin/users/u2/approve"], "not re-read after a refusal");
});

test("a click in the pending table off any Approve button sends nothing", async () => {
  await openDesk({ users: WAITING });
  await click($("userRows").querySelector("td"));
  assert.deepEqual(sent(/approve/), []);
});

/* ----------------------------------------------------- release a phone */

const RELEASE = "POST /v1/admin/device-releases";

async function release(email) {
  $("releaseEmail").value = email;
  await click($("releasePhone"));
}

test("releasing a phone needs an address; nothing is sent without one", async () => {
  await openDesk();
  await release("  ");
  assert.deepEqual(status(), ["Enter the account's email.", "muted err"]);
  assert.deepEqual(sent(/device-releases/), []);
});

test("a release names each phone it freed, per app, and clears the box", async () => {
  const answer = deferred();
  await openDesk({ routes: { [RELEASE]: () => answer.promise } });
  await release(" pat@lab.org ");
  assert.equal($("releasePhone").disabled, true);
  assert.deepEqual(bodyOf(net, "POST", "/v1/admin/device-releases"), { email: "pat@lab.org" });
  answer.resolve(json(200, { email: "pat@lab.org", releasedDeviceId: "dev-1", releasedDeviceIdMaterialTesting: "mt-1" }));
  await settle();
  assert.deepEqual(status(), ["Released dev-1 and mt-1 (Material Testing) for pat@lab.org; the new phone can sign in.", "muted"]);
  assert.equal($("releaseEmail").value, "");
  assert.equal($("releasePhone").disabled, false);
});

test("a release with only a Material Testing phone, or none, says which", async () => {
  await openDesk({ routes: { [RELEASE]: () => json(200, { email: "pat@lab.org", releasedDeviceIdMaterialTesting: "mt-1" }) } });
  await release("pat@lab.org");
  assert.deepEqual(status(), ["Released mt-1 (Material Testing) for pat@lab.org; the new phone can sign in.", "muted"]);

  reset();
  await openDesk({ routes: { [RELEASE]: () => json(200, {}) } });
  await release("Pat@Lab.org");
  assert.deepEqual(status(), ["Pat@Lab.org had no phone registered; any phone can sign in.", "muted"],
    "the address typed, when the answer names none");
});

test("a refused release is explained, and the address stays for another try", async () => {
  for (const [reply, text] of [
    [() => json(409, { detail: "license_device_clear_required" }),
      "Could not release: That account is licensed: use New device on its licence."],
    [() => json(429, { detail: "rate_limited" }), "Could not release: Too many requests just now — wait a moment and try again."],
    [() => json(404, { detail: "user_not_found" }), "Could not release: user_not_found"],
    [offline, "Could not release: Failed to fetch"],
  ]) {
    reset();
    await openDesk({ routes: { [RELEASE]: reply } });
    await release("pat@lab.org");
    assert.deepEqual(status(), [text, "muted err"], text);
    assert.equal($("releaseEmail").value, "pat@lab.org");
    assert.equal($("releasePhone").disabled, false);
  }
});
