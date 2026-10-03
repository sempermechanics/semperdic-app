package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.AppConfigDto
import com.sempermechanics.semper.data.net.DeviceRegisterRequest
import com.sempermechanics.semper.data.net.FileCompleteRequest
import com.sempermechanics.semper.data.net.FileSpecDto
import com.sempermechanics.semper.data.net.LicenseActivateRequest
import com.sempermechanics.semper.data.net.LicenseActivateResponse
import com.sempermechanics.semper.data.net.MeResponse
import com.sempermechanics.semper.data.net.SessionCreateRequest
import com.sempermechanics.semper.data.net.SessionCreateResponse
import com.sempermechanics.semper.data.net.SessionFilesResponse
import com.sempermechanics.semper.data.net.SessionUploadsResponse
import com.sempermechanics.semper.data.net.SessionsResponse
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Wire-contract tests for the DTOs in [ApiDtos.kt].
 *
 * The property names on these classes ARE the JSON contract with the GCP
 * backend (backend/app/models.py + main.py responses) — there is no schema
 * file in between. A rename that compiles fine here silently breaks the
 * backend, so this suite pins the exact field names in both directions,
 * using the same Json configuration as [SemperApi].
 */
class ApiDtosContractTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ------------------------------------------------------------ responses

    @Test
    fun `me response decodes backend shape including snake_case access_status`() {
        val me = json.decodeFromString<MeResponse>(
            """{"uid":"u1","email":"a@b.com","role":"user","access_status":"APPROVED"}""",
        )
        assertEquals("u1", me.uid)
        assertEquals("APPROVED", me.accessStatus)
    }

    @Test
    fun `session list decodes sessions and quota`() {
        val resp = json.decodeFromString<SessionsResponse>(
            """
            {"sessions":[{"sessionId":"s1","localSessionId":"l1","specimen":"sp",
              "status":"COMPLETED","fileCount":2,"completedCount":2,
              "totalBytes":123,"driveFolderId":"df"}],
             "quota":{"used":1,"max":50}}
            """.trimIndent(),
        )
        val s = resp.sessions.single()
        assertEquals("s1", s.sessionId)
        assertEquals("l1", s.localSessionId)
        assertEquals("df", s.driveFolderId)
        assertEquals(1, resp.quota.used)
        assertEquals(50, resp.quota.max)
    }

    @Test
    fun `session create response decodes upload targets`() {
        val resp = json.decodeFromString<SessionCreateResponse>(
            """{"sessionId":"s2","uploads":[
                 {"fileId":"f1","uploadUrl":"https://u","chunkSize":33554432}]}""",
        )
        assertEquals("s2", resp.sessionId)
        assertEquals(33554432, resp.uploads.single().chunkSize)
    }

    @Test
    fun `pending uploads decode for the resume path`() {
        val resp = json.decodeFromString<SessionUploadsResponse>(
            """{"sessionId":"s3","status":"UPLOADING","uploads":[
                 {"fileId":"f2","uploadUrl":"https://u2","chunkSize":8388608,
                  "name":"Session.zip","role":"bundle","sizeBytes":42}]}""",
        )
        val u = resp.uploads.single()
        assertEquals("bundle", u.role)
        assertEquals(42L, u.sizeBytes)
    }

    @Test
    fun `session files manifest decodes for restore`() {
        val resp = json.decodeFromString<SessionFilesResponse>(
            """{"sessionId":"s4","localSessionId":"l4","files":[
                 {"fileId":"f3","name":"metadata.json","role":"metadata",
                  "sizeBytes":10,"sha256":null,"status":"COMPLETED"}]}""",
        )
        assertEquals("metadata", resp.files.single().role)
        assertNull(resp.files.single().sha256)
    }

    // ------------------------------------------------------------- requests

    @Test
    fun `session create request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            SessionCreateRequest(
                specimen = "sp",
                files = listOf(FileSpecDto("Session.zip", "bundle", 42L, "a".repeat(64))),
                metrics = mapOf("frameCount" to 2f),
                localSessionId = "local1",
            ),
        )
        // backend/app/models.py: SessionCreate + FileSpec
        for (key in listOf(
            "\"specimen\"",
            "\"files\"",
            "\"metrics\"",
            "\"localSessionId\"",
            "\"name\"",
            "\"role\"",
            "\"bytes\"",
            "\"sha256\"",
        )) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    @Test
    fun `file complete request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            FileCompleteRequest(sessionId = "s", driveFileId = "d", bytes = 5L, md5 = "m"),
        )
        // backend/app/models.py: FileComplete
        for (key in listOf("\"sessionId\"", "\"driveFileId\"", "\"bytes\"", "\"md5\"")) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    @Test
    fun `device register request encodes the exact backend field names`() {
        val encoded = json.encodeToString(
            DeviceRegisterRequest(
                deviceId = "and-12345678",
                publicKeyPem = "pem",
                model = "m",
                osVersion = "o",
                appVersion = "v",
            ),
        )
        // backend/app/models.py: DeviceReg
        for (key in listOf(
            "\"deviceId\"",
            "\"publicKeyPem\"",
            "\"model\"",
            "\"osVersion\"",
            "\"appVersion\"",
        )) {
            assertTrue("missing $key in $encoded", encoded.contains(key))
        }
    }

    // ---------------------------------------------------------- licensing

    @Test
    fun `config response decodes mode and licenseKind for an institution seat`() {
        val cfg = json.decodeFromString<AppConfigDto>(
            """
            {"maxSessions":0,"maxFilesPerSession":0,"maxFrames":0,
             "mode":"licensed","plan":"professional","cloudBackupEnabled":true,
             "shareEnabled":true,"licensePrefix":"SEMP-AB12",
             "licenseKind":"institution"}
            """.trimIndent(),
        )
        assertEquals("licensed", cfg.mode)
        assertEquals("professional", cfg.plan)
        assertTrue(cfg.cloudBackupEnabled)
        assertEquals("institution", cfg.licenseKind)
    }

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
    fun `config response decodes the license duration and grace fields`() {
        val cfg = json.decodeFromString<AppConfigDto>(
            """
            {"mode":"licensed","licenseDuration":"timed",
             "licenseExpiresAt":"2027-03-01T00:00:00Z",
             "licenseGraceEndsAt":"2027-03-15T00:00:00Z","inGrace":true}
            """.trimIndent(),
        )
        assertEquals("timed", cfg.licenseDuration)
        assertEquals("2027-03-01T00:00:00Z", cfg.licenseExpiresAt)
        assertEquals("2027-03-15T00:00:00Z", cfg.licenseGraceEndsAt)
        assertTrue(cfg.inGrace)
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
    fun `config response decodes the floating seat fields`() {
        val cfg = json.decodeFromString<AppConfigDto>(
            """
            {"mode":"licensed","licenseKind":"institution","licenseSeating":"floating",
             "leaseExpiresAt":"2027-03-01T00:00:00Z","leaseHeartbeatMinutes":30}
            """.trimIndent(),
        )
        assertEquals("floating", cfg.licenseSeating)
        assertEquals("2027-03-01T00:00:00Z", cfg.leaseExpiresAt)
        assertEquals(30, cfg.leaseHeartbeatMinutes)
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
    fun `license activate request encodes the exact backend field name`() {
        val encoded = json.encodeToString(LicenseActivateRequest(key = "SEMP-AAAA-BBBB-CCCC-DDDD"))
        assertTrue(encoded.contains("\"key\""))
        assertTrue(encoded.contains("SEMP-AAAA-BBBB-CCCC-DDDD"))
    }

    @Test
    fun `license activate response decodes the nested config`() {
        val resp = json.decodeFromString<LicenseActivateResponse>(
            """{"config":{"mode":"licensed","plan":"professional","cloudBackupEnabled":true,
                "shareEnabled":true,"licensePrefix":"SEMP-ZZ99","licenseKind":"individual"}}""",
        )
        assertEquals("licensed", resp.config.mode)
        assertEquals("professional", resp.config.plan)
        assertEquals("individual", resp.config.licenseKind)
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
