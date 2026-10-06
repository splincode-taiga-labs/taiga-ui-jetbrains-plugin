package org.taigaui.designtokens.documentation

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Font
import java.awt.MouseInfo
import java.awt.Point
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.SwingUtilities

internal class TaigaQuickDocumentationPopupPanel(
    private val resolved: TaigaResolvedDocumentation,
    onClose: () -> Unit,
) : JPanel(BorderLayout()) {
    init {
        border = JBUI.Borders.empty(12, 16)
        isOpaque = true

        val content =
            JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
            }

        content.add(createHeader(onClose))
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        createMetaLabel()?.let(content::add)

        resolved.description?.takeIf(String::isNotBlank)?.let { description ->
            content.add(Box.createVerticalStrut(JBUI.scale(10)))
            content.add(wrappedLabel(description))
        }

        content.add(Box.createVerticalStrut(JBUI.scale(10)))
        content.add(separator())
        content.add(Box.createVerticalStrut(JBUI.scale(10)))

        when (resolved) {
            is TaigaResolvedDocumentation.Entity -> content.addEntityDetails(resolved)
            is TaigaResolvedDocumentation.Member -> content.addMemberDetails(resolved)
        }

        add(content, BorderLayout.CENTER)
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
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(
                JBLabel(resolved.badge).apply {
                    foreground = UIUtil.getContextHelpForeground()
                    border = JBUI.Borders.empty(1, 6)
                },
            )
            add(Box.createHorizontalGlue())
            add(
                LinkLabel<Any>("View documentation ↗", null) { _, _ ->
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

    private fun JPanel.addEntityDetails(entityDocs: TaigaResolvedDocumentation.Entity) {
        entityDocs.typeText?.takeIf(String::isNotBlank)?.let { type ->
            addDetail("Type", "<code>${type.html()}</code>")
        }

        entityDocs.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
            addCodeSection("Usage", usage)
        }

        entityDocs.canonicalImport()?.let { statement ->
            addCodeSection("Import", statement)
        }

        addApiSection("Inputs", entityDocs.entity.inputs)
        addApiSection("Outputs", entityDocs.entity.outputs)
    }

    private fun JPanel.addMemberDetails(memberDocs: TaigaResolvedDocumentation.Member) {
        memberDocs.typeText?.takeIf(String::isNotBlank)?.let { type ->
            addDetail("Type", "<code>${type.html()}</code>")
        }

        memberDocs.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
            addCodeSection("Usage", usage)
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

        addSectionTitle("$title (${properties.size})")

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
                    "<html><code>${property.name.html()}</code>$type$description</html>",
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

        add(Box.createVerticalStrut(JBUI.scale(8)))
    }

    private fun JPanel.addCodeSection(
        title: String,
        code: String,
    ) {
        addSectionTitle(title)
        add(
            JBLabel(
                "<html><div width='$POPUP_TEXT_WIDTH'><code>${code.html()}</code></div></html>",
            ).apply {
                isOpaque = true
                background = UIUtil.getTextFieldBackground()
                border = JBUI.Borders.empty(7, 9)
                alignmentX = LEFT_ALIGNMENT
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(8)))
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
        add(Box.createVerticalStrut(JBUI.scale(8)))
    }

    private fun JPanel.addSectionTitle(title: String) {
        add(
            JBLabel(title).apply {
                foreground = UIUtil.getContextHelpForeground()
                alignmentX = LEFT_ALIGNMENT
            },
        )
        add(Box.createVerticalStrut(JBUI.scale(5)))
    }

    private fun wrappedLabel(text: String): JComponent =
        JBLabel(
            "<html><div width='$POPUP_TEXT_WIDTH'>${text.html()}</div></html>",
        ).apply {
            alignmentX = LEFT_ALIGNMENT
        }

    private fun separator(): JComponent =
        JSeparator().apply {
            alignmentX = LEFT_ALIGNMENT
            maximumSize = java.awt.Dimension(Int.MAX_VALUE, preferredSize.height)
        }

    private fun String.html(): String = StringUtil.escapeXmlEntities(this)

    private companion object {
        const val POPUP_TEXT_WIDTH = 520
        const val MAX_VISIBLE_API_PROPERTIES = 6
    }
}


internal fun JComponent.containsPointer(): Boolean =
    isShowing &&
        MouseInfo
            .getPointerInfo()
            ?.location
            ?.let(::Point)
            ?.also { point -> SwingUtilities.convertPointFromScreen(point, this) }
            ?.let(::contains)
            ?: false
