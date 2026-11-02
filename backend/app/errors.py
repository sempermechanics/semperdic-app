"""Stable error codes returned as FastAPI's `{"detail": "<code>"}`.

These strings are a wire contract, not messages: the Android client branches on
them (`app/src/main/java/com/sempermechanics/semper/data/net/ApiErrors.kt`) to tell a
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
INVALID_TOKEN = "invalid_token"  # noqa: S105 - an error code, not a credential
NOT_APPROVED = "not_approved"
NOT_ADMIN = "not_admin"
TERMS_VERSION_MISMATCH = "terms_version_mismatch"
INVALID_SIGNATURE = "invalid_signature"
BAD_SIGNATURE = "bad_signature"
NONCE_INVALID_OR_REPLAYED = "nonce_invalid_or_replayed"

# --- device binding --------------------------------------------------------
DEVICE_CONFLICT = "device_conflict"
DEVICE_IN_USE = "device_in_use"
#: 409 from `POST /v1/devices/register`: staff released this phone from this
#: test account (TD-314, ADR-027), and it may not take its place back. Builds
#: before it read any 409 there as `device_conflict`.
DEVICE_RELEASED = "device_released"
DEVICE_NOT_ACTIVE = "device_not_active"
#: 404 from a staff release of one test-account phone (ADR-027): the device
#: is not an ACTIVE device of that account.
DEVICE_NOT_FOUND = "device_not_found"
#: `X-App-Id` names an app this backend does not bind devices for (ADR-010).
UNKNOWN_APP = "unknown_app"
#: 410 from `POST /v1/devices/register`: `X-App-Id` is a retired id
#: (`apps.RETIRED`), whose registration would replace the key of the app that
#: succeeded it on the same phone. Only those builds receive it.
APP_RETIRED = "app_retired"

# --- Cloud Tasks callback --------------------------------------------------
INVALID_TASK_TOKEN = "invalid_task_token"  # noqa: S105 - an error code, not a credential
NOT_TASK_INVOKER = "not_task_invoker"

# --- sessions, files, quotas -----------------------------------------------
SESSION_NOT_FOUND = "session_not_found"
SESSION_QUOTA_EXCEEDED = "session_quota_exceeded"
# 409 from `POST /v1/sessions` when a demo account's own count has room but
# this device's stored analyses, every account's, are at the demo cap
# (TD-279). Its own code because a re-check of the account's count cannot
# clear it: the app holds this stop until an upload succeeds or an analysis
# is deleted, rather than reporting room the next upload refuses.
DEVICE_QUOTA_EXCEEDED = "device_quota_exceeded"
TOO_MANY_FILES = "too_many_files"
FILE_NOT_FOUND = "file_not_found"
FILE_NOT_UPLOADED = "file_not_uploaded"
FILE_NOT_IN_SESSION = "file_not_in_session"
SIZE_MISMATCH = "size_mismatch"
CHECKSUM_MISMATCH = "checksum_mismatch"
SIZE_OR_STATE_MISMATCH = "size_or_state_mismatch"
RANGE_NOT_SATISFIABLE = "range_not_satisfiable"
# PUT /v1/sessions/{sid}/metadata: the upload has not finished, so there is no
# metadata.json to replace yet (409; the app waits for the upload), no
# metadata file in the session (404), or a body that is not this session's
# metadata (422) or is too big for one (413).
SESSION_NOT_COMPLETE = "session_not_complete"
# DELETE /v1/sessions/{sid} while the session is still PROVISIONING (409, with
# Retry-After): the worker is creating its Drive folder, and erasing the
# metadata now would leave that folder with nothing pointing at it.
SESSION_PROVISIONING = "session_provisioning"
# 409 from `POST /v1/sessions` when the same analysis (`localSessionId`) is
# in the account's recycle bin, and from `POST /v1/files/{id}/complete` for
# a file of a binned session (ADR-035). Detail `session_in_recycle_bin: <sid>`
# on the create, so the app can mark its row as binned and stop re-uploading.
SESSION_IN_RECYCLE_BIN = "session_in_recycle_bin"
METADATA_NOT_FOUND = "metadata_not_found"
METADATA_INVALID = "metadata_invalid"
METADATA_TOO_LARGE = "metadata_too_large"

# --- admin -----------------------------------------------------------------
USER_NOT_FOUND = "user_not_found"
#: A staff phone release on an account whose live licence still holds the
#: device lock: **New device** on the licence moves both bindings (TD-126).
LICENSE_DEVICE_CLEAR_REQUIRED = "license_device_clear_required"
EMPTY_PATCH = "empty_patch"
#: 403 from `POST /v1/admin/test-accounts` (ADR-027): `TEST_ACCOUNT_DOMAINS`
#: is empty, or the address is not in one of its domains.
TEST_ACCOUNTS_DISABLED = "test_accounts_disabled"
#: 409 from the same route: the address already has an account, a Firebase
#: Auth user or a licence, or is a staff address (`ADMIN_EMAILS`).
EMAIL_IN_USE = "email_in_use"

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
    return f"{FEATURE_NOT_LICENSED}: Restore isn't available on Demo."


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
# Activation (ADR-033). Every slot of the licence's device count is taken:
# 409 `device_limit_reached: <active>/<max>`, so the app can say "1 of 1
# phones" and offer Deactivate. The count is the only limit on moving a
# licence between phones; the self-service cooldown it replaced is gone.
DEVICE_LIMIT_REACHED = "device_limit_reached"
# 404: no activation the caller may act on for that phone. Another member's
# phone in the same pool, or a licence outside an IT admin's domain, reads the
# same as one that does not exist.
ACTIVATION_NOT_FOUND = "activation_not_found"
# 409: Activate on a floating licence. Its lease is the activation
# (`POST /v1/licenses/checkout`), so there is nothing to activate.
SEATING_FLOATING = "seating_floating"
# 422 on a staff licence edit: a `maxDevices` below the phones active on the
# licence now. Lowering the count deactivates nobody; deactivate first.
MAX_DEVICES_BELOW_ACTIVE = "max_devices_below_active"
# 422 on a staff licence edit: a `maxDevices` on a licence from before the
# split, which locks one phone per app and has no count to change.
MAX_DEVICES_NEEDS_APP = "max_devices_needs_app"
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
# Upgrading by issuing a new licence (ADR-033, repo/licence_upgrade.py).
# `not_upgradable` is anything but a live individual licence naming its app
# (409); `upgrade_pending` is an upgrade already waiting on it (409, with its
# id); `no_pending_upgrade` is an Activate or staff apply with none waiting
# for the account (404).
LICENSE_NOT_UPGRADABLE = "license_not_upgradable"
UPGRADE_PENDING = "upgrade_pending"
NO_PENDING_UPGRADE = "no_pending_upgrade"
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
#   Semper staff only since 2026-10-28: institution IT is given the answer an
#   unknown address gets instead, a held invite (repo/invites.py,
#   ADDENDUM-2), so its roster is no oracle on other licences.
EMAIL_ALREADY_LICENSED = "email_already_licensed"
ALREADY_LICENSED = "already_licensed"
MEMBER_ALREADY_LICENSED = "member_already_licensed"
#: 422 to institution IT adding an address that is not on the licence's
#: `domainLock` (single add and each import row). Decided from the address
#: and the licence alone, before anything about the person is read. Semper
#: staff may still add an external collaborator.
MEMBER_OFF_DOMAIN = "member_off_domain"
#: Not a refusal: a staff mint's `claimError` when the address already holds a
#: live licence, so the new one is minted but not attached (repo/mint.py).
HOLDER_ALREADY_LICENSED = "holder_already_licensed"
INVITE_NOT_FOUND = "invite_not_found"
INVALID_EMAIL = "invalid_email"

# --- roster import and audit export (routers/roster.py) ----------------------
# Refusals of a whole bulk import, before any row is touched: nothing to
# import, more rows or more text than one request may hold, a file the CSV
# reader cannot parse (sent as `roster_import_malformed: line <n>`, the line
# its bad record starts on), or a header row with no `email` column.
ROSTER_IMPORT_EMPTY = "roster_import_empty"
ROSTER_IMPORT_TOO_MANY_ROWS = "roster_import_too_many_rows"
ROSTER_IMPORT_TOO_LARGE = "roster_import_too_large"
ROSTER_IMPORT_MALFORMED = "roster_import_malformed"
ROSTER_IMPORT_NO_EMAIL_COLUMN = "roster_import_no_email_column"
#: Not a refusal: one import row's `error` when it was not reached (the import
#: ran out of time) or a backend fault stopped it. Importing the same file
#: again finishes it, since rows already on the roster report `exists`.
ROSTER_ROW_UNPROCESSED = "roster_row_unprocessed"
#: `since` / `until` on the audit export is not a date, or ends before it starts.
INVALID_AUDIT_RANGE = "invalid_audit_range"

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
DRIVE_WRITE_FAILED = "drive_write_failed"
# Drive answered 404 for an object the index points at: it was deleted straight
# in Drive, or the client's upload never landed. Not an outage, so never 502 —
# a 5xx makes the app retry forever. Download → 404 (the app gives up and says
# why); complete → 400 (the app discards the session and rebuilds it).
DRIVE_FILE_GONE = "drive_file_gone"

# Reported as `{"detail": …}` too: an unhandled exception on Cloud Run, and the
# DependencyError codes the readiness probe and the Drive/Firestore clients
# raise (main.dependency_error_handler returns exc.code as the detail).
INTERNAL_ERROR = "internal_error"
# 413 from `main.EdgeMiddleware`: a body over its cap, refused before it is read.
REQUEST_TOO_LARGE = "request_too_large"
DRIVE_UNREACHABLE = "drive_unreachable"
DRIVE_UNHEALTHY = "drive_unhealthy"
FIRESTORE_UNREACHABLE = "firestore_unreachable"
READYZ_FAILED = "readyz_failed"
# 503: every attempt to bind an empty device lock lost to contention and the
# lock is still empty. Nobody holds the licence, so the caller retries; it is
# never reported as `license_device_mismatch`.
DEVICE_LOCK_CONTENDED = "device_lock_contended"
# 503 from the browser step-up when this instance has spent its Firebase Auth
# Admin budget (`rate_limit.auth_admin_bucket`, TD-239) and cannot ask whether
# the token was revoked. Nothing is wrong with the caller's token, so it is
# never `invalid_token`; it carries Retry-After and the caller tries again.
AUTH_CHECK_BUSY = "auth_check_busy"
# 503 with Retry-After: an operator has switched this feature off fleet-wide
# (`KILL_SWITCHES`, `deps.unless_paused`). Nothing is wrong with the caller or
# the request; the app holds the work and tries again later rather than
# failing it, and says the feature is paused rather than "server error".
FEATURE_PAUSED = "feature_paused"

#: Codes the Android client branches on or surfaces by name. Changing
#: one of these needs the matching edit in ApiErrors.kt in the same commit --
#: tests/test_error_codes.py fails otherwise.
CLIENT_BRANCHED = frozenset(
    {
        NOT_APPROVED,
        DEVICE_CONFLICT,
        DEVICE_IN_USE,
        DEVICE_RELEASED,
        DEVICE_NOT_ACTIVE,
        NONCE_INVALID_OR_REPLAYED,
        BAD_SIGNATURE,
        SESSION_NOT_FOUND,
        # The app waits for the upload to finish before re-sending metadata.
        SESSION_NOT_COMPLETE,
        FILE_NOT_FOUND,
        RATE_LIMITED,
        SESSION_QUOTA_EXCEEDED,
        DEVICE_QUOTA_EXCEEDED,
        # The app renders this as "all seats are in use, try again" rather
        # than a failure, so a rename here needs the matching ApiErrors.kt edit.
        NO_FLOATING_SEAT,
        FEATURE_NOT_LICENSED,
        LICENSE_DEVICE_MISMATCH,
        # Activate and Deactivate (ADR-033): the app says the count is full,
        # there is no licence, check out a seat, or no upgrade waits, rather
        # than "something went wrong" (`DeviceActivation.kt`).
        DEVICE_LIMIT_REACHED,
        NO_LICENSE,
        SEATING_FLOATING,
        NO_PENDING_UPGRADE,
        ACTIVATION_NOT_FOUND,
        APP_CHECK_REQUIRED,
        DRIVE_FILE_GONE,
        # The upload worker rebuilds the session on the first and restages on
        # the other two (`UploadErrors.kt`), rather than failing as "too large"
        # or retrying the same bytes forever.
        SIZE_OR_STATE_MISMATCH,
        CHECKSUM_MISMATCH,
        SIZE_MISMATCH,
        # A kill switch is on: the upload or restore worker waits (Retry-After)
        # and keeps its work, instead of retrying at once or giving up.
        FEATURE_PAUSED,
    }
)


# --- refusals ---------------------------------------------------------------
#: The HTTP status of every code the repo refuses with. Status is a property of
#: the code, so a route never maps codes to statuses itself; `main.py` answers
#: any `Refusal` from this table, whatever the route (ADR-020).
STATUS: dict[str, int] = {
    # Nothing there, or nothing the caller may see.
    USER_NOT_FOUND: 404,
    SESSION_NOT_FOUND: 404,
    DEVICE_NOT_FOUND: 404,
    LICENSE_NOT_FOUND: 404,
    NO_LICENSE: 404,
    SEAT_NOT_FOUND: 404,
    DELETED_LICENSE_NOT_FOUND: 404,
    ACTIVATION_NOT_FOUND: 404,
    # There, but not for this caller.
    LICENSE_EXPIRED: 403,
    LICENSE_REVOKED: 403,
    LICENSE_EMAIL_MISMATCH: 403,
    EMAIL_NOT_VERIFIED: 403,
    LICENSE_DEVICE_MISMATCH: 403,
    NOT_ELIGIBLE: 403,
    TEST_ACCOUNTS_DISABLED: 403,
    # Conflicts with the state it is in.
    ALREADY_LICENSED: 409,
    LICENSE_ALREADY_REDEEMED: 409,
    LICENSE_SEATS_EXHAUSTED: 409,
    LICENSE_SEAT_DISABLED: 409,
    SEATING_NOT_FLOATING: 409,
    NO_FLOATING_SEAT: 409,
    LICENSE_DEVICE_CLEAR_REQUIRED: 409,
    DEMO_KEY_NOT_DELETABLE: 409,
    LICENSE_EXISTS: 409,
    EMAIL_IN_USE: 409,
    LICENSE_NOT_CONVERTIBLE: 409,
    MEMBER_ALREADY_LICENSED: 409,
    INVITE_EXISTS: 409,
    SEAT_REVOKED: 409,
    SEAT_BUSY: 409,
    DEVICE_LIMIT_REACHED: 409,
    SEATING_FLOATING: 409,
    # The analysis is in the account's recycle bin (ADR-035).
    SESSION_IN_RECYCLE_BIN: 409,
    # Device registration (`repo.register_device`): the account is bound to
    # another phone for this app, or this phone is another account's, or
    # staff released this phone from this test account.
    DEVICE_CONFLICT: 409,
    DEVICE_IN_USE: 409,
    DEVICE_RELEASED: 409,
    DELETED_LICENSE_PURGED: 410,
    LICENSE_NOT_UPGRADABLE: 409,
    UPGRADE_PENDING: 409,
    NO_PENDING_UPGRADE: 404,
    # A licence edit or conversion that is not allowed as asked.
    CONVERT_DOMAIN_MISMATCH: 422,
    MEMBER_OFF_DOMAIN: 422,
    EXPIRY_IN_PAST: 422,
    EXPIRY_BEFORE_CURRENT: 422,
    LICENSE_PERPETUAL: 422,
    CAP_ON_DEMO_KEY: 422,
    INSTITUTION_ONLY: 422,
    FLOATING_NEEDS_MAX_SEATS: 422,
    MAX_SEATS_BELOW_USED: 422,
    MAX_DEVICES_BELOW_ACTIVE: 422,
    MAX_DEVICES_NEEDS_APP: 422,
    INVALID_EMAIL: 400,
    KIND_NOT_INSTITUTION: 400,
    # Lost a race; a retry succeeds.
    CLAIM_CONTENDED: 503,
}


class Refusal(Exception):
    """A request the service refuses, named by its wire code.

    Raised by the repo; `main.py` answers it as `{"detail": "<code>"}` (or
    `"<code>: <suffix>"`, as `device_limit_reached` carries its count) with
    `STATUS[code]`, and `Retry-After` when `retry_at` says when to come back.
    A code without a status is a programming error, refused at construction.
    """

    def __init__(self, code: str, suffix: str = "", *, retry_at=None):
        if code not in STATUS:
            raise ValueError(f"{code!r} has no status in errors.STATUS")
        super().__init__(code)
        self.code = code
        self.suffix = suffix
        self.retry_at = retry_at
        self.status = STATUS[code]

    @property
    def detail(self) -> str:
        return f"{self.code}: {self.suffix}" if self.suffix else self.code
