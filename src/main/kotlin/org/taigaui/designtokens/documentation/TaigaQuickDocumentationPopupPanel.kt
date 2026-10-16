package org.taigaui.designtokens.documentation

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

internal class TaigaQuickDocumentationPopupPanel(
    private val resolved: TaigaResolvedDocumentation,
    onClose: () -> Unit,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(10, 14)
        isOpaque = true

        val content = verticalPanel()

        content.add(createHeader(onClose))
        createMetaLabel()?.let { meta ->
            content.add(Box.createVerticalStrut(JBUI.scale(3)))
            content.add(meta)
        }

        resolved.description?.takeIf(String::isNotBlank)?.let { description ->
            content.add(Box.createVerticalStrut(JBUI.scale(8)))
            content.add(wrappedLabel(description))
        }

        content.add(Box.createVerticalStrut(JBUI.scale(8)))

        when (resolved) {
            is TaigaResolvedDocumentation.Entity -> content.add(createEntityTabs(resolved))
            is TaigaResolvedDocumentation.Member -> {
                content.add(quickDocsSeparator())
                content.add(Box.createVerticalStrut(JBUI.scale(8)))
                content.add(createMemberContent(resolved))
            }
        }

        add(content, BorderLayout.CENTER)

        val width = JBUI.scale(POPUP_WIDTH)
        val naturalHeight = super.getPreferredSize().height

        preferredSize = Dimension(width, naturalHeight)
        minimumSize = preferredSize
        maximumSize = Dimension(width, Int.MAX_VALUE)
    }

    private fun createHeader(onClose: () -> Unit): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT

            add(
                JBLabel(resolved.presentationName).apply {
                    font = font.deriveFont(font.style or Font.BOLD)
                },
            )
            add(Box.createHorizontalStrut(JBUI.scale(7)))
            add(quickDocsBadge(resolved.badge))
            add(Box.createHorizontalGlue())
            add(
                LinkLabel<Any>("Documentation ↗", null) { _, _ ->
                    BrowserUtil.browse(resolved.documentationUri.toString())
                    onClose()
                },
            )
        }

    private fun createMetaLabel(): JComponent? {
        val meta =
            listOfNotNull(
                resolved.ownerName?.let { owner -> "of $owner" },
                resolved.packageName,
            ).joinToString("  ·  ")

        return meta
            .takeIf(String::isNotBlank)
            ?.let { value ->
                JBLabel(value).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                }
            }
    }

    private fun createEntityTabs(entityDocs: TaigaResolvedDocumentation.Entity): JComponent {
        val overview =
            verticalPanel().apply {
                entityDocs.typeText?.takeIf(String::isNotBlank)?.let { type ->
                    addDetail("Type", "<code>${type.html()}</code>")
                }

                entityDocs.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
                    addCodeSection(
                        title = "Usage",
                        code = usage,
                        copyTooltip = "Copy usage",
                        language = PopupCodeLanguage.HTML,
                    )
                }

                entityDocs.canonicalImport()?.let { statement ->
                    addCodeSection(
                        title = "Import",
                        code = statement,
                        copyTooltip = "Copy import",
                        language = PopupCodeLanguage.TYPESCRIPT,
                    )
                }
            }

        val hasApi = entityDocs.entity.inputs.isNotEmpty() || entityDocs.entity.outputs.isNotEmpty()

        if (!hasApi) {
            return overview
        }

        val api =
            verticalPanel().apply {
                addApiSection("Inputs", entityDocs.entity.inputs)
                addApiSection("Outputs", entityDocs.entity.outputs)
            }

        return JTabbedPane().apply {
            isOpaque = false
            border = JBUI.Borders.empty()
            alignmentX = LEFT_ALIGNMENT
            addTab("Overview", overview)
            addTab("API", api)
        }
    }

    private fun createMemberContent(memberDocs: TaigaResolvedDocumentation.Member): JComponent =
        verticalPanel().apply {
            memberDocs.typeText?.takeIf(String::isNotBlank)?.let { type ->
                addDetail("Type", "<code>${type.html()}</code>")
            }

            memberDocs.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
                addCodeSection(
                    title = "Usage",
                    code = usage,
                    copyTooltip = "Copy usage",
                    language = PopupCodeLanguage.HTML,
                )
            }

            memberDocs.possibleValues().takeIf(List<String>::isNotEmpty)?.let { values ->
                addDetail(
                    "Possible values",
                    values.joinToString("&nbsp;&nbsp;") { value -> "<code>${value.html()}</code>" },
                )
            }

            memberDocs.relatedMembers().takeIf(List<String>::isNotEmpty)?.let { related ->
                addDetail(
                    "See also",
                    related.joinToString("&nbsp;&nbsp;") { value -> "<code>${value.html()}</code>" },
                )
            }
        }

    private fun JPanel.addApiSection(
        title: String,
        properties: List<TaigaApiProperty>,
    ) {
        if (properties.isEmpty()) {
            return
        }

        addSectionTitle("$title · ${properties.size}")

        val visible = properties.take(MAX_VISIBLE_API_PROPERTIES)

        visible.forEach { property ->
            val type = property.documentedType?.let { value -> "&nbsp;&nbsp;<code>${value.html()}</code>" }.orEmpty()
            val description =
                property.description
                    ?.takeIf(String::isNotBlank)
                    ?.let { value -> "&nbsp;&nbsp;—&nbsp;${value.html()}" }
                    .orEmpty()

            add(
                JBLabel(
                    "<html><div width='$POPUP_TEXT_WIDTH'><code>${property.signature.html()}</code>$type$description</div></html>",
                ).apply {
                    alignmentX = LEFT_ALIGNMENT
                },
            )
            add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        val remaining = properties.size - visible.size

        if (remaining > 0) {
            add(
                JBLabel("+$remaining more in full documentation").apply {
                    foreground = UIUtil.getContextHelpForeground()
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }

        add(Box.createVerticalStrut(JBUI.scale(6)))
    }

    private fun JPanel.addCodeSection(
        title: String,
        code: String,
        copyTooltip: String,
        language: PopupCodeLanguage,
    ) {
        addSectionTitle(title.uppercase())
        add(
            RoundedRowPanel().apply {
                layout = BorderLayout(JBUI.scale(6), 0)
                border = JBUI.Borders.empty(6, 8)
                alignmentX = LEFT_ALIGNMENT

                add(
                    JBLabel(
                        "<html><div width='$CODE_TEXT_WIDTH'><code>${highlightCode(code, language)}</code></div></html>",
                    ),
                    BorderLayout.CENTER,
                )
                add(CopyValueButton(code, copyTooltip), BorderLayout.EAST)
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(7)))
    }

    private fun JPanel.addDetail(
        label: String,
        htmlValue: String,
    ) {
        add(
            JBLabel(
                "<html><span>${label.html()}</span>&nbsp;&nbsp;&nbsp;$htmlValue</html>",
            ).apply {
                alignmentX = LEFT_ALIGNMENT
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(7)))
    }

    private fun JPanel.addSectionTitle(title: String) {
        add(
            JBLabel(title).apply {
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = LEFT_ALIGNMENT
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(4)))
    }

    private fun verticalPanel(): JPanel =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
        }

    private fun String.html(): String = StringUtil.escapeXmlEntities(this)

    private companion object {
        const val MAX_VISIBLE_API_PROPERTIES = 6
    }
}

private enum class PopupCodeLanguage {
    HTML,
    TYPESCRIPT,
}

private fun highlightCode(
    code: String,
    language: PopupCodeLanguage,
): String =
    when (language) {
        PopupCodeLanguage.HTML -> highlightHtml(code)
        PopupCodeLanguage.TYPESCRIPT -> highlightTypeScript(code)
    }

private fun highlightHtml(code: String): String =
    buildString {
        var offset = 0

        HTML_TAG.findAll(code).forEach { tag ->
            append(code.substring(offset, tag.range.first).codeHtml())

            append(tag.groupValues[1].codeHtml())
            append(tag.groupValues[2].highlight(CODE_TAG_COLOR))
            append(highlightHtmlAttributes(tag.groupValues[3]))
            append(tag.groupValues[4].codeHtml())

            offset = tag.range.last + 1
        }

        append(code.substring(offset).codeHtml())
    }

private fun highlightHtmlAttributes(attributes: String): String =
    buildString {
        var offset = 0

        HTML_ATTRIBUTE.findAll(attributes).forEach { attribute ->
            append(attributes.substring(offset, attribute.range.first).codeHtml())
            append(attribute.groupValues[1].highlight(CODE_ATTRIBUTE_COLOR))
            append(attribute.groupValues[2].codeHtml())
            append(attribute.groupValues[3].highlight(CODE_STRING_COLOR))
            offset = attribute.range.last + 1
        }

        append(attributes.substring(offset).codeHtml())
    }

private fun highlightTypeScript(code: String): String =
    buildString {
        var offset = 0

        TYPESCRIPT_TOKEN.findAll(code).forEach { token ->
            append(code.substring(offset, token.range.first).codeHtml())

            val value = token.value
            val color =
                when {
                    value.firstOrNull() == '\'' || value.firstOrNull() == '"' -> CODE_STRING_COLOR
                    value in TYPESCRIPT_KEYWORDS -> CODE_KEYWORD_COLOR
                    value.firstOrNull()?.isUpperCase() == true -> CODE_SYMBOL_COLOR
                    else -> CODE_TEXT_COLOR
                }

            append(value.highlight(color))
            offset = token.range.last + 1
        }

        append(code.substring(offset).codeHtml())
    }

private fun String.highlight(color: Color): String =
    "<span style='color:${color.htmlColor()}'>${codeHtml()}</span>"

private fun String.codeHtml(): String =
    StringUtil
        .escapeXmlEntities(this)
        .replace("\n", "<br>")

private fun Color.htmlColor(): String = String.format("#%02x%02x%02x", red, green, blue)

private fun quickDocsSeparator(): JComponent =
    JSeparator().apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, preferredSize.height)
    }

private fun quickDocsBadge(text: String): JComponent =
    JBLabel(text).apply {
        isOpaque = true
        background = UIUtil.getTextFieldBackground()
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.empty(2, 6)
        font = font.deriveFont((font.size2D - 1F).coerceAtLeast(10F))
        alignmentY = JComponent.CENTER_ALIGNMENT
    }

private fun wrappedLabel(text: String): JComponent =
    JBLabel(
        "<html><div width='$POPUP_TEXT_WIDTH'>${StringUtil.escapeXmlEntities(text)}</div></html>",
    ).apply {
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

private val HTML_TAG = Regex("""(</?)([A-Za-z][\w:-]*)([^<>]*?)(/?>)""")
private val HTML_ATTRIBUTE = Regex("""([:@*#\[\]()A-Za-z_][^\s=/>]*)(\s*=\s*)("[^"]*"|'[^']*')""")
private val TYPESCRIPT_TOKEN =
    Regex(
        """('(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|\b(?:import|from|export|const|let|type|interface|extends|implements|as)\b|\b[A-Z][A-Za-z0-9_]*\b)""",
    )
private val TYPESCRIPT_KEYWORDS =
    setOf(
        "import",
        "from",
        "export",
        "const",
        "let",
        "type",
        "interface",
        "extends",
        "implements",
        "as",
    )

private val CODE_TAG_COLOR = JBColor(Color(0x00, 0x67, 0xA3), Color(0x56, 0xB6, 0xC2))
private val CODE_ATTRIBUTE_COLOR = JBColor(Color(0x00, 0x5C, 0xB9), Color(0x9C, 0xDC, 0xFE))
private val CODE_STRING_COLOR = JBColor(Color(0x06, 0x7D, 0x17), Color(0x98, 0xC3, 0x79))
private val CODE_KEYWORD_COLOR = JBColor(Color(0x7A, 0x3E, 0x9D), Color(0xC6, 0x78, 0xDD))
private val CODE_SYMBOL_COLOR = JBColor(Color(0x00, 0x65, 0xA8), Color(0x61, 0xAF, 0xEF))
private val CODE_TEXT_COLOR = UIUtil.getLabelForeground()

private const val POPUP_WIDTH = 560
private const val POPUP_TEXT_WIDTH = 500
private const val CODE_TEXT_WIDTH = 455

internal fun JComponent.containsPointer(): Boolean =
    isShowing &&
        MouseInfo
            .getPointerInfo()
            ?.location
            ?.let(::Point)
            ?.also { point -> SwingUtilities.convertPointFromScreen(point, this) }
            ?.let(::contains)
            ?: false
