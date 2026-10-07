package org.taigaui.designtokens.packageinfo

import java.nio.file.Files
import java.nio.file.Path

internal data class LocatedTaigaUiPackage(
    val name: String,
    val root: Path,
    val realRoot: Path,
    val identity: String,
    val contentVersion: String,
    val invalidationRoots: Set<Path> = emptySet(),
)

internal data class TaigaUiPackageScope(
    val workspaceRoot: Path,
    val discoveryRoot: Path,
    val packages: Map<String, LocatedTaigaUiPackage>,
    val identity: String,
    val contentVersion: String,
    val invalidationRoots: Set<Path> = emptySet(),
) {
    val cacheKey: Path =
        if (Files.isDirectory(discoveryRoot)) {
            discoveryRoot
        } else {
            Path
                .of(
                    System.getProperty("java.io.tmpdir"),
                    "taiga-ui-jetbrains-plugin",
                    "yarn-pnp-scopes",
                    stablePnpHash(identity + "|" + contentVersion),
                ).toAbsolutePath()
                .normalize()
        }
}

internal class TaigaUiPackageLocator(
    private val packageJsonReader: PackageJsonReader = PackageJsonReader(),
    private val pnpManifestReader: YarnPnpManifestReader = YarnPnpManifestReader(),
    private val pnpMaterializer: YarnPnpPackageMaterializer = YarnPnpPackageMaterializer(),
) {
    fun locate(start: Path): TaigaUiPackageScope? {
        val startDirectory = start.startDirectory() ?: return null

        return locateNodeModules(startDirectory) ?: locateYarnPnp(startDirectory)
    }

    private fun locateNodeModules(startDirectory: Path): TaigaUiPackageScope? {
        val scopeRoot =
            generateSequence(startDirectory) { directory -> directory.parent }
                .map { directory -> directory.resolve(TAIGA_UI_SCOPE) }
                .firstOrNull(Files::isDirectory)
                ?: return null
        val packages =
            runCatching {
                Files.list(scopeRoot).use { paths ->
                    paths
                        .filter(Files::isDirectory)
                        .map(::readPhysicalPackage)
                        .filter { located -> located != null }
                        .map { located -> requireNotNull(located) }
                        .toList()
                        .associateBy(LocatedTaigaUiPackage::name)
                }
            }.getOrElse { emptyMap() }
        val normalizedScope = scopeRoot.toAbsolutePath().normalize()
        val workspaceRoot = requireNotNull(normalizedScope.parent?.parent)

        return TaigaUiPackageScope(
            workspaceRoot = workspaceRoot,
            discoveryRoot = normalizedScope,
            packages = packages,
            identity = NODE_MODULES_IDENTITY_PREFIX + normalizedScope.toRealPathOrSelf(),
            contentVersion =
                packages.values
                    .sortedBy(LocatedTaigaUiPackage::name)
                    .joinToString("|") { located ->
                        located.name + "@" + located.contentVersion
                    },
        )
    }

    // Guard clauses keep package discovery fail-closed for incomplete metadata.
    @Suppress("ReturnCount")
    private fun readPhysicalPackage(packageRoot: Path): LocatedTaigaUiPackage? {
        val metadata = packageJsonReader.readMetadata(packageRoot.resolve(PACKAGE_JSON)) ?: return null

        if (!metadata.name.startsWith(TAIGA_UI_PACKAGE_PREFIX)) {
            return null
        }

        val logicalRoot = packageRoot.toAbsolutePath().normalize()
        val realRoot = logicalRoot.toRealPathOrSelf()

        return LocatedTaigaUiPackage(
            name = metadata.name,
            root = logicalRoot,
            realRoot = realRoot,
            identity = FILE_SYSTEM_IDENTITY_PREFIX + realRoot,
            contentVersion = metadata.version,
        )
    }

    // Guard clauses keep package discovery fail-closed for incomplete metadata.
    @Suppress("ReturnCount")
    private fun locateYarnPnp(startDirectory: Path): TaigaUiPackageScope? {
        val manifest =
            generateSequence(startDirectory) { directory -> directory.parent }
                .mapNotNull(pnpManifestReader::read)
                .firstOrNull()
                ?: return null
        val issuer = manifest.findIssuer(startDirectory) ?: return null
        val reachablePackages = manifest.reachableTaigaUiPackages(issuer)

        if (reachablePackages.isEmpty()) {
            return null
        }

        val packages =
            reachablePackages
                .mapNotNull { packageInfo ->
                    materializePnpPackage(manifest, packageInfo)
                }.associateBy(LocatedTaigaUiPackage::name)

        if (packages.isEmpty()) {
            return null
        }

        val workspaceRoot =
            manifest
                .packagePath(issuer)
                ?.takeIf { packagePath -> startDirectory.startsWith(packagePath) }
                ?: manifest.root
        val identity =
            YARN_PNP_IDENTITY_PREFIX +
                manifest.primarySource.toAbsolutePath().normalize() +
                ":" +
                issuer.displayName
        val contentVersion =
            buildList {
                add(manifest.contentVersion)
                packages.values
                    .sortedBy(LocatedTaigaUiPackage::name)
                    .forEach { located ->
                        add(located.name + "@" + located.contentVersion)
                    }
            }.joinToString("|")
        val invalidationRoots =
            buildSet {
                addAll(manifest.sourceFiles)
                packages.values.forEach { located ->
                    addAll(located.invalidationRoots)
                }
            }

        return TaigaUiPackageScope(
            workspaceRoot = workspaceRoot.toAbsolutePath().normalize(),
            discoveryRoot = manifest.primarySource.toAbsolutePath().normalize(),
            packages = packages,
            identity = identity,
            contentVersion = contentVersion,
            invalidationRoots = invalidationRoots,
        )
    }

    // Guard clauses keep package discovery fail-closed for incomplete metadata.
    @Suppress("ReturnCount")
    private fun materializePnpPackage(
        manifest: YarnPnpManifest,
        packageInfo: YarnPnpPackageInfo,
    ): LocatedTaigaUiPackage? {
        val stableIdentity =
            YARN_PNP_IDENTITY_PREFIX +
                manifest.primarySource.toAbsolutePath().normalize() +
                ":" +
                packageInfo.locator.displayName +
                ":" +
                packageInfo.packageLocation
        val materialized =
            pnpMaterializer.materialize(
                manifestRoot = manifest.root,
                packageLocation = packageInfo.packageLocation,
                identity = stableIdentity,
            ) ?: return null
        val metadata =
            packageJsonReader.readMetadata(materialized.root.resolve(PACKAGE_JSON))
                ?: return null

        if (!metadata.name.startsWith(TAIGA_UI_PACKAGE_PREFIX)) {
            return null
        }

        return LocatedTaigaUiPackage(
            name = metadata.name,
            root = materialized.root,
            realRoot = materialized.root.toRealPathOrSelf(),
            identity = stableIdentity,
            contentVersion = metadata.version + ":" + materialized.contentVersion,
            invalidationRoots = materialized.invalidationRoots + manifest.sourceFiles,
        )
    }

    private fun Path.startDirectory(): Path? {
        val normalized = toAbsolutePath().normalize()

        return if (Files.isDirectory(normalized)) {
            normalized
        } else {
            normalized.parent
        }
    }

    private companion object {
        val TAIGA_UI_SCOPE = Path.of("node_modules", "@taiga-ui")
        const val PACKAGE_JSON = "package.json"
        const val TAIGA_UI_PACKAGE_PREFIX = "@taiga-ui/"
        const val NODE_MODULES_IDENTITY_PREFIX = "node-modules:"
        const val FILE_SYSTEM_IDENTITY_PREFIX = "fs:"
        const val YARN_PNP_IDENTITY_PREFIX = "yarn-pnp:"
    }
}
