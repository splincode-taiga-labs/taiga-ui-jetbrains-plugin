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
