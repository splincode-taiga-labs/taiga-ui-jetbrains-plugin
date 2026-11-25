package org.taigaui.designtokens.releases

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.intellij.openapi.components.Service
import com.intellij.util.io.HttpRequests
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

@Service(Service.Level.APP)
internal class TaigaReleaseFeed {
    private val mutex = Mutex()
    private var checkedAt: TimeMark? = null
    private var latest = emptyMap<Int, TaigaReleaseVersion>()

    suspend fun latestFor(major: Int): TaigaReleaseVersion? =
        mutex.withLock {
            val interval = if (latest.isEmpty()) 15.minutes else RELEASE_CHECK_INTERVAL

            if (checkedAt?.elapsedNow()?.let { it < interval } != true) {
                latest = runInterruptible(Dispatchers.IO) { fetchStableReleases() }
                checkedAt = TimeSource.Monotonic.markNow()
            }

            latest[major]
        }
}

internal val RELEASE_CHECK_INTERVAL = 6.hours

private fun fetchStableReleases(): Map<Int, TaigaReleaseVersion> =
    try {
        HttpRequests
            .request("https://api.github.com/repos/taiga-family/taiga-ui/releases?per_page=100")
            .productNameAsUserAgent()
            .accept("application/vnd.github+json")
            .connectTimeout(3_000)
            .readTimeout(8_000)
            .readString()
            .let(::parseTaigaReleases)
    } catch (_: IOException) {
        emptyMap()
    } catch (_: JsonParseException) {
        emptyMap()
    }

internal fun parseTaigaReleases(json: String): Map<Int, TaigaReleaseVersion> {
    val releases = JsonParser.parseString(json).takeIf(JsonElement::isJsonArray)?.asJsonArray ?: return emptyMap()
    val latest = mutableMapOf<Int, TaigaReleaseVersion>()

    releases.mapNotNull(::stableVersion).forEach { version ->
        latest.merge(version.major, version, ::maxOf)
    }

    return latest
}

private fun stableVersion(element: JsonElement): TaigaReleaseVersion? {
    val release =
        (element as? JsonObject)?.takeIf {
            it.flag("draft") == false && it.flag("prerelease") == false
        }
    val tag = release?.get("tag_name")?.takeIf(JsonElement::isJsonPrimitive)?.asString ?: return null

    return TaigaReleaseVersion.parse(tag)?.takeIf { it.stable && it.tag == tag }
}

private fun JsonObject.flag(name: String): Boolean? =
    get(name)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive
        ?.takeIf { it.isBoolean }
        ?.asBoolean

internal data class TaigaReleaseVersion(
    val major: Int,
    val minor: Int,
    val patch: Int,
    val stable: Boolean,
) : Comparable<TaigaReleaseVersion> {
    val tag: String
        get() = "v$major.$minor.$patch"

    val url: String
        get() = "https://github.com/taiga-family/taiga-ui/releases/tag/$tag"

    override fun compareTo(other: TaigaReleaseVersion): Int =
        compareValuesBy(
            this,
            other,
            TaigaReleaseVersion::major,
            TaigaReleaseVersion::minor,
            TaigaReleaseVersion::patch,
            TaigaReleaseVersion::stable,
        )

    companion object {
        private val pattern = Regex("""v?(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.-]+)?(?:\+[0-9A-Za-z.-]+)?""")

        fun parse(value: String): TaigaReleaseVersion? {
            val match = pattern.matchEntire(value) ?: return null
            val numbers = match.groupValues.subList(1, 4).mapNotNull(String::toIntOrNull)

            return numbers.takeIf { it.size == 3 }?.let { (major, minor, patch) ->
                TaigaReleaseVersion(major, minor, patch, match.groups[4] == null)
            }
        }
    }
}

internal fun shouldShowRelease(
    installed: TaigaReleaseVersion,
    available: TaigaReleaseVersion,
    lastNotified: TaigaReleaseVersion?,
): Boolean =
    available.stable &&
        available.major == installed.major &&
        available > installed &&
        (lastNotified == null || available > lastNotified)
