package com.sempermechanics.semper.fixtures

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import java.io.File
import java.security.MessageDigest

/**
 * Byte-for-byte oracles: a committed file under `app/src/test/resources/oracles/`
 * that an output must equal exactly. Unlike a parity test against an older copy
 * of the code, a golden file holds when both sides of a change move together.
 *
 * `./gradlew :app:testDebugUnitTest -PupdateGoldens --tests "*Oracle*"` rewrites
 * the files from the current code (and passes). Do that only for a change that
 * is meant to move the output, and say so in the PR: the diff of the binary file
 * is the review.
 */
object Goldens {

    private val dir: File
        get() = File(checkNotNull(System.getProperty(DIR_PROPERTY)) { "$DIR_PROPERTY is set by app/build.gradle.kts" })

    private const val DIR_PROPERTY = "semper.goldens.dir"

    private val updating: Boolean get() = System.getProperty("semper.goldens.update") == "true"

    fun assertMatches(name: String, actual: ByteArray) {
        val file = File(dir, name)
        if (updating) {
            file.parentFile?.mkdirs()
            file.writeBytes(actual)
            return
        }
        if (!file.isFile) fail("No golden $name; generate it with -PupdateGoldens and commit it")
        val expected = file.readBytes()
        if (expected.contentEquals(actual)) return
        val firstDiff = expected.indices.firstOrNull { it >= actual.size || expected[it] != actual[it] } ?: actual.size
        assertEquals(
            "$name differs from its golden (sizes ${expected.size} / ${actual.size}, " +
                "first difference at byte $firstDiff). If the change is meant to move this output, " +
                "regenerate with -PupdateGoldens and review the diff.",
            sha256(expected),
            sha256(actual),
        )
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
