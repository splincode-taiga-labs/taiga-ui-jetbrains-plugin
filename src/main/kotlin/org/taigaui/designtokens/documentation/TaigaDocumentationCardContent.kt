package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.Font
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal fun documentationApiTable(
    entity: TaigaResolvedDocumentation.Entity,
    properties: List<TaigaApiProperty>,
    kind: TaigaApiMemberKind,
    actions: TaigaDocumentationPopupActions,
): JComponent =
    JPanel(GridBagLayout()).apply {
        isOpaque = false
        listOf("Name", "Type", "Default", "Description").forEachIndexed { column, text ->
            addApiCell(JBLabel(text).apply { foreground = CARD_MUTED_FOREGROUND }, column, 0)
        }
        properties.take(MAX_CARD_API_PROPERTIES).forEachIndexed { index, property ->
            val member = entity.focusedMember(property, kind)
            val default =
                entity.localDocumentation.defaults.firstOrNull { it.name == property.name }
            addApiCell(
                cardContentLink(property.name) { actions.openMember?.invoke(member) }.apply {
                    font =
                        taigaDocumentationCodeFont()
                },
                0,
                index + 1,
            )
            addApiCell(
                documentationCodeLabel(member.localMember?.declaredAcceptedType ?: member.typeText.orEmpty(), 100),
                1,
                index + 1,
            )
            addApiCell(documentationCodeLabel(default?.value ?: default?.provider ?: "—", 100), 2, index + 1)
            addApiCell(
                wrappedLabel(property.description.orEmpty().take(MAX_CARD_DESCRIPTION_LENGTH), 150),
                3,
                index + 1,
            )
        }
        if (properties.size > MAX_CARD_API_PROPERTIES) {
            addApiCell(
                cardContentLink(
                    "Show all ${properties.size} ${kind.name.lowercase()}s →",
                ) { actions.openOwner?.invoke(entity) },
                0,
                MAX_CARD_API_PROPERTIES + 1,
            )
        }
    }

private fun JPanel.addApiCell(
    component: JComponent,
    column: Int,
    row: Int,
) {
    add(
        component,
        GridBagConstraints().apply {
            gridx = column
            gridy = row
            weightx = if (column == 3) 1.0 else 0.0
            fill = GridBagConstraints.HORIZONTAL
            anchor = GridBagConstraints.NORTHWEST
            insets = JBUI.insets(7, 4)
        },
    )
}

@Suppress("ReturnCount")
internal fun documentationSummaryUsage(documentation: TaigaResolvedDocumentation): String? {
    if (documentation is TaigaResolvedDocumentation.Entity && documentation.usage == null) return null
    documentation.effectiveUsage?.takeIf { it.length <= MAX_CARD_EXAMPLE_LENGTH }?.let { return it }
    val selector = documentation.localDocumentation.selector ?: documentation.subject.selector ?: return null
    val tag =
        if (selector.contains(
                "button[",
            )
        ) {
            "button"
        } else {
            selector.substringBefore('[').substringBefore(',').trim().ifEmpty {
                "div"
            }
        }
    val attribute =
        Regex("""\[([\w-]+)]""").find(selector)?.groupValues?.get(1)
            ?: selector.takeIf { Regex("""[\w-]+""").matches(it) && it != tag }
    val member = documentation as? TaigaResolvedDocumentation.Member
    val binding =
        member?.binding?.text ?: member?.let {
            if (it.kind == TaigaApiMemberKind.INPUT) {
                "[${it.property.name}]=\"value\""
            } else {
                "(${it.property.name})=\"handler(\$event)\""
            }
        }
    return "<$tag${attribute?.let { " $it" }.orEmpty()}${binding?.let { " $it" }.orEmpty()}>…</$tag>"
}

internal fun documentationRelatedMembers(
    member: TaigaResolvedDocumentation.Member,
    open: ((TaigaResolvedDocumentation.Member) -> Unit)?,
): JComponent? {
    val siblings = (member.entity.inputs + member.entity.outputs).filter { it.name != member.property.name }
    val related = member.relatedMembers().ifEmpty { siblings.take(3).map { it.name } }
    if (related.isEmpty()) return null
    return JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
        isOpaque = false
        siblings.filter { it.name in related }.forEach { property ->
            add(
                RoundedRowPanel().apply {
                    border = JBUI.Borders.empty(3, 7)
                    add(
                        cardContentLink(property.name) {
                            val kind =
                                if (property in
                                    member.entity.inputs
                                ) {
                                    TaigaApiMemberKind.INPUT
                                } else {
                                    TaigaApiMemberKind.OUTPUT
                                }
                            open?.invoke(member.ownerDocumentation().focusedMember(property, kind))
                        },
                    )
                },
            )
        }
    }
}

private fun cardContentLink(
    title: String,
    action: () -> Unit,
): LinkLabel<Any> =
    LinkLabel<Any>(title, null) { _, _ -> action() }.apply {
        foreground = DESIGN_TOKEN_POPUP_LINK_COLOR
        font = font.deriveFont(Font.PLAIN)
        isFocusable = true
        bind("ENTER", "activate-taiga-link", action)
    }

private const val MAX_CARD_API_PROPERTIES = 5
private const val MAX_CARD_DESCRIPTION_LENGTH = 240
private const val MAX_CARD_EXAMPLE_LENGTH = 1_200

internal fun documentationDefaultProviderInfo(default: TaigaInputDefault): JComponent? =
    default.provider?.let {
        documentationRow(
            "Default ${default.name}",
            wrappedLabel("Read from $it. Project providers may change this value.", 370),
        )
    }
