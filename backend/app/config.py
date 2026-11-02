import logging
import os
import re

from .apps import SEMPER_CLASSROOM, canonical


def _env_int(name: str, default: str) -> int:
    """Parse an integer env var, failing with a clear message rather than a bare
    ValueError traceback when the value is non-numeric."""
    raw = os.environ.get(name, default)
    try:
        return int(raw)
    except ValueError as e:
        raise RuntimeError(f"Environment variable {name}={raw!r} is not an integer") from e


def _env_int_renamed(name: str, old_name: str, default: str) -> int:
    """`_env_int` for a variable that was renamed: `name` when it is set,
    else `old_name`, so a service deployed with only the old name keeps its
    value. An empty value counts as unset."""
    for env in (name, old_name):
        if os.environ.get(env):
            return _env_int(env, default)
    return int(default)


#: Features `KILL_SWITCHES` may name. The app ignores any other value.
KILL_SWITCH_NAMES = ("backup", "share", "restore", "upload")


def parse_kill_switches(raw: str) -> list[str]:
    """The known kill switches in `raw`, in canonical order, de-duplicated.

    An unknown name is logged and dropped: a typo must not stop the service
    starting, and the app could not act on it anyway."""
    named = {v for v in re.split(r"[\s,;]+", raw.strip().lower()) if v}
    unknown = sorted(named.difference(KILL_SWITCH_NAMES))
    if unknown:
        logging.getLogger("semper").warning(
            "KILL_SWITCHES: ignoring unknown value(s) %s; allowed: %s",
            unknown, ", ".join(KILL_SWITCH_NAMES),
        )
    return [n for n in KILL_SWITCH_NAMES if n in named]


def parse_domains(raw: str) -> frozenset[str]:
    """Address domains from a list separated by commas, whitespace or `;`:
    lowercased, a leading "@" dropped, blanks ignored."""
    return frozenset(
        d.lower().lstrip("@") for d in re.split(r"[\s,;]+", raw) if d.lstrip("@")
    )


class Settings:
    # There is deliberately no sign-in domain gate. Any account Firebase Auth
    # accepts may authenticate; whether it may *use* anything is decided by
    # access_status (see AUTO_APPROVE_HD / ADMIN_EMAILS below and
    # get_or_create_user). Earlier revisions carried WEB_CLIENT_ID and
    # ALLOWED_HD settings that no code read — do not reintroduce them.

    # Accounts whose verified email is in this domain are created APPROVED
    # automatically (e.g. "indicvision.com"). Everyone else is created PENDING
    # and must be approved individually. Empty = nobody auto-approved by domain.
    AUTO_APPROVE_HD = os.environ.get("AUTO_APPROVE_HD", "").lower()

    # Fleet-wide defaults for product limits. Per-user overrides live on the
    # Firestore users/{uid} document (maxSessions / maxFilesPerSession /
    # maxFrames); resolve_user_config merges override → these defaults.
    #
    # There is exactly one cap per mode, and `mode` picks between them:
    # DEMO_MAX_ANALYSES for an unlicensed account, LICENSED_MAX_SESSIONS_PER_USER
    # for a licensed one (unless a key or admin override sets a tighter
    # ceiling). Both are the number of analyses the account may keep in the
    # cloud — routers/sessions.py enforces creation against whichever applies.
    #
    # `MAX_SESSIONS_PER_USER` USED TO BE that number for everyone, at a default
    # of 4. It is deliberately gone rather than left unread: keeping a variable
    # that a deployment still sets, and that silently no longer does anything,
    # is worse than removing it and saying so. A service that still sets it now
    # gets DEMO_MAX_ANALYSES for unlicensed users — see
    # docs/backend/BACKEND_SETUP_GCP.md.
    #
    # MAX_FILES_PER_SESSION bounds one analysis's upload manifest. Apps since
    # 2026-08-12 send three objects (metadata + Session.zip + Extras.zip) whatever
    # the frame count; 600 was sized for earlier builds that sent one file per
    # artifact (3 x frames + 4).
    # MAX_FRAMES_PER_ANALYSIS is the deformed-frame ceiling the app enforces
    # (AppSettings.MAX_MAX_FRAMES, the app's offline fallback, is also 500).
    DEMO_MAX_ANALYSES = _env_int("DEMO_MAX_ANALYSES", "25")
    LICENSED_MAX_SESSIONS_PER_USER = _env_int("LICENSED_MAX_SESSIONS_PER_USER", "999")

    # Grace window stamped onto a newly minted timed license when the mint
    # request does not name one. It exists so a renewal in flight does not
    # strand a paying user mid-project; entitlements stay FULL throughout.
    #
    # This is a mint-time default only. A license already in Firestore that
    # carries no graceDays reads as ZERO, not as this value — see
    # repo.user_config._grace_days. Otherwise deploying a grace default would
    # retroactively reinstate every account that expired within the window.
    LICENSE_GRACE_DAYS_DEFAULT = _env_int("LICENSE_GRACE_DAYS_DEFAULT", "14")

    # How long a floating-seat lease lasts before it stops entitling anyone,
    # and how often the app should renew it. A lease must outlive a working
    # session comfortably — losing a seat because of a tunnel or a lunch break
    # would be worse than a crashed client parking one — so the window is long
    # and the heartbeat is what actually keeps it alive.
    #
    # A crashed or uninstalled client therefore holds its slot for up to
    # LICENSE_LEASE_HOURS. That only costs anything on a pool that is full, and
    # the expiry sweep reclaims it without anyone intervening.
    LICENSE_LEASE_HOURS = _env_int("LICENSE_LEASE_HOURS", "8")
    LICENSE_LEASE_HEARTBEAT_MINUTES = _env_int("LICENSE_LEASE_HEARTBEAT_MINUTES", "30")
    MAX_FILES_PER_SESSION = _env_int("MAX_FILES_PER_SESSION", "600")
    MAX_FRAMES_PER_ANALYSIS = _env_int("MAX_FRAMES_PER_ANALYSIS", "500")

    # Version gate for the .dat archive codec (Phase 1.3 of the perf plan):
    # SessionZip's *read* side has understood a DatCodec-encoded .dat entry
    # since it shipped (self-describing by magic header, decode is a no-op
    # passthrough for a raw legacy entry) — but the *write* side must not
    # start producing encoded entries until every client that could ever
    # restore one has that read support. An already-installed app with zero
    # DatCodec awareness writes a restored .dat straight to disk with no
    # decode step; an encoded entry would silently corrupt that restore.
    # Default off (fleet-wide) until adoption of the DatCodec-aware app
    # version is confirmed high enough — flip via this env var, or canary a
    # single account first via the per-user override (PATCH /v1/admin/users/
    # {uid}/config, same mechanism as the numeric limits above).
    DAT_CODEC_ENCODING_ENABLED = os.environ.get(
        "DAT_CODEC_ENCODING_ENABLED", "false",
    ).strip().lower() == "true"

    # Emails treated as admins (role=admin, always approved) — they can call
    # the /v1/admin/* endpoints. e.g.
    # "support@indicvision.com damodar@indicvision.com".
    # Separated by whitespace, `;` or `,`. Prefer spaces, for the reason
    # CONSOLE_ORIGINS gives below: the deploy action's env_vars block splits
    # pairs on commas, so a comma-separated list of operators arrives
    # truncated to the first address — and the operator desk is exactly where
    # losing the second name is least visible until someone is locked out.
    ADMIN_EMAILS = {
        e.lower()
        for e in re.split(r"[\s,;]+", os.environ.get("ADMIN_EMAILS", ""))
        if e
    }

    # --- test accounts (QC and CI, ADR-027) ----------------------------------
    # The address domains a staff-created test account may use, e.g.
    # "qa.sempermechanics.com". Empty (the default) turns the feature off:
    # every create answers 403 `test_accounts_disabled`. A test account can
    # therefore never be made on a customer's address, only on a domain the
    # company controls. Separated by commas, whitespace or `;`; prefer spaces
    # for a list, for the reason ADMIN_EMAILS gives (the deploy action splits
    # its env_vars block on commas). Lowercased; a leading "@" is dropped.
    TEST_ACCOUNT_DOMAINS = parse_domains(os.environ.get("TEST_ACCOUNT_DOMAINS", ""))

    # --- browser dashboards ---------------------------------------------------
    # Origins allowed to call the API from a browser. The dashboards live on
    # Firebase Hosting (app.sempermechanics.com, and the project's own
    # firebaseapp.com host), never on the gateway host, so every console fetch
    # is cross-origin and carries Authorization, which makes the browser
    # preflight it. Without a matching Access-Control-Allow-Origin the
    # response is discarded and the page silently does nothing. The phone
    # never sends an Origin and is unaffected. Exact origins (scheme + host),
    # no wildcard, separated by whitespace, `;` or `,`. Prefer spaces: the
    # deploy action's env_vars block splits pairs on commas, so a
    # comma-separated value arrives truncated with its tail as a stray key.
    CONSOLE_ORIGINS = [
        o.rstrip("/")
        for o in re.split(
            r"[\s,;]+",
            os.environ.get(
                "CONSOLE_ORIGINS",
                "https://app.sempermechanics.com "
                "https://indicvision-dic-app-auth.firebaseapp.com",
            ),
        )
        if o
    ]

    # --- staff console second factor ---------------------------------------
    # Every state-changing /v1/admin/* route requires a device attestation: an
    # ECDSA signature from a registered device keypair, which proves the call
    # came from a specific enrolled phone and not merely from a stolen ID
    # token. A browser cannot produce one, which is why the staff console was
    # read-only.
    #
    # These two settings are the deliberate substitute. A browser caller is
    # accepted for those routes when the ID token carries a *second factor*
    # (firebase.sign_in_second_factor, present only when MFA was actually
    # completed) and the sign-in behind it is recent. That is a real, checkable
    # control rather than an absent one — a leaked token from a session that
    # never did MFA is still refused — but it is weaker than device binding,
    # and the freshness window is what limits the damage a stolen token can do.
    #
    # ADMIN_WEB_MFA_ENABLED=0 disables the browser path entirely and restores
    # attestation-only admin. Set it that way if the console is not in use.
    ADMIN_WEB_MFA_ENABLED = os.environ.get("ADMIN_WEB_MFA_ENABLED", "1") == "1"
    # How old a sign-in may be and still authorise a state change, in seconds.
    # Short on purpose: this is "sudo mode", re-entered by re-authenticating,
    # not a session length. Dashboard reads that sit behind step-up use this
    # too (institution IT console, account unbind/bundle).
    ADMIN_WEB_REAUTH_SECONDS = _env_int("ADMIN_WEB_REAUTH_SECONDS", "900")
    # Tighter window for whole-licence revoke: password (or Google re-auth)
    # plus TOTP must be fresh, not merely "signed into the dashboard earlier".
    ADMIN_WEB_REVOKE_REAUTH_SECONDS = _env_int(
        "ADMIN_WEB_REVOKE_REAUTH_SECONDS", "120"
    )

    # Both settings above govern the browser step-up for the *user* and
    # *institution IT* tiers too, not only for staff: the same trade is being
    # made either way. The names are historical — the staff console is what
    # first needed them.

    # Whether a request from a build that does not tap Activate (no
    # `X-Activation: explicit` header) activates its phone by itself, on a
    # licence that runs the activation model (ADR-033). `legacy`: the holder's
    # first phone activates on its own while a slot is free, so installed
    # builds keep their licence. `off`: only Activate does; set once the
    # builds that send the header are what the fleet runs (TD-363).
    IMPLICIT_ACTIVATION = os.getenv("IMPLICIT_ACTIVATION", "legacy").strip().lower()

    # How long a phone released by a device-lock clear or a staff release is
    # refused if it tries to register again. The old phone's upload worker
    # re-registers whenever it reads `device_not_active`, so without a hold it
    # would sign straight back in. Other phones registering do not end it (an
    # account holds several, ADR-033); after it, the old phone may come back
    # (a mistaken clear strands nobody). Set to 0 to disable it.
    DEVICE_RELEASE_HOLD_HOURS = _env_int("DEVICE_RELEASE_HOLD_HOURS", "24")

    # How many phones a real account may hold registered per app at once
    # (ADR-033). Only a licensed one is licensed; the rest run as Demo. Past
    # it, the phone that registered first and holds no licence makes room. A
    # test account has its own cap (`testAccount.maxDevices`, ADR-027).
    MAX_PHONES_PER_APP = _env_int("MAX_PHONES_PER_APP", "5")

    # --- App Check (device callers only) ------------------------------------
    # The Firebase Web API key ships inside the APK (google-services.json) and
    # is an identifier, not a secret, so anyone can mint a genuine ID token from
    # a script. For the routes behind `verified_device` or a step-up tier that
    # buys an attacker nothing — a DeviceKeys signature is a stronger
    # proof than App Check. The exposed set is the ID-token-only routes, and
    # `POST /v1/licenses/checkout` is the one worth abusing: it reads the device
    # id from a *header* and doubles as the seat heartbeat, so a scripted client
    # can hold a licence's floating seats under invented device ids.
    #
    # App Check closes that by attesting the *app binary* (Play Integrity) as
    # well as the account. It is required only of callers that send
    # `X-Device-Id` — that is the app, and it is the header the abuse needs.
    # Browsers never send it, so the four consoles are unaffected and need no
    # reCAPTCHA provider.
    #
    #   off     — header ignored entirely (the default, and what to run until
    #             an App Check-carrying build is the fleet).
    #   monitor — verified when present, logged when absent or bad, never
    #             refused. The rollout setting: it tells you what fraction of
    #             live traffic would break before anything does.
    #   enforce — a device caller without a valid token is refused 403.
    #
    # Mirrors Firebase's own unenforced/enforced rollout, with `monitor` named
    # for what it is. Flipping to `enforce` before the fleet has updated locks
    # out every older build, so it is deliberately not the default.
    APP_CHECK_MODE = os.environ.get("APP_CHECK_MODE", "off").strip().lower()

    # --- revocation on the browser step-up (A3.4) ---------------------------
    # On: a browser caller on the second-factor path (`step_up.step_up_check`,
    # via="mfa") has its ID token re-verified with check_revoked=True, so a
    # user revoked or disabled in Firebase Auth stops changing anything at
    # once rather than when the token expires (up to an hour). The device-
    # signed path is not affected: a revoked device fails its own check.
    #
    # PREREQUISITE: the runtime service account must hold
    # `roles/firebaseauth.admin` on the Auth project (FIREBASE_PROJECT_ID).
    # The check reads the user record, and without that grant it fails, which
    # this setting treats as a refusal (fail closed, 401 invalid_token, logged
    # as `revocation_check_failed`). Hence off by default: grant the role,
    # then turn this on.
    CHECK_REVOKED_ON_STEP_UP = os.environ.get(
        "CHECK_REVOKED_ON_STEP_UP", "false",
    ).strip().lower() in ("1", "true", "yes")

    # --- Firebase Auth Admin budget (TD-239) ---------------------------------
    # The Admin-SDK calls that read or change a Firebase Auth user — the
    # step-up revocation check, `revoke_sessions`, `delete_auth_user` — share
    # the Auth project's Identity Toolkit quota, which no route bucket stands
    # in front of on its own. `rate_limit.auth_admin_bucket` holds them to
    # this many calls a second per instance, with this burst. Over it, a revoke
    # or a delete answers False (the caller's audit row says so) and the
    # revocation check answers 503 `auth_check_busy`, so one flood cannot
    # spend the quota every step-up needs. Per instance, like every bucket in
    # rate_limit.py.
    # At least 1 each: a 0 (meant as "off") would refuse every call for the
    # life of the instance, so every console step-up would answer 503 and no
    # account deletion would remove its Firebase user.
    AUTH_ADMIN_CALLS_PER_SECOND = max(1, _env_int("AUTH_ADMIN_CALLS_PER_SECOND", "10"))
    AUTH_ADMIN_BURST = max(1, _env_int("AUTH_ADMIN_BURST", "20"))

    # --- signed entitlement (A4.1 / A4.3) -----------------------------------
    # PEM of an EC P-256 private key. When set, GET /v1/config adds
    # `entitlement`: a compact JWS (ES256) the app verifies offline against
    # its pinned public keys (backend/app/entitlement_token.py). Read from
    # Secret Manager at deploy (--set-secrets), never --set-env-vars and never
    # the repo. Empty = no token is issued and nothing fails.
    ENTITLEMENT_SIGNING_KEY = os.environ.get("ENTITLEMENT_SIGNING_KEY", "")
    # Names the key in the token header, so the app can pin the next key
    # beside the current one and a rotation needs no flag day.
    ENTITLEMENT_KID = os.environ.get("ENTITLEMENT_KID", "ent-1")
    # How long a token lets the app run its entitlement offline. A timed
    # licence caps it at its end plus its own grace; a floating seat at the
    # lease's end.
    ENTITLEMENT_OFFLINE_GRACE_DAYS = _env_int("ENTITLEMENT_OFFLINE_GRACE_DAYS", "14")

    # --- release gate and kill switches (A6.1 / A6.4) -----------------------
    # The release gate is published by GET /v1/config and the server refuses
    # nothing on it: the app compares its own versionCode and blocks (below
    # minimum) or nudges (below recommended). 0 = no gate.
    #
    # Per app: Semper and Semper Classroom share this service, and each
    # numbers its builds from its own release run counter, so one number for
    # both would retire the other app's builds by accident. The unsuffixed
    # pair is Semper's; `release_gate(app)` picks the caller's. Semper
    # Classroom's pair was `*_MATERIAL_TESTING` before the rename (ADR-030),
    # read when the new name is unset.
    MIN_SUPPORTED_VERSION_CODE = _env_int("MIN_SUPPORTED_VERSION_CODE", "0")
    RECOMMENDED_VERSION_CODE = _env_int("RECOMMENDED_VERSION_CODE", "0")
    MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM = _env_int_renamed(
        "MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM",
        "MIN_SUPPORTED_VERSION_CODE_MATERIAL_TESTING", "0")
    RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM = _env_int_renamed(
        "RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM",
        "RECOMMENDED_VERSION_CODE_MATERIAL_TESTING", "0")

    def release_gate(self, app: str) -> tuple[int, int]:
        """(minSupportedVersionCode, recommendedVersionCode) for `app`
        (`apps.SEMPER` / `apps.SEMPER_CLASSROOM`, or the old
        `materialtesting`): only that app's builds are compared with them."""
        if canonical(app) == SEMPER_CLASSROOM:
            return (self.MIN_SUPPORTED_VERSION_CODE_SEMPER_CLASSROOM,
                    self.RECOMMENDED_VERSION_CODE_SEMPER_CLASSROOM)
        return self.MIN_SUPPORTED_VERSION_CODE, self.RECOMMENDED_VERSION_CODE
    # Features switched off, fleet-wide, without a release: any of backup,
    # share, restore, upload. Separated by whitespace, `;` or `,` (prefer
    # spaces: the deploy action splits env_vars on commas). An unknown name is
    # dropped with a warning rather than failing the start.
    #
    # Published by GET /v1/config so the app hides the feature, AND enforced
    # here, so a build that ignores the list (or predates it) is refused too:
    # `upload` or `backup` refuses POST /v1/sessions and
    # POST /v1/files/{id}/complete; `restore` refuses
    # GET /v1/files/{id}/content and GET /v1/sessions/{sid}/bundle. The
    # refusal is 503 `feature_paused` with Retry-After (`deps.unless_paused`).
    # `share` has no backend route: it is the app's own export, so it is
    # published only. Changing the list is a new revision (an env change).
    KILL_SWITCHES = parse_kill_switches(os.environ.get("KILL_SWITCHES", ""))

    # Where "a new user is waiting for approval" mail goes. Same address the app
    # shows in Settings -> Help & support and on the pending-approval screen.
    SUPPORT_EMAIL = os.environ.get("SUPPORT_EMAIL", "support@sempermechanics.com")

    # Verified sender for outbound mail, e.g. "semper <noreply@indicvision.com>",
    # and the Resend API key (the one secret this service holds — set it with
    # --set-secrets, never --set-env-vars). Either one empty disables
    # notification mail entirely: nothing is sent and nothing fails.
    NOTIFY_FROM = os.environ.get("NOTIFY_FROM", "")
    RESEND_API_KEY = os.environ.get("RESEND_API_KEY", "")
    # Verified sender for the mail an account's holder gets when a new device
    # registers to it (`notify.device_registered`, TD-295), e.g.
    # "semper security <security@sempermechanics.com>". Its own sender, so
    # the holder's mail does not come from the support inbox's address.
    # Empty (the default) leaves that mail off; it also needs RESEND_API_KEY.
    NOTIFY_SECURITY_FROM = os.environ.get("NOTIFY_SECURITY_FROM", "")

    # The runtime service account email Cloud Run runs as. Used for the keyless
    # self-impersonation that mints Drive-scoped tokens.
    SERVICE_ACCOUNT_EMAIL = os.environ.get("SERVICE_ACCOUNT_EMAIL", "")

    # Google Shared Drive id (the 5 TB company pool) and, optionally, a folder
    # inside it to root everything under. Defaults to the drive root.
    SHARED_DRIVE_ID = os.environ.get("SHARED_DRIVE_ID", "")
    ROOT_FOLDER_ID = os.environ.get("ROOT_FOLDER_ID", "") or SHARED_DRIVE_ID

    GCP_PROJECT = os.environ.get("GOOGLE_CLOUD_PROJECT", "")

    # The Firebase project whose ID tokens we accept (their audience). Usually
    # the same as GCP_PROJECT, but can differ if Firebase Auth lives in a
    # separate project — e.g. when org policy blocks adding Firebase to the main
    # one. Verification only needs the project id, not that project's resources.
    FIREBASE_PROJECT_ID = os.environ.get("FIREBASE_PROJECT_ID", "") or GCP_PROJECT

    # --- async session provisioning (Cloud Tasks) ---
    # Opening one Drive resumable session per file, inline, meant a 600-file
    # analysis made ~1200 sequential round-trips inside a 60s request budget.
    # Provisioning now runs as a Cloud Task; POST /v1/sessions returns
    # immediately with status PROVISIONING and the client polls the existing
    # /uploads resume endpoint.
    #
    # Leave TASKS_QUEUE empty to provision inline (local dev, tests, and any
    # deployment that has not created the queue) — the request is slower but the
    # behaviour is identical from the client's point of view.
    TASKS_QUEUE = os.environ.get("TASKS_QUEUE", "")
    TASKS_LOCATION = os.environ.get("TASKS_LOCATION", "asia-south1")
    # Where Cloud Tasks delivers the job. The service's own base URL.
    TASKS_TARGET_BASE_URL = os.environ.get("TASKS_TARGET_BASE_URL", "")
    # The service account Cloud Tasks signs the OIDC token with, and therefore
    # the only identity /v1/tasks/* accepts.
    TASKS_INVOKER_SA = os.environ.get("TASKS_INVOKER_SA", "") or SERVICE_ACCOUNT_EMAIL
    # Bounded fan-out when the worker opens resumable sessions.
    TASKS_PROVISION_WORKERS = _env_int("TASKS_PROVISION_WORKERS", "8")
    # Manifests this small are provisioned inside the request even when a queue
    # exists. A bundle upload is three files (~1 s of Drive calls once the
    # folders are cached); queueing it instead costs a task hop plus the
    # client's first 1 s poll, so it was slower, not faster. 0 = always queue.
    INLINE_PROVISION_MAX_FILES = _env_int("INLINE_PROVISION_MAX_FILES", "8")

    # A signed call may carry a client-minted nonce `t1.<unix seconds>.<random>`
    # instead of one from POST /v1/challenge, saving a round-trip per call. It
    # is accepted within this many seconds of server time either way; outside
    # it the client falls back to a challenge. 0 disables client nonces.
    CLIENT_NONCE_WINDOW_SECONDS = _env_int("CLIENT_NONCE_WINDOW_SECONDS", "120")

    # How long an `audit_logs` row is kept (TD-237; one year, the owner's
    # decision of 2026-10-22). Each new row carries `expireAt` = now + this,
    # which the Firestore TTL policy on `audit_logs.expireAt` deletes; the
    # locked `audit-trail` log bucket keeps its copy for the same period
    # (docs/backend/FIRESTORE_DATA_PROTECTION.md). Changing it moves only rows
    # written afterwards.
    AUDIT_RETENTION_DAYS = _env_int("AUDIT_RETENTION_DAYS", "365")

    # The recycle bin (ADR-035). A deleted cloud backup is held this many days
    # before it is purged (`deleted_sessions.purgeAt`); once purged, an
    # erased marker of ids alone is kept `ERASED_MARKER_DAYS` (its `expireAt`,
    # the Firestore TTL field), so another phone can tell "deleted" from
    # "lost". Fixed, not read from the environment: the Privacy Policy names
    # the 30 days, so changing one is a policy change, not a deploy setting.
    RECYCLE_BIN_DAYS = 30
    ERASED_MARKER_DAYS = 30
    # How many due bin entries one run of the daily purge job takes on; the
    # rest are due again the next day.
    RECYCLE_BIN_PURGE_BATCH = 100

    @property
    def tasks_enabled(self) -> bool:
        return bool(self.TASKS_QUEUE and self.TASKS_TARGET_BASE_URL and self.GCP_PROJECT)

    # --- pilot / testing switches (turn OFF for production) ---
    # 1 = skip ID-token + device-signature checks entirely. Lets you smoke-test
    # the Drive + Firestore path with curl before wiring the app. NEVER in prod:
    # it hands every caller the dev identity, which carries role=admin.
    DEV_INSECURE_AUTH = os.environ.get("DEV_INSECURE_AUTH", "") == "1"

    # Cloud Run always sets K_SERVICE, so this tells "deployed" from "on my
    # laptop". Bypassing auth on a deployed service additionally requires the
    # acknowledgement below, so a stray DEV_INSECURE_AUTH=1 cannot quietly ship
    # a wide-open backend — startup fails loudly instead (see main._startup).
    ON_CLOUD_RUN = bool(os.environ.get("K_SERVICE"))
    INSECURE_AUTH_ACK = os.environ.get("INSECURE_AUTH_I_ACCEPT_THE_RISK", "") == "1"
    # 1 = new users are created APPROVED instead of PENDING (smooth pilot).
    AUTO_APPROVE = os.environ.get("AUTO_APPROVE", "") == "1"

    # Env vars the service cannot function without: GCP_PROJECT is the token
    # audience for ID-token verification, SERVICE_ACCOUNT_EMAIL mints Drive
    # tokens, SHARED_DRIVE_ID is where every file lives. A deployed service
    # missing any of these serves broken Drive/token calls, so startup rejects
    # it (see main._startup) instead of only warning.
    def missing_required(self) -> list[str]:
        required = ("GCP_PROJECT", "SERVICE_ACCOUNT_EMAIL", "SHARED_DRIVE_ID")
        return [k for k in required if not getattr(self, k)]


settings = Settings()
