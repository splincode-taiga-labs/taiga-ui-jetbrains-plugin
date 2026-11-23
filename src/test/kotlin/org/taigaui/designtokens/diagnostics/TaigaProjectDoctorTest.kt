package org.taigaui.designtokens.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TaigaProjectDoctorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val doctor = TaigaProjectDoctor()

    @Test
    fun reportsVersionDriftWithinTheActivePackageScope() {
        val workspace = temporaryFolder.newFolder().toPath()
        val legacy = Files.createDirectories(workspace.resolve("apps/legacy/src"))
        val modernApp = Files.createDirectories(workspace.resolve("apps/modern"))
        val modern = Files.createDirectories(modernApp.resolve("src"))
        install(workspace, "core", "4.93.0")
        install(workspace, "kit", "4.93.0")
        install(modernApp, "core", "5.18.0")
        install(modernApp, "kit", "5.18.0")
        install(modernApp, "event-plugins", "5.0.0")
        install(modernApp, "design-tokens", "0.322.0")

        assertEquals("4.93.0", doctor.inspect(legacy).reference?.version)
        assertEquals("5.18.0", doctor.inspect(modern).reference?.version)
        assertFalse(doctor.inspect(legacy).supportsV5Migration)
        assertTrue(doctor.inspect(modern).supportsV5Migration)
        assertEquals(emptyList<TaigaDoctorPackage>(), doctor.inspect(legacy).warnings())
        assertEquals(emptyList<TaigaDoctorPackage>(), doctor.inspect(modern).warnings())

        val kitManifest = install(modernApp, "kit", "4.93.0")

        assertEquals(
            listOf(TaigaDoctorPackage("@taiga-ui/kit", "4.93.0", kitManifest, expectedVersion = "5.18.0")),
            doctor.inspect(modern).warnings(),
        )
        assertEquals(emptyList<TaigaDoctorPackage>(), doctor.inspect(legacy).warnings())
    }

    @Test
    fun ignoresBuildMetadataAndShowsIndependentPackagesWithoutAReleaseReference() {
        val workspace = temporaryFolder.newFolder().toPath()
        install(workspace, "design-tokens", "0.322.0")
        val independent = doctor.inspect(workspace)

        assertNull(independent.reference)
        assertEquals(listOf("@taiga-ui/design-tokens"), independent.packages.map(TaigaDoctorPackage::name))

        install(workspace, "core", "5.18.0+core")
        install(workspace, "kit", "5.18.0+kit")
        assertEquals(emptyList<TaigaDoctorPackage>(), doctor.inspect(workspace).warnings())

        install(workspace, "kit", "5.18.0-next.1")
        assertEquals(listOf("@taiga-ui/kit"), doctor.inspect(workspace).warnings().map(TaigaDoctorPackage::name))
    }

    @Test
    fun copiesAReportScopedToTheSelectedAppWithoutLeakingLocalPaths() {
        val workspace = temporaryFolder.newFolder().toPath()
        val legacy = Files.createDirectories(workspace.resolve("apps/legacy/src"))
        val modernApp = Files.createDirectories(workspace.resolve("apps/modern"))
        val modern = Files.createDirectories(modernApp.resolve("src"))
        install(workspace, "core", "4.93.0")
        install(modernApp, "core", "5.18.0")
        install(modernApp, "kit", "4.93.0")
        install(modernApp, "event-plugins", "5.0.0")

        val markdown = doctor.inspect(modern).toMarkdown()

        assertTrue(markdown.startsWith("# Taiga UI Doctor"))
        assertTrue(markdown.contains("Detected release: @taiga-ui/core 5.18.0"))
        assertTrue(markdown.contains("Installed packages: 3"))
        assertTrue(markdown.contains("Version mismatches: 1"))
        assertTrue(markdown.contains("| @taiga-ui/kit | 4.93.0 | Expected 5.18.0 |"))
        assertTrue(markdown.contains("| @taiga-ui/event-plugins | 5.0.0 | — |"))
        assertFalse(markdown.contains("| @taiga-ui/event-plugins | 5.0.0 | Expected"))
        assertFalse(markdown.contains(workspace.toString()))
        assertFalse(doctor.inspect(legacy).toMarkdown().contains("5.18.0"))
    }

    @Test
    fun reportsIndependentPackagesWithoutInventingAnExpectedRelease() {
        val workspace = temporaryFolder.newFolder().toPath()
        install(workspace, "design-tokens", "0.322.0")

        val markdown = doctor.inspect(workspace).toMarkdown()

        assertTrue(markdown.contains("Detected release: Unavailable"))
        assertTrue(markdown.contains("Installed packages: 1"))
        assertTrue(markdown.contains("Version mismatches: 0"))
        assertTrue(markdown.contains("| @taiga-ui/design-tokens | 0.322.0 | — |"))
    }

    @Test
    fun reportsAnEmptyWorkspaceWithoutInventingPackagesOrARelease() {
        val workspace = temporaryFolder.newFolder().toPath()
        val report = doctor.inspect(workspace)

        assertNull(report.reference)
        assertTrue(report.packages.isEmpty())
        assertFalse(report.supportsV5Migration)
        assertTrue(report.toMarkdown().contains("Installed packages: 0"))
        assertTrue(report.toMarkdown().contains("Version mismatches: 0"))
    }

    @Test
    fun selectsTheFirstValidReleaseWhenCoreVersionIsNotSemver() {
        val workspace = temporaryFolder.newFolder().toPath()
        install(workspace, "core", "workspace:*")
        install(workspace, "icons", "5.17.0")
        install(workspace, "kit", "5.18.0")

        val report = doctor.inspect(workspace)

        assertEquals("@taiga-ui/icons", report.reference?.name)
        assertEquals("5.17.0", report.reference?.version)
        assertTrue(report.supportsV5Migration)
        assertEquals(listOf("@taiga-ui/kit"), report.warnings().map(TaigaDoctorPackage::name))
        assertNull(report.packages.first { it.name == "@taiga-ui/core" }.expectedVersion)
    }

    @Test
    fun skipsInstalledPackagesWithMissingVersionMetadata() {
        val workspace = temporaryFolder.newFolder().toPath()
        val missingVersion = install(workspace, "kit", "5.18.0")
        Files.writeString(missingVersion, """{"name":"@taiga-ui/kit"}""")
        install(workspace, "core", "5.18.0")

        val report = doctor.inspect(workspace)

        assertEquals(listOf("@taiga-ui/core"), report.packages.map(TaigaDoctorPackage::name))
        assertEquals("@taiga-ui/core", report.reference?.name)
    }

    @Test
    fun escapesMarkdownCellSeparatorsAndNewlinesInPackageMetadata() {
        val workspace = temporaryFolder.newFolder().toPath()
        val report =
            TaigaDoctorReport(
                reference = null,
                packages =
                    listOf(
                        TaigaDoctorPackage(
                            name = "@taiga-ui/kit|extra",
                            version = "5.18.0\ninjected",
                            manifest = workspace.resolve("package.json"),
                            expectedVersion = "5.17.0|legacy",
                        ),
                    ),
            )

        val markdown = report.toMarkdown()

        assertTrue(markdown.contains("Version mismatches: 1"))
        assertTrue(
            markdown.contains(
                "| @taiga-ui/kit&#124;extra | 5.18.0 injected | Expected 5.17.0&#124;legacy |",
            ),
        )
        assertFalse(markdown.contains("5.18.0\ninjected"))
        assertFalse(markdown.contains(workspace.toString()))
    }

    private fun install(
        directory: Path,
        name: String,
        version: String,
    ): Path {
        val installed = Files.createDirectories(directory.resolve("node_modules/@taiga-ui/$name"))

        return Files.writeString(
            installed.resolve("package.json"),
            """{"name":"@taiga-ui/$name","version":"$version"}""",
        )
    }

    private fun TaigaDoctorReport.warnings(): List<TaigaDoctorPackage> =
        packages.filter { entry -> entry.expectedVersion != null }
}
