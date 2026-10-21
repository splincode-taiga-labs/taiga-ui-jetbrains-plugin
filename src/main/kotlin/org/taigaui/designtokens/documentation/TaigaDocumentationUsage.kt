package org.taigaui.designtokens.documentation

/** Pick the selector applicable to the actual host tag, never render a CSS selector list as HTML. */
internal fun documentationMemberUsage(
    tagName: String,
    ownerSelector: String?,
    memberName: String,
    kind: TaigaApiMemberKind,
): String {
    val directiveAttribute =
        ownerSelector
            ?.split(',')
            ?.asSequence()
            ?.map(String::trim)
            ?.firstNotNullOfOrNull { selector ->
                when {
                    selector == tagName -> ""
                    selector.startsWith("$tagName[") && selector.endsWith(']') ->
                        selector.removePrefix("$tagName[").removeSuffix("]").takeIf(USAGE_ATTRIBUTE::matches)
                    USAGE_DIRECTIVE_ATTRIBUTE.matches(selector) -> selector
                    else -> null
                }
            }?.takeIf(String::isNotEmpty)
            ?.let { " $it" }
            .orEmpty()
    val binding =
        when (kind) {
            TaigaApiMemberKind.INPUT -> "[$memberName]=\"value\""
            TaigaApiMemberKind.OUTPUT -> "($memberName)=\"handler(\$event)\""
        }

    return "<$tagName$directiveAttribute $binding>...</$tagName>"
}

private val USAGE_ATTRIBUTE = Regex("""[A-Za-z_$][\w$-]*""")
private val USAGE_DIRECTIVE_ATTRIBUTE = Regex("""tui[A-Z][A-Za-z0-9]*""")
