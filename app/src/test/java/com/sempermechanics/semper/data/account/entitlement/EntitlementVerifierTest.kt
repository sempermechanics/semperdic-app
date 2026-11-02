package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.Signature
import java.util.Base64

class EntitlementVerifierTest {

    private val tokens = TestTokens()

    private fun verify(token: String, expected: EntitlementVerifier.Expected = tokens.expected) =
        tokens.verifier().verify(token, expected)

    private fun rejected(reason: RejectReason) = Verification.Rejected(reason)

    @Test
    fun `a token signed by a pinned key round-trips`() {
        val result = verify(tokens.token())
        assertTrue("$result", result is Verification.Verified)
        val claims = (result as Verification.Verified).claims
        assertEquals("uid-1", claims.sub)
        assertEquals("lic-1", claims.lic)
        assertEquals(TestTokens.IAT + TestTokens.GRACE_DAYS * TestTokens.DAY_S, claims.graceUntil)
    }

    @Test
    fun `a demo token carries a null lic`() {
        val result = verify(tokens.token(claims = tokens.claims(mode = "demo"))) as Verification.Verified
        assertNull(result.claims.lic)
        assertEquals(false, result.claims.isLicensed)
    }

    @Test
    fun `a rotated key is accepted beside the old one`() {
        val next = TestTokens(kid = "next")
        val both = EntitlementPins.parse(
            "test-kid:" + Base64.getEncoder().encodeToString(tokens.signer.public.encoded) +
                ", next:" + Base64.getEncoder().encodeToString(next.signer.public.encoded),
        )
        val token = tokens.token(header = tokens.header(kid = "next"), key = next.signer)
        assertTrue(EntitlementVerifier(both).verify(token, tokens.expected) is Verification.Verified)
        assertTrue(EntitlementVerifier(both).verify(tokens.token(), tokens.expected) is Verification.Verified)
    }

    @Test
    fun `an unknown kid is refused even with a good signature`() {
        assertEquals(rejected(RejectReason.UNKNOWN_KID), verify(tokens.token(header = tokens.header(kid = "other"))))
    }

    @Test
    fun `a kid pinned to another key fails on the signature`() {
        val token = tokens.token(key = TestTokens.p256())
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verify(token))
    }

    @Test
    fun `a tampered payload is refused`() {
        val (h, _, s) = tokens.token().split('.')
        val forged = Base64Url.encode(tokens.claims(maxAnalyses = 100_000).toString().toByteArray())
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verify("$h.$forged.$s"))
    }

    @Test
    fun `a tampered header is refused`() {
        val (_, p, s) = tokens.token().split('.')
        val header = buildJsonObject {
            put("alg", "ES256")
            put("kid", "test-kid")
            put("typ", "semper-ent+jws")
            put("x", 1)
        }
        val forged = Base64Url.encode(header.toString().toByteArray()) + ".$p.$s"
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verify(forged))
    }

    @Test
    fun `alg none, HS256 and a crit header are refused`() {
        assertEquals(rejected(RejectReason.BAD_ALG), verify(tokens.token(header = tokens.header(alg = "none"))))
        assertEquals(rejected(RejectReason.BAD_ALG), verify(tokens.token(header = tokens.header(alg = "HS256"))))
        val crit = buildJsonObject {
            tokens.header().forEach { (k, v) -> put(k, v) }
            put("crit", "exp")
        }
        assertEquals(rejected(RejectReason.BAD_ALG), verify(tokens.token(header = crit)))
    }

    @Test
    fun `a wrong typ is refused`() {
        assertEquals(rejected(RejectReason.BAD_TYP), verify(tokens.token(header = tokens.header(typ = "JWT"))))
    }

    @Test
    fun `issuer, app, device and device key must all be this install's`() {
        val o = { key: String, value: String -> tokens.claims(overrides = mapOf(key to JsonPrimitive(value))) }
        assertEquals(rejected(RejectReason.WRONG_ISSUER), verify(tokens.token(claims = o("iss", "evil"))))
        assertEquals(rejected(RejectReason.WRONG_APP), verify(tokens.token(claims = o("app", "materialtesting"))))
        assertEquals(rejected(RejectReason.WRONG_DEVICE), verify(tokens.token(claims = o("dev", "and-other"))))
        assertEquals(rejected(RejectReason.WRONG_DEVICE_KEY), verify(tokens.token(claims = o("dkt", "AAAA"))))
    }

    @Test
    fun `a token for another account is refused, and none is checked with no account known`() {
        val other = tokens.expected.copy(accountUid = "uid-2")
        assertEquals(rejected(RejectReason.WRONG_ACCOUNT), verify(tokens.token(), other))
        assertTrue(verify(tokens.token(), tokens.expected.copy(accountUid = "uid-1")) is Verification.Verified)
        assertTrue(verify(tokens.token(), tokens.expected.copy(accountUid = null)) is Verification.Verified)
    }

    @Test
    fun `the same token on another device is refused`() {
        val elsewhere = tokens.expected.copy(deviceId = "and-second-phone")
        assertEquals(rejected(RejectReason.WRONG_DEVICE), verify(tokens.token(), elsewhere))
        val otherKey = tokens.expected.copy(
            deviceKeyThumbprint = DeviceKeyThumbprint.of(TestTokens.p256().public.encoded),
        )
        assertEquals(rejected(RejectReason.WRONG_DEVICE_KEY), verify(tokens.token(), otherKey))
    }

    @Test
    fun `claims of the wrong type are refused`() {
        val quoted = tokens.claims(overrides = mapOf("iat" to JsonPrimitive("1800000000")))
        assertEquals(rejected(RejectReason.BAD_CLAIMS), verify(tokens.token(claims = quoted)))
        val fraction = tokens.claims(overrides = mapOf("exp" to JsonPrimitive(1.5)))
        assertEquals(rejected(RejectReason.BAD_CLAIMS), verify(tokens.token(claims = fraction)))
        val missing = buildJsonObject {
            tokens.claims().filterKeys { it != "features" }.forEach { (k, v) -> put(k, v) }
        }
        assertEquals(rejected(RejectReason.BAD_CLAIMS), verify(tokens.token(claims = missing)))
    }

    @Test
    fun `malformed shapes are refused before any key is chosen`() {
        val good = tokens.token()
        listOf("", "a.b", "$good.extra", good.replaceFirst('.', '+'), "$good=", "bm90IGpzb24.e30.AA").forEach {
            assertEquals(it, rejected(RejectReason.MALFORMED), verify(it))
        }
    }

    @Test
    fun `a signature of the wrong length is refused`() {
        val (h, p, s) = tokens.token().split('.')
        val short = Base64Url.encode(Base64Url.decode(s)!!.copyOf(63))
        assertEquals(rejected(RejectReason.BAD_SIGNATURE), verify("$h.$p.$short"))
    }

    @Test
    fun `raw to DER keeps integers minimal and positive`() {
        repeat(50) {
            val key = TestTokens.p256()
            val message = "m$it".toByteArray()
            val der = Signature.getInstance("SHA256withECDSA").run {
                initSign(key.private)
                update(message)
                sign()
            }
            assertArrayEquals(der, EcdsaDer.rawToDer(TestTokens.derToRaw(der)))
        }
        val highBits = ByteArray(64) { 0x80.toByte() }
        val der = EcdsaDer.rawToDer(highBits)!!
        assertEquals(0x30, der[0].toInt())
        assertEquals(33, der[3].toInt())
        assertEquals(0, der[4].toInt())
        val leadingZeros = ByteArray(64).also {
            it[31] = 1
            it[63] = 2
        }
        assertArrayEquals(byteArrayOf(0x30, 6, 2, 1, 1, 2, 1, 2), EcdsaDer.rawToDer(leadingZeros))
        assertNull(EcdsaDer.rawToDer(ByteArray(65)))
    }

    @Test
    fun `base64url refuses padding and the standard alphabet`() {
        assertArrayEquals("hi".toByteArray(), Base64Url.decode("aGk"))
        assertNull(Base64Url.decode("aGk="))
        assertNull(Base64Url.decode("a+/A"))
        assertNull(Base64Url.decode("a"))
        assertEquals("aGk", Base64Url.encode("hi".toByteArray()))
    }

    @Test
    fun `pins skip bad entries and keep the good ones`() {
        val good = Base64.getEncoder().encodeToString(tokens.signer.public.encoded)
        val rsa = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(1024) }.generateKeyPair()
        val p384 = java.security.KeyPairGenerator.getInstance("EC").apply {
            initialize(java.security.spec.ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val pins = EntitlementPins.parse(
            " a:$good , b:notbase64, :$good, c:" + Base64.getEncoder().encodeToString(rsa.public.encoded) +
                ", d:" + Base64.getEncoder().encodeToString(p384.public.encoded) + ", a:$good,,",
        )
        assertEquals(setOf("a"), pins.keys.keys)
        assertEquals(listOf("b", ":$good".take(8), "c", "d", "a"), pins.invalid)
    }

    @Test
    fun `an empty pin set is empty`() {
        assertTrue(EntitlementPins.parse("").isEmpty)
        assertTrue(EntitlementPins.parse(" , ").isEmpty)
        assertTrue(EntitlementPins.NONE.isEmpty)
        assertEquals(
            rejected(RejectReason.UNKNOWN_KID),
            EntitlementVerifier(EntitlementPins.NONE).verify(tokens.token(), tokens.expected),
        )
    }

    @Test
    fun `the short app id mirrors the backend's map`() {
        assertEquals("semper", EntitlementApp.shortId("com.sempermechanics.semper"))
        assertEquals("semperclassroom", EntitlementApp.shortId("com.sempermechanics.semperclassroom"))
        assertEquals("materialtesting", EntitlementApp.shortId("com.sempermechanics.materialtesting"))
        assertEquals("semper", EntitlementApp.shortId("com.indicvision.semper"))
        assertEquals("com.example.other", EntitlementApp.shortId("com.example.other"))
    }
}
