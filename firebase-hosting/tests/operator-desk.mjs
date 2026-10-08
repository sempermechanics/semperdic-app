// Shared set-up for the operator desk's card tests (operator-*.test.mjs; not
// a test file itself): the licences they open the desk on, the desk opened
// as staff, and readers for the status line and the licence table. Import it
// after harness.mjs has loaded auth.js (it imports the harness itself).
import { openPage, json, $ } from "./harness.mjs";

const DAY = 86400e3;
/** The end of the day `days` from now, as the desk stores a licence end. */
export const at = (days) => new Date(Date.now() + days * DAY).toISOString().slice(0, 10) + "T23:59:59Z";

export const IND = {
  id: "lic-individual-1", keyPrefix: "SEMP-IND1", kind: "individual", mode: "licensed",
  status: "redeemed", duration: "timed", expiresAt: at(200), graceDays: 14, maxAnalyses: 50,
  emailLock: "pat@lab.org", note: "Prof. Chen, PO 4471",
};
export const UNI = {
  id: "lic-uni-1", keyPrefix: "SEMP-UNI1", kind: "institution", mode: "licensed", status: "redeemed",
  duration: "perpetual", seating: "floating", maxSeats: 5, leasesActive: 1, seatsUsed: 4,
  domainLock: "uni.edu", adminEmails: ["it@uni.edu"],
};
export const DEMO = {
  id: "lic-demo-1", keyPrefix: "SEMP-DEMO", kind: "individual", mode: "demo", status: "redeemed",
  duration: "perpetual", createdByUid: "system", emailLock: "d@x.org",
};

export const LIST = "GET /v1/admin/licenses?limit=50&include_revoked=false";
export const PENDING = "GET /v1/admin/users?status=PENDING&limit=50";

/** The desk, signed in as staff, with the first licence page answering `licenses`. */
export async function openDesk({ licenses = [IND, UNI], page = {}, users = [], routes = {}, user, resume, redirect } = {}) {
  await openPage("operator", {
    user,
    resume,
    redirect,
    routes: {
      "GET /v1/me": () => json(200, { role: "admin", email: "staff@semper.test" }),
      [PENDING]: () => json(200, { users }),
      [LIST]: () => json(200, { licenses, page, demoMaxAnalyses: 25 }),
      "GET /v1/institutions/licenses": () => json(200, { licenses: [] }),
      ...routes,
    },
  });
}

/** The status line, as [text, className]. */
export const status = () => [$("status").textContent, $("status").className];

/** A table body's rows, each as its cells' text, whitespace collapsed. */
export const tableRows = (id) => $(id).querySelectorAll("tr").map((tr) =>
  tr.cells.map((td) => td.textContent.replace(/\s+/g, " ").trim()));

export const rows = () => tableRows("licenceRows");
export const labels = () => rows().map((r) => r[0]);
export const rowButton = (kind, id) => $("licenceRows").querySelector(`button[data-${kind}="${id}"]`);

/** A request the fetch never answered: the network failed. */
export const offline = () => Promise.reject(new TypeError("Failed to fetch"));

/** The body of the one request matching METHOD and path, parsed. */
export function bodyOf(net, method, path) {
  const found = net.requests.filter((r) => r.method === method && r.url.endsWith(path));
  if (found.length !== 1) throw new Error(`${found.length} ${method} ${path} requests, expected 1`);
  return JSON.parse(found[0].body);
}
