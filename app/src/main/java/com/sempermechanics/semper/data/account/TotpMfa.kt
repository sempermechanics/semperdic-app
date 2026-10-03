package com.sempermechanics.semper.data.account

import com.google.firebase.auth.MultiFactorInfo
import com.google.firebase.auth.TotpMultiFactorGenerator

/**
 * Picks the TOTP second factor for a sign-in challenge.
 *
 * SMS is deliberately unsupported on Semper — dashboards enrol TOTP only —
 * so a phone-only enrolment is treated as unusable here rather than half-
 * handled.
 */
object TotpMfa {

    /** Enrolment id for the first TOTP hint, or null when none is enrolled. */
    fun enrollmentId(hints: List<MultiFactorInfo>): String? =
        enrollmentIdFromPairs(hints.map { it.factorId to it.uid })

    /**
     * Same selection against plain factor-id / uid pairs — used by unit tests
     * that must not construct Firebase [MultiFactorInfo] instances.
     */
    fun enrollmentIdFromPairs(factorIdAndUid: List<Pair<String, String>>): String? =
        factorIdAndUid.firstOrNull { it.first == TotpMultiFactorGenerator.FACTOR_ID }?.second
}
