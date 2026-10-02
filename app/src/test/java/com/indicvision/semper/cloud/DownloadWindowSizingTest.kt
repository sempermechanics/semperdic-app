package com.indicvision.semper.cloud

import com.indicvision.semper.data.net.drive.nextWindowBytes
import org.junit.Assert.assertEquals
import org.junit.Test

private const val ONE_MIB = 1 shl 20
private const val FOUR_MIB = 4 shl 20
private const val SIXTEEN_MIB = 16 shl 20

/**
 * [nextWindowBytes] sizes each proxied restore-download Range window from the
 * throughput just observed, so a slow link stays small (frequent progress,
 * cheap retries) and a fast one grows toward fewer, larger requests — see the
 * class doc on `MIN_DOWNLOAD_WINDOW_BYTES` in `DriveTransfer.kt` for why a fixed
 * size is wrong for either extreme.
 */
class DownloadWindowSizingTest {

    @Test
    fun `no prior data falls back to the initial guess`() {
        assertEquals(FOUR_MIB, nextWindowBytes(bytesInWindow = 0, elapsedMs = 0))
        assertEquals(FOUR_MIB, nextWindowBytes(bytesInWindow = 100, elapsedMs = 0))
        assertEquals(FOUR_MIB, nextWindowBytes(bytesInWindow = 0, elapsedMs = 100))
    }

    @Test
    fun `a slow link clamps down to the minimum window`() {
        // 10,000 B/s * 30s target = 300,000 B — well under the 1 MiB floor.
        val got = nextWindowBytes(bytesInWindow = 100_000, elapsedMs = 10_000)
        assertEquals(ONE_MIB, got)
    }

    @Test
    fun `a fast link clamps up to the maximum window`() {
        // 50 MB/s * 30s target — far past the 16 MiB ceiling.
        val got = nextWindowBytes(bytesInWindow = 50_000_000, elapsedMs = 1_000)
        assertEquals(SIXTEEN_MIB, got)
    }

    @Test
    fun `mid-range throughput rounds down to the nearest power of two`() {
        // 200,000 B/s * 30s = 6,000,000 B target — between the 4 MiB and 8 MiB
        // powers of two, so it must round down to 4 MiB, not up to 8.
        val got = nextWindowBytes(bytesInWindow = 2_000_000, elapsedMs = 10_000)
        assertEquals(FOUR_MIB, got)
    }

    @Test
    fun `a target landing exactly on a power of two is not rounded down past it`() {
        // 8 MiB / 30s ≈ throughput that puts the target exactly at 8 MiB.
        val eightMib = 8L shl 20
        val got = nextWindowBytes(bytesInWindow = eightMib, elapsedMs = 30_000)
        assertEquals((8 shl 20), got)
    }
}
