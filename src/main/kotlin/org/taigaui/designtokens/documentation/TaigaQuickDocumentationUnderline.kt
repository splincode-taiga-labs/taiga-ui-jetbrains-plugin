package org.taigaui.designtokens.documentation

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.colors.EditorColors
import com.intellij.openapi.editor.markup.EffectType
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import java.awt.Font

internal class TaigaQuickDocumentationUnderline {
    private var current: HoverUnderline? = null

    fun show(
        editor: Editor,
        startOffset: Int,
        endOffset: Int,
    ) {
        if (current.matches(editor, startOffset, endOffset)) {
            return
        }

        clear()

        val effectColor =
            editor.colorsScheme
                .getAttributes(EditorColors.REFERENCE_HYPERLINK_COLOR)
                ?.foregroundColor
                ?: editor.colorsScheme.defaultForeground
        val attributes =
            TextAttributes(
                null,
                null,
                effectColor,
                EffectType.LINE_UNDERSCORE,
                Font.PLAIN,
            )
        val highlighter =
            editor.markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.HYPERLINK,
                attributes,
                HighlighterTargetArea.EXACT_RANGE,
            )

        current = HoverUnderline(editor, highlighter, startOffset, endOffset)
    }

    fun clear() {
        val underline = current

        current = null

        underline
            ?.takeUnless { value -> value.editor.isDisposed }
            ?.let { value -> value.editor.markupModel.removeHighlighter(value.highlighter) }
    }

    private fun HoverUnderline?.matches(
        editor: Editor,
        startOffset: Int,
        endOffset: Int,
    ): Boolean =
        this?.editor === editor &&
            this.startOffset == startOffset &&
            this.endOffset == endOffset
}

private data class HoverUnderline(
    val editor: Editor,
    val highlighter: RangeHighlighter,
    val startOffset: Int,
    val endOffset: Int,
)
