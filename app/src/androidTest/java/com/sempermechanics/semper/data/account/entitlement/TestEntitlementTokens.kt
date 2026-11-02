package com.sempermechanics.semper.data.account.entitlement

import android.content.Context
import com.sempermechanics.semper.BuildConfig
import com.sempermechanics.semper.data.account.DeviceKeys
import com.sempermechanics.semper.data.net.AccountCache
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64

/**
 * Entitlement tokens as the backend signs them, under a P-256 key made for the
 * test run and pinned only through [EntitlementStore.testPins] (readiness
 * TE4-101). Test keys and pins only: nothing here is a real signing key.
 */
internal class TestEntitlementTokens(private val context: Context) {

    private val signer: KeyPair = p256()

    /** The pins naming [signer]'s public key under [KID]. */
    val pins: EntitlementPins = EntitlementPins.parse("$KID:" + Base64.getEncoder().encodeToString(signer.public.encoded))

    /** This install's device-key thumbprint, the one a token must name to verify here. */
    fun thisDevice(): String = DeviceKeyThumbprint.of(DeviceKeys(context).publicKeyDer())

    /**
     * A signed token. Times are epoch seconds; by default issued now, with the
     * backend's 14-day offline grace and an expiry at the grace's end.
     */
    @Suppress("LongParameterList") // one argument per claim a test varies
    fun token(
        mode: String = "licensed",
        share: Boolean = true,
        backup: Boolean = true,
        maxAnalyses: Int = 500,
        iat: Long = System.currentTimeMillis() / 1000,
        graceUntil: Long = iat + GRACE_S,
        exp: Long = graceUntil,
        dkt: String = thisDevice(),
    ): String {
        val header = """{"alg":"ES256","kid":"$KID","typ":"semper-ent+jws"}"""
        // The account signed in on this phone, if one is, which `sub` must name.
        val sub = AccountCache.cachedUid(context)?.takeIf { it.isNotBlank() } ?: "uid-device-test"
        val claims = """{"iss":"semper-api","sub":"$sub",""" +
            """"app":"${EntitlementApp.shortId(BuildConfig.APPLICATION_ID)}",""" +
            """"dev":"${DeviceKeys.deviceId(context)}","dkt":"$dkt","mode":"$mode",""" +
            """"features":{"share":$share,"backup":$backup,"maxAnalyses":$maxAnalyses},""" +
            """"lic":"lic-device-test",""" +
            """"iat":$iat,"refreshAfter":${iat + DAY_S},"graceUntil":$graceUntil,"exp":$exp}"""
        val input = Base64Url.encode(header.toByteArray()) + "." + Base64Url.encode(claims.toByteArray())
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(signer.private)
            update(input.toByteArray(Charsets.US_ASCII))
            sign()
        }
        return input + "." + Base64Url.encode(derToRaw(der))
    }

    companion object {
        const val KID = "device-test"
        const val DAY_S = 86_400L

        /** The backend's offline grace after issue. */
        const val GRACE_S = 14 * DAY_S

        fun p256(): KeyPair = KeyPairGenerator.getInstance("EC").run {
            initialize(ECGenParameterSpec("secp256r1"))
            generateKeyPair()
        }

        /** DER `SEQUENCE { INTEGER r, INTEGER s }` to raw `r || s`, 32 bytes each. */
        private fun derToRaw(der: ByteArray): ByteArray {
            var at = 2
            fun next(): ByteArray {
                check(der[at] == 0x02.toByte())
                val len = der[at + 1].toInt()
                val value = der.copyOfRange(at + 2, at + 2 + len)
                at += 2 + len
                val trimmed = value.dropWhile { it == 0.toByte() }.toByteArray()
                return ByteArray(32 - trimmed.size) + trimmed
            }
            return next() + next()
        }
    }
}
