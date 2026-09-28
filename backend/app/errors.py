"""Stable error codes returned as FastAPI's `{"detail": "<code>"}`.

These strings are a wire contract, not messages: the Android client branches on
them (`app/src/main/java/com/indicvision/semper/data/net/ApiErrors.kt`) to tell a
device conflict from a quota rejection inside the same status code. Naming them
here means a rename is one edit that `tests/test_error_codes.py` then holds
against the client's copy, instead of a literal typed in a router that silently
stops matching.

Codes are lowercase snake_case and carry no user data — they are safe to log and
safe to show. Anything a caller needs beyond the code (counts, sizes) goes after
a colon, as `session_quota_exceeded` does.

Not exhaustive in one respect: `validation.py` generates a `missing_<field>` /
`invalid_<field>` family per rejected identifier, so those codes are built at
runtime rather than named here.
"""
from __future__ import annotations

# --- authentication / authorisation ----------------------------------------
MISSING_BEARER = "missing_bearer"
INVALID_TOKEN = "invalid_token"
NOT_APPROVED = "not_approved"
NOT_ADMIN = "not_admin"
TERMS_VERSION_MISMATCH = "terms_version_mismatch"
INVALID_SIGNATURE = "invalid_signature"
BAD_SIGNATURE = "bad_signature"
NONCE_INVALID_OR_REPLAYED = "nonce_invalid_or_replayed"

# --- device binding --------------------------------------------------------
DEVICE_CONFLICT = "device_conflict"
DEVICE_IN_USE = "device_in_use"
DEVICE_NOT_ACTIVE = "device_not_active"
#: `X-App-Id` names an app this backend does not bind devices for (ADR-010).
UNKNOWN_APP = "unknown_app"

# --- Cloud Tasks callback --------------------------------------------------
INVALID_TASK_TOKEN = "invalid_task_token"
NOT_TASK_INVOKER = "not_task_invoker"

# --- sessions, files, quotas -----------------------------------------------
SESSION_NOT_FOUND = "session_not_found"
SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"
TOO_MANY_FILES = "too_many_files"
FILE_NOT_FOUND = "file_not_found"
FILE_NOT_UPLOADED = "file_not_uploaded"
FILE_NOT_IN_SESSION = "file_not_in_session"
SIZE_MISMATCH = "size_mismatch"
CHECKSUM_MISMATCH = "checksum_mismatch"
SIZE_OR_STATE_MISMATCH = "size_or_state_mismatch"
RANGE_NOT_SATISFIABLE = "range_not_satisfiable"

# --- admin -----------------------------------------------------------------
USER_NOT_FOUND = "user_not_found"
#: A staff phone release on an account whose live licence still holds the
#: device lock: **New device** on the licence moves both bindings (TD-126).
LICENSE_DEVICE_CLEAR_REQUIRED = "license_device_clear_required"
EMPTY_PATCH = "empty_patch"

# --- licensing, seats and leases -------------------------------------------
# `feature_not_licensed` gates *retrieval* (file content, session bundle)
# behind a licensed mode. Recording an analysis (`POST /v1/sessions` and the
# upload broker) is open to every approved account, demo included. The
# `license_*` family is returned by activation. The seat/lease codes describe a
# floating pool: `no_floating_seat` in particular is the pool being FULL, not
# a fault in the account — the caller stays eligible and may retry.
FEATURE_NOT_LICENSED = "feature_not_licensed"


def feature_not_licensed_detail() -> str:
    """What a phone shows for a refused restore.

    Pre-licensing builds print the raw detail, so the code carries a sentence
    a person can read; the app and the console match on the part before the
    colon, as for the quota code. A function rather than another constant so
    test_error_codes keeps treating every constant here as a bare code.
    """
    return f"{FEATURE_NOT_LICENSED}: Restore isn't available in demo mode."


LICENSE_NOT_FOUND = "license_not_found"
LICENSE_REVOKED = "license_revoked"
LICENSE_EXPIRED = "license_expired"
LICENSE_EMAIL_MISMATCH = "license_email_mismatch"
LICENSE_DEVICE_MISMATCH = "license_device_mismatch"
LICENSE_ALREADY_REDEEMED = "license_already_redeemed"
LICENSE_SEAT_DISABLED = "license_seat_disabled"
LICENSE_SEATS_EXHAUSTED = "license_seats_exhausted"
# 503: the claim lost every transaction attempt to other requests on the same
# licence. Nothing was granted and nothing is wrong with the licence — the
# caller retries and, on a licence with room, wins. Never reported as
# `license_seats_exhausted`, which told IT the licence was full when it wasn't.
CLAIM_CONTENDED = "claim_contended"
# 422 on a licence edit. Extend only moves an expiry later: a date already
# past, or earlier than the one in force, would end or shorten the licence
# for everyone on it, and a perpetual licence has no expiry to extend.
EXPIRY_IN_PAST = "expiry_in_past"
EXPIRY_BEFORE_CURRENT = "expiry_before_current"
LICENSE_PERPETUAL = "license_perpetual"
SEAT_NOT_FOUND = "seat_not_found"
# Hold / resume on a seat that was removed. Resuming one used to reactivate it
# without taking a slot back; the member is re-added instead.
SEAT_REVOKED = "seat_revoked"
# Hold / resume lost a race with another change to the same seat; try again.
SEAT_BUSY = "seat_busy"
NOT_ELIGIBLE = "not_eligible"
NO_LICENSE = "no_license"
NO_FLOATING_SEAT = "no_floating_seat"
SEATING_NOT_FLOATING = "seating_not_floating"
# Reconciliation is a roster operation. An individual licence has one
# redeemer and no seats, so there are no two counts to compare and an empty
# report would read like a clean bill of health.
KIND_NOT_INSTITUTION = "kind_not_institution"
EMAIL_NOT_VERIFIED = "email_not_verified"
# Self-service device change only. Clearing a lock is not revoking — the
# entitlement is untouched and the next device to sign in binds — so the only
# thing that limits it is how often the holder may do it themselves. Staff and
# IT are never subject to this. Sent as `device_change_too_soon: <ISO instant>`
# (with `Retry-After`), the instant being when the holder may change again.
DEVICE_CHANGE_TOO_SOON = "device_change_too_soon"
# 422 on a staff licence edit: a `maxSeats` below the members already on an
# assigned roster. Lowering the cap removes nobody, so it would only make the
# count read "12 of 10"; remove members first.
MAX_SEATS_BELOW_USED = "max_seats_below_used"
# 422 on a staff licence edit: a `maxAnalyses` on a demo-mode key. A demo
# holder gets DEMO_MAX_ANALYSES whatever the key says (`resolve_user_config`),
# so the edit was stored, mirrored and reported as done while the app kept
# showing "N of 25". Raising a demo account's allowance is a licensed key.
CAP_ON_DEMO_KEY = "cap_on_demo_key"
# 422 on a staff licence edit: `maxSeats`, `seating` or `adminEmails` on an
# individual licence, which has no roster. Converting one is its own route.
INSTITUTION_ONLY = "institution_only"
# 422: a floating licence with no `maxSeats`. Nobody would ever be refused a
# lease, so it would be an assigned licence with extra steps.
FLOATING_NEEDS_MAX_SEATS = "floating_needs_max_seats"
# Converting an individual licence to an institution one. `not_convertible`
# is an institution licence or a Demo key (409); `domain_mismatch` is a
# holder whose address is not on the new licence's domain (422).
LICENSE_NOT_CONVERTIBLE = "license_not_convertible"
CONVERT_DOMAIN_MISMATCH = "convert_domain_mismatch"
# Deleting a licence into the 30-day hold (repo/deletion.py). A system Demo
# key is refused (409): the account would only be issued another. Restore
# answers 404 for nothing held, 410 once the hold has ended (the TTL purge
# runs up to a day late, so the date is checked), and 409 if a licence with
# that id exists again.
DEMO_KEY_NOT_DELETABLE = "demo_key_not_deletable"
DELETED_LICENSE_NOT_FOUND = "deleted_license_not_found"
DELETED_LICENSE_PURGED = "deleted_license_purged"
LICENSE_EXISTS = "license_exists"

# --- institution invites ---------------------------------------------------
# An invite reserves a roster place for an address with no account yet. It is
# consumed at that address's first sign-in; until then it holds no seat and no
# uid. `invite_exists` means the address is already promised to a DIFFERENT
# licence — re-inviting to the same one is a no-op, not an error.
INVITE_EXISTS = "invite_exists"

# --- one licence per person ------------------------------------------------
# 409 wherever a licence would be granted to someone who already holds or is
# promised a different live one (repo/holders.py). Renewal is Extend on the
# licence they have; a second one used to be granted and then either sat
# unattached or silently moved them off the first.
# `email_already_licensed: <licence id>` — staff mint; the id is the one they
#   hold, so the desk can open it.
# `already_licensed` — a key typed in the app.
# `member_already_licensed` — institution IT adding someone to a roster. No
#   id: IT has no business learning which other licence a person holds.
EMAIL_ALREADY_LICENSED = "email_already_licensed"
ALREADY_LICENSED = "already_licensed"
MEMBER_ALREADY_LICENSED = "member_already_licensed"
INVITE_NOT_FOUND = "invite_not_found"
INVALID_EMAIL = "invalid_email"

# --- admin second factor ---------------------------------------------------
# The console is a browser, which cannot produce a device attestation, so the
# staff web path proves itself with a second factor and a recent sign-in
# instead. `mfa_required` means the account has no second factor on the token
# at all; `reauth_required` means it has one but the sign-in is too old for a
# state-changing call and the operator must re-authenticate.
MFA_REQUIRED = "mfa_required"
REAUTH_REQUIRED = "reauth_required"

# The caller sent X-Device-Id but no valid App Check token, while
# APP_CHECK_MODE=enforce. It means "this is not our app binary", not "this
# account is not allowed" — the account may be perfectly entitled. Distinct
# from `not_approved` so a support conversation starts in the right place.
APP_CHECK_REQUIRED = "app_check_required"

# --- upstream / throttling -------------------------------------------------
RATE_LIMITED = "rate_limited"
DRIVE_DOWNLOAD_FAILED = "drive_download_failed"
DRIVE_META_FAILED = "drive_meta_failed"
# Drive answered 404 for an object the index points at: it was deleted straight
# in Drive, or the client's upload never landed. Not an outage, so never 502 —
# a 5xx makes the app retry forever. Download → 404 (the app gives up and says
# why); complete → 400 (the app discards the session and rebuilds it).
DRIVE_FILE_GONE = "drive_file_gone"

# Reported as `{"detail": …}` too: an unhandled exception on Cloud Run, and the
# DependencyError codes the readiness probe and the Drive/Firestore clients
# raise (main.dependency_error_handler returns exc.code as the detail).
INTERNAL_ERROR = "internal_error"
DRIVE_UNREACHABLE = "drive_unreachable"
DRIVE_UNHEALTHY = "drive_unhealthy"
FIRESTORE_UNREACHABLE = "firestore_unreachable"
READYZ_FAILED = "readyz_failed"
# 503: every attempt to bind an empty device lock lost to contention and the
# lock is still empty. Nobody holds the licence, so the caller retries; it is
# never reported as `license_device_mismatch`.
DEVICE_LOCK_CONTENDED = "device_lock_contended"

#: Codes the Android client branches on or surfaces by name. Changing
#: one of these needs the matching edit in ApiErrors.kt in the same commit --
#: tests/test_error_codes.py fails otherwise.
CLIENT_BRANCHED = frozenset(
    {
        NOT_APPROVED,
        DEVICE_CONFLICT,
        DEVICE_IN_USE,
        DEVICE_NOT_ACTIVE,
        NONCE_INVALID_OR_REPLAYED,
        BAD_SIGNATURE,
        SESSION_NOT_FOUND,
        FILE_NOT_FOUND,
        RATE_LIMITED,
        SESSION_QUOTA_EXCEEDED,
        # The app renders this as "all seats are in use, try again" rather
        # than a failure, so a rename here needs the matching ApiErrors.kt edit.
        NO_FLOATING_SEAT,
        FEATURE_NOT_LICENSED,
        LICENSE_DEVICE_MISMATCH,
        APP_CHECK_REQUIRED,
        DRIVE_FILE_GONE,
    }
)
