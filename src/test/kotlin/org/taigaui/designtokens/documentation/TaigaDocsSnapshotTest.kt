package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaigaDocsSnapshotTest {
    @Test
    fun filtersDocumentationForPackagesNotInstalledInProject() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val core = entity(source, "components/button", "@taiga-ui/core", "TuiButton")
        val kit = entity(source, "components/badge", "@taiga-ui/kit", "TuiBadge")
        val future = entity(source, "components/keypad", "@taiga-ui/core", "TuiKeypad", version = "5.19.0")
        val snapshot =
            TaigaDocsSnapshot(
                projectContext =
                    TaigaUiProjectContext(
                        version = "5.18.0",
                        majorVersion = 5,
                        versionSourcePackage = "@taiga-ui/core",
                        installedPackages = setOf("@taiga-ui/core"),
                        packageScopeIdentity = "fixture",
                    ),
                index = TaigaDocsIndex(source, listOf(core, kit, future)),
            )

        assertEquals(listOf(core), snapshot.entities)
        assertEquals(listOf(core), snapshot.findByPublicSymbol("TuiButton"))
        assertEquals(emptyList<TaigaEntityDoc>(), snapshot.findByPublicSymbol("TuiBadge"))
        assertEquals(emptyList<TaigaEntityDoc>(), snapshot.findByPublicSymbol("TuiKeypad"))
        assertNull(snapshot.findBySectionId("components/badge"))
        assertNull(snapshot.findBySectionId("components/keypad"))
    }

    @Test
    fun `keeps package agnostic docs and handles partial or unknown versions`() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val packageAgnostic =
            entity(
                source = source,
                sectionId = "components/common",
                packageName = null,
                symbol = "TuiCommon",
                version = null,
                selector = "[tuiCommon]",
            )
        val partialVersion =
            entity(
                source = source,
                sectionId = "components/partial",
                packageName = "@taiga-ui/core",
                symbol = "TuiPartial",
                version = "5.18",
                selector = "[tuiPartial]",
            )
        val futurePatch =
            entity(
                source = source,
                sectionId = "components/future",
                packageName = "@taiga-ui/core",
                symbol = "TuiFuture",
                version = "5.18.1",
                selector = "[tuiFuture]",
            )
        val unknownVersion =
            entity(
                source = source,
                sectionId = "components/unknown",
                packageName = "@taiga-ui/core",
                symbol = "TuiUnknown",
                version = "next",
                selector = "[tuiUnknown]",
            )
        val snapshot =
            TaigaDocsSnapshot(
                projectContext =
                    TaigaUiProjectContext(
                        version = "5.18",
                        majorVersion = 5,
                        versionSourcePackage = "@taiga-ui/core",
                        installedPackages = setOf("@taiga-ui/core"),
                        packageScopeIdentity = "fixture",
                    ),
                index =
                    TaigaDocsIndex(
                        source,
                        listOf(packageAgnostic, partialVersion, futurePatch, unknownVersion),
                    ),
            )

        assertEquals(
            listOf(packageAgnostic, partialVersion, unknownVersion),
            snapshot.entities,
        )
        assertEquals(listOf(partialVersion), snapshot.findBySelector("[tuiPartial]"))
        assertEquals(packageAgnostic, snapshot.findBySectionId("components/common"))
        assertNull(snapshot.findBySectionId("components/future"))
    }

    @Test
    fun `keeps versioned docs when installed version is not numeric`() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val future =
            entity(
                source = source,
                sectionId = "components/future",
                packageName = "@taiga-ui/core",
                symbol = "TuiFuture",
                version = "99.1.2",
            )
        val snapshot =
            TaigaDocsSnapshot(
                projectContext =
                    TaigaUiProjectContext(
                        version = "workspace:*",
                        majorVersion = 5,
                        versionSourcePackage = "@taiga-ui/core",
                        installedPackages = setOf("@taiga-ui/core"),
                        packageScopeIdentity = "fixture",
                    ),
                index = TaigaDocsIndex(source, listOf(future)),
            )

        assertEquals(listOf(future), snapshot.entities)
    }

    private fun entity(
        source: TaigaDocsSource,
        sectionId: String,
        packageName: String?,
        symbol: String,
        version: String? = "5.0.0",
        selector: String? = null,
    ): TaigaEntityDoc =
        TaigaEntityDoc(
            sectionId = sectionId,
            title = symbol.removePrefix("Tui"),
            packageNames = packageName?.let { setOf(it) }.orEmpty(),
            kind = TaigaDocKind.COMPONENT,
            version = version,
            description = null,
            publicSymbols = setOf(symbol),
            selectors = selector?.let { setOf(it) }.orEmpty(),
            inputs = emptyList(),
            outputs = emptyList(),
            example = null,
            documentationUri = source.documentationUri(sectionId),
        )
}
