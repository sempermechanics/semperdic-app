package com.sempermechanics.semper.fixtures

import android.view.Choreographer
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private const val DEFAULT_TIMEOUT_MS = 10_000L
private const val POLL_MS = 20L

/**
 * Polls [done] until it holds, failing with "timed out waiting for [what]"
 * after [timeoutMs]. Use it instead of a fixed `Thread.sleep`: it returns as
 * soon as the state is there and fails loudly when it never comes, where a
 * sleep is too long on a fast device and too short on a slow emulator.
 */
fun awaitCondition(what: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS, done: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (!done()) {
        check(System.currentTimeMillis() < deadline) { "timed out waiting for $what" }
        Thread.sleep(POLL_MS)
    }
}

/**
 * Returns once the UI thread is idle and a full frame has been drawn since,
 * so a screenshot taken next shows the state the test just set. Two frame
 * callbacks: the first starts a frame, the second runs after it drew.
 */
fun awaitDrawnFrame(timeoutMs: Long = DEFAULT_TIMEOUT_MS) {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    instrumentation.waitForIdleSync()
    val drawn = CountDownLatch(1)
    instrumentation.runOnMainSync {
        val choreographer = Choreographer.getInstance()
        choreographer.postFrameCallback { choreographer.postFrameCallback { drawn.countDown() } }
    }
    check(drawn.await(timeoutMs, TimeUnit.MILLISECONDS)) { "no frame drawn in $timeoutMs ms" }
}
