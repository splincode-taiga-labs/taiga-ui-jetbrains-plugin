package org.taigaui.designtokens.icons

import org.taigaui.designtokens.packageinfo.TaigaUiPackageScope
import java.nio.file.Files
import java.nio.file.Path

internal data class IconCatalogContext(
    val scopeRoot: Path,
    val proprietaryPackageRoot: Path,
    val publicIconsRoot: Path,
    val tdsIconsRoot: Path,
    val invalidationRoots: Set<Path> = emptySet(),
    val physicalScopeRoot: Path? = scopeRoot,
) {
    val isProprietary: Boolean
        get() = Files.isDirectory(proprietaryPackageRoot)

    companion object {
        fun from(scopeRoot: Path): IconCatalogContext {
            val normalizedScope = scopeRoot.toAbsolutePath().normalize()

            return IconCatalogContext(
                scopeRoot = normalizedScope,
                proprietaryPackageRoot = normalizedScope.resolve(PROPRIETARY_PACKAGE),
                publicIconsRoot = normalizedScope.resolve(ICONS_SOURCE),
                tdsIconsRoot = normalizedScope.resolve(TDS_ICONS_SOURCE),
            )
        }

        fun from(scope: TaigaUiPackageScope): IconCatalogContext {
            if (Files.isDirectory(scope.discoveryRoot)) {
                return from(scope.discoveryRoot)
            }

            val cacheKey = scope.cacheKey.toAbsolutePath().normalize()
            val packages = scope.packages

            return IconCatalogContext(
                scopeRoot = cacheKey,
                proprietaryPackageRoot =
                    packages[PROPRIETARY_PACKAGE_NAME]
                        ?.realRoot
                        ?: cacheKey.resolve(PROPRIETARY_PACKAGE),
                publicIconsRoot =
                    packages[ICONS_PACKAGE_NAME]
                        ?.realRoot
                        ?.resolve(SRC_DIRECTORY)
                        ?: cacheKey.resolve(ICONS_SOURCE),
                tdsIconsRoot =
                    packages[TDS_ICONS_PACKAGE_NAME]
                        ?.realRoot
                        ?.resolve(SRC_DIRECTORY)
                        ?: cacheKey.resolve(TDS_ICONS_SOURCE),
                invalidationRoots =
                    buildSet {
                        addAll(scope.invalidationRoots)
                        packages.values.forEach { locatedPackage ->
                            addAll(locatedPackage.invalidationRoots)
                        }
                    },
                physicalScopeRoot =
                    scope.discoveryRoot
                        .takeIf(Files::isDirectory)
                        ?.toAbsolutePath()
                        ?.normalize(),
            )
        }

        private val ICONS_SOURCE = Path.of("icons", "src")
        private val TDS_ICONS_SOURCE = Path.of("tds-icons", "src")
        private const val SRC_DIRECTORY = "src"
        private const val PROPRIETARY_PACKAGE = "proprietary"
        private const val ICONS_PACKAGE_NAME = "@taiga-ui/icons"
        private const val TDS_ICONS_PACKAGE_NAME = "@taiga-ui/tds-icons"
        private const val PROPRIETARY_PACKAGE_NAME = "@taiga-ui/proprietary"
    }
}

internal interface IconCatalogSource {
    fun supports(context: IconCatalogContext): Boolean

    fun load(context: IconCatalogContext): IconCatalogLoadResult
}

internal class TdsIconCatalogSource(
    private val scanner: LocalIconCatalogScanner = LocalIconCatalogScanner(),
) : IconCatalogSource {
    override fun supports(context: IconCatalogContext): Boolean =
        context.isProprietary && Files.isDirectory(context.tdsIconsRoot)

    override fun load(context: IconCatalogContext): IconCatalogLoadResult = scanner.load(context.tdsIconsRoot)
}

internal class TbankCdnIconCatalogSource(
    private val fetcher: IconCatalogFetcher,
) : IconCatalogSource {
    override fun supports(context: IconCatalogContext): Boolean = context.isProprietary

    override fun load(context: IconCatalogContext): IconCatalogLoadResult {
        val remoteContent = runCatching(fetcher::fetch).getOrNull()
        val entries = remoteContent?.let(TbankIconCatalogParser::parseEntries).orEmpty()
        val cachePolicy =
            if (remoteContent != null && entries.isNotEmpty()) {
                IconCatalogCachePolicy.REMOTE_SUCCESS
            } else {
                IconCatalogCachePolicy.REMOTE_RETRY
            }

        return IconCatalogLoadResult(
            catalog = IconCatalog(entries),
            cachePolicy = cachePolicy,
        )
    }
}

internal class PublicIconCatalogSource(
    private val scanner: LocalIconCatalogScanner = LocalIconCatalogScanner(),
) : IconCatalogSource {
    override fun supports(context: IconCatalogContext): Boolean = !context.isProprietary

    override fun load(context: IconCatalogContext): IconCatalogLoadResult = scanner.load(context.publicIconsRoot)
}

internal class LocalIconCatalogScanner {
    fun load(root: Path): IconCatalogLoadResult =
        IconCatalogLoadResult(
            catalog = IconCatalog(scan(root)),
            cachePolicy = IconCatalogCachePolicy.LOCAL,
        )

    private fun scan(root: Path): List<IconCatalogEntry> {
        if (!Files.isDirectory(root)) {
            return emptyList()
        }

        return runCatching {
            Files.walk(root).use { paths ->
                paths
                    .filter(Files::isRegularFile)
                    .filter { file -> file.fileName.toString().endsWith(SVG_EXTENSION, ignoreCase = true) }
                    .map { file -> IconCatalogEntry(file.toIconName(root), IconSvgSource.Local(file)) }
                    .sorted(compareBy(IconCatalogEntry::name))
                    .toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun Path.toIconName(root: Path): String {
        val relative = root.relativize(this)
        val segments =
            (0 until relative.nameCount)
                .map { index -> relative.getName(index).toString() }
                .toMutableList()
        val lastIndex = segments.lastIndex

        segments[lastIndex] =
            segments[lastIndex].dropLast(SVG_EXTENSION.length)

        return ICON_PREFIX + segments.joinToString(".")
    }

    private companion object {
        const val SVG_EXTENSION = ".svg"
    }
}

internal fun defaultIconCatalogSources(remoteFetcher: IconCatalogFetcher): List<IconCatalogSource> =
    listOf(
        TdsIconCatalogSource(),
        TbankCdnIconCatalogSource(remoteFetcher),
        PublicIconCatalogSource(),
    )
