package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.resolution.DesignTokenColorFormat
import org.taigaui.designtokens.resolution.DesignTokenColorValue
import org.taigaui.designtokens.resolution.DesignTokenResolutionNode
import org.taigaui.designtokens.resolution.DesignTokenUnresolvedReason
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import java.awt.Color
import java.awt.Point
import java.math.BigDecimal
import java.math.RoundingMode

class DesignTokenHoverPureCoverageTest {
    @Test
    fun `hit tester accepts only same-line points inside reference bounds`() {
        val start = Point(10, 20)
        val end = Point(30, 20)

        assertTrue(DesignTokenReferenceHitTester.contains(start, end, 16, Point(10, 20)))
        assertTrue(DesignTokenReferenceHitTester.contains(start, end, 16, Point(29, 35)))
        assertFalse(DesignTokenReferenceHitTester.contains(start, end, 16, Point(30, 20)))
        assertFalse(DesignTokenReferenceHitTester.contains(start, end, 16, Point(20, 36)))
        assertFalse(
            DesignTokenReferenceHitTester.contains(
                start,
                Point(30, 21),
                16,
                Point(20, 20),
            ),
        )
    }

    @Test
    fun `rem hover values include pixel equivalent only for complete rem values`() {
        assertEquals("1rem, 16px", "1rem".withRemPixels())
        assertEquals("-0rem, 0px", "-0rem".withRemPixels())
        assertEquals(".125REM, 2px", ".125REM".withRemPixels())
        assertEquals(" 1.5rem , 24px", " 1.5rem ".withRemPixels())
        assertEquals("calc(1rem + 1px)", "calc(1rem + 1px)".withRemPixels())
        assertEquals("1px", "1px".withRemPixels())
    }

    @Test
    fun `hover color conversion supports hex and rgb families`() {
        assertEquals("rgba(170, 187, 204, 1)", resolvedColor("#abc", "#abc").rgbaText())
        assertEquals("rgba(170, 187, 204, 0.87)", resolvedColor("#abcd", "#abcd").rgbaText())
        assertEquals("rgba(17, 34, 51, 1)", resolvedColor("#112233", "#112233").rgbaText())
        assertEquals("rgba(17, 34, 51, 0.27)", resolvedColor("#11223344", "#11223344").rgbaText())
        assertEquals(
            "rgba(255, 0, 128, 1)",
            resolvedColor("rgb(100% 0% 50%)", "not-hex").rgbaText(),
        )
        assertEquals(
            "rgba(255, 0, 128, 0.5)",
            resolvedColor("rgb(255 0 128 / 50%)", "not-hex").rgbaText(),
        )
        assertEquals(
            "rgba(255, 0, 128, 0.5)",
            resolvedColor("rgba(255, 0, 128, 0.5)", "not-hex").rgbaText(),
        )
    }

    @Test
    fun `hover color conversion rejects malformed colors and clamps channels`() {
        assertNull(resolvedColor("wat", "wat").toHoverColorOrNull())
        assertNull(resolvedColor("rgb(a b c)", "not-hex").toHoverColorOrNull())
        assertNull(resolvedColor("rgb(1 2)", "not-hex").toHoverColorOrNull())

        assertEquals(
            "rgba(255, 0, 255, 1)",
            resolvedColor("rgb(999 -1 300)", "not-hex").rgbaText(),
        )
        assertEquals(
            "rgba(255, 0, 0, 1)",
            resolvedColor("rgb(120% -10% 0%)", "not-hex").rgbaText(),
        )
        assertEquals(
            "rgba(1, 2, 3, 0)",
            resolvedColor("rgba(1, 2, 3, -2)", "not-hex").rgbaText(),
        )
        assertEquals(
            "rgba(1, 2, 3, 1)",
            resolvedColor("rgba(1, 2, 3, 2)", "not-hex").rgbaText(),
        )
    }

    @Test
    fun `reference value text preserves rgba and decorates colors and rem values`() {
        val rgba = DesignTokenValueResolution.Resolved("raw", "rgba(1, 2, 3, 0.5)")
        val rem = DesignTokenValueResolution.Resolved("raw", "1rem")
        val plain = DesignTokenValueResolution.Resolved("raw", "solid")

        assertEquals(
            "rgba(1, 2, 3, 0.5)",
            rgba.hoverReferenceValueText(Color(1, 2, 3, 128)),
        )
        assertEquals("1rem, 16px", rem.hoverReferenceValueText(null))
        assertEquals(
            "solid, rgba(1, 2, 3, 1)",
            plain.hoverReferenceValueText(Color(1, 2, 3)),
        )
    }

    @Test
    fun `hover value text explains every unresolved reason`() {
        val context = DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT)

        assertEquals(
            "Missing reference: --missing",
            unresolved(
                DesignTokenUnresolvedReason.MissingReference(
                    "--missing",
                    context,
                ),
            ).hoverValueText(),
        )
        assertEquals(
            "Ambiguous reference: --ambiguous (0 candidates)",
            unresolved(
                DesignTokenUnresolvedReason.AmbiguousReference(
                    "--ambiguous",
                    context,
                    emptyList(),
                ),
            ).hoverValueText(),
        )
        assertEquals(
            "Circular reference: --a → --b",
            unresolved(
                DesignTokenUnresolvedReason.CircularReference(
                    listOf(
                        DesignTokenResolutionNode("--a", context, context, "var(--b)"),
                        DesignTokenResolutionNode("--b", context, context, "var(--a)"),
                    ),
                ),
            ).hoverValueText(),
        )
        assertEquals(
            "Invalid expression at offset 3: broken",
            unresolved(
                DesignTokenUnresolvedReason.InvalidExpression(
                    3,
                    "broken",
                ),
            ).hoverValueText(),
        )
    }

    @Test
    fun `platform labels compact compatible context matrices and expand irregular ones`() {
        fun context(
            platform: DesignTokenPlatform,
            theme: DesignTokenTheme,
        ): DesignTokenContext = DesignTokenContext(platform, theme)

        assertEquals(
            "🖥️ Desktop · Light ☀️",
            listOf(
                context(
                    DesignTokenPlatform.DESKTOP,
                    DesignTokenTheme.LIGHT,
                ),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "📱 Mobile · Any theme",
            listOf(
                context(
                    DesignTokenPlatform.MOBILE,
                    DesignTokenTheme.UNSPECIFIED,
                ),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "📱 iOS · Dark 🌚",
            listOf(
                context(
                    DesignTokenPlatform.IOS,
                    DesignTokenTheme.DARK,
                ),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "🤖 Android · Light ☀️",
            listOf(
                context(
                    DesignTokenPlatform.ANDROID,
                    DesignTokenTheme.LIGHT,
                ),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "All platforms · Light ☀️ and dark 🌚",
            listOf(
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.DARK),
                context(DesignTokenPlatform.MOBILE, DesignTokenTheme.LIGHT),
                context(DesignTokenPlatform.MOBILE, DesignTokenTheme.DARK),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "📱 Mobile · Any theme",
            listOf(
                context(
                    DesignTokenPlatform.IOS,
                    DesignTokenTheme.UNSPECIFIED,
                ),
                context(
                    DesignTokenPlatform.ANDROID,
                    DesignTokenTheme.UNSPECIFIED,
                ),
            ).hoverPlatformLabel(),
        )
        assertEquals(
            "📱 iOS · Light ☀️, 🤖 Android · Dark 🌚",
            listOf(
                context(DesignTokenPlatform.IOS, DesignTokenTheme.LIGHT),
                context(DesignTokenPlatform.ANDROID, DesignTokenTheme.DARK),
            ).hoverPlatformLabel(),
        )
    }

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

    private fun DesignTokenValueResolution.Resolved.rgbaText(): String =
        requireNotNull(toHoverColorOrNull()).let { color ->
            "rgba(${color.red}, ${color.green}, ${color.blue}, ${formatAlpha(color.alpha)})"
        }

    private fun formatAlpha(alpha: Int): String =
        if (alpha == 255) {
            "1"
        } else {
            BigDecimal(alpha)
                .divide(BigDecimal(255), 2, RoundingMode.HALF_UP)
                .stripTrailingZeros()
                .toPlainString()
        }

    private fun unresolved(reason: DesignTokenUnresolvedReason): DesignTokenValueResolution.Unresolved =
        DesignTokenValueResolution.Unresolved(
            rawValue = "raw",
            reason = reason,
        )
}
