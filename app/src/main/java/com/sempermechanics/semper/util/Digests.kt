package com.sempermechanics.semper.util

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** Shared digest helpers (upload integrity, API request signing). */
object Digests {
    private const val SHA_256 = "SHA-256"
    private const val MD5 = "MD5"

    fun sha256(): MessageDigest = MessageDigest.getInstance(SHA_256)

    fun sha256(bytes: ByteArray): ByteArray = sha256().digest(bytes)

    fun md5(): MessageDigest = MessageDigest.getInstance(MD5)

    /** Lowercase hex MD5 of [file] contents (Drive `md5Checksum` format). */
    fun md5Hex(file: File): String = hexOf(file.inputStream(), md5())

    /** Lowercase hex SHA-256 of [file] contents (Firestore file attestation). */
    fun sha256Hex(file: File): String = hexOf(file.inputStream(), sha256())

    /** Lowercase hex SHA-256 of an open [InputStream] (caller owns close). */
    fun sha256HexStream(input: InputStream): String = hexOf(input, sha256())

    private fun hexOf(input: InputStream, md: MessageDigest): String {
        input.use { stream ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return toHex(md.digest())
    }

    fun toHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
