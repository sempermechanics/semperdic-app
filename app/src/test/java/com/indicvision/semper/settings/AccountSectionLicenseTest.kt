package com.indicvision.semper.settings

import android.content.Context
import android.view.View
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import com.indicvision.semper.R
import com.indicvision.semper.data.net.AppConfigDto
import com.indicvision.semper.data.net.AppRemoteConfig
import com.indicvision.semper.ui.settings.SettingsActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings → Account's "Licensed as SEMP-…" row. The backend sends the prefix
 * of whatever key the account points at, so a Demo key, and a licence that is
 * revoked, lapsed or waiting on a floating seat, all arrive with one. The row
 * must follow the entitlement, not the prefix (v1.2-beta.3 smoke test: a Demo
 * account read "Licensed as SEMP-K8W4").
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccountSectionLicenseTest {

    private val ctx: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun setUp() = AppRemoteConfig.clear(ctx)

    @After
    fun tearDown() = AppRemoteConfig.clear(ctx)

    private fun licenceRow(): TextView =
        Robolectric.buildActivity(SettingsActivity::class.java).setup().get()
            .findViewById(R.id.tvAccountLicense)

    @Test
    fun `a licensed account shows its prefix`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed", licensePrefix = "SEMP-AB12"))

        val row = licenceRow()

        assertEquals(View.VISIBLE, row.visibility)
        assertEquals("Licensed as SEMP-AB12", row.text.toString())
    }

    @Test
    fun `a demo account holding a demo key is not shown as licensed`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "demo", licensePrefix = "SEMP-K8W4"))

        assertEquals(View.GONE, licenceRow().visibility)
    }

    @Test
    fun `a floating seat with no lease is not shown as licensed`() {
        AppRemoteConfig.apply(
            ctx,
            AppConfigDto(
                mode = "demo",
                licensePrefix = "SEMP-FL0A",
                licenseKind = "institution",
                licenseSeating = AppRemoteConfig.SEATING_FLOATING,
            ),
        )

        assertEquals(View.GONE, licenceRow().visibility)
    }

    @Test
    fun `a licensed account on a backend without the prefix hides the row`() {
        AppRemoteConfig.apply(ctx, AppConfigDto(mode = "licensed"))

        assertEquals(View.GONE, licenceRow().visibility)
    }
}
