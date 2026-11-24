package org.taigaui.designtokens.packageinfo

import java.nio.file.Files
import java.nio.file.Path

class DesignTokensPackageResolver(
    private val packageJsonReader: PackageJsonReader = PackageJsonReader(),
) {
    private val packageLocator = TaigaUiPackageLocator(packageJsonReader)

    fun resolve(start: Path): DesignTokensPackage? = packageLocator.locate(start)?.let(::resolve)

    internal fun resolve(scope: TaigaUiPackageScope): DesignTokensPackage? =
        discoverSourcePackages(scope)
            .takeIf(List<DesignTokenSourcePackage>::isNotEmpty)
            ?.let { packages -> createPackageSet(scope, packages) }

    private fun createPackageSet(
        scope: TaigaUiPackageScope,
        sourcePackages: List<DesignTokenSourcePackage>,
    ): DesignTokensPackage {
        val primary = sourcePackages.minBy(::sourcePackageRank)
        val locatedSourcePackages =
            sourcePackages.mapNotNull { sourcePackage ->
                scope.packages[sourcePackage.name]
            }

        return DesignTokensPackage(
            root = primary.root,
            realRoot = primary.realRoot,
            version = primary.version,
            sourcePackages = sourcePackages,
            cacheVersion =
                locatedSourcePackages
                    .sortedBy(LocatedTaigaUiPackage::name)
                    .joinToString("|") { located ->
                        located.name + "@" + located.contentVersion
                    },
            discoveryRoot = scope.discoveryRoot.toAbsolutePath().normalize(),
            cacheIdentity =
                locatedSourcePackages
                    .map(LocatedTaigaUiPackage::identity)
                    .sorted()
                    .joinToString("|"),
            invalidationRoots = scope.invalidationRoots,
            workspaceRoot = scope.workspaceRoot,
        )
    }

    private fun discoverSourcePackages(scope: TaigaUiPackageScope): List<DesignTokenSourcePackage> =
        scope.packages.values
            .mapNotNull(::readSourcePackage)
            .sortedWith(SOURCE_PACKAGE_COMPARATOR)

    private fun readSourcePackage(locatedPackage: LocatedTaigaUiPackage): DesignTokenSourcePackage? =
        packageJsonReader
            .readMetadata(locatedPackage.root.resolve(PACKAGE_JSON))
            ?.takeIf { metadata -> metadata.name.startsWith(TAIGA_UI_PACKAGE_PREFIX) }
            ?.let { metadata -> createSourcePackage(locatedPackage, metadata) }

    private fun createSourcePackage(
        locatedPackage: LocatedTaigaUiPackage,
        metadata: PackageJsonMetadata,
    ): DesignTokenSourcePackage? {
        val sourceRoots = metadata.resolveSourceRoots(locatedPackage.realRoot)

        return sourceRoots
            .takeIf(List<Path>::isNotEmpty)
            ?.let { roots ->
                DesignTokenSourcePackage(
                    name = metadata.name,
                    root = locatedPackage.root.toAbsolutePath().normalize(),
                    realRoot = locatedPackage.realRoot.toAbsolutePath().normalize(),
                    version = metadata.version,
                    sourceRoots = roots,
                )
            }
    }

    private fun PackageJsonMetadata.resolveSourceRoots(realRoot: Path): List<Path> =
        when {
            name == DESIGN_TOKENS_PACKAGE -> listOf(realRoot)
            name == STYLES_PACKAGE && exportsPackageRoot -> listOf(realRoot)
            exportsStyles && Files.isDirectory(realRoot.resolve(STYLES_DIRECTORY)) ->
                listOf(realRoot.resolve(STYLES_DIRECTORY))

            else -> emptyList()
        }

    private fun sourcePackageRank(sourcePackage: DesignTokenSourcePackage): Int =
        when (sourcePackage.name) {
            DESIGN_TOKENS_PACKAGE -> 0
            STYLES_PACKAGE -> 1
            else -> 2
        }

    private companion object {
        val SOURCE_PACKAGE_COMPARATOR =
            compareBy<DesignTokenSourcePackage>(
                { sourcePackage -> sourcePackage.name },
                { sourcePackage -> sourcePackage.version },
                { sourcePackage -> sourcePackage.realRoot.toString() },
            )
        const val PACKAGE_JSON = "package.json"
        const val STYLES_DIRECTORY = "styles"
        const val TAIGA_UI_PACKAGE_PREFIX = "@taiga-ui/"
        const val DESIGN_TOKENS_PACKAGE = "@taiga-ui/design-tokens"
        const val STYLES_PACKAGE = "@taiga-ui/styles"
    }
}
