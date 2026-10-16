package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.SyntaxHighlighterFactory
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator

/** Bounded lexer-only presentation: no PSI, editor instances or source modifications. */
internal fun documentationCodeLabel(
    code: String,
    width: Int = 370,
): JBLabel {
    val html = documentationCodeHtml(code)
    return JBLabel("<html><div width='$width'>$html</div></html>").apply {
        font = taigaDocumentationCodeFont()
        alignmentX = JComponent.LEFT_ALIGNMENT
        getAccessibleContext().accessibleName = code
    }
}

internal fun documentationCodeHtml(code: String): String {
    val highlighter =
        ApplicationManager.getApplication()?.let {
            val language = if (code.trimStart().startsWith('<')) "html" else "ts"
            val fileType = FileTypeManager.getInstance().getFileTypeByExtension(language)
            SyntaxHighlighterFactory.getSyntaxHighlighter(fileType, null, null)
        }
    if (highlighter == null) return StringUtil.escapeXmlEntities(code)
    val scheme = EditorColorsManager.getInstance().globalScheme
    return buildString {
        val lexer = highlighter.highlightingLexer
        lexer.start(code)
        while (lexer.tokenType != null) {
            val text = code.substring(lexer.tokenStart, lexer.tokenEnd)
            val color =
                highlighter
                    .getTokenHighlights(lexer.tokenType!!)
                    .mapNotNull { scheme.getAttributes(it)?.foregroundColor }
                    .lastOrNull()
                    ?: scheme.defaultForeground
            append("<span style='color:")
            append(documentationColor(color))
            append("'>")
            append(
                StringUtil
                    .escapeXmlEntities(text)
                    .replace("\t", "    ")
                    .replace("  ", "&nbsp; ")
                    .replace("\n", "<br>"),
            )
            append("</span>")
            lexer.advance()
        }
    }
}

internal fun documentationCodeBlock(
    code: String,
    width: Int = 340,
): JComponent =
    RoundedRowPanel().apply {
        layout = BorderLayout(JBUI.scale(8), 0)
        border = JBUI.Borders.empty(9, 10)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(documentationCodeLabel(formattedDocumentationExample(code), width), BorderLayout.CENTER)
    }

internal fun documentationTypePresentation(type: String): JComponent =
    if (Regex("""[A-Za-z_$][\w$]*""").matches(type)) {
        JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            isOpaque = false
            add(
                RoundedRowPanel().apply {
                    layout = BorderLayout()
                    border = JBUI.Borders.empty(3, 7)
                    add(
                        JBLabel(type).apply {
                            font = taigaDocumentationCodeFont()
                            foreground = DESIGN_TOKEN_POPUP_LINK_COLOR
                            getAccessibleContext().accessibleName = type
                        },
                    )
                },
            )
        }
    } else {
        documentationCodeLabel(type)
    }

internal fun documentationAcceptedType(
    declared: String,
    accepted: String,
    expandedType: String? = null,
): JComponent =
    JPanel().apply {
        layout = javax.swing.BoxLayout(this, javax.swing.BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(
            JPanel(BorderLayout()).apply {
                isOpaque = false
                alignmentX = JComponent.LEFT_ALIGNMENT
                add(documentationTypePresentation(declared), BorderLayout.CENTER)
            },
        )
        val expanded = expandedType?.takeIf { sameDocumentationType(declared, accepted) } ?: accepted
        if (!sameDocumentationType(declared, expanded)) add(documentationCodeLabel("= $expanded"))
    }

internal fun documentationRow(
    title: String,
    value: JComponent,
): JPanel =
    JPanel(BorderLayout(JBUI.scale(12), 0)).apply {
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.emptyBottom(12)
        add(
            JBLabel(title).apply {
                foreground = CARD_MUTED_FOREGROUND
                font = font.deriveFont(Font.PLAIN)
                preferredSize = Dimension(JBUI.scale(104), preferredSize.height)
            },
            BorderLayout.WEST,
        )
        add(value, BorderLayout.CENTER)
    }

internal fun documentationSeparator(): JSeparator =
    JSeparator().apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        foreground = JBColor(Color(215, 219, 226), Color(65, 69, 77))
        background = foreground
    }

private fun documentationColor(color: Color): String = "#%02x%02x%02x".format(color.red, color.green, color.blue)

internal fun sameDocumentationType(
    type: String,
    other: String?,
): Boolean {
    if (other == null) return false
    val values = finiteStringValues(type)
    return type.trim() == other.trim() ||
        (values.isNotEmpty() && values.toSet() == finiteStringValues(other).toSet())
}
