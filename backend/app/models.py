from datetime import datetime, timezone
from typing import Annotated, List, Literal, Optional

from cryptography.hazmat.primitives.asymmetric import ec
from cryptography.hazmat.primitives.serialization import load_pem_public_key
from pydantic import (
    BaseModel, ConfigDict, Field, StringConstraints, field_validator, model_validator,
)

from .config import settings
from .validation import DeviceId, DocumentId, LicenceSearch, PageToken, SessionId

# "bundle" = Session.zip, holding only what a restore needs to rebuild a working
# session: ref/ (the reference; raw/Reference.png before metadata schema /4),
# raw/ (deformed originals) and dat/ (engine results). The backend never reads
# inside it, so "ref" is an archive folder, not a Role.
# "extras" = Extras.zip, holding the derived deliverables — csv/, reports/,
# processed/. Nothing in the app reads those back after a restore (they are
# regenerated on export), so keeping them out of the bundle is what lets a restore
# download only the bytes it actually needs.
#
# Both live at the session root, so a session still costs ~3 Firestore file docs
# rather than 3F+4. Older clients look for exactly one "bundle" and therefore keep
# restoring correctly against a split backup — they simply never see the extras.
Role = Literal["raw", "processed", "reports", "metadata", "csv", "dat", "bundle", "extras"]

_HEX = frozenset("0123456789abcdefABCDEF")
DisplayString = Annotated[str, StringConstraints(max_length=128)]


def _analysis_cap(value: Optional[int]) -> Optional[int]:
    """A licence's per-user analysis cap may not undercut demo.

    `resolve_user_config` floors the ceiling at `DEMO_MAX_ANALYSES` anyway;
    refusing here tells the operator at mint rather than storing a number that
    never takes effect.
    """
    if value is not None and value < settings.DEMO_MAX_ANALYSES:
        raise ValueError(
            f"maxAnalyses must be at least {settings.DEMO_MAX_ANALYSES} (the Demo "
            "allowance); leave it out for the Licensed default"
        )
    return value


class DeviceReg(BaseModel):
    deviceId: DeviceId
    publicKeyPem: str = Field(max_length=4096)
    model: DisplayString = ""
    osVersion: DisplayString = ""
    appVersion: DisplayString = ""

    @field_validator("model", "osVersion", "appVersion")
    @classmethod
    def _display_text(cls, value: str) -> str:
        # Device names and versions are human-facing and may legitimately be
        # Unicode. Reject only control characters that can corrupt logs/docs.
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
            raise ValueError("must not contain control characters")
        return value

    @field_validator("publicKeyPem")
    @classmethod
    def _ec_public_pem(cls, v: str) -> str:
        try:
            key = load_pem_public_key(v.encode())
        except Exception as e:
            raise ValueError("publicKeyPem must be a valid PEM public key") from e
        if not isinstance(key, ec.EllipticCurvePublicKey):
            raise ValueError("publicKeyPem must be an EC public key")
        return v


class FileSpec(BaseModel):
    name: str = Field(min_length=1, max_length=256)
    role: Role
    bytes: int = Field(gt=0, le=5 * 1024 * 1024 * 1024)  # cap 5 GB
    sha256: str = Field(min_length=64, max_length=64)

    @field_validator("name")
    @classmethod
    def _no_path_separators(cls, v: str) -> str:
        # name is interpolated into a Firestore document id ("{sid}_{role}_{name}").
        # A "/" would add path segments (an even-segment path Firestore rejects with
        # a 500), and "." / ".." are reserved id values. Reject those and control
        # chars here so a bad name is a clean 422 at the edge, not a 500 mid-write.
        if "/" in v or "\\" in v:
            raise ValueError("name must not contain path separators")
        if v in (".", ".."):
            raise ValueError("name must not be '.' or '..'")
        if any(ord(c) < 0x20 for c in v):
            raise ValueError("name must not contain control characters")
        return v

    @field_validator("sha256")
    @classmethod
    def _sha256_hex(cls, v: str) -> str:
        if len(v) != 64 or any(c not in _HEX for c in v):
            raise ValueError("sha256 must be 64 hex characters")
        return v


class SessionCreate(BaseModel):
    specimen: str = Field(min_length=1, max_length=200)
    # The analysis's own name, which the backup list shows (TD-208). Optional:
    # an older app sends none, and its sessions are listed by `specimen`.
    name: str = Field(default="", max_length=200)
    files: List[FileSpec] = Field(min_length=1, max_length=5000)
    metrics: dict = {}
    # The app's local analysis id. Lets the client reconcile its local sync
    # state against what actually exists in the cloud (and restore later).
    # Every phone of the account restores it as a directory name, so only the
    # characters the app's ids use (a UUID prefix, `restored-…`): never `.`,
    # `/` or `\`, which once let `..` make filesDir the session dir (L5-1).
    # Empty stays allowed (an older app's backups carry none).
    localSessionId: str = Field(default="", max_length=128, pattern=r"^[A-Za-z0-9_-]*$")

    @field_validator("metrics")
    @classmethod
    def _bounded_metrics(cls, v: dict) -> dict:
        # metrics is written verbatim to the Firestore session doc, so an
        # unbounded client dict could inflate it toward the 1 MiB doc limit.
        # It is meant to be a small map of scalar run stats (pointsConverged,
        # avgIcgnIters, execMs), so reject anything larger or nested.
        if len(v) > 32:
            raise ValueError("metrics has too many keys")
        for key, val in v.items():
            if not isinstance(key, str) or len(key) > 64:
                raise ValueError("metrics keys must be strings <= 64 chars")
            # bool is a subclass of int; strings are length-bounded; no nesting.
            if isinstance(val, str):
                if len(val) > 256:
                    raise ValueError("metrics string values must be <= 256 chars")
            elif not isinstance(val, (int, float, bool)):
                raise ValueError("metrics values must be scalar (num / bool / short str)")
        return v


class FileComplete(BaseModel):
    sessionId: SessionId
    driveFileId: DocumentId
    bytes: int = Field(gt=0)
    md5: Optional[str] = None

    @field_validator("md5")
    @classmethod
    def _md5_hex(cls, v: Optional[str]) -> Optional[str]:
        if v is None:
            return v
        if len(v) != 32 or any(c not in _HEX for c in v):
            raise ValueError("md5 must be 32 hex characters")
        return v


class LicenseFanoutTask(BaseModel):
    """Body of the Cloud Tasks callback that finishes a licence's pending
    fan-out (ADR-026)."""
    licenseId: DocumentId


class ProvisionTask(BaseModel):
    """Body of the Cloud Tasks provisioning callback. Validated like any other
    input — the queue is trusted to be Google, not to be well-behaved."""
    sessionId: SessionId


class AdminDeviceRelease(BaseModel):
    """The account whose phone staff release, by the address the request came from."""
    email: str = Field(min_length=3, max_length=254, pattern=r"^[^@\s]+@[^@\s]+$")


class UserConfigPatch(BaseModel):
    """Admin overrides for per-user product limits. Omitted fields stay unchanged;
    send JSON null to clear an override and re-inherit the fleet default."""
    maxSessions: Optional[int] = Field(default=None, gt=0)
    maxFilesPerSession: Optional[int] = Field(default=None, gt=0)
    maxFrames: Optional[int] = Field(default=None, gt=0)
    datCodecEncodingEnabled: Optional[bool] = None
    mode: Optional[Literal["demo", "licensed"]] = None

    #: An unknown key is a 422, not silently dropped: the pre-rename `plan`
    #: patch was retired (TD-45), and an operator who still sends it must hear
    #: that the mode did not change rather than get a 200 that changed nothing.
    model_config = ConfigDict(extra="forbid")


class LicenseActivate(BaseModel):
    """Typed license key from Settings. Canonicalised server-side before hash."""
    key: str = Field(min_length=8, max_length=64)


class LicenseClaim(BaseModel):
    """A claim code typed on the web account page (`POST /v1/me/claim`).

    The same code a key is, under the name the page gives it, so the same
    bounds; canonicalised server-side (`licenses.canonicalize`) before it is
    hashed, so case, spaces and dashes do not matter. Any other field is 422.
    """
    model_config = ConfigDict(extra="forbid")

    code: str = Field(min_length=8, max_length=64)


def _admin_email_list(value: List[str]) -> List[str]:
    """IT contacts for an institution licence, normalised as they are stored."""
    out = []
    for raw in value:
        email = (raw or "").strip().lower()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("adminEmails entries must be email addresses")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
            raise ValueError("adminEmails must not contain control characters")
        out.append(email)
    return list(dict.fromkeys(out))


def _bare_domain(value: str) -> str:
    domain = value.strip().lower()
    if "@" in domain or domain.startswith(".") or domain.endswith(".") or "." not in domain:
        raise ValueError("domainLock must be a bare domain, e.g. university.edu")
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in domain):
        raise ValueError("domainLock must not contain control characters")
    return domain


class AdminLicenseCreate(BaseModel):
    """Ops mint for a licensed key.

    `kind="individual"` (the default, and the only shape before institution
    licensing existed) requires both `emailLock` and `deviceIdLock` — one
    seat, redeemed by exactly one email on exactly one device.

    `kind="institution"` mints an institution key instead: no email/device
    lock at mint time. Membership is decided per-activation by `domainLock` (a
    verified-email domain match — see repo.activate_license), and
    `adminEmails` names the institution IT contacts who may manage seats via
    `backend/app/routers/institutions.py`
    (list/clear-device/enable-disable/revoke). `maxSeats` is an optional hard
    cap; omitted means unlimited.

    The pre-rename `"campus"` spelling is refused (422) since TD-45 retired it.
    """
    kind: Literal["individual", "institution"] = "individual"
    #: Orthogonal to `kind`. `timed` requires a future `expiresAt`; `perpetual`
    #: must not carry one. Default is perpetual — the shape that cannot expire
    #: on someone by accident.
    duration: Literal["perpetual", "timed"] = "perpetual"
    emailLock: Optional[str] = Field(default=None, min_length=3, max_length=320)
    deviceIdLock: Optional[DeviceId] = None
    domainLock: Optional[str] = Field(default=None, min_length=1, max_length=253)
    adminEmails: List[str] = Field(default_factory=list, max_length=20)
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)
    #: Institution licenses only. `assigned` (the default) entitles every
    #: member of the roster. `floating` entitles only the `maxSeats` members
    #: holding a live lease; the rest are demo until one frees up.
    seating: Literal["assigned", "floating"] = "assigned"
    expiresAt: Optional[datetime] = None
    #: Days after `expiresAt` that entitlement continues, unchanged, so a
    #: renewal in flight does not interrupt work. Omitted uses the fleet
    #: default; 0 is a hard cliff.
    graceDays: Optional[int] = Field(default=None, ge=0, le=365)
    #: Informational. Never gates anything — a perpetual license whose support
    #: has lapsed still grants full use.
    supportUntil: Optional[datetime] = None
    maxAnalyses: Optional[int] = Field(default=None, gt=0)
    note: DisplayString = ""
    #: The app this licence is for (ADR-033): it then licenses only the
    #: phones activated on it, up to `maxDevices`. Omitted, the licence holds
    #: for every app and locks to one phone per app, as before the split.
    #: `materialtesting` is Semper Classroom's old name (ADR-030).
    app: Optional[Literal["semper", "semperclassroom", "materialtesting"]] = None
    #: Phones that may be active at once; with `app` only. Default 1 on an
    #: individual licence and `maxSeats` on an assigned institution one, a
    #: pool across the licence; a floating licence counts its leases instead.
    maxDevices: Optional[int] = Field(default=None, ge=0, le=100000)

    @field_validator("expiresAt", "supportUntil")
    @classmethod
    def _aware_utc(cls, value: Optional[datetime]) -> Optional[datetime]:
        """Coerce a naive datetime to UTC at the boundary.

        A payload may legitimately omit an offset. Letting a naive value
        through means every later comparison has to coerce or raise TypeError,
        so it is settled once, here.
        """
        if value is None or value.tzinfo is not None:
            return value
        return value.replace(tzinfo=timezone.utc)

    @field_validator("maxAnalyses")
    @classmethod
    def _max_analyses(cls, value: Optional[int]) -> Optional[int]:
        return _analysis_cap(value)

    @field_validator("emailLock")
    @classmethod
    def _email_lock(cls, value: Optional[str]) -> Optional[str]:
        if value is None:
            return value
        email = value.strip().lower()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("emailLock must be an email address")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
            raise ValueError("emailLock must not contain control characters")
        return email

    @field_validator("domainLock")
    @classmethod
    def _domain_lock(cls, value: Optional[str]) -> Optional[str]:
        return None if value is None else _bare_domain(value)

    @field_validator("adminEmails")
    @classmethod
    def _admin_emails(cls, value: List[str]) -> List[str]:
        return _admin_email_list(value)

    @model_validator(mode="after")
    def _duration_requires_matching_expiry(self) -> "AdminLicenseCreate":
        """A timed license without an expiry would be perpetual by accident,
        and a perpetual one carrying an expiry says two contradictory things.
        Reject both rather than silently picking a winner."""
        if self.duration == "timed":
            if self.expiresAt is None:
                raise ValueError("timed licenses require expiresAt")
            if self.expiresAt <= datetime.now(timezone.utc):
                raise ValueError("expiresAt must be in the future")
        elif self.expiresAt is not None:
            raise ValueError('perpetual licenses must not set expiresAt (use duration="timed")')
        return self

    @model_validator(mode="after")
    def _kind_requires_matching_locks(self) -> "AdminLicenseCreate":
        if self.kind == "individual":
            # `emailLock` alone. A device lock is accepted but no longer
            # demanded, because demanding it meant the customer had to read a
            # device id off their phone and send it to us before we could mint
            # anything. Left empty, the licence binds to the first device that
            # signs in as `emailLock` — see repo.devlock._device_lock_state.
            if not self.emailLock:
                raise ValueError("individual licenses require emailLock")
        else:  # institution
            if not self.domainLock:
                raise ValueError("institution licenses require domainLock")
            if not self.adminEmails:
                raise ValueError("institution licenses require at least one adminEmails entry")
        if self.seating == "floating":
            if self.kind != "institution":
                raise ValueError('only institution licenses may be floating')
            if self.maxSeats is None:
                # A floating pool with no cap is an assigned license with extra
                # steps: nobody would ever be refused a lease.
                raise ValueError("floating licenses require maxSeats")
        if self.maxDevices is not None:
            if not self.app:
                raise ValueError("maxDevices goes with an app (ADR-033)")
            if self.seating == "floating":
                raise ValueError("a floating licence counts leases, not maxDevices")
        return self


class InstitutionSeatPatch(BaseModel):
    """Institution IT self-service seat edit. Both fields optional; send only
    what changes. `clearDeviceLock=true` lets a seat holder re-bind to a new
    device (lost phone, factory reset) without a support ticket to Semper
    staff. `enabled=false` drops the seat to Demo without freeing the slot —
    see routers/institutions.py and repo.set_seat_enabled."""
    clearDeviceLock: Optional[bool] = None
    enabled: Optional[bool] = None


class InstitutionSeatAdd(BaseModel):
    """Institution IT adding a member to the roster, by email.

    The person must already have an account — everyone can sign up and use
    demo, so "sign in once, then I'll add you" is the flow rather than an
    invite system. Adding by email keeps IT working from the address they
    already have; the backend resolves it to a uid.

    On a floating license this grants eligibility, not a slot: the member
    still checks out a lease when they want to work.
    """
    email: str = Field(min_length=3, max_length=320)

    @field_validator("email")
    @classmethod
    def _email(cls, value: str) -> str:
        email = value.strip().lower()
        if "@" not in email or email.startswith("@") or email.endswith("@"):
            raise ValueError("email must be an email address")
        if any(ord(char) < 0x20 or ord(char) == 0x7F for char in email):
            raise ValueError("email must not contain control characters")
        return email


class AdminLicenseUpdate(BaseModel):
    """Ops edit of an already-minted license — renewal, mostly.

    Unknown fields are refused (422) rather than dropped: a desk that sends a
    field this model does not know was being told "saved" for a change that
    never happened.

    Before this existed a timed license could only be replaced, which meant
    issuing a new key and re-activating every holder. Extending `expiresAt`
    here re-entitles everyone already on the license in place.

    Terms, and how an institution licence is run. It cannot change `kind`,
    the domain or email locks, or the key — those decide *who* the license is
    for, and changing them under existing holders is a different operation
    (individual to institution is `POST .../convert`). Send only what
    changes; at least one field is required.

    - `perpetual=true` drops the expiry and grace: the licence never ends.
    - `allowShorten=true` lets `expiresAt` move earlier, or give a perpetual
      licence an end date. A date already past is still refused — ending a
      licence now is revoke.
    - `seating`, `maxSeats` and `adminEmails` are for institution licences.
      Switching to `assigned` needs `maxSeats` at least the roster; switching
      to `floating` needs a `maxSeats`, and every member then checks out a
      lease to work.

    `clearDeviceLock=true` is the exception that proves the rule: the device
    lock says *where* the licence may be used, not who for, and staff have to
    be able to move a customer to a new phone. It is not a term — nothing is
    mirrored onto the holder and nothing is revoked — so it travels alongside
    the terms rather than among them, and it only applies to an individual
    licence, whose lock lives on the licence document. An institution licence
    keeps its locks on the seats.
    """
    expiresAt: Optional[datetime] = None
    graceDays: Optional[int] = Field(default=None, ge=0, le=365)
    supportUntil: Optional[datetime] = None
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)
    maxAnalyses: Optional[int] = Field(default=None, gt=0)
    #: Drop the licence's analysis cap so holders get the licensed default.
    #: A flag, not `maxAnalyses: null`, because a null here already means
    #: "unchanged" for every other term.
    clearMaxAnalyses: Optional[bool] = None
    note: Optional[DisplayString] = None
    clearDeviceLock: Optional[bool] = None
    adminEmails: Optional[List[str]] = Field(default=None, min_length=1, max_length=20)
    seating: Optional[Literal["assigned", "floating"]] = None
    perpetual: Optional[Literal[True]] = None
    allowShorten: Optional[Literal[True]] = None
    #: The licence's device count (ADR-033), on a licence that names its
    #: app; not below the phones active on it now.
    maxDevices: Optional[int] = Field(default=None, ge=0, le=100000)

    model_config = ConfigDict(extra="forbid")

    @field_validator("expiresAt", "supportUntil")
    @classmethod
    def _aware_utc(cls, value: Optional[datetime]) -> Optional[datetime]:
        if value is None or value.tzinfo is not None:
            return value
        return value.replace(tzinfo=timezone.utc)

    @field_validator("maxAnalyses")
    @classmethod
    def _max_analyses(cls, value: Optional[int]) -> Optional[int]:
        return _analysis_cap(value)

    @field_validator("adminEmails")
    @classmethod
    def _admin_emails(cls, value: Optional[List[str]]) -> Optional[List[str]]:
        return None if value is None else _admin_email_list(value)

    @model_validator(mode="after")
    def _at_least_one_field(self) -> "AdminLicenseUpdate":
        if all(
            getattr(self, name) is None
            for name in ("expiresAt", "graceDays", "supportUntil", "maxSeats",
                         "maxAnalyses", "clearMaxAnalyses", "note", "clearDeviceLock",
                         "adminEmails", "seating", "perpetual", "maxDevices")
        ):
            raise ValueError("at least one field must be set")
        if self.clearMaxAnalyses and self.maxAnalyses is not None:
            raise ValueError("send maxAnalyses or clearMaxAnalyses, not both")
        if self.perpetual and (self.expiresAt is not None or self.graceDays is not None):
            raise ValueError("a perpetual licence has no expiresAt or graceDays")
        if self.allowShorten and self.expiresAt is None:
            raise ValueError("allowShorten goes with an expiresAt")
        return self


class AdminLicenseSearch(BaseModel):
    """`POST /v1/admin/licenses/search`: the desk's search, in a body (TD-244).

    The same fields and limits as `GET /v1/admin/licenses`'s query, so the
    two answer alike; an address in a body stays out of every request log
    that records the URL (uvicorn's, Cloud Run's, API Gateway's). `q` is
    required here, and an empty one pages the listing as the GET does.
    """
    q: LicenceSearch
    limit: int = 50
    page_token: PageToken = ""
    include_demo: bool = False
    include_revoked: bool = True
    include_test: bool = False

    model_config = ConfigDict(extra="forbid")


class AdminLicenseConvert(BaseModel):
    """Turn an individual licence into an institution licence.

    The individual licence's terms carry over; these are the fields an
    institution licence has and an individual one does not. The holder must
    have an address on `domainLock`.
    """
    domainLock: str = Field(min_length=1, max_length=253)
    adminEmails: List[str] = Field(min_length=1, max_length=20)
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)
    seating: Literal["assigned", "floating"] = "assigned"

    model_config = ConfigDict(extra="forbid")

    @field_validator("domainLock")
    @classmethod
    def _domain_lock(cls, value: str) -> str:
        return _bare_domain(value)

    @field_validator("adminEmails")
    @classmethod
    def _admin_emails(cls, value: List[str]) -> List[str]:
        return _admin_email_list(value)

    @model_validator(mode="after")
    def _floating_needs_seats(self) -> "AdminLicenseConvert":
        if self.seating == "floating" and self.maxSeats is None:
            raise ValueError("floating licenses require maxSeats")
        return self


class AdminLicenseUpgrade(BaseModel):
    """Issue the holder of an individual licence a new one for the same app
    (ADR-033). What the old licence carries over unless named here: its
    device count, end, analysis cap and note. `kind="institution"` makes it
    an assigned institution licence on `domainLock`, the holder's domain,
    administered by `adminEmails`. The holder moves when they tap Activate.
    """
    kind: Literal["individual", "institution"] = "individual"
    maxDevices: Optional[int] = Field(default=None, ge=0, le=100000)
    expiresAt: Optional[datetime] = None
    perpetual: Optional[Literal[True]] = None
    maxAnalyses: Optional[int] = Field(default=None, gt=0)
    note: Optional[DisplayString] = None
    domainLock: Optional[str] = Field(default=None, min_length=1, max_length=253)
    adminEmails: List[str] = Field(default_factory=list, max_length=20)
    maxSeats: Optional[int] = Field(default=None, gt=0, le=100000)

    model_config = ConfigDict(extra="forbid")

    @field_validator("expiresAt")
    @classmethod
    def _aware_utc(cls, value: Optional[datetime]) -> Optional[datetime]:
        if value is None or value.tzinfo is not None:
            return value
        return value.replace(tzinfo=timezone.utc)

    @field_validator("domainLock")
    @classmethod
    def _domain_lock(cls, value: Optional[str]) -> Optional[str]:
        return None if value is None else _bare_domain(value)

    @field_validator("adminEmails")
    @classmethod
    def _admin_emails(cls, value: List[str]) -> List[str]:
        return _admin_email_list(value) if value else value

    @model_validator(mode="after")
    def _shape(self) -> "AdminLicenseUpgrade":
        if self.expiresAt is not None and self.perpetual:
            raise ValueError("expiresAt and perpetual are exclusive")
        institutional = self.domainLock or self.adminEmails or self.maxSeats is not None
        if self.kind == "institution" and not (self.domainLock and self.adminEmails):
            raise ValueError("an institution licence needs domainLock and adminEmails")
        if self.kind == "individual" and institutional:
            raise ValueError("domainLock, adminEmails and maxSeats are for an institution licence")
        return self

    def terms(self) -> dict:
        """What overrides the old licence's terms, as `issue_upgrade` takes it."""
        named = {"maxDevices": self.maxDevices, "expiresAt": self.expiresAt,
                 "perpetual": self.perpetual, "maxAnalyses": self.maxAnalyses, "note": self.note}
        return {k: v for k, v in named.items() if v is not None}

    def institution(self) -> Optional[dict]:
        if self.kind != "institution":
            return None
        return {"domainLock": self.domainLock, "adminEmails": self.adminEmails,
                "maxSeats": self.maxSeats}


class DeviceActivate(BaseModel):
    """Activate's body, optional (ADR-033). `upgrade` activates this phone on
    the new licence issued to the account, which moves it there."""
    upgrade: bool = False

    model_config = ConfigDict(extra="forbid")


def _no_control_chars(value: str, name: str) -> str:
    if any(ord(char) < 0x20 or ord(char) == 0x7F for char in value):
        raise ValueError(f"{name} must not contain control characters")
    return value


class AdminTestAccountCreate(BaseModel):
    """`POST /v1/admin/test-accounts` (ADR-027): a QC or CI login staff hand
    over. `days` is how long it lasts, 1..90: 30 when a QC account names
    none, and no end at all when a CI account names none. `maxDevices` is how
    many phones per app may be signed in at once. `note` says who or what it
    is for, never a password; at most 200 characters.

    A QC account's licence runs the activation model (ADR-033): one licence
    per app in `apps`, each licensing `licenceMaxDevices` activated phones,
    or none at all (`licence: "none"`), which is Demo. A CI account keeps one
    licence for every app that no phone is bound to, so Tier 3 can rotate
    emulators through it; the three licence fields are QC's alone."""
    email: str = Field(min_length=3, max_length=254)
    purpose: Literal["qc", "ci"]
    days: Optional[int] = Field(default=None, ge=1, le=90)
    maxDevices: int = Field(default=3, ge=1, le=10)
    note: str = Field(default="", max_length=200)
    apps: Optional[Literal["semper", "semperclassroom", "both"]] = None
    licence: Optional[Literal["individual", "none"]] = None
    licenceMaxDevices: Optional[int] = Field(default=None, ge=1, le=10)

    model_config = ConfigDict(extra="forbid")

    @model_validator(mode="after")
    def _qc_licence_fields(self) -> "AdminTestAccountCreate":
        named = self.apps is not None or self.licence is not None or self.licenceMaxDevices is not None
        if self.purpose == "ci" and named:
            raise ValueError("apps, licence and licenceMaxDevices are for a QC account")
        return self

    def licence_apps(self) -> tuple[str, ...]:
        """The apps a QC account's licences are for, none for Demo; empty for
        a CI account, whose one licence holds for every app."""
        if self.purpose != "qc" or self.licence == "none":
            return ()
        chosen = self.apps or "both"
        return ("semper", "semperclassroom") if chosen == "both" else (chosen,)

    @field_validator("email")
    @classmethod
    def _email(cls, value: str) -> str:
        email = value.strip().lower()
        if email.count("@") != 1 or email.startswith("@") or email.endswith("@"):
            raise ValueError("email must be an email address")
        if any(char.isspace() for char in email):
            raise ValueError("email must not contain whitespace")
        return _no_control_chars(email, "email")

    @field_validator("note")
    @classmethod
    def _note(cls, value: str) -> str:
        return _no_control_chars(value.strip(), "note")


class AdminTestAccountExtend(BaseModel):
    """`POST /v1/admin/test-accounts/{uid}/extend`: the account now ends
    `days` from now, at most 90."""
    days: int = Field(ge=1, le=90)

    model_config = ConfigDict(extra="forbid")
