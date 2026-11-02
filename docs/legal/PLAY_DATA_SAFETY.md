# Google Play Data safety — answers to submit (2026-10-22)

The answers for Play Console → **App content → Data safety**, in the form's own
order, for readiness item 8.4
([READINESS_FRAMEWORK_2026-10.md](../ops/READINESS_FRAMEWORK_2026-10.md)). Every
answer is checked against the code, the merged release manifest, the SDK
inventory, [DATA_MAP.md](DATA_MAP.md) and [PRIVACY_POLICY.md](PRIVACY_POLICY.md).
**Not submitted** (TD-232): the operator copies these into the Play Console and
submits. Answers marked **OWNER DECISION** need the owner first; everything else
is ready to copy. This file is **not rendered** to Hosting
(`scripts/render_legal_pages.py` renders only the Privacy Policy and the Terms).

## Before you start

1. **Submit with a build that has the TD-232 manifest change.** The CI release APK
   of `main` at `968637cb` declares `com.google.android.gms.permission.AD_ID`
   (and the Privacy Sandbox `ACCESS_ADSERVICES_AD_ID` /
   `ACCESS_ADSERVICES_ATTRIBUTION`), all merged in by Firebase Analytics
   (`play-services-measurement-impl` / `-api` 24.0.0). Any build from before this
   change, including `v1.2-beta.4`, carries them. The manifest now removes all three
   (`app/src/main/AndroidManifest.xml:16-27`) and turns advertising-ID collection
   off (`:53-57`). Play checks the declaration against the uploaded bundle: answer
   **App content → Advertising ID → "Does your app use advertising ID?" No** only
   for a build with this change, and check it with
   `aapt2 dump permissions <apk>` (the list below).
2. **App content → Ads → "Does your app contain ads?" No.** There is no ad SDK
   (`app/build.gradle.kts:378-440`).
3. **Check the Firebase / Google Analytics console** (OWNER DECISION 2 below):
   the answer "Shared: No" assumes no Google Ads link, Google signals off, and
   Analytics data sharing with Google products off.

### Release permissions (`aapt2 dump permissions`, CI APK at `968637cb`)

| Permission | From | After this change |
|---|---|---|
| `READ_EXTERNAL_STORAGE` (max SDK 32), `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` | app manifest `:6-10` | kept: picking frames and videos, read on the device |
| `INTERNET`, `ACCESS_NETWORK_STATE` | app, `firebase-auth`, `play-services-measurement` | kept |
| `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC` | app, `work-runtime` | kept: transfer notifications |
| `USE_BIOMETRIC`, `USE_FINGERPRINT` | `androidx.biometric` 1.1.0 | kept |
| `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED` | `play-services-measurement`, `work-runtime` | kept |
| `com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE` | `play-services-measurement` | kept: Analytics reads the Play install referrer (install source), only while collection is on. Not an advertising ID |
| `com.google.android.providers.gsf.permission.READ_GSERVICES` | `recaptcha` 18.6.1 (via `firebase-auth`) | kept |
| `com.google.android.gms.permission.AD_ID` | `play-services-measurement-impl` | **removed** |
| `ACCESS_ADSERVICES_AD_ID`, `ACCESS_ADSERVICES_ATTRIBUTION` | `play-services-measurement-api` | **removed** |
| `com.sempermechanics.semper.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | kept (signature permission of the app itself) |

No location, contacts, camera, microphone, phone-state or account permissions.
The merged release manifest
(`app/build/intermediates/merged_manifest/release/processReleaseMainManifest/AndroidManifest.xml`)
lists the AD_ID and ADSERVICES permissions as `REJECTED` in
`app/build/outputs/logs/manifest-merger-release-report.txt`.

### SDK inventory (`app/build.gradle.kts:378-440`, Firebase BoM 35.0.2, `gradle/libs.versions.toml:11`)

| SDK | Line | What it sends off the device |
|---|---|---|
| Firebase Analytics | `:384` | Usage events with enum and bucket parameters (`diagnostics/SemperAnalytics.kt:58-75`), app instance id, device and app metadata, and approximate location that Google derives from the masked IP. **Only with consent**: collection is off in the manifest (`AndroidManifest.xml:47-52`) and turned on by `Diagnostics.apply` (`diagnostics/Diagnostics.kt:72-85`). Never the advertising ID (`AndroidManifest.xml:16-27, 53-57`) |
| Firebase Authentication | `:386` | Email, display name (Google sign-in), sign-in provider, password (to Google), IP address and user agent for abuse protection, reCAPTCHA / Play Integrity tokens |
| Firebase App Check (Play Integrity) | `:394` | Integrity request to Google Play (app metadata, device attestation); App Check token to the backend |
| Firebase Crashlytics | `:400` | Stack traces, device model, OS and app version, Crashlytics installation UUID, breadcrumbs. **Only with consent** |
| Credential Manager, Google ID (`androidx.credentials`, `googleid`) | `:428-430` | Google sign-in token exchange |
| OkHttp, kotlinx.serialization, WorkManager, Timber, AndroidX | `:433-440` | Nothing of their own; OkHttp carries the app's own calls |

### Data the app sends to Semper's backend and Drive

| Data | Endpoint | When |
|---|---|---|
| Firebase ID token (email, uid inside) | every call | signed in |
| Device id `and-{ANDROID_ID}`, device public key, model, OS and app version | `POST /v1/devices/register` | first sign-in on a phone |
| `X-App-Id`, `X-App-Version` | every call | always |
| Analysis name, specimen file name, small run metrics | `POST /v1/sessions` | each analysis recorded to the cloud |
| Source frames under the user's file names, rendered images, `.dat` fields, PDF reports, CSV, `metadata.json`, `Session.zip` / `Extras.zip` (`data/net/ArtifactRoles.kt:17-42`) | Drive resumable upload | each analysis recorded to the cloud. Licensed: the Settings "Save to cloud" toggle. **Demo: always, no toggle** (`data/cloud/CloudSync.kt:342-345`) |
| Terms acceptance and improvement consent (version, time, uid, device id, source) | `POST /v1/me/terms`, `PUT /v1/me/consents` | sign-in gate (`ui/auth/TermsActivity.kt:92`); Settings → Your data (`ui/settings/SettingsYourDataSection.kt:85-87`) |

Not collected, because it never leaves the device: videos (frames are extracted on
the phone and only the frames are uploaded), the analysis itself (the engine runs
on the device), and anything shared through the Android share sheet or the
feedback mail, which the user sends from their own app
(`ui/common/auth/SupportMail.kt:29-35`).

---

## Step 1 — Data collection and security

| Question | Answer | Evidence |
|---|---|---|
| Does your app collect or share any of the required user data types? | **Yes** | below |
| Is all of the user data collected by your app encrypted in transit? | **Yes** | Cleartext refused app-wide (`app/src/main/res/xml/network_security_config.xml:4`); a release build fails unless the API URL is `https://` (`app/build.gradle.kts:71-72`); Firebase SDKs and Drive uploads use HTTPS |
| Which methods of account creation does your app support? | **Username and password**; **Username and other authentication** (passwordless email link); **OAuth** (Sign in with Google) | Email-link and reset App Links (`AndroidManifest.xml`, `AuthActivity` intent filters); Credential Manager + Google ID (`app/build.gradle.kts:428-430`) |
| Delete account URL | **OWNER DECISION 1** | In the app: Settings → Your data → Delete account (`ui/settings/SettingsYourDataSection.kt:232`, `DELETE /v1/me`, `data/net/SemperApi.kt:385`) |
| Do you provide a way for users to request that some or all of their data is deleted, without requiring them to delete their account? | **Yes** | Each cloud analysis can be deleted (`DELETE /v1/sessions/{id}`, `data/net/SemperApi.kt:397-399`); turning diagnostics off deletes queued crash reports (`diagnostics/Diagnostics.kt:76-77`); privacy requests by mail (Privacy Policy §10) |

**Security practices badges** (if the form shows them): *Independent security
review*: **No** (TD-240). *Families policy*: not applicable (adults only, Privacy
Policy §8).

## Step 2 — Data types

Tick exactly these; leave every other type unticked.

| Category | Type | Tick |
|---|---|---|
| Location | Approximate location | **Yes** (Firebase Analytics derives it from the masked IP, only with diagnostics consent) |
| Location | Precise location | No |
| Personal info | Name | **Yes** |
| Personal info | Email address | **Yes** |
| Personal info | User IDs | **Yes** |
| Personal info | Address, Phone number, Race and ethnicity, Political or religious beliefs, Sexual orientation, Other info | No |
| Financial info | all | No (no in-app purchases; access is arranged outside the app, [ADR-024](../adr/ADR-024-account-anchored-entitlements.md)) |
| Health and fitness | all | No |
| Messages | all | No |
| Photos and videos | Photos | **Yes** |
| Photos and videos | Videos | No (read on the device; only extracted frames leave it, as photos) |
| Audio files | all | No |
| Files and docs | Files and docs | **Yes** |
| Calendar, Contacts | all | No |
| App activity | App interactions | **Yes** |
| App activity | Other user-generated content | **Yes** (analysis names, specimen file names, analysis parameters) |
| App activity | In-app search history, Installed apps, Other actions | No |
| Web browsing | Web browsing history | No |
| App info and performance | Crash logs | **Yes** |
| App info and performance | Diagnostics | **Yes** |
| App info and performance | Other app performance data | No |
| Device or other IDs | Device or other IDs | **Yes** |

## Step 3 — Data usage and handling (per ticked type)

For every type: **Shared: No**, subject to OWNER DECISION 2. Transfers to service
providers that process for Semper (Google Cloud and Firebase, Google Play for
Integrity, Resend for the support and security mails) are not sharing under Play's
definition. **Processed ephemerally: No** for every type.

| Type | Collected | Required or optional | Purposes |
|---|---|---|---|
| Approximate location | Yes | **Optional** (diagnostics opt-in, `AndroidManifest.xml:47-52`; Settings → Your data → Send crash reports and usage data) | Analytics |
| Name | Yes | **Optional** (only from Google sign-in, `backend/app/repo/users.py:288`; password and email-link accounts have none) | Account management |
| Email address | Yes | **Required** (every account) | App functionality, Account management, Fraud prevention, security, and compliance |
| User IDs | Yes (Firebase uid; it keys the terms-acceptance and consent records) | **Required** | App functionality, Account management, Fraud prevention, security, and compliance |
| Photos | Yes (source frames and rendered images of each recorded analysis) | **Required**: demo analyses upload with no toggle (`data/cloud/CloudSync.kt:342-345`), so not every user can turn it off | App functionality; **Analytics** (product improvement, only for users who keep the improvement consent: Privacy Policy §2.8) |
| Files and docs | Yes (`.dat` fields, PDF reports, CSV, `metadata.json`, bundles) | **Required** (as for Photos) | App functionality; **Analytics** (product improvement, consent only) |
| Other user-generated content | Yes (analysis name, specimen file name, parameters, run metrics) | **Required** (as for Photos) | App functionality; **Analytics** (product improvement, consent only) |
| App interactions | Yes (Analytics events) | **Optional** (diagnostics opt-in) | Analytics |
| Crash logs | Yes (Crashlytics) | **Optional** (diagnostics opt-in) | Analytics |
| Diagnostics | Yes (Crashlytics breadcrumbs and non-fatals, device and app version) | **Optional** (diagnostics opt-in) | Analytics |
| Device or other IDs | Yes (`and-{ANDROID_ID}` device id and its public key, on every signed call and in the terms and consent records; App Check / Play Integrity attestation; Analytics app instance id and Crashlytics installation UUID with consent). **Not** the advertising ID | **Required** (device binding) | App functionality, Fraud prevention, security, and compliance, Analytics (the Analytics and Crashlytics ids only) |

Notes for the reviewer of these answers:

- **Gate item: analysis content may be used for app improvement (optional,
  user-controlled).** Play records "optional" per data type, not per purpose.
  Photos, Files and docs and Other user-generated content are *required* (demo
  uploads them), and their **Analytics** purpose covers the improvement use,
  which only users who keep the separate consent are subject to (Privacy Policy
  §2.8, Settings → Your data → Use my data to improve Semper). The gate also says
  no improvement dataset may be built until its tooling exists
  ([PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md), Legal
  rollout); declaring the purpose now keeps the form true for when it is.
- **Gate item: account data includes terms-acceptance / consent records.** Those
  records are the version, time, uid, device id and source (Privacy Policy §2.9,
  `users/{uid}.termsAccepted` and `improvementConsent`, DATA_MAP). They add no new
  Play type; they are why User IDs and Device or other IDs carry **Fraud
  prevention, security, and compliance** (evidence of the agreement and of
  consent choices).
- **Gate item: demo analysis content (images, results) is uploaded to the
  operator's cloud.** That is why Photos, Files and docs and Other user-generated
  content are **Required**, not optional.
- **Email address**: Firebase Auth sends the sign-in-link and password-reset
  mails; the backend mails support on a new pending account and, when
  `NOTIFY_SECURITY_FROM` is set (it is not, TD-309), the holder on a new device.
  None of these is marketing, so *Developer communications* is not ticked.
- **Approximate location** is not mentioned in the Privacy Policy (TD-317).

## OWNER DECISION

1. **Delete account URL.** Play requires a web page where a user can request
   deletion of the account and its data without the app, naming the app or
   developer, the steps, and what is kept and for how long. Today
   `app.sempermechanics.com/account` needs a sign-in and only says to delete from
   the app (`firebase-hosting/public/console/messages.js:69-74`), so it does not
   serve a user who has uninstalled. Options: (a) a public page (for example on
   `sempermechanics.com/support/`) giving the in-app steps and a request to
   `support@sempermechanics.com` (`app/src/main/res/values/strings.xml:925`) from
   the account's address; (b) point at the Privacy Policy
   (`https://sempermechanics.com/privacy/`, `strings.xml:444`) once its §5.1 / §10
   say the same. Either page must say that audit rows are kept 365 days after
   erasure (DATA_MAP, `audit_logs`) and that vendor copies (Crashlytics, Cloud
   Logging, Resend, Drive trash) follow their own retention.
2. **Firebase / Google Analytics sharing settings.** "Shared: No" for App
   interactions, Approximate location and Device or other IDs holds only while the
   Analytics property has no Google Ads (or other advertising product) link,
   Google signals is off, and the data-sharing settings that send data to Google
   for its own purposes are off. These live in the Firebase and Analytics consoles,
   not in this repository. Confirm and record the date here; if any is on, those
   three types are **Shared** (purpose Analytics) and the Privacy Policy needs a
   line (TD-317).

## Gate items this answers

[PRODUCTION_READINESS_GATE.md](../ops/PRODUCTION_READINESS_GATE.md): "Update the
Google Play Data safety form" (consent and terms records, improvement use) and
"Google Play Data safety form: demo analysis content". Both stay open until the
form is submitted.
