package org.taigaui.designtokens.documentation

import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JComponent
import javax.swing.JPanel

internal fun documentationApiList(
    entity: TaigaResolvedDocumentation.Entity,
    properties: List<TaigaApiProperty>,
    kind: TaigaApiMemberKind,
    actions: TaigaDocumentationPopupActions,
): JComponent =
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        properties.forEachIndexed { index, property ->
            if (index > 0) add(documentationSeparator())
            val member = entity.focusedMember(property, kind)
            val default =
                member.declaration
                    ?.localDocumentation
                    ?.defaults
                    ?.firstOrNull { it.name == property.name }
                    ?: entity.localDocumentation.defaults.firstOrNull { it.name == property.name }
            add(documentationApiEntry(member, default, actions))
        }
    }

private fun documentationApiEntry(
    member: TaigaResolvedDocumentation.Member,
    default: TaigaInputDefault?,
    actions: TaigaDocumentationPopupActions,
): JComponent =
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(10, 0)
        add(documentationApiEntryName(member, actions.openMember))
        val type =
            member.localMember?.expandedType?.takeIf { finiteStringValues(it).isNotEmpty() }
                ?: member.localMember?.declaredAcceptedType
                ?: member.typeText.orEmpty()
        if (type.isNotBlank()) {
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(documentationUnionChips(type, 470) ?: documentationCodeLabel(type, 470))
        }
        member.property.description?.takeIf(String::isNotBlank)?.let { description ->
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(wrappedLabel(description.take(MAX_CARD_DESCRIPTION_LENGTH), 470))
        }
        val facts =
            listOfNotNull(
                default?.value?.let { "Default: $it" } ?: default?.provider?.let { "Default from $it" },
                "Required".takeIf { member.localMember?.required == true },
            )
        if (facts.isNotEmpty()) {
            add(Box.createVerticalStrut(JBUI.scale(5)))
            add(
                JBLabel(facts.joinToString(" · ")).apply {
                    foreground = CARD_MUTED_FOREGROUND
                    alignmentX = JComponent.LEFT_ALIGNMENT
                },
            )
        }
    }

private fun documentationApiEntryName(
    member: TaigaResolvedDocumentation.Member,
    openMember: ((TaigaResolvedDocumentation.Member) -> Unit)?,
): JComponent =
    if (openMember == null) {
        JBLabel(member.property.name).apply {
            font = taigaDocumentationCodeFont().deriveFont(Font.BOLD)
            alignmentX = JComponent.LEFT_ALIGNMENT
        }
    } else {
        cardContentLink(member.property.name) { openMember(member) }.apply {
            font = taigaDocumentationCodeFont().deriveFont(Font.BOLD)
        }
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
    val related = member.relatedMembers()
    return if (open == null || related.isEmpty()) {
        null
    } else {
        JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0)).apply {
            isOpaque = false
            siblings.filter { it.name in related }.forEach { property ->
                add(
                    RoundedRowPanel().apply {
                        border = JBUI.Borders.empty(3, 7)
                        add(
                            cardContentLink(property.name) {
                                val kind =
                                    if (property in member.entity.inputs) {
                                        TaigaApiMemberKind.INPUT
                                    } else {
                                        TaigaApiMemberKind.OUTPUT
                                    }
                                open(member.ownerDocumentation().focusedMember(property, kind))
                            },
                        )
                    },
                )
            }
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
        alignmentX = JComponent.LEFT_ALIGNMENT
        bind("ENTER", "activate-taiga-link", action)
    }

private const val MAX_CARD_DESCRIPTION_LENGTH = 240
private const val MAX_CARD_EXAMPLE_LENGTH = 1_200
