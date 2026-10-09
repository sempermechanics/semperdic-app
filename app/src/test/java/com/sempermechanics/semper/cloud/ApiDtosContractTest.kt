package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.ChallengeResponse
import com.sempermechanics.semper.data.net.ConsentUpdateRequest
import com.sempermechanics.semper.data.net.DeviceRegisterRequest
import com.sempermechanics.semper.data.net.ErasureStatus
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.LicenseActivateRequest
import com.sempermechanics.semper.data.net.LicenseActivateResponse
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionFilesResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.net.SessionsResponse
import com.sempermechanics.semper.data.net.TermsAcceptRequest
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.serializer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Wire-contract tests for the DTOs in [ApiDtos.kt].
 *
 * The property names on these classes ARE the JSON contract with the backend.
 * The bodies themselves live in the top-level `contracts/` directory, one file
 * per request the app sends and response it reads; the backend's
 * `tests/test_wire_contracts.py` holds the same files to its pydantic models
 * and to what its routes really return. Here, using the same Json
 * configuration as [SemperApi]:
 *
 * - a **request** fixture decodes into its DTO and re-encodes to the same
 *   JSON, so the DTO sends exactly the fixture's keys, no more, no fewer;
 * - a **response** fixture decodes, and every field the DTO knows is a key the
 *   fixture (so the backend) sends: a renamed field would otherwise decode to
 *   its default without a word.
 *
 * The inline bodies further down are older backends' answers, which the
 * fixtures (today's backend) cannot show.
 */
class ApiDtosContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val contractsDir = File(System.getProperty("semper.contracts.dir") ?: "../contracts")

    private fun contract(name: String): String = File(contractsDir, "$name.json").readText()

    /** Decodes contracts/[name].json; asserts the DTO's every field is in it. */
    private inline fun <reified T> response(name: String): T = response(name, serializer())

    private fun <T> response(name: String, serializer: KSerializer<T>): T {
        val body = contract(name)
        val decoded = json.decodeFromString(serializer, body)
        val missing = keysNotIn(json.parseToJsonElement(body), json.encodeToJsonElement(serializer, decoded))
        assertTrue("$name.json lacks fields the DTO reads (they decode to a default): $missing", missing.isEmpty())
        return decoded
    }

    /** Asserts contracts/[name].json survives decode + encode unchanged; returns the DTO. */
    private inline fun <reified T> request(name: String): T {
        val body = json.parseToJsonElement(contract(name))
        val decoded = json.decodeFromJsonElement<T>(serializer(), body)
        assertEquals("$name.json and the DTO's encoding differ", body, json.encodeToJsonElement(serializer(), decoded))
        return decoded
    }

    /** Paths of the keys in [encoded] that [fixture] does not have, recursively. */
    private fun keysNotIn(fixture: JsonElement, encoded: JsonElement, path: String = "$"): List<String> =
        when {
            encoded is JsonObject && fixture is JsonObject -> encoded.flatMap { (key, value) ->
                fixture[key]?.let { keysNotIn(it, value, "$path.$key") } ?: listOf("$path.$key")
            }
            encoded is JsonArray && fixture is JsonArray ->
                encoded.zip(fixture).flatMap { (e, f) -> keysNotIn(f, e, "$path[]") }
            else -> emptyList()
        }

    // ------------------------------------------------------------ responses

    @Test
    fun `erasure response decodes the backend's answer`() {
        assertEquals(ErasureStatus(erased = false), response<ErasureStatus>("erasure_response"))
    }

    @Test
    fun `me response decodes the backend's answer including snake_case keys`() {
        val me = response<MeResponse>("me_response")
        assertEquals("Xq3LkP9sTzVbN2mR7wYc4HdJ1eF6", me.uid)
        assertEquals("APPROVED", me.accessStatus)
        assertEquals("licensed", me.license?.mode)
        assertEquals("2026-09-15", me.terms?.requiredVersion)
        assertEquals("2026-09-15", me.terms?.acceptedVersion)
        assertEquals(false, me.improvementConsent)
    }

    @Test
    fun `session list decodes sessions, quota and page`() {
        val resp = response<SessionsResponse>("sessions_response")
        val s = resp.sessions.single()
        assertEquals("3f9c2a7e5b1d4c8e9a0b6f2d1e4c7a95", s.sessionId)
        assertEquals("a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d", s.localSessionId)
        assertEquals("COMPLETED", s.status)
        assertEquals(2, s.completedCount)
        assertEquals(48215355L, s.totalBytes)
        assertEquals("1QwErTyUiOpAsDfGhJkLzXcVbNm098765", s.driveFolderId)
        assertEquals(1, resp.quota.used)
        assertEquals(999, resp.quota.max)
        assertFalse(resp.page!!.hasMore)
    }

    @Test
    fun `session create response decodes upload targets`() {
        val resp = response<SessionCreateResponse>("session_create_response")
        assertEquals("UPLOADING", resp.status)
        assertEquals(listOf(33554432, 33554432), resp.uploads.map { it.chunkSize })
        assertTrue(resp.uploads.first().uploadUrl.startsWith("https://www.googleapis.com/upload/drive/"))
    }

    @Test
    fun `pending uploads decode for the resume path`() {
        val resp = response<SessionUploadsResponse>("session_uploads_response")
        val u = resp.uploads.single()
        assertEquals("metadata", u.role)
        assertEquals(2048L, u.sizeBytes)
        assertEquals(33554432, u.chunkSize)
        assertNull(resp.provisionError)
    }

    @Test
    fun `session files manifest decodes for restore`() {
        val resp = response<SessionFilesResponse>("session_files_response")
        assertEquals(listOf("bundle", "metadata"), resp.files.map { it.role })
        assertEquals(64, resp.files.first().sha256?.length)
        assertEquals("COMPLETED", resp.files.first().status)
        assertEquals(48213307L, resp.files.first().sizeBytes)
    }

    @Test
    fun `config response decodes an institution floating seat in grace`() {
        val cfg = response<AppConfigDto>("config_response")
        assertEquals("licensed", cfg.mode)
        assertEquals("professional", cfg.plan)
        assertTrue(cfg.cloudBackupEnabled)
        assertEquals("institution", cfg.licenseKind)
        assertEquals("SEMP-AB12", cfg.licensePrefix)
        assertEquals("timed", cfg.licenseDuration)
        assertEquals("2027-03-01T00:00:00+00:00", cfg.licenseExpiresAt)
        assertEquals("2027-03-15T00:00:00+00:00", cfg.licenseGraceEndsAt)
        assertTrue(cfg.inGrace)
        assertEquals("floating", cfg.licenseSeating)
        assertEquals("2027-03-01T00:30:00+00:00", cfg.leaseExpiresAt)
        assertEquals(30, cfg.leaseHeartbeatMinutes)
        assertEquals(999, cfg.maxSessions)
    }

    @Test
    fun `license activate response decodes the nested config`() {
        val resp = response<LicenseActivateResponse>("license_activate_response")
        assertEquals("licensed", resp.config.mode)
        assertEquals("individual", resp.config.licenseKind)
        assertEquals("perpetual", resp.config.licenseDuration)
        assertNull(resp.config.licenseExpiresAt)
    }

    @Test
    fun `challenge response decodes the nonce`() {
        assertEquals("c7Fq2xLp9ZbT4mWv8nRk3sYd6hJa1eUg", response<ChallengeResponse>("challenge_response").nonce)
    }

    @Test
    fun `a DTO field the backend does not send is caught`() {
        // The check the response tests rest on: drop a key from a fixture and
        // the DTO's field for it is reported, not silently defaulted.
        val body = json.parseToJsonElement(contract("sessions_response")).toString().replace("totalBytes", "bytes")
        val decoded = json.decodeFromString<SessionsResponse>(body)
        assertEquals(0L, decoded.sessions.single().totalBytes)
        assertEquals(
            listOf("$.sessions[].totalBytes"),
            keysNotIn(json.parseToJsonElement(body), json.encodeToJsonElement(SessionsResponse.serializer(), decoded)),
        )
    }

    // ------------------------------------------------------------- requests

    @Test
    fun `session create request is exactly the fixture's body`() {
        val req = request<SessionCreateRequest>("session_create_request")
        assertEquals(listOf("bundle", "metadata"), req.files.map { it.role })
        assertEquals(48213307L, req.files.first().bytes)
        assertEquals(24f, req.metrics["frameCount"])
    }

    @Test
    fun `file complete request is exactly the fixture's body`() {
        val req = request<FileCompleteRequest>("file_complete_request")
        assertEquals(48213307L, req.bytes)
        assertEquals(32, req.md5?.length)
    }

    @Test
    fun `device register request is exactly the fixture's body`() {
        val req = request<DeviceRegisterRequest>("device_register_request")
        assertEquals("and-7f3e9a1c2b4d6e8f", req.deviceId)
        assertTrue(req.publicKeyPem.startsWith("-----BEGIN PUBLIC KEY-----"))
    }

    @Test
    fun `license activate, terms and consent requests are exactly the fixtures' bodies`() {
        assertEquals("SEMP-U8BX-4KQ2-ZP7M-W3RT", request<LicenseActivateRequest>("license_activate_request").key)
        assertEquals("2026-09-15", request<TermsAcceptRequest>("terms_accept_request").version)
        assertTrue(request<ConsentUpdateRequest>("consent_update_request").improvement)
    }

    // ------------------------------------------------- older backends' bodies

    @Test
    fun `config response missing mode still decodes the plan mirror`() {
        // A backend deploy predating the plan->mode rename sends only `plan`.
        // `mode` decodes blank, which AppRemoteConfig reads as "not told" and
        // resolves from the mirror — not as demo.
        val cfg = json.decodeFromString<AppConfigDto>("""{"plan":"professional"}""")
        assertEquals("", cfg.mode)
        assertEquals("professional", cfg.plan)
    }

    @Test
    fun `config response missing plan mirror still decodes mode`() {
        // The mirror is dropped once the fleet has moved; `mode` alone must
        // keep decoding, and the mirror's own default must not contradict it.
        val cfg = json.decodeFromString<AppConfigDto>("""{"mode":"licensed"}""")
        assertEquals("licensed", cfg.mode)
    }

    @Test
    fun `config response missing licenseKind fails closed to blank not campus or individual`() {
        // An older backend deploy this app talks to may not send licenseKind
        // yet — must not be misread as either shape.
        val cfg = json.decodeFromString<AppConfigDto>("""{"plan":"professional"}""")
        assertEquals("", cfg.licenseKind)
    }

    @Test
    fun `config response without duration fields decodes as a perpetual license`() {
        // A backend deploy predating duration sends none of them. Null expiry
        // and inGrace=false is exactly "nothing to warn about", which is the
        // right reading — not "expired at the epoch".
        val cfg = json.decodeFromString<AppConfigDto>("""{"mode":"licensed"}""")
        assertEquals("", cfg.licenseDuration)
        assertNull(cfg.licenseExpiresAt)
        assertNull(cfg.licenseGraceEndsAt)
        assertFalse(cfg.inGrace)
    }

    @Test
    fun `config response without seating decodes blank, which reads as assigned`() {
        // A deploy predating floating seats sends nothing here. Blank must not
        // be misread as floating, or every institution user would be gated.
        val cfg = json.decodeFromString<AppConfigDto>("""{"mode":"licensed"}""")
        assertEquals("", cfg.licenseSeating)
        assertNull(cfg.leaseExpiresAt)
        assertEquals(0, cfg.leaseHeartbeatMinutes)
    }

    @Test
    fun `unknown backend fields are tolerated`() {
        // The backend may add fields at any time; the app must not crash.
        val me = json.decodeFromString<MeResponse>(
            """{"uid":"u","access_status":"PENDING","brand_new_field":123}""",
        )
        assertEquals("PENDING", me.accessStatus)
    }
}
