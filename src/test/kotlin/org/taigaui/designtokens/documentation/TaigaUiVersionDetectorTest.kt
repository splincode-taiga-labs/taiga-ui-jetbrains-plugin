package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TaigaUiVersionDetectorTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val detector = TaigaUiVersionDetector()

    @Test
    fun prefersInstalledCorePackageAsTaigaMajorSource() {
        val workspace = temporaryFolder.newFolder("workspace").toPath()
        createPackage(workspace, "design-tokens", "0.322.0")
        createPackage(workspace, "core", "5.18.0")
        createPackage(workspace, "kit", "5.18.0")
        val sourceFile = createSource(workspace)

        val context = requireNotNull(detector.detect(sourceFile))

        assertEquals(5, context.majorVersion)
        assertEquals("5.18.0", context.version)
        assertEquals("@taiga-ui/core", context.versionSourcePackage)
        assertEquals(
            setOf("@taiga-ui/core", "@taiga-ui/design-tokens", "@taiga-ui/kit"),
            context.installedPackages,
        )
    }

    @Test
    fun usesNearestTaigaPackageScopeInMonorepo() {
        val workspace = temporaryFolder.newFolder("monorepo").toPath()
        createPackage(workspace, "core", "4.60.0")
        val app = Files.createDirectories(workspace.resolve("apps/admin"))
        createPackage(app, "core", "5.18.0")
        val sourceFile = createSource(app)

        assertEquals(5, requireNotNull(detector.detect(sourceFile)).majorVersion)
    }

    @Test
    fun ignoresIndependentDesignTokensVersionWhenCoreIsMissing() {
        val workspace = temporaryFolder.newFolder("without-core").toPath()
        createPackage(workspace, "design-tokens", "0.322.0")
        createPackage(workspace, "kit", "5.18.0")
        val sourceFile = createSource(workspace)

        val context = requireNotNull(detector.detect(sourceFile))

        assertEquals(5, context.majorVersion)
        assertEquals("@taiga-ui/kit", context.versionSourcePackage)
    }

    @Test
    fun skipsPackageWithInvalidVersionAndUsesNextCandidate() {
        val workspace = temporaryFolder.newFolder("invalid-core-version").toPath()
        createPackage(workspace, "core", "workspace:*")
        createPackage(workspace, "kit", "5.18.0")
        createPackage(workspace, "proprietary", "5.18.0")
        val sourceFile = createSource(workspace)

        val context = requireNotNull(detector.detect(sourceFile))

        assertEquals(5, context.majorVersion)
        assertEquals("5.18.0", context.version)
        assertEquals("@taiga-ui/kit", context.versionSourcePackage)
        assertEquals(
            setOf("@taiga-ui/core", "@taiga-ui/kit", "@taiga-ui/proprietary"),
            context.installedPackages,
        )
    }

    @Test
    fun parsesMajorFromPrereleaseAndMajorOnlyVersions() {
        listOf(
            "5.0.0-next.1" to 5,
            "4" to 4,
        ).forEachIndexed { index, (version, expectedMajor) ->
            val workspace = temporaryFolder.newFolder("version-$index").toPath()
            createPackage(workspace, "core", version)

            val context = requireNotNull(detector.detect(createSource(workspace)))

            assertEquals(expectedMajor, context.majorVersion)
            assertEquals(version, context.version)
        }
    }

    @Test
    fun returnsNullWhenTaigaUiPackagesAreMissing() {
        val workspace = temporaryFolder.newFolder("missing").toPath()
        val sourceFile = createSource(workspace)

        assertNull(detector.detect(sourceFile))
    }

    private fun createPackage(
        workspace: Path,
        name: String,
        version: String,
    ) {
        val root = workspace.resolve("node_modules/@taiga-ui/$name")

        Files.createDirectories(root)
        Files.writeString(
            root.resolve("package.json"),
            "{\"name\":\"@taiga-ui/$name\",\"version\":\"$version\"}",
        )
    }

    private fun createSource(workspace: Path): Path {
        val source = workspace.resolve("src/app.ts")

        Files.createDirectories(source.parent)
        Files.writeString(source, "export const app = true;")

        return source
    }
}
