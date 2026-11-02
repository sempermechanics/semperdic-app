package com.sempermechanics.semper.data.net

import kotlinx.serialization.Serializable

/**
 * Wire DTOs for a licence's phones (ADR-033): `/v1/config`'s `activation` and
 * `pendingLicence` blocks, and the `/v1/me/devices*` routes
 * (`backend/app/routers/activations.py`). Every field has a default, so a
 * backend that predates a field, or the block, still decodes; the bodies in
 * `contracts/` (`device_activate_*`, `devices_response`,
 * `device_deactivate_response`) pin today's names on both sides.
 */

/**
 * `/v1/config`'s `activation`: this phone's place in its licence's device
 * count. [state] is one of [STATE_ACTIVE] (this phone is licensed),
 * [STATE_AVAILABLE] (a slot is free: Activate), [STATE_FULL] (every slot is
 * taken: deactivate one first), [STATE_FLOATING] (a floating seat, checked out
 * instead) or [STATE_NONE] (no live licence for this app).
 */
@Serializable
data class ActivationDto(
    val state: String = STATE_NONE,
    val devicesActive: Int = 0,
    val maxDevices: Int = 0,
    /** The count is an institution's pool, shared by its members. */
    val pool: Boolean = false,
    /** `activation`, or `lock` for a licence from before the split (one phone, bound on first use). */
    val model: String = "",
) {
    companion object {
        const val STATE_ACTIVE = "active"
        const val STATE_AVAILABLE = "available"
        const val STATE_FULL = "full"
        const val STATE_FLOATING = "floating"
        const val STATE_NONE = "none"

        /** The model of a licence from before the split, which binds its phone itself. */
        const val MODEL_LOCK = "lock"
    }
}

/**
 * `/v1/config`'s `pendingLicence`: a new licence staff issued this account for
 * this app, which it moves to on Activate (upgrade by issue). Null when none waits.
 */
@Serializable
data class PendingLicenceDto(
    val licenseId: String = "",
    val prefix: String = "",
    val kind: String = "",
    /** ISO-8601, or null for a perpetual licence. */
    val expiresAt: String? = null,
    val maxDevices: Int? = null,
)

/** `POST /v1/me/devices/activate`'s body: `upgrade` moves the account to its [PendingLicenceDto]. */
@Serializable
data class DeviceActivateRequest(val upgrade: Boolean = false)

/** What Activate did: the licence's count after it, and whether the phone was already active. */
@Serializable
data class DeviceActivationDto(
    val licenseId: String = "",
    val deviceId: String = "",
    val already: Boolean = false,
    val devicesActive: Int = 0,
    val maxDevices: Int = 0,
    val pool: Boolean = false,
    val model: String = "",
)

/**
 * `POST /v1/me/devices/activate`'s answer: the activation, and the config this
 * phone holds from now on, so the app unlocks without a second call.
 */
@Serializable
data class DeviceActivateResponse(
    val activation: DeviceActivationDto? = null,
    val config: AppConfigDto,
)

/** One of the account's phones for this app (`GET /v1/me/devices`). */
@Serializable
data class AccountPhoneDto(
    val deviceId: String,
    val model: String = "",
    /** ISO-8601, or null for a phone the activation names but the account no longer registers. */
    val registeredAt: String? = null,
    /** The licence licenses this phone. */
    val activated: Boolean = false,
    /** The phone that asked. */
    val thisDevice: Boolean = false,
    val activatedAt: String? = null,
    /** `self`, `legacy`, `migration`, `it` or `staff`. */
    val activatedBy: String = "",
)

/** `GET /v1/me/devices`: the account's phones for this app and its licence's count. */
@Serializable
data class AccountPhonesDto(
    val licenseId: String = "",
    val devicesActive: Int = 0,
    val maxDevices: Int = 0,
    val pool: Boolean = false,
    /** `activation`, `lock`, or `none` with no live licence (or a floating one). */
    val model: String = "",
    val devices: List<AccountPhoneDto> = emptyList(),
)

/** `DELETE /v1/me/devices/{id}`'s answer: the licence's count after it. */
@Serializable
data class DeviceDeactivateResponse(
    val deviceId: String = "",
    val devicesActive: Int = 0,
    val maxDevices: Int = 0,
)
