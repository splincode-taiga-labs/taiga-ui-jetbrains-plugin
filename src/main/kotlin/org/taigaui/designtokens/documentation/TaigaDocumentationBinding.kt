package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlAttribute

/** Immutable facts captured under the resolver's background read action. */
internal data class TaigaDocumentationBinding(
    val name: String,
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val valueStart: Int,
    val valueEnd: Int,
    val literal: String?,
    val expression: Boolean,
) {
    fun replacement(value: String): String {
        val quoted = if (expression) "'$value'" else value
        val escaped = quoted.replace("&", "&amp;").replace("\"", "&quot;").replace("'", "&#39;")
        return text.replaceRange(valueStart, valueEnd, escaped)
    }
}

@Suppress("ReturnCount")
internal fun XmlAttribute.documentationBinding(): TaigaDocumentationBinding? {
    val rawName = bindingName()
    if (rawName.startsWith('(') || rawName.startsWith("[(") || rawName.startsWith("on-")) return null
    val match = BINDING_VALUE.matchEntire(text) ?: return null
    val expression = rawName.startsWith('[') || rawName.startsWith("bind-")
    val value = value ?: return null
    val literal = if (expression) STRING_LITERAL.matchEntire(value.trim())?.groupValues?.get(2) else value
    val range = match.groups[2]?.range ?: return null
    return TaigaDocumentationBinding(
        rawName.removeSurrounding("[", "]").removePrefix("bind-"),
        textRange.startOffset,
        textRange.endOffset,
        text,
        range.first,
        range.last + 1,
        literal,
        expression,
    )
}

/** Tracks unrelated edits, but never replaces a binding edited since the card opened. */
internal class TaigaDocumentationBindingEditor(
    private val project: Project,
    private val document: Document,
    private val binding: TaigaDocumentationBinding,
    private val values: List<String>,
) : Disposable {
    private var marker = document.createRangeMarker(binding.startOffset, binding.endOffset)
    private var expected = binding.text
    var currentValue: String? = binding.literal
        private set

    @Suppress("ReturnCount")
    fun apply(value: String): String {
        if (project.isDisposed || value !in values || binding.literal == null) return "Copy this value instead"
        if (!document.isWritable) return "File is read-only; copy this value instead"
        var result = "Binding changed; reopen its card before applying"
        WriteCommandAction.writeCommandAction(project).withName("Change Taiga UI ${binding.name}").run<RuntimeException> {
            if (marker.isValid && document.charsSequence.subSequence(marker.startOffset, marker.endOffset).toString() == expected) {
                val start = marker.startOffset
                val replacement = binding.replacement(value)
                document.replaceString(start, marker.endOffset, replacement)
                marker.dispose()
                marker = document.createRangeMarker(start, start + replacement.length)
                expected = replacement
                currentValue = value
                PsiDocumentManager.getInstance(project).commitDocument(document)
                result = "Applied $value · Undo available"
            }
        }
        return result
    }

    override fun dispose() {
        marker.dispose()
    }
}

private val BINDING_VALUE = Regex("""[^=]+\s*=\s*(["'])([\s\S]*)\1""")
private val STRING_LITERAL = Regex("""(['"])([\w .@/-]*)\1""")
