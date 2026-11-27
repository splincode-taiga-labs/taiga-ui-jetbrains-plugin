package org.taigaui.designtokens.diagnostics

import org.taigaui.designtokens.documentation.TaigaBundledDocs
import org.taigaui.designtokens.documentation.TaigaDocsCacheStatus
import org.taigaui.designtokens.documentation.TaigaDocsSource
import org.taigaui.designtokens.documentation.TaigaDocsSources
import org.taigaui.designtokens.documentation.TaigaUiVersionDetector
import org.taigaui.designtokens.icons.IconCatalogContext
import org.taigaui.designtokens.packageinfo.DesignTokenSourcePackage
import org.taigaui.designtokens.packageinfo.DesignTokensPackageResolver
import org.taigaui.designtokens.packageinfo.PackageJsonReader
import org.taigaui.designtokens.packageinfo.TaigaUiPackageLocator
import org.taigaui.designtokens.packageinfo.TaigaUiPackageScope
import java.nio.file.Files
import java.nio.file.Path

internal data class TaigaDoctorPackage(
    val name: String,
    val version: String,
    val manifest: Path,
    val expectedVersion: String?,
)

internal data class TaigaDoctorReport(
    val reference: TaigaDoctorPackage?,
    val packages: List<TaigaDoctorPackage>,
    val sources: TaigaDoctorSources? = null,
) {
    val supportsV5Migration: Boolean
        get() = reference?.version?.substringBefore('.') == "5"
}

internal data class TaigaDoctorSources(
    val designTokens: List<String>,
    val icons: String,
    val documentation: TaigaDoctorDocumentation?,
) {
    val designTokenSummary: String
        get() = designTokens.joinToString().ifEmpty { "No installed source found" }

    val documentationSummary: String
        get() = documentation?.summary ?: "No versioned source available"
}

internal data class TaigaDoctorDocumentation(
    val source: TaigaDocsSource,
    val cacheStatus: TaigaDocsCacheStatus,
) {
    val bundledAvailable: Boolean
        get() = TaigaBundledDocs.hasSnapshot(source)

    val summary: String
        get() {
            val status =
                when (cacheStatus) {
                    TaigaDocsCacheStatus.IN_MEMORY -> "Loaded in memory"
                    TaigaDocsCacheStatus.ON_DISK -> "Disk cache present"
                    TaigaDocsCacheStatus.NOT_CACHED ->
                        if (bundledAvailable) "Bundled snapshot available" else "Not cached"
                }

            return "v${source.majorVersion} · $status"
        }
}

internal fun TaigaDoctorReport.toMarkdown(): String =
    buildString {
        appendLine("# Taiga UI Doctor")
        appendLine()
        appendLine("Detected release: " + (reference?.let { it.name + " " + it.version } ?: "Unavailable"))
        appendLine("Installed packages: " + packages.size)
        appendLine("Version mismatches: " + packages.count { it.expectedVersion != null })
        appendLine()
        appendLine("| Package | Installed version | Version mismatch |")
        appendLine("| --- | --- | --- |")
        packages.forEach { entry ->
            val mismatch = entry.expectedVersion?.let { "Expected " + it } ?: "—"

            appendLine(
                "| " + entry.name.asReportCell() + " | " + entry.version.asReportCell() +
                    " | " + mismatch.asReportCell() + " |",
            )
        }
        sources?.let { status ->
            appendLine()
            appendLine("## Sources")
            appendLine()
            appendLine("- Design tokens: " + status.designTokenSummary)
            appendLine("- Icons: " + status.icons)
            appendLine("- Documentation: " + status.documentationSummary)
        }
    }

private fun String.asReportCell(): String = replace("|", "&#124;").lines().joinToString(" ")

internal class TaigaProjectDoctor(
    private val locator: TaigaUiPackageLocator = TaigaUiPackageLocator(),
    private val reader: PackageJsonReader = PackageJsonReader(),
) {
    private val tokenPackages = DesignTokensPackageResolver(reader)
    private val versionDetector = TaigaUiVersionDetector(locator, reader)

    fun inspect(source: Path): TaigaDoctorReport = inspect(locator.locate(source))

    fun inspectWithSources(
        source: Path,
        cacheStatus: (TaigaDocsSource) -> TaigaDocsCacheStatus,
    ): TaigaDoctorReport {
        val scope = locator.locate(source)
        val report = inspect(scope)

        return if (scope == null || scope.packages.isEmpty()) {
            report
        } else {
            val docSource = versionDetector.detect(scope)?.majorVersion?.let(TaigaDocsSources::forMajor)
            val designTokens =
                tokenPackages
                    .resolve(
                        scope,
                    )?.sourcePackages
                    .orEmpty()
                    .map(DesignTokenSourcePackage::name)

            report.copy(
                sources =
                    TaigaDoctorSources(
                        designTokens = designTokens,
                        icons = IconCatalogContext.from(scope).doctorIconSource(),
                        documentation = docSource?.let { TaigaDoctorDocumentation(it, cacheStatus(it)) },
                    ),
            )
        }
    }

    private fun inspect(scope: TaigaUiPackageScope?): TaigaDoctorReport {
        val packages =
            scope
                ?.packages
                .orEmpty()
                .toSortedMap()
                .mapNotNull { (name, installed) ->
                    val manifest = installed.root.resolve("package.json")
                    val version = reader.readVersion(manifest) ?: return@mapNotNull null

                    TaigaDoctorPackage(name, version, manifest, expectedVersion = null)
                }
        val aligned = packages.filter { entry -> entry.name in RELEASE_PACKAGES && entry.version.isReleaseVersion() }
        val reference = aligned.firstOrNull { entry -> entry.name == "@taiga-ui/core" } ?: aligned.firstOrNull()
        val reportPackages =
            packages.map { entry ->
                if (entry in aligned && reference != null && entry.version.release() != reference.version.release()) {
                    entry.copy(expectedVersion = reference.version)
                } else {
                    entry
                }
            }

        return TaigaDoctorReport(reference, reportPackages)
    }

    private fun String.isReleaseVersion(): Boolean = RELEASE_VERSION.matches(release())

    private fun String.release(): String = substringBefore('+')

    private companion object {
        val RELEASE_VERSION = Regex("""\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""")
        val RELEASE_PACKAGES =
            setOf(
                "@taiga-ui/cdk",
                "@taiga-ui/core",
                "@taiga-ui/icons",
                "@taiga-ui/kit",
                "@taiga-ui/layout",
                "@taiga-ui/legacy",
                "@taiga-ui/styles",
            )
    }
}

private fun IconCatalogContext.doctorIconSource(): String =
    when {
        isProprietary -> if (hasLocalTdsIcons()) "Local TDS icons" else "Remote fallback configured"
        Files.isDirectory(publicIconsRoot) -> "Local public icons"
        else -> "No local source found"
    }
