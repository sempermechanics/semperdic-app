package com.sempermechanics.semper.fixtures

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sempermechanics.semper.data.net.TokenStore
import com.sempermechanics.semper.data.session.SessionStore
import org.junit.rules.ExternalResource

/**
 * Signed out with no saved sessions, before and after each test: clears the
 * token store (and with it the remote config) and empties the session index
 * and its directories. Runs around the test's own @Before/@After.
 */
class CleanAppState : ExternalResource() {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    override fun before() {
        TokenStore.clear(context)
        SessionStore.deleteAll(context)
    }

    override fun after() {
        SessionStore.deleteAll(context)
        TokenStore.clear(context)
    }
}
