# Semper — Privacy Policy

**Last updated:** 2026-08-05  
**Product:** Semper DIC Android app and optional cloud sync backend  
**Contact:** support mailbox configured as `SUPPORT_EMAIL` for the deployment

This policy describes personal data processed by Semper when you use the app
and, if enabled, the Semper cloud backend. Analysis itself runs **on-device**;
the cloud path is optional and only active when the app is built with an API
base URL.

## 1. Controllers and processors

| Role | Who |
|------|-----|
| App operator / controller | The organization that distributes your build and operates the GCP/Firebase project |
| Subprocessors (typical) | Google (Firebase Auth, Firestore, Google Drive, Firebase Crashlytics, Cloud Logging / Cloud Run), Resend (transactional email for access-request notifications) |

No large-language-model or generative-AI provider is integrated.

## 2. Data we process

### 2.1 Account and authentication (Firebase Authentication)

- Email address, display name, and provider identifiers from Google / email sign-in (as configured for the Firebase project).
- Authentication tokens used to call the backend (not stored as long-lived
  secrets on the server beyond normal session verification).

### 2.2 Access control and devices (Firestore)

- User profile: uid, email, role, access status (PENDING / APPROVED / SUSPENDED),
  optional product-limit overrides, Drive folder pointer.
- Registered device: device id, public key material for attestation, model /
  OS / app version strings.
- Short-lived auth challenges (nonces) with TTL.

### 2.3 Analysis metadata and files (Firestore + Google Drive)

- Session metadata (specimen label, status, sizes, engine metrics you sync).
- File manifests (names, roles, checksums, Drive object ids).
- Binary artifacts (images, archives, reports, `.dat` / CSV) stored in a
  company Shared Drive under the Cloud Run service account — **bytes are
  uploaded by the device directly to Drive**, not through Cloud Run.

### 2.4 Diagnostics (Firebase Crashlytics and Analytics) — opt-in

- **Off by default, and nothing is collected until you agree.** Collection is
  disabled in the app manifest; the app asks once on first launch and you can
  change the answer at any time in **Settings → Your data → Send crash reports**.
  Declining, or switching it off later, also deletes any report still queued on
  the device.
- When enabled: crash reports and non-fatal diagnostics from the Android app,
  including device/app version metadata as provided by the Crashlytics SDK. R8
  mapping files are retained by operators for deobfuscation and are not
  published.
- Diagnostics never include your images, measurement results, specimen names, or
  file contents.

### 2.5 Operational logs (Cloud Logging / Error Reporting)

- Structured request logs: timestamp, request id, path, status, latency,
  opaque error codes, and uid/device id when resolved. Tokens, signatures, and
  upload URIs are not logged.

### 2.6 Notifications (Resend)

- When a new account is created in PENDING status, support may receive an email
  containing the applicant’s email, display name, sign-in provider, and user id
  so an admin can approve access.

### 2.7 Audit trail

- Append-only `audit_logs` in Firestore record security-relevant actions
  (auth denials, approvals, deletes, exports). They intentionally retain the
  fact of actions after account erasure and do **not** store analysis content.

## 3. Purposes and legal bases (summary)

| Purpose | Examples | Basis (typical) |
|---------|----------|-----------------|
| Provide the product | Sign-in, sync, restore, quotas | Contract / legitimate interest |
| Access control | Pending approval, admin approve/revoke | Legitimate interest / compliance |
| Security | Device attestation, rate limits, audit | Legitimate interest |
| Reliability (server) | Cloud Logging, readiness probes | Legitimate interest |
| Reliability (app diagnostics) | Crashlytics / Analytics crash reports | **Consent** — opt-in, withdrawable in Settings |
| Support onboarding | Resend access-request mail | Legitimate interest |

Exact legal bases depend on your jurisdiction and the deploying organization’s
policies; replace this section with counsel-approved language before public
launch if required.

## 4. Retention

| Data | Retention |
|------|-----------|
| Firebase Auth account | Until you delete the account or an admin removes it |
| Firestore profile, devices, sessions, file docs | Until account/session erasure via the app/API |
| Drive artifacts | Deleted with session or account erasure (Shared Drive trash may retain per Workspace policy) |
| Crashlytics | Per Firebase project retention settings (**UNKNOWN** until verified in console) |
| Cloud Logging | Per GCP log retention (**UNKNOWN** until verified; default often 30 days) |
| Resend message content | Per Resend retention (**UNKNOWN** until verified) |
| Audit logs | Retained after erasure for security/compliance; not included in user export of analysis content |

Scheduled Firestore exports / PITR, where enabled, follow
[FIRESTORE_DATA_PROTECTION.md](../backend/FIRESTORE_DATA_PROTECTION.md).

## 5. Your rights — export and deletion

- **Export:** In the app, **Settings → Your data → Download my cloud account
  data**. (Directly: authenticated, device-attested `GET /v1/me/export`, which
  returns profile, devices, and complete session manifests — `complete: true` is
  written last, so a truncated download is detectable.) Binary artifacts are
  downloaded via `GET /v1/files/{id}/content` (or the app Restore flow).
- **Delete session:** Device-attested `DELETE /v1/sessions/{id}` removes Drive
  folder + Firestore metadata for that analysis.
- **Delete account:** Device-attested `DELETE /v1/me` removes the Drive user
  subtree and Firestore user/session/device/file docs. **Audit logs remain.**
- **Limits:** Third-party processor retention (Crashlytics, Logging, Resend,
  Workspace trash) may outlive application erasure until those systems’ own
  retention/TTL elapse. Export does not include other users’ data or raw
  Cloud Logging streams.

## 6. Sharing

Data is shared with subprocessors above to operate the service. It is not sold.
Admin operators of your deployment can approve users and view operational logs
according to project IAM.

## 7. Security (summary)

TLS in transit (Cloud Run / Gateway), deny-all client Firestore rules (server
SDK only), device attestation for high-consequence mutations, rate limits,
security headers, and opaque client error bodies on Cloud Run. See
[CLOUD_ARCHITECTURE_GCP.md](../backend/CLOUD_ARCHITECTURE_GCP.md).

## 8. Children

Semper is intended for professional / research use, not for children under 16
(or the applicable age of digital consent).

## 9. Changes

Material changes will update the “Last updated” date. Significant changes to
cloud processing should be reflected in-app or in release notes.

## 10. Contact

Use the in-app support / help action or the configured support email for privacy
requests (export, deletion, access questions).
