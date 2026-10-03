package com.sempermechanics.semper.fixtures

import android.os.Looper
import org.robolectric.Shadows.shadowOf

private const val POLL_MS = 20L

/**
 * Idles the main looper until [done] holds, for work that runs on a background
 * dispatcher and posts its result back to main: each pass runs what has been
 * posted, then checks. Fails after [timeoutMs], naming [what] it waited on.
 */
fun idleUntil(what: String, timeoutMs: Long = 10_000L, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (true) {
        shadowOf(Looper.getMainLooper()).idle()
        if (done()) return
        check(System.currentTimeMillis() < deadline) { "timed out waiting on $what" }
        Thread.sleep(POLL_MS)
    }
}
