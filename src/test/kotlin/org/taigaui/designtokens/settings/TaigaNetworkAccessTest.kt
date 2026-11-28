package org.taigaui.designtokens.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class TaigaNetworkAccessTest {
    @Test
    fun onlyUserInitiatedRequestsRunInCacheFirstMode() {
        var mode = TaigaNetworkMode.CACHE_FIRST
        val access = TaigaNetworkAccess { mode }
        var calls = 0

        assertNull(access.request { ++calls })
        assertEquals(1, access.request(explicit = true) { ++calls })

        access.changeMode { mode = TaigaNetworkMode.OFFLINE_ONLY }
        assertNull(access.request { ++calls })
        assertNull(access.request(explicit = true) { ++calls })

        access.changeMode { mode = TaigaNetworkMode.ONLINE }
        assertEquals(2, access.request { ++calls })
    }

    @Test
    fun switchingOfflineInterruptsInFlightRequestsAndDiscardsTheirResult() {
        var mode = TaigaNetworkMode.ONLINE
        val access = TaigaNetworkAccess { mode }
        val started = CountDownLatch(1)
        val blocked = CountDownLatch(1)
        val interrupted = CountDownLatch(1)

        Executors.newSingleThreadExecutor().use { executor ->
            val result =
                executor.submit<String?> {
                    access.request {
                        started.countDown()
                        try {
                            blocked.await(5, TimeUnit.SECONDS)
                        } catch (_: InterruptedException) {
                            interrupted.countDown()
                        }
                        "discarded"
                    }
                }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            access.changeMode { mode = TaigaNetworkMode.OFFLINE_ONLY }
            assertNull(result.get(5, TimeUnit.SECONDS))
            assertEquals(0L, interrupted.count)
        }
    }
}
