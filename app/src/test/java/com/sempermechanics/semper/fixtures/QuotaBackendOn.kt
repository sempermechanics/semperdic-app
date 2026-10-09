package com.sempermechanics.semper.fixtures

import com.sempermechanics.semper.cloud.FakeCloudApi
import com.sempermechanics.semper.data.session.SessionQuota
import org.junit.rules.ExternalResource

/**
 * A backend for the session quota's rule ([SessionQuota.blocked]) for the
 * length of a test. A JVM build has no API URL, and with no backend the rule
 * has no cap, so a test of the cap swaps in [FakeCloudApi] and this puts the
 * real client back afterwards.
 */
class QuotaBackendOn : ExternalResource() {

    private var realApi = SessionQuota.api

    override fun before() {
        realApi = SessionQuota.api
        SessionQuota.api = { FakeCloudApi() }
    }

    override fun after() {
        SessionQuota.api = realApi
    }
}
