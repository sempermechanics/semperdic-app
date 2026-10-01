package com.indicvision.semper.data.account

import android.content.Context
import com.indicvision.semper.data.net.TokenStore

/**
 * The clickwrap gate's one question: has this user agreed to the Terms version
 * currently in force?
 *
 * The version in force is whatever `/v1/me` last reported ([TokenStore.termsRequiredVersion]),
 * so a bump on the server re-gates approved users without an app update. Until
 * the server has answered — first registration, or a PENDING account that
 * `/v1/me` refuses — [TERMS_VERSION] stands in. It must match `**Version:**` in
 * `docs/legal/TERMS_OF_SERVICE.md` and `backend/app/legal.py`.
 */
object LegalTerms {

    const val TERMS_VERSION = "2026-09-15"

    fun requiredVersion(context: Context): String =
        TokenStore.termsRequiredVersion(context) ?: TERMS_VERSION

    /** True when the acceptance screen must be shown before the user goes anywhere else. */
    fun needsAcceptance(context: Context): Boolean =
        TokenStore.termsAcceptedVersion(context) != requiredVersion(context)
}
