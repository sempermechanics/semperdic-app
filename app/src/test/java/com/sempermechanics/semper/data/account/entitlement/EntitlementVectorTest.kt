package com.sempermechanics.semper.data.account.entitlement

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * The verifier against tokens signed outside Kotlin: the app's own vector
 * (`entitlement_vector.json`, from `entitlement_vector_gen.py`, Python
 * `cryptography`) and the backend's (`entitlement_vector_backend.json`, a copy
 * of `backend/tests/fixtures/entitlement_vector.json`). If either side changes
 * the encoding, the header, the claims or the signature format, this fails.
 */
class EntitlementVectorTest {

    private fun resource(name: String): JsonObject {
        val text = checkNotNull(javaClass.getResource("/$name")) { "$name missing" }.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.text(key: String): String = getValue(key).jsonPrimitive.content

    private fun appVerifier(vector: JsonObject) =
        EntitlementVerifier(EntitlementPins.parse(vector.text("kid") + ":" + vector.text("publicKeySpkiB64")))

    private fun pemBody(pem: String): String =
        pem.lineSequence().filterNot { it.startsWith("-----") }.joinToString("").trim()

    @Test
    fun `the app vector verifies and names its claims`() {
        val vector = resource("entitlement_vector.json")
        val verifier = appVerifier(vector)
        val payload = vector.getValue("payload").jsonObject
        val devicePublic = Base64.getDecoder().decode(vector.text("devicePublicKeySpkiB64"))
        val expected = EntitlementVerifier.Expected(
            appId = EntitlementApp.shortId("com.sempermechanics.semper"),
            deviceId = payload.text("dev"),
            deviceKeyThumbprint = DeviceKeyThumbprint.of(devicePublic),
        )

        val result = verifier.verify(vector.text("token"), expected)

        assertTrue("$result", result is Verification.Verified)
        val claims = (result as Verification.Verified).claims
        assertEquals("app-vector-1", result.kid)
        assertEquals(payload.text("sub"), claims.sub)
        assertEquals(payload.getValue("graceUntil").jsonPrimitive.long, claims.graceUntil)
        assertEquals(500, claims.features.maxAnalyses)
        assertTrue(claims.isLicensed)
    }

    @Test
    fun `every signed reject in the app vector fails for its own reason`() {
        val vector = resource("entitlement_vector.json")
        val verifier = appVerifier(vector)
        val payload = vector.getValue("payload").jsonObject
        val expected = EntitlementVerifier.Expected(
            appId = "semper",
            deviceId = payload.text("dev"),
            deviceKeyThumbprint = payload.text("dkt"),
            accountUid = payload.text("sub"),
        )
        val rejects = vector.getValue("rejects").jsonObject
        assertEquals(RejectReason.entries.size, rejects.size)
        rejects.forEach { (reason, token) ->
            assertEquals(
                reason,
                Verification.Rejected(RejectReason.valueOf(reason)),
                verifier.verify(token.jsonPrimitive.content, expected),
            )
        }
    }

    @Test
    fun `the backend vector verifies byte for byte`() {
        val vector = resource("entitlement_vector_backend.json")
        val pins = EntitlementPins.parse(vector.text("kid") + ":" + pemBody(vector.text("publicKeyPem")))
        assertTrue(pins.invalid.toString(), pins.invalid.isEmpty())
        val payload = vector.getValue("payload").jsonObject
        val devicePublic = Base64.getDecoder().decode(pemBody(vector.text("devicePublicKeyPem")))
        val expected = EntitlementVerifier.Expected(
            appId = EntitlementApp.shortId("com.sempermechanics.semper"),
            deviceId = payload.text("dev"),
            deviceKeyThumbprint = DeviceKeyThumbprint.of(devicePublic),
        )
        assertEquals(payload.text("dkt"), expected.deviceKeyThumbprint)

        val result = EntitlementVerifier(pins).verify(vector.text("token"), expected)

        assertTrue("$result", result is Verification.Verified)
        val claims = (result as Verification.Verified).claims
        assertEquals("test-ent-1", result.kid)
        assertEquals(vector.getValue("verifyAt").jsonPrimitive.long, claims.iat)
        assertEquals(vector.getValue("expiredAt").jsonPrimitive.long, claims.exp)
        assertEquals(999, claims.features.maxAnalyses)
        assertEquals(payload.text("lic"), claims.lic)
    }

    @Test
    fun `the backend's tampered token is refused on its signature`() {
        val vector = resource("entitlement_vector_backend.json")
        val pins = EntitlementPins.parse(vector.text("kid") + ":" + pemBody(vector.text("publicKeyPem")))
        val payload = vector.getValue("payload").jsonObject
        val expected = EntitlementVerifier.Expected("semper", payload.text("dev"), payload.text("dkt"))

        assertEquals(
            Verification.Rejected(RejectReason.BAD_SIGNATURE),
            EntitlementVerifier(pins).verify(vector.text("tamperedToken"), expected),
        )
    }

    @Test
    fun `the backend vector's grace runs from verifyAt to expiredAt`() {
        val vector = resource("entitlement_vector_backend.json")
        val verifyAtMs = vector.getValue("verifyAt").jsonPrimitive.long * 1000
        val expiredAt = vector.getValue("expiredAt").jsonPrimitive.long
        val start = DeviceTime(verifyAtMs, 0L, 1)
        val clock = EntitlementClock.anchored(verifyAtMs, start)

        val (_, inside) = EntitlementClock.evaluate(clock, start, expiredAt, expiredAt)
        val atExpiry = DeviceTime(expiredAt * 1000, (expiredAt * 1000) - verifyAtMs, 1)
        val (_, after) = EntitlementClock.evaluate(clock, atExpiry, expiredAt, expiredAt)

        assertEquals(GraceVerdict.Within, inside)
        assertEquals(GraceVerdict.Ended(GraceEnd.EXPIRED), after)
    }
}
