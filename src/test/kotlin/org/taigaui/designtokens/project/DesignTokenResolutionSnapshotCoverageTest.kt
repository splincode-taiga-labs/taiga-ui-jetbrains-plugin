package org.taigaui.designtokens.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.DesignTokenVariant
import org.taigaui.designtokens.resolution.DesignTokenReferenceResolution
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenUnresolvedReason
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution
import java.nio.file.Path

class DesignTokenResolutionSnapshotCoverageTest {
    @Test
    fun `normalizes nullable context roots and distinct entry files`() {
        val entry = Path.of("src/../src/styles.scss")
        val key =
            TokenContextKey(
                workspaceRoot = null,
                projectRoot = Path.of("project/.."),
                packageRoot = null,
                projectEntryFiles = listOf(entry, entry),
            ).normalized()

        assertNull(key.workspaceRoot)
        assertNull(key.packageRoot)
        assertTrue(requireNotNull(key.projectRoot).isAbsolute)
        assertEquals(1, key.projectEntryFiles.size)
        assertTrue(key.projectEntryFiles.single().isAbsolute)
    }

    @Test
    fun `empty snapshot has no merged index resolver or catalog`() {
        val snapshot =
            DesignTokenResolutionSnapshot.build(
                DesignTokenResolutionSnapshotInputs(
                    installedIndex = null,
                    projectIndex = null,
                ),
            )

        assertNull(snapshot.installedIndex)
        assertNull(snapshot.projectIndex)
        assertNull(snapshot.mergedIndex)
        assertNull(snapshot.resolver)
        assertTrue(snapshot.tokenCatalog.isEmpty())
        assertTrue(snapshot.tokenNames.isEmpty())
        assertNull(snapshot.deprecationFor("--tui-missing"))
    }

    @Test
    fun `catalog labels fall back when package metadata is missing`() {
        val installed =
            index(
                declaration(
                    name = "--tui-installed",
                    packageName = null,
                ),
            )
        val catalog =
            index(
                declaration(
                    name = "--tui-installed",
                    packageName = null,
                ),
                declaration(
                    name = "--tui-catalog-only",
                    packageName = null,
                ),
            )
        val snapshot =
            DesignTokenResolutionSnapshot.build(
                DesignTokenResolutionSnapshotInputs(
                    installedIndex = installed,
                    projectIndex = null,
                    nameCatalogIndex = catalog,
                ),
            )
        val installedEntry =
            snapshot.tokenCatalog.single { entry -> entry.name == "--tui-installed" }
        val catalogEntry =
            snapshot.tokenCatalog.single { entry -> entry.name == "--tui-catalog-only" }

        assertEquals("Taiga UI", installedEntry.sourceLabel)
        assertTrue(installedEntry.effective)
        assertEquals("catalog", catalogEntry.sourceLabel)
        assertFalse(catalogEntry.effective)
    }

    @Test
    fun `snapshot matching allows absent candidate catalog but rejects different explicit catalog`() {
        val installed = index(declaration("--tui-a", "@taiga-ui/core"))
        val otherCatalog = index(declaration("--tui-b", "@taiga-ui/core"))
        val inputs =
            DesignTokenResolutionSnapshotInputs(
                installedIndex = installed,
                projectIndex = null,
                nameCatalogIndex = installed,
            )
        val snapshot = DesignTokenResolutionSnapshot.build(inputs)

        assertTrue(
            snapshot.matches(
                DesignTokenResolutionSnapshotInputs(
                    installedIndex = installed,
                    projectIndex = null,
                ),
            ),
        )
        assertFalse(
            snapshot.matches(
                DesignTokenResolutionSnapshotInputs(
                    installedIndex = installed,
                    projectIndex = null,
                    nameCatalogIndex = otherCatalog,
                ),
            ),
        )
    }

    @Test
    fun `snapshot cache clear removes all entries`() {
        val cache = DesignTokenResolutionSnapshotCache()
        val index = index(declaration("--tui-a", "@taiga-ui/core"))
        val key =
            TokenContextKey(
                workspaceRoot = Path.of("."),
                projectRoot = Path.of("."),
                packageRoot = Path.of("."),
                projectEntryFiles = emptyList(),
            )

        cache.getOrBuild(
            key,
            DesignTokenResolutionSnapshotInputs(
                installedIndex = index,
                projectIndex = null,
            ),
        )

        assertEquals(1, cache.size)

        cache.clear()

        assertEquals(0, cache.size)
    }

    @Test
    fun `resolution group rejects empty input and exposes effective fallback and nested origins`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesignTokenResolutionGroup(emptyList())
        }

        val rootOrigin = origin("root.css", 1)
        val selectedOrigin = origin("selected.css", 2)
        val fallbackOrigin = origin("fallback.css", 3)
        val context =
            DesignTokenContext(
                DesignTokenPlatform.DESKTOP,
                DesignTokenTheme.LIGHT,
            )
        val selectedVariant =
            variant(
                "--tui-selected",
                "red",
                selectedOrigin,
                context,
            )
        val fallbackVariant =
            variant(
                "--tui-fallback",
                "blue",
                fallbackOrigin,
                context,
            )
        val fallbackLeaf =
            DesignTokenValueResolution.Resolved(
                rawValue = "blue",
                value = "blue",
                references =
                    listOf(
                        DesignTokenReferenceResolution(
                            name = "--tui-fallback",
                            requestedContext = context,
                            selectedVariant = fallbackVariant,
                            primaryResult =
                                DesignTokenValueResolution.Resolved(
                                    rawValue = "blue",
                                    value = "blue",
                                ),
                            fallbackRawValue = null,
                            fallbackResult = null,
                            fallbackUsed = false,
                        ),
                    ),
            )
        val primary =
            DesignTokenValueResolution.Unresolved(
                rawValue = "var(--missing)",
                reason =
                    DesignTokenUnresolvedReason.MissingReference(
                        "--missing",
                        context,
                    ),
            )
        val reference =
            DesignTokenReferenceResolution(
                name = "--tui-selected",
                requestedContext = context,
                selectedVariant = selectedVariant,
                primaryResult = primary,
                fallbackRawValue = "blue",
                fallbackResult = fallbackLeaf,
                fallbackUsed = true,
            )

        assertSame(fallbackLeaf, reference.effectiveResult)

        val rootVariant =
            variant(
                "--tui-root",
                "var(--tui-selected, blue)",
                rootOrigin,
                context,
            )
        val result =
            DesignTokenValueResolution.Resolved(
                rawValue = rootVariant.rawValue,
                value = "blue",
                references = listOf(reference),
            )
        val group =
            DesignTokenResolutionGroup(
                listOf(
                    DesignTokenVariantResolution(
                        variant = rootVariant,
                        result = result,
                    ),
                ),
            )

        assertSame(result, group.representative)
        assertEquals(listOf(rootOrigin), group.origins)
        assertEquals(
            setOf(rootOrigin, selectedOrigin, fallbackOrigin),
            group.allOrigins.toSet(),
        )
    }

    private fun index(vararg declarations: DesignTokenDeclaration): DesignTokenIndex =
        DesignTokenIndex.build(
            packageRoot = Path.of("build/fixtures/snapshot-coverage"),
            declarations = declarations.toList(),
        )

    private fun declaration(
        name: String,
        packageName: String?,
    ): DesignTokenDeclaration =
        DesignTokenDeclaration(
            name = name,
            value = "red",
            sourceFile = Path.of("build/fixtures/snapshot-coverage/$name.css"),
            line = 1,
            packageName = packageName,
        )

    private fun origin(
        file: String,
        line: Int,
    ): DesignTokenOrigin =
        DesignTokenOrigin(
            sourceFile = Path.of(file),
            line = line,
            format = DesignTokenSourceFormat.CSS,
        )

    private fun variant(
        name: String,
        value: String,
        origin: DesignTokenOrigin,
        context: DesignTokenContext,
    ): DesignTokenVariant =
        DesignTokenVariant(
            name = name,
            context = context,
            rawValue = value,
            origins = listOf(origin),
        )
}
