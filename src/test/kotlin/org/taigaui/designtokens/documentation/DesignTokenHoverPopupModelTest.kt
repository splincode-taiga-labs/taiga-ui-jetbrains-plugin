package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.DesignTokenVariant
import org.taigaui.designtokens.resolution.DesignTokenColorFormat
import org.taigaui.designtokens.resolution.DesignTokenColorValue
import org.taigaui.designtokens.resolution.DesignTokenReferenceResolution
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenUnresolvedReason
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution
import java.nio.file.Path

class DesignTokenHoverPopupModelTest {
    @Test
    fun `keeps a direct final value once`() {
        val model = DesignTokenHoverPopupModel.create(TOKEN, listOf(group(resolved("#fff", "#fff"))))
        val section = model.sections.single()
        val row = section.rows.single()
        val chain = section.chains.single()

        assertEquals(DESIGN_TOKENS_PACKAGE, section.packageName)
        assertEquals("#fff", row.resolvedValue)
        assertNotNull(row.color)
        assertNotNull(row.navigationTarget)
        assertEquals(
            listOf(TOKEN, "#fff, rgba(255, 255, 255, 1)"),
            chain.lines.map(DesignTokenHoverReferenceLine::text),
        )
        assertEquals(1, model.referenceChainCount)
        assertNull(model.description)
    }

    @Test
    fun `shows referenced final value in its original CSS color notation`() {
        val cssColor = "rgba(0, 0, 0, 0.54)"
        val terminalVariant = variant("--tui-const-black-alpha-54", cssColor, line = 2)
        val terminalResult =
            resolved(
                rawValue = cssColor,
                value = "#0000008A",
                cssText = cssColor,
                canonicalValue = "#0000008A",
            )
        val rootResult =
            DesignTokenValueResolution.Resolved(
                rawValue = "var(--tui-const-black-alpha-54)",
                value = "#0000008A",
                color = terminalResult.color,
                references =
                    listOf(
                        DesignTokenReferenceResolution(
                            name = "--tui-const-black-alpha-54",
                            requestedContext = LIGHT_DESKTOP,
                            selectedVariant = terminalVariant,
                            primaryResult = terminalResult,
                            fallbackRawValue = null,
                            fallbackResult = null,
                            fallbackUsed = false,
                        ),
                    ),
            )
        val model = DesignTokenHoverPopupModel.create(TOKEN, listOf(group(rootResult)))
        val section = model.sections.single()
        val row = section.rows.single()
        val chain = section.chains.single()

        assertEquals("🖥️ Desktop · Light ☀️", row.platform)
        assertEquals(cssColor, row.resolvedValue)
        assertNotNull(row.color)
        assertEquals(
            listOf(TOKEN, "--tui-const-black-alpha-54", cssColor),
            chain.lines.map(DesignTokenHoverReferenceLine::text),
        )
    }

    @Test
    fun `creates a swatch for rgba terminal values`() {
        val rgba = "rgba(255, 255, 255, 0.72)"
        val row =
            DesignTokenHoverPopupModel
                .create(TOKEN, listOf(group(resolved(rgba, rgba, rgba, rgba))))
                .sections
                .single()
                .rows
                .single()
        val color = requireNotNull(row.color)

        assertEquals(255, color.red)
        assertEquals(255, color.green)
        assertEquals(255, color.blue)
        assertEquals(184, color.alpha)
    }

    @Test
    fun `groups values and chains by source package`() {
        val designTokensGroup =
            group(
                result = resolved("#fff", "#fff"),
                packageName = DESIGN_TOKENS_PACKAGE,
            )
        val coreResult =
            resolved("rgba(0, 0, 0, 0.65)", "rgba(0, 0, 0, 0.65)")
        val coreGroup =
            group(
                result = coreResult,
                packageName = CORE_PACKAGE,
            )
        val model = DesignTokenHoverPopupModel.create(TOKEN, listOf(coreGroup, designTokensGroup))
        val packageNames = model.sections.map { section -> section.packageName }
        val designTokensValue =
            model.sections[0]
                .rows
                .single()
                .resolvedValue
        val coreValue =
            model.sections[1]
                .rows
                .single()
                .resolvedValue

        assertEquals(listOf(DESIGN_TOKENS_PACKAGE, CORE_PACKAGE), packageNames)
        assertEquals("#fff", designTokensValue)
        assertEquals("rgba(0, 0, 0, 0.65)", coreValue)
        assertEquals(2, model.referenceChainCount)
    }

    @Test
    fun `orders styles package between design tokens and core`() {
        val model =
            DesignTokenHoverPopupModel.create(
                TOKEN,
                listOf(
                    group(resolved("#333", "#333"), packageName = CORE_PACKAGE),
                    group(resolved("#222", "#222"), packageName = STYLES_PACKAGE),
                    group(resolved("#111", "#111"), packageName = DESIGN_TOKENS_PACKAGE),
                ),
            )

        assertEquals(
            listOf(DESIGN_TOKENS_PACKAGE, STYLES_PACKAGE, CORE_PACKAGE),
            model.sections.map { section -> section.packageName },
        )
    }

    @Test
    fun `collapses a complete equivalent context set`() {
        val model =
            DesignTokenHoverPopupModel.create(
                TOKEN,
                listOf(group(resolved("#fff", "#fff"), ALL_CONTEXTS)),
            )
        val platform =
            model.sections
                .single()
                .rows
                .single()
                .platform

        assertEquals("All platforms · Light ☀️ and dark 🌚", platform)
    }

    @Test
    fun `keeps incomplete context combinations explicit`() {
        val model =
            DesignTokenHoverPopupModel.create(
                TOKEN,
                listOf(group(resolved("#fff", "#fff"), listOf(LIGHT_DESKTOP, DARK_MOBILE))),
            )
        val platform =
            model.sections
                .single()
                .rows
                .single()
                .platform

        assertEquals("🖥️ Desktop · Light ☀️, 📱 Mobile · Dark 🌚", platform)
    }

    @Test
    fun `shows unresolved reasons as the final value`() {
        val result =
            DesignTokenValueResolution.Unresolved(
                rawValue = "var(--tui-missing)",
                reason =
                    DesignTokenUnresolvedReason.MissingReference(
                        name = "--tui-missing",
                        requestedContext = LIGHT_DESKTOP,
                    ),
            )
        val row =
            DesignTokenHoverPopupModel
                .create(TOKEN, listOf(group(result)))
                .sections
                .single()
                .rows
                .single()

        assertEquals("Missing reference: --tui-missing", row.resolvedValue)
    }

    private fun group(
        result: DesignTokenValueResolution,
        contexts: List<DesignTokenContext> = listOf(LIGHT_DESKTOP),
        packageName: String = DESIGN_TOKENS_PACKAGE,
    ): DesignTokenResolutionGroup =
        DesignTokenResolutionGroup(
            contexts.map { context ->
                DesignTokenVariantResolution(
                    variant =
                        variant(
                            name = TOKEN,
                            rawValue = result.rawValue,
                            context = context,
                            packageName = packageName,
                        ),
                    result = result,
                )
            },
        )

    private fun resolved(
        rawValue: String,
        value: String,
        cssText: String = value,
        canonicalValue: String = value.normalizeTestColor(),
    ): DesignTokenValueResolution.Resolved =
        DesignTokenValueResolution.Resolved(
            rawValue = rawValue,
            value = value,
            color =
                DesignTokenColorValue(
                    cssText = cssText,
                    canonicalValue = canonicalValue,
                    format =
                        if (cssText.startsWith("rgb")) {
                            DesignTokenColorFormat.FUNCTION
                        } else {
                            DesignTokenColorFormat.HEX
                        },
                ),
        )

    private fun String.normalizeTestColor(): String =
        when (lowercase()) {
            "#fff" -> "#ffffff"
            else -> this
        }

    private fun variant(
        name: String,
        rawValue: String,
        line: Int = 1,
        context: DesignTokenContext = LIGHT_DESKTOP,
        packageName: String = DESIGN_TOKENS_PACKAGE,
    ): DesignTokenVariant =
        DesignTokenVariant(
            name = name,
            context = context,
            rawValue = rawValue,
            origins =
                listOf(
                    DesignTokenOrigin(
                        sourceFile = Path.of("palette/light.css"),
                        line = line,
                        format = DesignTokenSourceFormat.CSS,
                        selectorChain = listOf(":root", "[tuiTheme='light']"),
                        packageName = packageName,
                        packageVersion = "1.0.0",
                    ),
                ),
        )

    private companion object {
        const val TOKEN = "--tui-text-secondary"
        const val DESIGN_TOKENS_PACKAGE = "@taiga-ui/design-tokens"
        const val STYLES_PACKAGE = "@taiga-ui/styles"
        const val CORE_PACKAGE = "@taiga-ui/core"
        val LIGHT_DESKTOP = DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT)
        val DARK_DESKTOP = DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.DARK)
        val LIGHT_MOBILE = DesignTokenContext(DesignTokenPlatform.MOBILE, DesignTokenTheme.LIGHT)
        val DARK_MOBILE = DesignTokenContext(DesignTokenPlatform.MOBILE, DesignTokenTheme.DARK)
        val ALL_CONTEXTS = listOf(LIGHT_DESKTOP, DARK_DESKTOP, LIGHT_MOBILE, DARK_MOBILE)
    }
}
