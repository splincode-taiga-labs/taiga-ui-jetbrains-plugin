package org.taigaui.designtokens.events

import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionResultSet

/**
 * Complete only the modifier inside an existing Angular event attribute.
 * Web Types may insert an entire attribute (including =""), corrupting
 * the handler that follows an already present closing parenthesis.
 */
class HtmlEventPluginCompletionContributor : CompletionContributor() {
    override fun fillCompletionVariants(
        parameters: CompletionParameters,
        result: CompletionResultSet,
    ) {
        val caret = parameters.editor.caretModel.offset
        val text = parameters.editor.document.charsSequence
        val beforeCaret = text.subSequence((caret - MAX_HTML_EVENT_PREFIX).coerceAtLeast(0), caret).toString()
        val eventStart = beforeCaret.lastIndexOf('(')
        val tagStart = beforeCaret.lastIndexOf('<')

        if (eventStart <= tagStart || tagStart < 0 || eventStart == 0) return
        if (tagStart < beforeCaret.lastIndexOf('>')) return
        if (!beforeCaret[eventStart - 1].isWhitespace()) return
        if (beforeCaret.substring(tagStart + 1, eventStart).insideHtmlAttributeValue()) return

        val event = HostEventPluginCompletionContext.parse(beforeCaret.substring(eventStart)) ?: return
        if (event.kind != HostEventPluginCompletionContext.Kind.MODIFIER) return

        result.withPrefixMatcher(event.prefix).addAllElements(event.toLookupElements(null))
        result.stopHere()
    }
}

private fun String.insideHtmlAttributeValue(): Boolean {
    var quote: Char? = null
    for (character in this) {
        when {
            quote == character -> quote = null
            quote == null && (character == '\'' || character == '"') -> quote = character
        }
    }
    return quote != null
}

private const val MAX_HTML_EVENT_PREFIX = 2_048
