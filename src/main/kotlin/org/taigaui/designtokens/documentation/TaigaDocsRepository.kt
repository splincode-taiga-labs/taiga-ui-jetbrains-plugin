package org.taigaui.designtokens.documentation

import org.taigaui.designtokens.settings.TaigaNetworkAccess
import org.taigaui.designtokens.settings.TaigaNetworkMode
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant

internal fun interface TaigaDocsFetcher {
    fun fetch(source: TaigaDocsSource): String?
}

internal enum class TaigaDocsLoadOrigin {
    DISK_CACHE,
    BUNDLED,
    REMOTE,
}

internal data class TaigaDocsLoadResult(
    val index: TaigaDocsIndex,
    val origin: TaigaDocsLoadOrigin,
    val refreshRecommended: Boolean = false,
)

internal class TaigaDocsRepository(
    private val fetcher: TaigaDocsFetcher = HttpTaigaDocsFetcher(),
    private val cache: TaigaDocsCache = TaigaDocsCache(),
    private val parser: TaigaDocsParser = TaigaDocsParser(),
    private val network: TaigaNetworkAccess = TaigaNetworkAccess { TaigaNetworkMode.ONLINE },
    private val bundled: (TaigaDocsSource) -> TaigaDocsLoadResult? = { null },
) {
    fun load(source: TaigaDocsSource): TaigaDocsLoadResult? = loadLocal(source) ?: loadFresh(source)

    fun refresh(source: TaigaDocsSource): TaigaDocsLoadResult? = loadFresh(source, explicit = true) ?: loadLocal(source)

    fun loadLocal(source: TaigaDocsSource): TaigaDocsLoadResult? = loadCached(source) ?: bundled(source)

    fun allowsAutomaticRefresh(): Boolean = network.allowsRequest()

    fun loadCached(source: TaigaDocsSource): TaigaDocsLoadResult? =
        cache
            .read(source)
            ?.let { content ->
                parser.parse(
                    source,
                    content,
                    TaigaDocsProvenance(TaigaDocsLoadOrigin.DISK_CACHE, retrievedAt = cache.modifiedAt(source)),
                )
            }?.let { index ->
                TaigaDocsLoadResult(
                    index = index,
                    origin = TaigaDocsLoadOrigin.DISK_CACHE,
                    refreshRecommended = !cache.isFresh(source, CACHE_FRESHNESS),
                )
            }

    fun loadFresh(
        source: TaigaDocsSource,
        explicit: Boolean = false,
    ): TaigaDocsLoadResult? {
        val content = network.request(explicit) { fetcher.fetch(source) }?.takeIf(String::isNotBlank)
        val provenance = TaigaDocsProvenance(TaigaDocsLoadOrigin.REMOTE, retrievedAt = Instant.now())
        val index = content?.let { parser.parse(source, it, provenance) }

        if (content != null && index != null) {
            cache.write(source, content)
        }

        return index?.let {
            TaigaDocsLoadResult(
                index = it,
                origin = TaigaDocsLoadOrigin.REMOTE,
            )
        }
    }

    fun invalidate(source: TaigaDocsSource): Boolean = cache.invalidate(source)
}

private val CACHE_FRESHNESS: Duration = Duration.ofHours(6)

private class HttpTaigaDocsFetcher : TaigaDocsFetcher {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun fetch(source: TaigaDocsSource): String? =
        runCatching {
            val request =
                HttpRequest
                    .newBuilder(source.contentUri)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Accept", "text/plain")
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

            response.body().takeIf { response.statusCode() in HTTP_SUCCESS }
        }.getOrNull()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
        val HTTP_SUCCESS = 200..299
    }
}
