package com.indicvision.semper.auth

import com.indicvision.semper.data.account.AccessStatus
import com.indicvision.semper.ui.auth.AccessRouter
import com.indicvision.semper.ui.auth.AuthActivity
import com.indicvision.semper.ui.auth.PendingApprovalActivity
import com.indicvision.semper.ui.home.HomeActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessRouterTest {

    @Test
    fun `isHomeStatus returns true for APPROVED and OFFLINE_CACHE_APPROVED`() {
        assertTrue(AccessRouter.isHomeStatus(AccessStatus.APPROVED))
        assertTrue(AccessRouter.isHomeStatus(AccessStatus.OFFLINE_CACHE_APPROVED))
    }

    @Test
    fun `isHomeStatus returns false for PENDING and unknown strings`() {
        assertFalse(AccessRouter.isHomeStatus(AccessStatus.PENDING))
        assertFalse(AccessRouter.isHomeStatus("UNKNOWN"))
        assertFalse(AccessRouter.isHomeStatus(""))
    }

    @Test
    fun `afterSignIn returns PendingApprovalActivity for PENDING`() {
        assertEquals(PendingApprovalActivity::class.java, AccessRouter.afterSignIn(AccessStatus.PENDING))
    }

    @Test
    fun `afterSignIn returns HomeActivity for APPROVED`() {
        assertEquals(HomeActivity::class.java, AccessRouter.afterSignIn(AccessStatus.APPROVED))
    }

    @Test
    fun `afterSignIn returns HomeActivity for OFFLINE_CACHE_APPROVED`() {
        assertEquals(HomeActivity::class.java, AccessRouter.afterSignIn(AccessStatus.OFFLINE_CACHE_APPROVED))
    }

    @Test
    fun `afterSignIn returns HomeActivity for unknown`() {
        assertEquals(HomeActivity::class.java, AccessRouter.afterSignIn("WEIRD"))
    }

    @Test
    fun `afterRefresh returns HomeActivity for APPROVED and OFFLINE_CACHE_APPROVED`() {
        assertEquals(HomeActivity::class.java, AccessRouter.afterRefresh(AccessStatus.APPROVED))
        assertEquals(HomeActivity::class.java, AccessRouter.afterRefresh(AccessStatus.OFFLINE_CACHE_APPROVED))
    }

    @Test
    fun `afterRefresh returns PendingApprovalActivity for PENDING when stayOnPending is false`() {
        assertEquals(
            PendingApprovalActivity::class.java,
            AccessRouter.afterRefresh(AccessStatus.PENDING, stayOnPending = false),
        )
    }

    @Test
    fun `afterRefresh returns null for PENDING when stayOnPending is true`() {
        assertNull(AccessRouter.afterRefresh(AccessStatus.PENDING, stayOnPending = true))
    }

    @Test
    fun `afterRefresh returns AuthActivity for unknown strings`() {
        assertEquals(AuthActivity::class.java, AccessRouter.afterRefresh("UNKNOWN"))
    }
}
