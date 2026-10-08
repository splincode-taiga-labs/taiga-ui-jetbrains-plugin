package org.taigaui.designtokens.cache

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class CacheConcurrencyCoverageTest {
    @Test
    fun `single flight coalesces concurrent builds and updates pending metadata`() {
        data class Metadata(
            var requests: Int = 0,
        )

        val flight = GenerationAwareSingleFlight<String, String, Metadata>(::Metadata)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val joined = CountDownLatch(1)
        val builds = AtomicInteger()
        val pool = Executors.newFixedThreadPool(2)

        try {
            val first =
                pool.submit<String> {
                    flight.getOrBuild(
                        key = "a",
                        updateMetadata = { it.requests++ },
                        build = {
                            builds.incrementAndGet()
                            started.countDown()
                            release.await(5, TimeUnit.SECONDS)
                            "value"
                        },
                        isCurrent = { metadata, _ -> metadata.requests == 2 },
                    )
                }

            assertTrue(started.await(5, TimeUnit.SECONDS))

            val second =
                pool.submit<String> {
                    flight.getOrBuild(
                        key = "a",
                        updateMetadata = {
                            it.requests++
                            joined.countDown()
                        },
                        build = {
                            builds.incrementAndGet()
                            "other"
                        },
                        isCurrent = { metadata, _ -> metadata.requests == 2 },
                    )
                }

            assertTrue(joined.await(5, TimeUnit.SECONDS))
            release.countDown()

            assertEquals("value", first.get(5, TimeUnit.SECONDS))
            assertEquals("value", second.get(5, TimeUnit.SECONDS))
            assertEquals(1, builds.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `single flight retries stale generations after explicit invalidation`() {
        data class Metadata(
            var valid: Boolean = true,
        )

        val flight = GenerationAwareSingleFlight<String, Int, Metadata>(::Metadata)
        val builds = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val future =
                pool.submit<Int> {
                    flight.getOrBuild(
                        key = "a",
                        build = {
                            val number = builds.incrementAndGet()

                            if (number == 1) {
                                started.countDown()
                                release.await(5, TimeUnit.SECONDS)
                            }

                            number
                        },
                    )
                }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            flight.updatePending { key, metadata ->
                assertEquals("a", key)
                metadata.valid = false
                PendingBuildAction.INVALIDATE
            }
            release.countDown()

            assertEquals(2, future.get(5, TimeUnit.SECONDS))
            assertEquals(2, builds.get())
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `single flight retries values rejected by current predicate`() {
        data class Metadata(
            var attempts: Int = 0,
        )

        val flight = GenerationAwareSingleFlight<String, Int, Metadata>(::Metadata)
        val builds = AtomicInteger()

        val value =
            flight.getOrBuild(
                key = "a",
                updateMetadata = { it.attempts++ },
                build = { builds.incrementAndGet() },
                isCurrent = { _, current -> current >= 2 },
            )

        assertEquals(2, value)
        assertEquals(2, builds.get())
    }

    @Test
    fun `single flight propagates build and publication failures`() {
        val flight = GenerationAwareSingleFlight<String, String, Unit> { Unit }
        val buildFailure = IllegalStateException("build")

        val actualBuildFailure =
            assertThrows(IllegalStateException::class.java) {
                flight.getOrBuild(key = "build", build = { throw buildFailure })
            }

        assertSame(buildFailure, actualBuildFailure)

        val publishFailure = IllegalArgumentException("publish")
        val actualPublishFailure =
            assertThrows(IllegalArgumentException::class.java) {
                flight.getOrBuild(
                    key = "publish",
                    build = { "value" },
                    publish = { throw publishFailure },
                )
            }

        assertSame(publishFailure, actualPublishFailure)
    }

    @Test
    fun `clear invalidates pending build and forces retry`() {
        val flight = GenerationAwareSingleFlight<String, Int, Unit> { Unit }
        val builds = AtomicInteger()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()

        try {
            val future =
                pool.submit<Int> {
                    flight.getOrBuild(
                        key = "a",
                        build = {
                            val number = builds.incrementAndGet()

                            if (number == 1) {
                                started.countDown()
                                release.await(5, TimeUnit.SECONDS)
                            }

                            number
                        },
                    )
                }

            assertTrue(started.await(5, TimeUnit.SECONDS))
            flight.clear()
            release.countDown()

            assertEquals(2, future.get(5, TimeUnit.SECONDS))
        } finally {
            pool.shutdownNow()
        }
    }

    @Test
    fun `coalescing callbacks replace same target and respect activity and unchanged opt in`() {
        class Owner(
            var active: Boolean = true,
        )

        val owner = Owner()
        val callbacks = CoalescingRefreshCallbacks()
        val calls = mutableListOf<String>()

        callbacks.add(
            RefreshCallback(
                owner = owner,
                kind = "index",
                callback = { calls += "first" },
            ),
        )
        callbacks.add(
            RefreshCallback(
                owner = owner,
                kind = "index",
                notifyWhenUnchanged = true,
                callback = { calls += "replacement" },
            ),
        )

        assertEquals(1, callbacks.size)
        callbacks.take(entriesChanged = false).forEach(RefreshCallback<*>::invokeIfActive)
        assertEquals(listOf("replacement"), calls)
        assertEquals(0, callbacks.size)

        callbacks.add(
            RefreshCallback(
                owner = owner,
                kind = "other",
                isActivePredicate = { it.active },
                callback = { calls += "active" },
            ),
        )
        owner.active = false

        assertTrue(callbacks.take().isEmpty())
        assertEquals(listOf("replacement"), calls)
    }

    @Test
    fun `inactive callback is not added and different targets are retained`() {
        class Owner(
            val active: Boolean,
        )

        val first = Owner(false)
        val second = Owner(true)
        val callbacks = CoalescingRefreshCallbacks()

        callbacks.add(
            RefreshCallback(
                owner = first,
                kind = "same",
                isActivePredicate = { it.active },
                callback = {},
            ),
        )
        assertEquals(0, callbacks.size)

        callbacks.add(RefreshCallback(owner = second, kind = "one", callback = {}))
        callbacks.add(RefreshCallback(owner = second, kind = "two", callback = {}))

        assertEquals(2, callbacks.size)
        assertFalse(callbacks.take(entriesChanged = false).isNotEmpty())
    }
}
