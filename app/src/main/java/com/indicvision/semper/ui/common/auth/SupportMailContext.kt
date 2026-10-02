package com.indicvision.semper.ui.common.auth

/**
 * The context block at the end of a support mail: the account, any [extra]
 * lines (Session limit adds its quota), the device id, then
 * [SupportMail.deviceLines]. Pending, Session limit and Settings' "Email
 * support" each built it by hand, in this order.
 *
 * [account] is the caller's: Pending and Settings fall back to
 * `R.string.pending_unknown_account`, Session limit to the same text as a literal.
 */
fun SupportMail.contextLines(account: String, deviceId: String, extra: List<String> = emptyList()): String =
    buildString {
        append("Account: ").append(account).append('\n')
        for (line in extra) append(line).append('\n')
        append("Device ID: ").append(deviceId).append('\n')
        append(deviceLines())
    }
