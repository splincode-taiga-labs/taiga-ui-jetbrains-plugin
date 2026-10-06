package org.taigaui.designtokens.icons

import org.taigaui.designtokens.packageinfo.TaigaUiPackageLocator
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

internal fun interface IconCatalogFetcher {
    fun fetch(): String?
}

internal sealed interface IconSvgSource {
    val uri: URI

    data class Local(
        val path: Path,
    ) : IconSvgSource {
        override val uri: URI = path.toUri()
    }

    data class Remote(
        override val uri: URI,
    ) : IconSvgSource
}

internal data class IconCatalogEntry(
    val name: String,
    val svgSource: IconSvgSource,
)

internal class IconCatalog(
    entries: List<IconCatalogEntry>,
) {
    private val entriesByName = entries.associateBy(IconCatalogEntry::name)

    val names: List<String> = entriesByName.keys.sorted()

    fun svgSource(iconName: String): IconSvgSource? = entriesByName[iconName]?.svgSource
}

internal enum class IconCatalogCachePolicy(
    val maxAge: Duration?,
) {
    LOCAL(maxAge = null),
    REMOTE_SUCCESS(maxAge = Duration.ofMinutes(15)),
    REMOTE_RETRY(maxAge = Duration.ofSeconds(30)),
}

internal data class IconCatalogLoadResult(
    val catalog: IconCatalog,
    val cachePolicy: IconCatalogCachePolicy,
)

internal class IconCatalogLoader(
    sources: List<IconCatalogSource>,
    private val packageLocator: TaigaUiPackageLocator = TaigaUiPackageLocator(),
) {
    private val sources = sources.toList()
    private val resolvedContexts = ConcurrentHashMap<Path, IconCatalogContext>()

    constructor(remoteFetcher: IconCatalogFetcher = TbankIconCatalogFetcher()) :
        this(defaultIconCatalogSources(remoteFetcher))

    fun resolveScopeRoot(sourceFile: Path): Path? =
        packageLocator
            .locate(sourceFile)
            ?.let(IconCatalogContext::from)
            ?.also { context ->
                resolvedContexts[context.scopeRoot] = context
            }?.scopeRoot

    fun load(scopeRoot: Path): List<String> = loadCatalog(scopeRoot).names

    fun loadCatalog(scopeRoot: Path): IconCatalog = loadCatalogWithPolicy(scopeRoot).catalog

    fun loadCatalogWithPolicy(scopeRoot: Path): IconCatalogLoadResult {
        val normalizedScope = scopeRoot.toAbsolutePath().normalize()
        val context =
            resolvedContexts[normalizedScope]
                ?: IconCatalogContext.from(normalizedScope)

        return sources
            .firstOrNull { source -> source.supports(context) }
            ?.load(context)
            ?: EMPTY_CATALOG
    }

    fun isAffected(
        scopeRoot: Path,
        changedPath: Path,
    ): Boolean {
        val normalizedScope = scopeRoot.toAbsolutePath().normalize()
        val context = resolvedContexts[normalizedScope]

        return if (context == null) {
            IconCatalogInvalidation.isAffected(normalizedScope, changedPath)
        } else {
            IconCatalogInvalidation.isAffected(context, changedPath)
        }
    }

    fun clearResolvedContexts() {
        resolvedContexts.clear()
    }

    private companion object {
        val EMPTY_CATALOG = IconCatalogLoadResult(
            catalog = IconCatalog(emptyList()),
            cachePolicy = IconCatalogCachePolicy.LOCAL,
        )
    }
}

internal object TbankIconCatalogParser {
    fun parse(content: String): List<String> = parseEntries(content).map(IconCatalogEntry::name)

    fun parseEntries(content: String): List<IconCatalogEntry> {
        val iconsBody = ICONS_OBJECT.find(content)?.groupValues?.get(1) ?: return emptyList()

        return ICON_GROUP
            .findAll(iconsBody)
            .flatMap { group ->
                val path = normalizeGroupPath(group.groupValues[1])

                if (path == null) {
                    emptySequence()
                } else {
                    ICON_NAME
                        .findAll(group.groupValues[2])
                        .mapNotNull { match -> createRemoteEntry(path, match.groupValues[1]) }
                }
            }.distinctBy(IconCatalogEntry::name)
            .sortedBy(IconCatalogEntry::name)
            .toList()
    }

    private fun createRemoteEntry(
        path: String,
        rawIconName: String,
    ): IconCatalogEntry? {
        val iconName = normalizeSegment(rawIconName) ?: return null
        val namePath = path.replace('/', '.')
        val uri = runCatching { URI.create("$ICONS_BASE_URL/$path/$iconName.svg") }.getOrNull()

        return uri?.let { sourceUri ->
            IconCatalogEntry(
                name = "$ICON_PREFIX$namePath.$iconName",
                svgSource = IconSvgSource.Remote(sourceUri),
            )
        }
    }

    private fun normalizeGroupPath(rawPath: String): String? {
        val segments = rawPath.split('/').map(String::trim)

        return segments
            .takeIf { values -> values.isNotEmpty() && values.all { segment -> normalizeSegment(segment) != null } }
            ?.joinToString("/")
    }

    private fun normalizeSegment(value: String): String? =
        value
            .trim()
            .takeIf { segment -> segment.isNotEmpty() && segment.all(Char::isIconNameCharacter) }

    private val ICONS_OBJECT =
        Regex(
            pattern = "\\\"icons\\\"\\s*:\\s*\\{(.*?)\\}\\s*(?:,|\\})",
            option = RegexOption.DOT_MATCHES_ALL,
        )
    private val ICON_GROUP =
        Regex(
            pattern = "\\\"([^\\\"]+)\\\"\\s*:\\s*\\[(.*?)]",
            option = RegexOption.DOT_MATCHES_ALL,
        )
    private val ICON_NAME = Regex("\\\"([^\\\"]+)\\\"")
}

private class TbankIconCatalogFetcher : IconCatalogFetcher {
    private val client =
        HttpClient
            .newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()

    override fun fetch(): String? =
        runCatching {
            val request =
                HttpRequest
                    .newBuilder(URI.create(ICONS_CATALOG_URL))
                    .timeout(REQUEST_TIMEOUT)
                    .GET()
                    .build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))

            response.body().takeIf { response.statusCode() in HTTP_SUCCESS }
        }.getOrNull()

    private companion object {
        val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(3)
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(8)
        val HTTP_SUCCESS = 200..299
        const val ICONS_CATALOG_URL = "$ICONS_BASE_URL/data.json"
    }
}

internal const val ICON_PREFIX = "@tui."
internal const val ICONS_BASE_URL = "https://cdn.tbank.ru/core/design-tokens/v1/web"
