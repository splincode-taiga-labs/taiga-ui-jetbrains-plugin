package org.taigaui.designtokens.psi

internal object GlobalDesignTokenContext {
    fun isGlobal(selectorChain: List<String>): Boolean =
        selectorChain.isNotEmpty() &&
            selectorChain.all { selectorList ->
                selectorList
                    .split(',')
                    .map(String::trim)
                    .filter(String::isNotEmpty)
                    .let { selectors ->
                        selectors.isNotEmpty() && selectors.all(::isGlobalSelector)
                    }
            }

    private fun isGlobalSelector(selector: String): Boolean {
        val compact = selector.replace(WHITESPACE, "")

        if (THEME_MIXIN.matches(compact) || SHARED_VARIABLES_MIXIN.matches(compact)) {
            return true
        }

        val withoutGlobalParts =
            GLOBAL_SELECTOR_PART
                .replace(compact, "")
                .replace("&", "")

        return withoutGlobalParts.isEmpty() && GLOBAL_SELECTOR_PART.containsMatchIn(compact)
    }

    private const val ROOT_SELECTOR_PART = """:root|:host|\bhtml\b|\bbody\b"""
    private const val PLATFORM_SELECTOR_PART =
        """\[(?:tuiPlatform|data-platform)=(?:['"]?(?:ios|android)['"]?)\]"""
    private const val THEME_SELECTOR_PART =
        """\[tuiTheme=(?:['"]?(?:light|dark)['"]?)\]"""

    private val GLOBAL_SELECTOR_PART =
        Regex(
            pattern =
                "(?:$ROOT_SELECTOR_PART|$PLATFORM_SELECTOR_PART|$THEME_SELECTOR_PART)",
            option = RegexOption.IGNORE_CASE,
        )
    private val THEME_MIXIN =
        Regex(
            """\.(?:tui-theme-)?(?:light|dark)\(\)""",
            RegexOption.IGNORE_CASE,
        )
    private val SHARED_VARIABLES_MIXIN =
        Regex(
            """\.tui-theme-variables\(\)""",
            RegexOption.IGNORE_CASE,
        )
    private val WHITESPACE = Regex("""\s+""")
}
