package com.sempermechanics.semper.cloud

import com.sempermechanics.semper.data.net.CloudApi
import com.sempermechanics.semper.data.net.CloudFileDto
import com.sempermechanics.semper.data.net.SessionFilesResponse
import com.sempermechanics.semper.field.DicResult
import com.sempermechanics.semper.util.Digests
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * [FakeCloudApi] plus the read half of a restore: a scripted file manifest and
 * the bytes behind each file id. Kept beside [FakeCloudApi] rather than in it so
 * the restore tests own their scripting. Any other call is still unscripted.
 */
class FakeRestoreApi(private val base: FakeCloudApi = FakeCloudApi()) : CloudApi by base {

    /** Restore-side calls, in order: `listSessionFiles`, `downloadFile:<fileId>`. */
    val calls = mutableListOf<String>()

    /** What `listSessionFiles` answers. */
    var files: List<CloudFileDto> = emptyList()

    /** The bytes `downloadFile` writes for each file id. */
    val blobs = mutableMapOf<String, ByteArray>()

    /** Runs before a download writes anything, with the destination it was given. */
    var beforeDownload: (fileId: String, dest: File) -> Unit = { _, _ -> }

    override suspend fun listSessionFiles(idToken: String, sessionId: String): SessionFilesResponse {
        calls += "listSessionFiles"
        return SessionFilesResponse(sessionId = sessionId, files = files)
    }

    override suspend fun downloadFile(
        idToken: String,
        fileId: String,
        dest: File,
        expectedBytes: Long,
        onBytes: suspend (haveBytes: Long) -> Unit,
    ) {
        calls += "downloadFile:$fileId"
        beforeDownload(fileId, dest)
        val bytes = blobs[fileId] ?: throw AssertionError("no blob scripted for $fileId")
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        onBytes(bytes.size.toLong())
    }

    /** Script one COMPLETED file and its bytes; the sha256 is the real one unless given. */
    fun file(
        fileId: String,
        role: String,
        bytes: ByteArray,
        sha256: String? = sha256Of(bytes),
    ): CloudFileDto {
        blobs[fileId] = bytes
        return CloudFileDto(
            fileId = fileId,
            name = fileId,
            role = role,
            sizeBytes = bytes.size.toLong(),
            sha256 = sha256,
            status = "COMPLETED",
        )
    }

    companion object {
        fun sha256Of(bytes: ByteArray): String = Digests.toHex(Digests.sha256(bytes))

        /** A split-layout (schema 3) metadata.json with one deformed frame. */
        fun metadataJson(name: String = "Specimen"): ByteArray =
            """{"schema":"indic.session.metadata/3","name":"$name","frameCount":1,"frames":[{"image":"def.png"}]}"""
                .toByteArray()

        /** One `.dat` point, raw (pre-DatCodec) layout. */
        fun onePointDat(): ByteArray {
            val buf = ByteBuffer.allocate(DicResult.BYTES_PER_POINT).order(ByteOrder.nativeOrder())
            repeat(DicResult.STRIDE) { buf.putFloat(0.5f) }
            return buf.array()
        }

        /** A Session.zip holding exactly [entries] (`role/name` → bytes), in order. */
        fun zipOf(entries: List<Pair<String, ByteArray>>): ByteArray {
            val out = ByteArrayOutputStream()
            ZipOutputStream(out).use { zip ->
                entries.forEach { (name, bytes) ->
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(bytes)
                    zip.closeEntry()
                }
            }
            return out.toByteArray()
        }
    }
}
