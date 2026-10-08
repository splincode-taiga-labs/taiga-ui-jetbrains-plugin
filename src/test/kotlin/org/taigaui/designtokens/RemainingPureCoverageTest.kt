package org.taigaui.designtokens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.documentation.hoverPlatformLabel
import org.taigaui.designtokens.documentation.toHoverPackageSections
import org.taigaui.designtokens.events.EventPluginModifier
import org.taigaui.designtokens.icons.IconReferenceAtOffsetFinder
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenDeclarationParser
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.DesignTokenVariant
import org.taigaui.designtokens.packageinfo.YarnPnpManifestReader
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenValueParseResult
import org.taigaui.designtokens.resolution.DesignTokenValueParser
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import org.taigaui.designtokens.resolution.DesignTokenValueResolver
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution
import java.nio.file.Files
import java.nio.file.Path

class RemainingPureCoverageTest {
    @Test
    fun `groups unresolved variants independently`() {
        val root = Path.of("build", "pure-coverage")
        val index =
            DesignTokenIndex.build(
                packageRoot = root,
                declarations =
                    listOf(
                        DesignTokenDeclaration(
                            name = "--tui-root",
                            value = "var(--tui-missing)",
                            sourceFile = root.resolve("palette/base.css"),
                            line = 1,
                        ),
                    ),
            )

        assertEquals(
            2,
            DesignTokenValueResolver(index)
                .resolveGrouped("--tui-root")
                .size,
        )
    }

    @Test
    fun `hover labels fall back for non compact platform and theme sets`() {
        val desktopLight =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.LIGHT,
            )
        val iosLight =
            DesignTokenContext(
                platform = DesignTokenPlatform.IOS,
                theme = DesignTokenTheme.LIGHT,
            )
        val desktopAny =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.UNSPECIFIED,
            )

        assertEquals(
            "🖥️ Desktop · Light ☀️, 📱 iOS · Light ☀️",
            listOf(desktopLight, iosLight).hoverPlatformLabel(),
        )
        assertEquals(
            "🖥️ Desktop · Light ☀️, 🖥️ Desktop · Any theme",
            listOf(desktopLight, desktopAny).hoverPlatformLabel(),
        )
    }

    @Test
    fun `icon finder rejects a non icon character directly`() {
        assertNull(IconReferenceAtOffsetFinder.find("!", 0))
    }

    @Test
    fun `value parser reaches invalid name and unterminated comment diagnostics`() {
        assertTrue(DesignTokenValueParser.parse("var(color)") is DesignTokenValueParseResult.Invalid)
        assertTrue(
            DesignTokenValueParser.parse("var(--tui-value, /*") is DesignTokenValueParseResult.Invalid,
        )
    }

    @Test
    fun `declaration parser accepts value that reaches end of file`() {
        val file = Files.createTempFile("token-eof", ".css")

        try {
            Files.writeString(file, "--tui-last: white")
            val declaration = DesignTokenDeclarationParser().extract(file).single()

            assertEquals("--tui-last", declaration.name)
            assertEquals("white", declaration.value)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `pnp decoder rejects truncated hex escape payloads`() {
        val reader = YarnPnpManifestReader()
        val method =
            YarnPnpManifestReader::class.java
                .getDeclaredMethod("extractInlineRuntimeState", String::class.java)
                .apply { isAccessible = true }

        assertNull(method.invoke(reader, "const RAW_RUNTIME_STATE = '\\xA"))
        assertNull(method.invoke(reader, "const RAW_RUNTIME_STATE = '\\uABC"))
    }

    @Test
    fun `timed event modifier handles throttle and invalid input`() {
        assertEquals("throttle~2s", EventPluginModifier.parse("throttle~2s")?.source)
        assertNull(EventPluginModifier.parse("unknown"))
    }

    @Test
    fun `hover package ordering handles custom package rank`() {
        val context =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.UNSPECIFIED,
            )
        val groups =
            listOf(
                group("@custom/theme", "custom.css", context),
                group("@taiga-ui/core", "core.css", context),
            )
        val sections = groups.toHoverPackageSections("--tui-test")

        assertEquals(
            listOf("@taiga-ui/core", "@custom/theme"),
            sections.map { section -> section.packageName },
        )
    }

    private fun group(
        packageName: String,
        fileName: String,
        context: DesignTokenContext,
    ): DesignTokenResolutionGroup {
        val origin =
            DesignTokenOrigin(
                sourceFile = Path.of(fileName),
                line = 1,
                format = DesignTokenSourceFormat.CSS,
                packageName = packageName,
            )
        val variant =
            DesignTokenVariant(
                name = "--tui-test",
                context = context,
                rawValue = "#fff",
                origins = listOf(origin),
            )

        return DesignTokenResolutionGroup(
            resolutions =
                listOf(
                    DesignTokenVariantResolution(
                        variant = variant,
                        result =
                            DesignTokenValueResolution.Resolved(
                                rawValue = "#fff",
                                value = "#fff",
                            ),
                    ),
                ),
        )
    }
}
