package com.sempermechanics.semper.ui.auth

import androidx.annotation.StringRes
import com.sempermechanics.semper.R
import java.security.SecureRandom

/**
 * Centralized password policy and generator. Applied at account creation
 * and password change; sign-in only checks minimum length (Firebase may
 * accept legacy shorter passwords).
 *
 * A forgotten password is reset through Firebase's own hosted page, which this
 * policy cannot reach — the rules bind wherever the app itself takes a password.
 */
object PasswordPolicy {
    const val MIN_LENGTH = 8
    const val MAX_LENGTH = 128

    /**
     * What a password failed on, or null when it passes.
     *
     * The length rules carry their bound rather than a message id: their wording
     * is a plural of that number, which only the screen can resolve.
     */
    sealed interface Failure {
        data class TooShort(val minLength: Int = MIN_LENGTH) : Failure

        data class TooLong(val maxLength: Int = MAX_LENGTH) : Failure

        data class Missing(@get:StringRes val message: Int) : Failure
    }

    /**
     * Returns null when the password is acceptable, else what it failed on.
     *
     * Resolves to a string resource rather than returning built text: the reason
     * is shown to the user, and this object has no Context to translate with.
     */
    fun validate(password: String): Failure? = when {
        password.length < MIN_LENGTH -> Failure.TooShort()
        password.length > MAX_LENGTH -> Failure.TooLong()
        !password.any { it.isUpperCase() } -> Failure.Missing(R.string.password_needs_upper)
        !password.any { it.isLowerCase() } -> Failure.Missing(R.string.password_needs_lower)
        !password.any { it.isDigit() } -> Failure.Missing(R.string.password_needs_digit)
        !password.any { !it.isLetterOrDigit() } -> Failure.Missing(R.string.password_needs_special)
        else -> null
    }

    private const val GENERATED_LENGTH = 16
    private const val UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ"
    private const val LOWER = "abcdefghjkmnpqrstuvwxyz"
    private const val DIGITS = "23456789"
    private const val SPECIAL = "!@#\$%^&*_+-="
    private const val ALL = UPPER + LOWER + DIGITS + SPECIAL

    /** One character of each required class is planted before the rest is filled. */
    private const val SEEDED = 4

    /** Generates a random password that satisfies [validate]. */
    fun generate(): String {
        val rng = SecureRandom()
        val chars = CharArray(GENERATED_LENGTH)
        listOf(UPPER, LOWER, DIGITS, SPECIAL).forEachIndexed { i, pool ->
            chars[i] = pool[rng.nextInt(pool.length)]
        }
        for (i in SEEDED until GENERATED_LENGTH) {
            chars[i] = ALL[rng.nextInt(ALL.length)]
        }
        // Fisher-Yates: drawing j from the whole array instead of 0..i is the
        // classic biased shuffle, and the four seeded positions are exactly what
        // an attacker would want to locate.
        for (i in chars.size - 1 downTo 1) {
            val j = rng.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
        return String(chars)
    }
}
