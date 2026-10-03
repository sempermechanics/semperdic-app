"""The Android apps that share this backend, and the device fields each one owns.

Semper and Material Testing sign in to the same accounts, but Android scopes
`ANDROID_ID` to the signing key, so one phone reports a different device id to
each app. An account binds one phone **per app** ([ADR-010]): each app has its
own registered device, release hold, licence or seat lock, and self-service
device-change cooldown.

The app is named by the `X-App-Id` header, the app's `applicationId`. No
header is Semper, so every build shipped before the header keeps its binding.
An id not listed here is refused (`unknown_app`) rather than read as Semper:
reading it as Semper would let a build nobody registered take Semper's slot.

Semper's slot is the fields the account has always had (`activeDeviceId`,
`deviceIdLock`, ...), so nothing is migrated. Another app's slot is the same
names with a suffix (`activeDeviceIdMaterialTesting`): flat fields, so every
existing single-field update, query and transaction reads them unchanged.

The header is a claim until App Check enforces the token's `app_id`. A
modified Semper build that calls itself Material Testing takes that app's
slot, which is one more phone and no more (ADR-010, Trade-offs).

[ADR-010]: ../../docs/adr/ADR-010-device-binding-per-app.md
"""
from __future__ import annotations

SEMPER = "semper"
MATERIAL_TESTING = "materialtesting"

#: Every app, Semper first. Staff and IT clears release all of them: the
#: holder's phone changed, and it changed for every app on it.
ALL = (SEMPER, MATERIAL_TESTING)

#: Both apps moved from `com.indicvision.*` to `com.sempermechanics.*`
#: (ADR-019). The old ids stay until no installed build sends them; each maps
#: to the same app, so a phone keeps its slot and its backups across the move.
_BY_APPLICATION_ID = {
    "com.sempermechanics.semper": SEMPER,
    "com.sempermechanics.materialtesting": MATERIAL_TESTING,
    "com.indicvision.semper": SEMPER,
    "com.indicvision.semper.materialtesting": MATERIAL_TESTING,
}

_SUFFIX = {
    SEMPER: "",
    MATERIAL_TESTING: "MaterialTesting",
}


def from_header(value: str | None) -> str | None:
    """The app an `X-App-Id` header names: Semper when it is absent, None
    when it names an app this backend does not know."""
    value = (value or "").strip()
    if not value:
        return SEMPER
    return _BY_APPLICATION_ID.get(value)


def from_name(value: str | None) -> str | None:
    """An app named by its short name (`semper`, `materialtesting`), as the
    consoles send it. Semper when empty, None when unknown."""
    value = (value or "").strip().lower()
    if not value:
        return SEMPER
    return value if value in _SUFFIX else None


def field(name: str, app: str) -> str:
    """The field that holds `name` for `app`: `name` itself for Semper."""
    return name + _SUFFIX[app]


def spread(name: str, values: dict[str, str]) -> dict[str, str]:
    """`{app: value}` as the per-app fields a response or audit record carries,
    every app present: `{"releasedDeviceId": ..., "releasedDeviceIdMaterialTesting": ...}`.
    Semper's value keeps the name callers already read."""
    return {field(name, app): values.get(app) or "" for app in ALL}
