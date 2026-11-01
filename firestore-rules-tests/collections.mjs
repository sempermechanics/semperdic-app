// Every Firestore collection the backend (backend/app) reads or writes, with
// the fields that tie a document to a person. firestore.rules denies every
// client read and write: only the backend's Admin SDK, which bypasses rules,
// touches these. rules.test.mjs proves the denial per collection, and
// collections.test.mjs fails when backend/app names a collection that is not
// listed here, so this list cannot go stale without a red build.
//
// `path(parent, id)` builds a document path; `parent` is only used by
// subcollections. `ownerFields(uid, email)` are the fields that would make a
// document "the caller's own" under an owner-scoped rule, so the tests seed
// documents that carry them and prove the caller still cannot read them.

/** @typedef {{ name: string, path: (id: string, parent?: string) => string,
 *   collectionPath: (parent?: string) => string, parentCollection?: string,
 *   idIsUid?: boolean, ownerFields: (uid: string, email: string) => object,
 *   source: string }} CollectionSpec */

/** @type {CollectionSpec[]} */
export const COLLECTIONS = [
  {
    name: 'users',
    collectionPath: () => 'users',
    path: (id) => `users/${id}`,
    idIsUid: true,
    ownerFields: (uid, email) => ({ email, role: 'admin', access_status: 'APPROVED', mode: 'licensed', uid }),
    source: 'backend/app/repo/users.py',
  },
  {
    name: 'auth_links',
    collectionPath: () => 'auth_links',
    path: (id) => `auth_links/${id}`,
    idIsUid: true,
    ownerFields: (uid) => ({ uid }),
    source: 'backend/app/repo/users.py',
  },
  {
    name: 'devices',
    collectionPath: () => 'devices',
    path: (id) => `devices/${id}`,
    ownerFields: (uid) => ({ uid, status: 'ACTIVE', publicKeyPem: 'pem' }),
    source: 'backend/app/repo/devices.py',
  },
  {
    name: 'challenges',
    collectionPath: () => 'challenges',
    path: (id) => `challenges/${id}`,
    ownerFields: (uid) => ({ uid, deviceId: 'device-1' }),
    source: 'backend/app/repo/devices.py',
  },
  {
    name: 'sessions',
    collectionPath: () => 'sessions',
    path: (id) => `sessions/${id}`,
    ownerFields: (uid) => ({ uid, deviceId: 'device-1', app: 'semper', status: 'SYNCED' }),
    source: 'backend/app/repo/sessions.py',
  },
  {
    name: 'files',
    collectionPath: () => 'files',
    path: (id) => `files/${id}`,
    ownerFields: (uid) => ({ uid, sessionId: 'session-1', status: 'UPLOADED' }),
    source: 'backend/app/repo/sessions.py',
  },
  {
    name: 'licenses',
    collectionPath: () => 'licenses',
    path: (id) => `licenses/${id}`,
    ownerFields: (uid, email) => ({
      redeemedByUid: uid, emailLock: email, adminEmails: [email], kind: 'institution', maxSeats: 99,
    }),
    source: 'backend/app/repo/mint.py',
  },
  {
    name: 'seats',
    parentCollection: 'licenses',
    collectionPath: (parent = 'lic-1') => `licenses/${parent}/seats`,
    path: (id, parent = 'lic-1') => `licenses/${parent}/seats/${id}`,
    idIsUid: true,
    ownerFields: (uid, email) => ({ uid, email, status: 'ACTIVE' }),
    source: 'backend/app/repo/_base.py',
  },
  {
    // A licence's activated phones (ADR-033), keyed by device id.
    name: 'activations',
    parentCollection: 'licenses',
    collectionPath: (parent = 'lic-1') => `licenses/${parent}/activations`,
    path: (id, parent = 'lic-1') => `licenses/${parent}/activations/${id}`,
    ownerFields: (uid) => ({ uid, app: 'semper', status: 'active' }),
    source: 'backend/app/repo/activations.py',
  },
  {
    name: 'licenseInvites',
    collectionPath: () => 'licenseInvites',
    path: (id) => `licenseInvites/${id}`,
    ownerFields: (uid, email) => ({ email, licenseId: 'lic-1', uid }),
    source: 'backend/app/repo/invites.py',
  },
  {
    name: 'deleted_licenses',
    collectionPath: () => 'deleted_licenses',
    path: (id) => `deleted_licenses/${id}`,
    ownerFields: (uid, email) => ({ redeemedByUid: uid, emailLock: email }),
    source: 'backend/app/repo/deletion.py',
  },
  {
    name: 'deleted_seats',
    parentCollection: 'deleted_licenses',
    collectionPath: (parent = 'lic-1') => `deleted_licenses/${parent}/deleted_seats`,
    path: (id, parent = 'lic-1') => `deleted_licenses/${parent}/deleted_seats/${id}`,
    idIsUid: true,
    ownerFields: (uid, email) => ({ uid, email }),
    source: 'backend/app/repo/deletion.py',
  },
  {
    // The recycle bin (ADR-035): a deleted backup's session fields for 30
    // days, then an erased marker of ids alone.
    name: 'deleted_sessions',
    collectionPath: () => 'deleted_sessions',
    path: (id) => `deleted_sessions/${id}`,
    ownerFields: (uid) => ({ uid, app: 'semper', localSessionId: 'local-1', state: 'binned' }),
    source: 'backend/app/repo/recycle_bin.py',
  },
  {
    name: 'audit_logs',
    collectionPath: () => 'audit_logs',
    path: (id) => `audit_logs/${id}`,
    ownerFields: (uid) => ({ uid, action: 'AUTH_DENIED', outcome: 'DENIED' }),
    source: 'backend/app/audit.py',
  },
];

/** Names only, for the guard. */
export const COLLECTION_NAMES = new Set(COLLECTIONS.map((c) => c.name));
