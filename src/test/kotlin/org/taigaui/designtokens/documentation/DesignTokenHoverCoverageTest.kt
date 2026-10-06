package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.DesignTokenVariant
import org.taigaui.designtokens.resolution.DesignTokenColorFormat
import org.taigaui.designtokens.resolution.DesignTokenColorValue
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenUnresolvedReason
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution
import java.awt.Color
import java.nio.file.Path

class DesignTokenHoverCoverageTest {
    @Test
    fun `parses css color syntaxes used by hover previews`() {
        assertEquals(
            Color(170, 187, 204, 255),
            resolvedColor("#abc", "#abc").toHoverColorOrNull(),
        )
        assertEquals(
            Color(170, 187, 204, 221),
            resolvedColor("#abcd", "#abcd").toHoverColorOrNull(),
        )
        assertEquals(
            Color(17, 34, 51, 255),
            resolvedColor("#112233", "#112233").toHoverColorOrNull(),
        )
        assertEquals(
            Color(17, 34, 51, 68),
            resolvedColor("#11223344", "#11223344").toHoverColorOrNull(),
        )
        assertEquals(
            Color(255, 0, 128, 64),
            resolvedColor(
                cssText = "rgb(100% 0% 50% / 25%)",
                canonicalValue = "not-a-color",
            ).toHoverColorOrNull(),
        )
        assertEquals(
            Color(255, 0, 128, 255),
            resolvedColor(
                cssText = "rgba(300, -1, 127.6, 2)",
                canonicalValue = "not-a-color",
            ).toHoverColorOrNull(),
        )
        assertNull(
            resolvedColor(
                cssText = "rgb(nope 10 20)",
                canonicalValue = "not-a-color",
            ).toHoverColorOrNull(),
        )
    }

    @Test
    fun `formats resolved hover colors without duplicating rgba values`() {
        val color = Color(10, 20, 30, 128)

        assertEquals(
            "red, rgba(10, 20, 30, 0.5)",
            resolved("red").hoverReferenceValueText(color),
        )
        assertEquals(
            "RGBA(10, 20, 30, 0.5)",
            resolved("RGBA(10, 20, 30, 0.5)").hoverReferenceValueText(color),
        )
    }

    @Test
    fun `display group navigates to latest cascade origin`() {
        val first = origin("first.css", line = 3, cascadeOrder = 1)
        val latest = origin("latest.css", line = 7, cascadeOrder = 2)
        val resolution =
            resolution(
                value = "#fff",
                origins = listOf(first, latest),
            )
        val group =
            listOf(
                DecoratedResolution(
                    resolution = resolution,
                    packageName = "@taiga-ui/design-tokens",
                    overrideMessage = null,
                ),
            ).toDisplayGroups().single()

        val row = group.toHoverValueRow()

        assertEquals(DesignTokenNavigationTarget(latest.sourceFile, latest.line), row.navigationTarget)
        assertEquals("#fff", row.resolvedValue)
    }

    @Test
    fun `display grouping keeps different unresolved reasons separate`() {
        val context = DesignTokenContext.DEFAULT
        val missing =
            DesignTokenValueResolution.Unresolved(
                rawValue = "var(--missing)",
                reason = DesignTokenUnresolvedReason.MissingReference("--missing", context),
            )
        val invalid =
            DesignTokenValueResolution.Unresolved(
                rawValue = "var(--missing)",
                reason = DesignTokenUnresolvedReason.InvalidExpression(0, "invalid"),
            )

        val groups =
            listOf(
                decorated(resolution(result = missing)),
                decorated(resolution(result = invalid)),
            ).toDisplayGroups()

        assertEquals(2, groups.size)
    }

    @Test
    fun `package mapping splits one logical resolution by physical package`() {
        val variantResolution =
            resolution(
                value = "#fff",
                origins =
                    listOf(
                        origin("design-tokens.css", packageName = "@taiga-ui/design-tokens"),
                        origin("core.css", packageName = "@taiga-ui/core"),
                    ),
            )

        val sections =
            listOf(DesignTokenResolutionGroup(listOf(variantResolution)))
                .toHoverPackageSections("--tui-test")

        assertEquals(
            setOf("@taiga-ui/design-tokens", "@taiga-ui/core"),
            sections.map(DesignTokenHoverPackageSection::packageName).toSet(),
        )
    }

    @Test
    fun `package mapping uses default package for origin without package metadata`() {
        val value =
            resolution(
                value = "#fff",
                origins = listOf(origin("tokens.css", packageName = null)),
            )

        assertEquals(DEFAULT_SOURCE_PACKAGE, value.sourcePackageName())
    }

    @Test
    fun `project local override reports generic message without comparable cascade order`() {
        val active =
            resolution(
                origins =
                    listOf(
                        origin(
                            "local.css",
                            packageName = PROJECT_STYLES_PACKAGE,
                            localOverride = true,
                        ),
                    ),
            )
        val overridden =
            resolution(
                origins =
                    listOf(
                        origin(
                            "global.css",
                            packageName = PROJECT_STYLES_PACKAGE,
                        ),
                    ),
            )

        val decorated = listOf(overridden, active).withOverrideState()

        assertEquals(
            "Overridden by another declaration",
            decorated.single { item -> item.resolution == overridden }.overrideMessage,
        )
    }

    @Test
    fun `row and reference comparators keep fallback labels after known platforms`() {
        val rows =
            listOf(
                row("Custom platform"),
                row("All platforms"),
                row("🖥️ Desktop · Any theme"),
                row("📱 Mobile · Any theme"),
                row("📱 iOS · Any theme"),
                row("🤖 Android · Any theme"),
            ).sortedWith(VALUE_ROW_COMPARATOR)

        assertEquals(
            listOf(
                "🖥️ Desktop · Any theme",
                "📱 Mobile · Any theme",
                "📱 iOS · Any theme",
                "🤖 Android · Any theme",
                "All platforms",
                "Custom platform",
            ),
            rows.map(DesignTokenHoverValueRow::platform),
        )

        val active = row("🖥️ Desktop · Any theme")
        val overridden = active.copy(overrideMessage = "Overridden")
        assertSame(active, listOf(overridden, active).sortedWith(VALUE_ROW_COMPARATOR).first())
    }

    private fun resolved(value: String): DesignTokenValueResolution.Resolved =
        DesignTokenValueResolution.Resolved(rawValue = value, value = value)

    private fun resolvedColor(
        cssText: String,
        canonicalValue: String,
    ): DesignTokenValueResolution.Resolved =
        DesignTokenValueResolution.Resolved(
            rawValue = cssText,
            value = cssText,
            color =
                DesignTokenColorValue(
                    cssText = cssText,
                    canonicalValue = canonicalValue,
                    format = DesignTokenColorFormat.FUNCTION,
                ),
        )

    private fun decorated(resolution: DesignTokenVariantResolution): DecoratedResolution =
        DecoratedResolution(
            resolution = resolution,
            packageName = resolution.sourcePackageName(),
            overrideMessage = null,
        )

    private fun resolution(
        value: String = "#fff",
        origins: List<DesignTokenOrigin> = listOf(origin("tokens.css")),
        result: DesignTokenValueResolution = resolved(value),
    ): DesignTokenVariantResolution =
        DesignTokenVariantResolution(
            variant =
                DesignTokenVariant(
                    name = "--tui-test",
                    context = DesignTokenContext.DEFAULT,
                    rawValue = value,
                    origins = origins,
                ),
            result = result,
        )

    private fun origin(
        fileName: String,
        line: Int = 1,
        packageName: String? = "@taiga-ui/design-tokens",
        cascadeOrder: Int? = null,
        localOverride: Boolean = false,
    ): DesignTokenOrigin =
        DesignTokenOrigin(
            sourceFile = Path.of(fileName),
            line = line,
            format = DesignTokenSourceFormat.CSS,
            packageName = packageName,
            cascadeOrder = cascadeOrder,
            localOverride = localOverride,
        )

    private fun row(platform: String): DesignTokenHoverValueRow =
        DesignTokenHoverValueRow(
            platform = platform,
            resolvedValue = "value",
            color = null,
            navigationTarget = null,
        )
}
