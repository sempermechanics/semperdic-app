package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.security.PublicKey
import java.security.Signature

/** Why a token was not accepted. Logged by name; never shown to the user. */
enum class RejectReason {
    /** Not three base64url parts, or a header that is not a JSON object. */
    MALFORMED,

    /** `alg` is not `ES256`, or the header names a `crit` extension. */
    BAD_ALG,

    /** `typ` is not [EntitlementVerifier.TYP]. */
    BAD_TYP,

    /** No `kid`, or one this build does not pin. */
    UNKNOWN_KID,

    /** The signature is not the pinned key's over this header and payload. */
    BAD_SIGNATURE,

    /** Signed, but a claim is missing or of the wrong type. */
    BAD_CLAIMS,
    WRONG_ISSUER,
    WRONG_APP,
    WRONG_DEVICE,
    WRONG_DEVICE_KEY,

    /** `sub` is not the account signed in on this phone. */
    WRONG_ACCOUNT,
}

/** The outcome of [EntitlementVerifier.verify]. */
sealed interface Verification {
    data class Verified(val kid: String, val claims: EntitlementClaims) : Verification

    data class Rejected(val reason: RejectReason) : Verification
}

/**
 * Checks an entitlement token against the pinned keys and this install.
 *
 * The contract (backend `W1`, and `app/src/test/resources/entitlement_vector.json`):
 * a compact JWS with header `{"alg":"ES256","kid":…,"typ":"semper-ent+jws"}`,
 * an ECDSA P-256 / SHA-256 signature carried raw as `r || s` over
 * ASCII(`header_b64 "." payload_b64`), and the claims in [EntitlementClaims].
 * The signature is checked over the base64url text exactly as received; the
 * backend serialises sorted compact JSON, but nothing here re-serialises.
 *
 * Order matters: nothing in the payload is read until the signature over it
 * has been checked with a key chosen by a pinned `kid`.
 */
class EntitlementVerifier(private val pins: EntitlementPins) {

    /** What the token must name to be this install's. */
    data class Expected(
        /** The short app name ([EntitlementApp.shortId]), not the applicationId. */
        val appId: String,
        val deviceId: String,
        /** [DeviceKeyThumbprint.of] this device's Keystore public key. */
        val deviceKeyThumbprint: String,
        /**
         * The account signed in here (its Firebase uid), which `sub` must
         * name; null when none is known, which checks nothing.
         */
        val accountUid: String? = null,
    )

    fun verify(token: String, expected: Expected): Verification {
        val jws = CompactJws.parse(token) ?: return Verification.Rejected(RejectReason.MALFORMED)
        val kid = jws.header.text("kid")
        return headerProblem(jws, kid)?.let { Verification.Rejected(it) }
            ?: verifiedClaims(kid.orEmpty(), jws, expected)
    }

    /** What is wrong with [jws]'s header or signature, or null when it is the pinned key's. */
    private fun headerProblem(jws: CompactJws, kid: String?): RejectReason? {
        val key = kid?.let { pins.keys[it] }
        return when {
            jws.header.text("alg") != ALG || "crit" in jws.header -> RejectReason.BAD_ALG
            jws.header.text("typ") != TYP -> RejectReason.BAD_TYP
            key == null -> RejectReason.UNKNOWN_KID
            !signatureHolds(key, jws) -> RejectReason.BAD_SIGNATURE
            else -> null
        }
    }

    /** The signed claims, checked against this install. */
    private fun verifiedClaims(kid: String, jws: CompactJws, expected: Expected): Verification {
        val claims = EntitlementClaims.parse(jws.payload) ?: return Verification.Rejected(RejectReason.BAD_CLAIMS)
        val mismatch = when {
            claims.iss != EntitlementClaims.ISSUER -> RejectReason.WRONG_ISSUER
            claims.app != expected.appId -> RejectReason.WRONG_APP
            claims.dev != expected.deviceId -> RejectReason.WRONG_DEVICE
            claims.dkt != expected.deviceKeyThumbprint -> RejectReason.WRONG_DEVICE_KEY
            expected.accountUid != null && claims.sub != expected.accountUid -> RejectReason.WRONG_ACCOUNT
            else -> null
        }
        return if (mismatch != null) Verification.Rejected(mismatch) else Verification.Verified(kid, claims)
    }

    private fun signatureHolds(key: PublicKey, jws: CompactJws): Boolean {
        val der = EcdsaDer.rawToDer(jws.signature) ?: return false
        return runCatching {
            Signature.getInstance(JCA_ALGORITHM).run {
                initVerify(key)
                update(jws.signingInput)
                verify(der)
            }
        }.getOrDefault(false)
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

    companion object {
        const val ALG = "ES256"
        const val TYP = "semper-ent+jws"
        private const val JCA_ALGORITHM = "SHA256withECDSA"
    }
}
