package com.sempermechanics.semper.auth

import com.sempermechanics.semper.R
import com.sempermechanics.semper.ui.auth.PasswordPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the app enforces wherever it takes a password itself, and the
 * generator that has to satisfy them by construction.
 */
class PasswordPolicyTest {

    @Test
    fun `a password meeting every rule passes`() {
        assertNull(PasswordPolicy.validate("Abcdef1!"))
    }

    @Test
    fun `each rule reports itself, not just a failure`() {
        // The user is told what to fix, so which rule tripped is the contract.
        assertEquals(
            PasswordPolicy.Failure.TooShort(),
            PasswordPolicy.validate("Ab1!xyz"),
        )
        assertEquals(
            PasswordPolicy.Failure.Missing(R.string.password_needs_upper),
            PasswordPolicy.validate("abcdefg1!"),
        )
        assertEquals(
            PasswordPolicy.Failure.Missing(R.string.password_needs_lower),
            PasswordPolicy.validate("ABCDEFG1!"),
        )
        assertEquals(
            PasswordPolicy.Failure.Missing(R.string.password_needs_digit),
            PasswordPolicy.validate("Abcdefgh!"),
        )
        assertEquals(
            PasswordPolicy.Failure.Missing(R.string.password_needs_special),
            PasswordPolicy.validate("Abcdefg1"),
        )
    }

    @Test
    fun `the length ceiling is enforced too`() {
        val huge = "Ab1!" + "x".repeat(PasswordPolicy.MAX_LENGTH)

        assertEquals(PasswordPolicy.Failure.TooLong(), PasswordPolicy.validate(huge))
    }

    @Test
    fun `a password exactly at the bounds is accepted`() {
        val atMin = "Abcdef1!"
        assertEquals(PasswordPolicy.MIN_LENGTH, atMin.length)
        assertNull(PasswordPolicy.validate(atMin))

        val atMax = "Ab1!" + "x".repeat(PasswordPolicy.MAX_LENGTH - 4)
        assertEquals(PasswordPolicy.MAX_LENGTH, atMax.length)
        assertNull(PasswordPolicy.validate(atMax))
    }

    @Test
    fun `generated passwords always satisfy the policy`() {
        repeat(200) {
            val pw = PasswordPolicy.generate()
            assertNull("generated '$pw' should pass", PasswordPolicy.validate(pw))
        }
    }

    @Test
    fun `generated passwords are shuffled, not seeded in a fixed order`() {
        // The generator plants one of each required class in the first four
        // slots and then shuffles. If the shuffle were dropped, position 0 would
        // be uppercase every single time and the layout would be guessable.
        val firstChars = (1..200).map { PasswordPolicy.generate()[0] }

        assertTrue(
            "position 0 was uppercase in every sample — is the shuffle still there?",
            firstChars.any { !it.isUpperCase() },
        )
        assertTrue("generator produced identical passwords", firstChars.toSet().size > 1)
    }
}
