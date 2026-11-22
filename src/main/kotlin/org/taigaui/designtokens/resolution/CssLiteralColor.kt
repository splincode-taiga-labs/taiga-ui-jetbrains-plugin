package org.taigaui.designtokens.resolution

import java.math.BigDecimal

@ConsistentCopyVisibility
internal data class CssLiteralColor private constructor(
    private val channels: List<BigDecimal>,
) {
    companion object {
        fun parse(text: String): CssLiteralColor? =
            text.trim().takeIf { it.length <= 128 }?.let { literal ->
                if (literal.startsWith('#')) parseHex(literal) else parseRgb(literal)
            }

        private fun parseHex(literal: String): CssLiteralColor? {
            val hex = DesignTokenColorDetector.detect(literal)?.canonicalValue ?: return null
            val channels = hex.drop(1).padEnd(8, 'f').chunked(2)

            return CssLiteralColor(channels.map { it.toInt(16).toBigDecimal().stripTrailingZeros() })
        }

        private fun parseRgb(literal: String): CssLiteralColor? {
            val arguments =
                RGB
                    .matchEntire(literal)
                    ?.groupValues
                    ?.get(1)
                    ?.let(::rgbArguments) ?: return null
            val (rgb, alpha) = arguments
            val opacity = if (alpha == null) BYTE_MAX else parseComponent(alpha, isAlpha = true)
            val channels = rgb.map { component -> parseComponent(component, isAlpha = false) } + opacity

            return channels.filterNotNull().takeIf { it.size == 4 }?.let { values ->
                CssLiteralColor(values.map(BigDecimal::stripTrailingZeros))
            }
        }

        private fun rgbArguments(arguments: String): Pair<List<String>, String?>? {
            val legacy = ',' in arguments
            val parts = arguments.split(if (legacy) ',' else '/').map(String::trim)
            val rgb = if (legacy) parts.take(3) else parts.first().split(WHITESPACE)
            val alphaIndex = if (legacy) 3 else 1
            val validUnits = !legacy || rgb.map { it.endsWith('%') }.distinct().size == 1

            return if (rgb.size == 3 &&
                parts.size <= alphaIndex + 1 &&
                validUnits
            ) {
                rgb to parts.getOrNull(alphaIndex)
            } else {
                null
            }
        }

        private fun parseComponent(
            component: String,
            isAlpha: Boolean,
        ): BigDecimal? {
            val match = NUMBER.matchEntire(component) ?: return null
            val percentage = match.groupValues[2].isNotEmpty()
            val maximum =
                when {
                    percentage -> PERCENT_MAX
                    isAlpha -> BigDecimal.ONE
                    else -> BYTE_MAX
                }

            return match.groupValues[1].toBigDecimalOrNull()?.takeIf { it in BigDecimal.ZERO..maximum }?.let { value ->
                when {
                    percentage -> value * PERCENT_TO_BYTE
                    isAlpha -> value * BYTE_MAX
                    else -> value
                }
            }
        }

        private val RGB = Regex("""rgba?\((.*)\)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
        private val NUMBER = Regex("""([+-]?(?:[0-9]*\.[0-9]+|[0-9]+)(?:[eE][+-]?[0-9]{1,3})?)(%?)""")
        private val WHITESPACE = Regex("""[ \t\n\r\f]+""")
        private val BYTE_MAX = BigDecimal("255")
        private val PERCENT_MAX = BigDecimal("100")
        private val PERCENT_TO_BYTE = BigDecimal("2.55")
    }
}
