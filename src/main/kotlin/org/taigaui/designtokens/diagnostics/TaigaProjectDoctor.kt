package org.taigaui.designtokens.diagnostics

import org.taigaui.designtokens.packageinfo.PackageJsonReader
import org.taigaui.designtokens.packageinfo.TaigaUiPackageLocator
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
) {
    val supportsV5Migration: Boolean
        get() = reference?.version?.substringBefore('.') == "5"
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
    }

private fun String.asReportCell(): String = replace("|", "&#124;").lines().joinToString(" ")

internal class TaigaProjectDoctor(
    private val locator: TaigaUiPackageLocator = TaigaUiPackageLocator(),
    private val reader: PackageJsonReader = PackageJsonReader(),
) {
    fun inspect(source: Path): TaigaDoctorReport {
        val packages =
            locator
                .locate(source)
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
