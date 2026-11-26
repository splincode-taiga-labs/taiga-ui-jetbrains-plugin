package org.taigaui.designtokens.releases

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class TaigaReleaseFeedTest {
    @Test
    fun selectsTheLatestStableGitHubReleaseOfEachMajor() {
        val json =
            """
            [
                {"tag_name":"v5.9.0","draft":false,"prerelease":false},
                {"tag_name":"v4.110.0","draft":false,"prerelease":false},
                {"tag_name":"v5.34.1","draft":false,"prerelease":false},
                {"tag_name":"v5.35.0","draft":true,"prerelease":false},
                {"tag_name":"v5.36.0","draft":false,"prerelease":true},
                {"tag_name":"v5.37.0-canary.1","draft":false,"prerelease":false},
                {"tag_name":"not-a-version","draft":false,"prerelease":false},
                {"tag_name":"v5.39.0"},
                null
            ]
            """.trimIndent()

        assertEquals(mapOf(4 to version("v4.110.0"), 5 to version("v5.34.1")), parseTaigaReleases(json))
        assertTrue(parseTaigaReleases("""{"message":"Unavailable"}""").isEmpty())
    }

    @Test
    fun onlyNotifiesForVersionsNewerThanTheInstallationAndPreviousAnnouncement() {
        val installed = version("5.9.0+build")

        assertTrue(shouldShowRelease(installed, version("v5.34.1"), null))
        assertTrue(shouldShowRelease(installed, version("v5.34.1"), version("v5.34.0")))
        assertFalse(shouldShowRelease(installed, version("v5.34.1"), version("v5.34.1")))
        assertFalse(shouldShowRelease(installed, version("v5.34.1"), version("v5.35.0")))
        assertFalse(shouldShowRelease(installed, version("v5.9.0"), null))
        assertFalse(shouldShowRelease(installed, version("v5.8.9"), null))
        assertFalse(shouldShowRelease(installed, version("v6.0.0"), null))
        assertFalse(shouldShowRelease(installed, version("v5.34.1-canary.1"), null))
    }

    @Test
    fun notifiesAboutTheStableVersionOfAnInstalledPrerelease() {
        assertTrue(shouldShowRelease(version("5.34.1-rc.0"), version("v5.34.1"), null))
        assertFalse(shouldShowRelease(version("5.35.0-rc.0"), version("v5.34.1"), null))
    }

    @Test
    fun ignoresNonCanonicalTagsAndInvalidReleaseMetadata() {
        val json =
            """
            [
                {"tag_name":"5.40.0","draft":false,"prerelease":false},
                {"tag_name":"v5.40.0+build","draft":false,"prerelease":false},
                {"tag_name":"v5.40.0","draft":"false","prerelease":false},
                {"tag_name":"v5.40.0","draft":false,"prerelease":0},
                {"tag_name":null,"draft":false,"prerelease":false},
                {"tag_name":{},"draft":false,"prerelease":false},
                {"draft":false,"prerelease":false},
                {"tag_name":"v999999999999.0.0","draft":false,"prerelease":false},
                {"tag_name":"v5.34.1","draft":false,"prerelease":false},
                {"tag_name":"v5.33.0","draft":false,"prerelease":false}
            ]
            """.trimIndent()

        assertEquals(mapOf(5 to version("5.34.1")), parseTaigaReleases(json))
        assertEquals("https://github.com/taiga-family/taiga-ui/releases/tag/v5.34.1", version("5.34.1").url)
    }

    @Test
    fun cachesStableReleasesAcrossMajorVersionsUntilTheRefreshInterval() =
        runBlocking {
            val feed = TaigaReleaseFeed()
            val clock = TestTimeSource()
            var requests = 0
            val fetch = {
                requests++
                mapOf(4 to version("4.110.0"), 5 to version("5.34.$requests"))
            }

            assertEquals(version("5.34.1"), feed.latestFor(5, fetch, clock))
            assertEquals(version("4.110.0"), feed.latestFor(4, fetch, clock))
            assertNull(feed.latestFor(6, fetch, clock))
            clock += RELEASE_CHECK_INTERVAL - 1.seconds
            assertEquals(version("5.34.1"), feed.latestFor(5, fetch, clock))
            assertEquals(1, requests)

            clock += 1.seconds
            assertEquals(version("5.34.2"), feed.latestFor(5, fetch, clock))
            assertEquals(2, requests)
        }

    @Test
    fun retriesAnEmptyReleaseFeedAfterFifteenMinutes() =
        runBlocking {
            val feed = TaigaReleaseFeed()
            val clock = TestTimeSource()
            var requests = 0
            val fetch = {
                requests++
                if (requests == 1) emptyMap() else mapOf(5 to version("5.34.1"))
            }

            assertNull(feed.latestFor(5, fetch, clock))
            clock += 15.minutes - 1.seconds
            assertNull(feed.latestFor(5, fetch, clock))
            assertEquals(1, requests)

            clock += 1.seconds
            assertEquals(version("5.34.1"), feed.latestFor(5, fetch, clock))
            clock += 15.minutes
            assertEquals(version("5.34.1"), feed.latestFor(5, fetch, clock))
            assertEquals(2, requests)
        }

    private fun version(value: String): TaigaReleaseVersion = requireNotNull(TaigaReleaseVersion.parse(value))
}
