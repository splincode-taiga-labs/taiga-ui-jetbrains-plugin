package org.taigaui.designtokens.documentation

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.RenderingHints
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.Icon
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JSeparator
import javax.swing.LayoutFocusTraversalPolicy

internal data class TaigaDocumentationIconPreview(
    val reference: TaigaDocumentationIcon,
    val icon: Icon,
)

internal data class TaigaDocumentationPopupActions(
    val navigateToSource: (() -> Unit)? = null,
    val chooseIcon: ((TaigaDocumentationIcon) -> Unit)? = null,
    val openMember: ((TaigaResolvedDocumentation.Member) -> Unit)? = null,
    val showExample: (() -> Unit)? = null,
    val togglePin: (() -> Unit)? = null,
    val applyValue: ((String) -> String)? = null,
    val currentValue: String? = null,
    val openOwner: ((TaigaResolvedDocumentation.Entity) -> Unit)? = null,
    val goBack: (() -> Unit)? = null,
    val queryChanged: ((String) -> Unit)? = null,
    val applyTemplateEdit: ((TaigaDocumentationTemplateEdit) -> String)? = null,
    val navigateDeclaration: ((TaigaDocumentationSource) -> Unit)? = null,
    val refresh: (() -> Unit)? = null,
)

/** The same compact card structure serves every kind, with kind-specific content. */
@Suppress("TooManyFunctions", "LongParameterList")
internal class TaigaQuickDocumentationPopupPanel(
    private val resolved: TaigaResolvedDocumentation,
    private val onClose: () -> Unit = {},
    private val actions: TaigaDocumentationPopupActions = TaigaDocumentationPopupActions(),
    private val previews: List<TaigaDocumentationIconPreview> = emptyList(),
    showExample: Boolean = false,
    pinned: Boolean = false,
    private val apiQuery: String? = null,
) : JPanel(BorderLayout(0, JBUI.scale(12))) {
    private val content = verticalPanel()
    private val previewContent = verticalPanel()
    private val scroll: JBScrollPane
    private var apiBrowser: TaigaDocumentationApiBrowser? = null
    private var backButton: JButton? = null
    private val sourceButtons = mutableListOf<JButton>()
    private var bindingPanel: TaigaDocumentationBindingPanel? = null
    private val contextStatus = verticalPanel()
    private var contextMessage: String? = null
    private val fullApi: Boolean get() = apiQuery != null

    val preferredFocus: JComponent get() = apiBrowser?.preferredFocus ?: backButton ?: firstFocusable(this) ?: this

    init {
        border = JBUI.Borders.empty(16, 18)
        background = DESIGN_TOKEN_POPUP_BACKGROUND
        getAccessibleContext().accessibleName = "Taiga UI documentation for ${resolved.presentationName}"
        isFocusCycleRoot = true
        focusTraversalPolicy = LayoutFocusTraversalPolicy()
        bind("ESCAPE", "close-taiga-card", onClose)
        actions.refresh?.let { refresh -> bind("F5", "refresh-taiga-card", refresh) }
        actions.goBack?.let { back -> bind("alt LEFT", "back-to-api", back) }

        content.add(header(pinned))
        content.add(Box.createVerticalStrut(JBUI.scale(4)))
        content.add(meta())
        contextStatus.isVisible = false
        content.add(contextStatus)
        resolved.description?.takeIf(String::isNotBlank)?.let { description ->
            content.add(Box.createVerticalStrut(JBUI.scale(12)))
            content.add(wrappedLabel(description.take(MAX_DESCRIPTION_LENGTH)))
        }
        content.add(Box.createVerticalStrut(JBUI.scale(14)))

        when (val documentation = resolved) {
            is TaigaResolvedDocumentation.Entity -> content.add(entityContent(documentation))
            is TaigaResolvedDocumentation.Member -> content.add(memberContent(documentation))
        }
        if (showExample) {
            resolved.entity.example?.let { example ->
                content.add(sectionTitle("Example"))
                content.add(codeRow(example.code.take(MAX_EXAMPLE_LENGTH)))
            }
        }

        scroll =
            JBScrollPane(content).apply {
                border = JBUI.Borders.empty()
                isOpaque = false
                viewport.isOpaque = false
                horizontalScrollBarPolicy = JBScrollPane.HORIZONTAL_SCROLLBAR_NEVER
                preferredSize =
                    Dimension(
                        JBUI.scale(CONTENT_WIDTH),
                        content.preferredSize.height.coerceAtMost(JBUI.scale(MAX_BODY_HEIGHT)),
                    )
            }
        add(scroll, BorderLayout.CENTER)
        add(footer(), BorderLayout.SOUTH)
        keyboardButtons(this)
    }

    fun invalidateContext(message: String = STALE_DOCUMENTATION_MESSAGE) {
        if (contextMessage == message) return
        contextMessage = message
        sourceButtons.forEach { it.isEnabled = false }
        bindingPanel?.invalidateContext()
        contextStatus.removeAll()
        contextStatus.add(wrappedLabel(message))
        actions.refresh?.let { refresh ->
            val button = JButton("Refresh").apply { addActionListener { refresh() } }
            contextStatus.add(button)
            keyboardButtons(button)
        }
        contextStatus.isVisible = true
        revalidate()
        repaint()
    }

    fun showRefreshFailure() = invalidateContext("The source target changed. Reopen its card at the caret.")

    private fun sourceButton(
        text: String,
        action: () -> Unit,
    ): JButton =
        JButton(text).apply {
            sourceButtons += this
            isEnabled = !contextStatus.isVisible
            addActionListener { action() }
        }

    private fun firstFocusable(component: java.awt.Container): JComponent? =
        component.components.firstNotNullOfOrNull { child ->
            when {
                child is JComponent &&
                    (child is JButton || child is LinkLabel<*>) &&
                    child.isFocusable &&
                    child.isEnabled -> child
                child is java.awt.Container -> firstFocusable(child)
                else -> null
            }
        }

    private fun keyboardButtons(component: JComponent) {
        if (component is JButton) {
            component.bind("ENTER", "activate-taiga-button") { if (component.isEnabled) component.doClick() }
        }
        component.components.filterIsInstance<JComponent>().forEach(::keyboardButtons)
    }

    fun showIconPreviews(values: List<TaigaDocumentationIconPreview>) {
        previewContent.removeAll()
        addIconPreviews(previewContent, values)
        scroll.preferredSize =
            Dimension(JBUI.scale(CONTENT_WIDTH), content.preferredSize.height.coerceAtMost(JBUI.scale(MAX_BODY_HEIGHT)))
        revalidate()
        repaint()
    }

    private fun header(pinned: Boolean): JComponent =
        JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            alignmentX = LEFT_ALIGNMENT
            actions.goBack?.let { back ->
                val button = JButton("Back").apply { addActionListener { back() } }
                backButton = button
                add(button)
                add(Box.createHorizontalStrut(JBUI.scale(8)))
            }
            add(
                JBLabel(resolved.presentationName).apply {
                    foreground = CARD_FOREGROUND
                    font = font.deriveFont(Font.BOLD, font.size2D + 2F)
                },
            )
            add(Box.createHorizontalStrut(JBUI.scale(10)))
            add(TaigaDocumentationBadge(resolved.badge))
            add(Box.createHorizontalGlue())
            actions.togglePin?.let { toggle ->
                add(JButton(if (pinned) "Unpin" else "Pin").apply { addActionListener { toggle() } })
            }
            add(JButton("Close").apply { addActionListener { onClose() } })
        }

    private fun meta(): JComponent =
        JBLabel(
            listOfNotNull(
                resolved.ownerName?.let { "of $it" } ?: resolved.subject.selector,
                resolved.packageName,
            ).joinToString("  ·  "),
        ).apply {
            foreground = CARD_MUTED_FOREGROUND
            alignmentX = LEFT_ALIGNMENT
            font = font.deriveFont(Font.PLAIN)
        }

    private fun entityContent(entity: TaigaResolvedDocumentation.Entity): JComponent =
        verticalPanel().apply {
            when (entity.badge) {
                "Pipe" -> add(pipeContent(entity.localDocumentation.pipe))
                "Type" -> entity.typeText?.let { add(codeRow(it)) }
                else -> addControlContent(this, entity)
            }
        }

    private fun addControlContent(
        content: JPanel,
        entity: TaigaResolvedDocumentation.Entity,
    ) {
        if (entity.icons.isNotEmpty()) {
            addIconPreviews(previewContent, previews)
            content.add(previewContent)
        }
        val elements =
            entity.localDocumentation.selector
                ?.split(',')
                ?.map { it.substringBefore('[').trim() }
                ?.filter(String::isNotBlank)
                ?.distinct()
                .orEmpty()
        if (elements.isNotEmpty() && entity.badge == "Directive") {
            content.add(detail("Elements", elements.joinToString(" · ")))
        }
        if (fullApi) {
            val browser =
                TaigaDocumentationApiBrowser(
                    entity,
                    apiQuery.orEmpty(),
                    { actions.openMember?.invoke(it) },
                    { actions.queryChanged?.invoke(it) },
                )
            apiBrowser = browser
            content.add(browser)
        } else {
            addApiSection(content, "Parameters", entity.entity.inputs, TaigaApiMemberKind.INPUT)
            addApiSection(content, "Events", entity.entity.outputs, TaigaApiMemberKind.OUTPUT)
        }
        addTemplateActions(content, entity)
        entity.localDocumentation.defaults
            .take(MAX_VISIBLE_DEFAULTS)
            .forEach { content.add(defaultNote(it)) }
    }

    private fun memberContent(member: TaigaResolvedDocumentation.Member): JComponent =
        verticalPanel().apply {
            if (member.documentationIcons.isNotEmpty()) {
                addIconPreviews(previewContent, previews)
                add(previewContent)
            }
            addMemberOwners(this, member)
            member.typeText?.takeIf { member.receivers.isEmpty() }?.let {
                val label = if (member.kind == TaigaApiMemberKind.OUTPUT) "\$event type" else "Type"
                add(detail(label, it))
            }
            add(
                TaigaDocumentationBindingPanel(member, actions.applyValue, actions.currentValue).also {
                    bindingPanel =
                        it
                },
            )
            val declaredRequiredInputs =
                member.declaration
                    ?.localDocumentation
                    ?.requiredInputs
                    .orEmpty()
            if (member.property.name in member.subject.localDocumentation.requiredInputs ||
                member.property.name in declaredRequiredInputs
            ) {
                add(detail("Requirement", "Required input"))
            }
            val default =
                member.declaration
                    ?.localDocumentation
                    ?.defaults
                    ?.firstOrNull { it.name == member.property.name }
                    ?: member.localDocumentation.defaults.firstOrNull { it.name == member.property.name }
            default?.let {
                add(
                    defaultNote(it),
                )
            }
            val legacyOwner = member.declaration?.publicSymbol?.takeIf { member.localMember == null }
            legacyOwner?.takeIf { it != member.ownerName }?.let { owner ->
                add(detail("Declared by", owner))
            }
            member.relatedMembers().takeIf(List<String>::isNotEmpty)?.let { related ->
                add(detail("See also", related.joinToString("  ·  ")))
            }
            addTemplateActions(this, member)
        }

    private fun addMemberOwners(
        content: JPanel,
        member: TaigaResolvedDocumentation.Member,
    ) {
        val receivers = member.receivers.ifEmpty { listOf(member) }
        if (receivers.size > 1) content.add(sectionTitle("Receives this binding"))
        receivers.forEach { receiver ->
            addReceiverDetails(content, receiver, receivers.size > 1)
            addReceiverLinks(content, receiver, receivers.size > 1)
        }
    }

    private fun addReceiverDetails(
        content: JPanel,
        receiver: TaigaResolvedDocumentation.Member,
        multiple: Boolean,
    ) {
        val local = receiver.localMember
        if (multiple) content.add(detail(receiver.ownerName.orEmpty(), receiver.typeText.orEmpty()))
        local?.expandedType?.let { content.add(detail("Expanded type", it)) }
        local?.valueType?.let { content.add(detail("Stored value type", it)) }
        local?.transform?.let { content.add(detail("Input transform", it)) }
        local?.deprecated?.let { content.add(note("Deprecated", it, DEFAULT_COLOR)) }
        if (multiple &&
            local?.required == true
        ) {
            content.add(detail(receiver.subject.presentationName, "Required input"))
        }
        receiver.declaration?.publicSymbol?.takeIf { it != receiver.subject.publicSymbol }?.let {
            content.add(detail("Declared by", it))
        }
    }

    private fun addReceiverLinks(
        content: JPanel,
        receiver: TaigaResolvedDocumentation.Member,
        multiple: Boolean,
    ) {
        actions.openOwner?.let { open ->
            content.add(link("View ${receiver.subject.presentationName} API →") { open(receiver.ownerDocumentation()) })
        }
        val source = receiver.source
        val navigate = actions.navigateDeclaration
        if (multiple && source != null && navigate != null) {
            content.add(link("Source of ${receiver.subject.presentationName} ↗") { navigate(source) })
        }
    }

    private fun addTemplateActions(
        content: JPanel,
        documentation: TaigaResolvedDocumentation,
    ) {
        val apply = actions.applyTemplateEdit ?: return
        val edits = documentation.templateEdits()
        if (edits.isEmpty()) return
        val status = JBLabel().apply { alignmentX = LEFT_ALIGNMENT }
        edits.forEach { edit ->
            content.add(
                sourceButton(edit.label) { status.text = apply(edit) }.apply {
                    alignmentX = LEFT_ALIGNMENT
                },
            )
        }
        content.add(status)
    }

    private fun pipeContent(pipe: TaigaPipeDocumentation?): JComponent =
        verticalPanel().apply {
            if (pipe != null) {
                pipe.invocation?.let { invocation ->
                    add(sectionTitle("What it calls"))
                    add(codeRow(invocation))
                }
                if (pipe.parameters.isNotEmpty()) {
                    add(sectionTitle("Parameters"))
                    add(
                        table(
                            pipe.parameters.map { parameter ->
                                parameter.presentationName to
                                    listOfNotNull(parameter.type, parameter.description).joinToString(" — ")
                            },
                        ),
                    )
                }
                pipe.resultType?.let { add(detail("Result", it)) }
                pipe.pure?.let { pure ->
                    add(
                        note(
                            if (pure) "Pure pipe" else "Impure pipe",
                            if (pure) {
                                "Recomputed when the value or arguments change. Mutating an object without replacing its reference does not trigger this pipe."
                            } else {
                                "Angular invokes this pipe during change detection."
                            },
                            PIPE_COLOR,
                        ),
                    )
                }
            }
        }

    private fun addIconPreviews(
        content: JPanel,
        values: List<TaigaDocumentationIconPreview>,
    ) {
        if (values.isEmpty()) return
        content.add(sectionTitle("Icon from current value"))
        values.forEach { preview ->
            content.add(
                RoundedRowPanel().apply {
                    layout = BorderLayout(JBUI.scale(12), 0)
                    border = JBUI.Borders.empty(8)
                    alignmentX = LEFT_ALIGNMENT
                    add(
                        JBLabel(preview.icon).apply {
                            preferredSize = JBUI.size(72, 72)
                            isOpaque = true
                            background = Color.WHITE
                        },
                        BorderLayout.WEST,
                    )
                    add(
                        wrappedLabel(preview.reference.name, CONTENT_WIDTH - 112).apply { font = codeFont() },
                        BorderLayout.CENTER,
                    )
                },
            )
            content.add(Box.createVerticalStrut(JBUI.scale(12)))
        }
    }

    private fun addApiSection(
        content: JPanel,
        title: String,
        properties: List<TaigaApiProperty>,
        kind: TaigaApiMemberKind,
    ) {
        if (properties.isEmpty()) return
        content.add(sectionTitle(title))
        content.add(
            table(
                properties.take(MAX_VISIBLE_API_PROPERTIES).map { property ->
                    property.name to
                        listOfNotNull(
                            resolved.localDocumentation.inputTypes[property.name] ?: property.documentedType,
                            property.description,
                        ).joinToString(" — ").take(MAX_PROPERTY_DESCRIPTION_LENGTH)
                },
            ) { name ->
                val entity = resolved as? TaigaResolvedDocumentation.Entity
                val property = properties.firstOrNull { it.name == name }
                if (entity != null && property != null) {
                    actions.openMember?.invoke(entity.focusedMember(property, kind))
                }
            },
        )
        if (properties.size > MAX_VISIBLE_API_PROPERTIES) {
            content.add(
                JBLabel("+${properties.size - MAX_VISIBLE_API_PROPERTIES} more in Browse API").apply {
                    foreground = CARD_MUTED_FOREGROUND
                },
            )
        }
        content.add(Box.createVerticalStrut(JBUI.scale(12)))
    }

    private fun footer(): JComponent =
        verticalPanel().apply {
            add(JSeparator())
            add(Box.createVerticalStrut(JBUI.scale(12)))
            add(
                JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    isOpaque = false
                    alignmentX = LEFT_ALIGNMENT
                    val reference = resolved.documentationIcons.firstOrNull()
                    if (reference != null && actions.chooseIcon != null) {
                        add(sourceButton("Choose icon") { actions.chooseIcon.invoke(reference) })
                    } else if (resolved.entity.example != null && actions.showExample != null) {
                        add(link("Example →") { actions.showExample.invoke() })
                    }
                    if (resolved is TaigaResolvedDocumentation.Entity && !fullApi) {
                        actions.openOwner?.let { open -> add(link("Browse API →") { open(resolved) }) }
                    }
                    add(Box.createHorizontalGlue())
                    actions.navigateToSource?.let { navigate ->
                        add(link("Source ↗", navigate))
                        add(Box.createHorizontalStrut(JBUI.scale(16)))
                    }
                    if (resolved.packageName != null) {
                        add(
                            link("Documentation ↗") {
                                BrowserUtil.browse(resolved.documentationUri.toString())
                                onClose()
                            },
                        )
                    }
                },
            )
        }
}

private fun table(
    rows: List<Pair<String, String>>,
    onSelect: ((String) -> Unit)? = null,
): JComponent =
    RoundedRowPanel().apply {
        layout = GridBagLayout()
        alignmentX = JComponent.LEFT_ALIGNMENT
        rows.forEachIndexed { index, (name, description) ->
            val member =
                if (onSelect == null) {
                    JBLabel(name).apply {
                        foreground = CARD_FOREGROUND
                        font = codeFont()
                    }
                } else {
                    link(name) { onSelect(name) }.apply { font = codeFont() }
                }
            add(
                member,
                GridBagConstraints().apply {
                    gridx = 0
                    gridy = index
                    anchor = GridBagConstraints.NORTHWEST
                    insets = JBUI.insets(8, 10)
                },
            )
            add(
                wrappedLabel(description, PROPERTY_TEXT_WIDTH),
                GridBagConstraints().apply {
                    gridx = 1
                    gridy = index
                    weightx = 1.0
                    fill = GridBagConstraints.HORIZONTAL
                    anchor = GridBagConstraints.NORTHWEST
                    insets = JBUI.insets(8, 10)
                },
            )
        }
    }

private fun defaultNote(default: TaigaInputDefault): JComponent =
    note(
        "If ${default.name} is omitted",
        default.provider?.let { "Read from $it. Project providers may change this value." }
            ?: "Library default: ${default.value}.",
        DEFAULT_COLOR,
    )

private fun note(
    title: String,
    description: String,
    color: Color,
): JComponent =
    DocumentationNotePanel(color).apply {
        border = JBUI.Borders.empty(10, 12)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(
            JBLabel(title).apply {
                foreground = color
                font = font.deriveFont(Font.BOLD)
            },
            BorderLayout.NORTH,
        )
        add(wrappedLabel(description, CONTENT_WIDTH - 36), BorderLayout.CENTER)
    }

private class DocumentationNotePanel(
    private val color: Color,
) : JPanel(BorderLayout(0, JBUI.scale(6))) {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        val arc = JBUI.scale(10)

        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = Color(color.red, color.green, color.blue, 18)
        copy.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        copy.color = Color(color.red, color.green, color.blue, 130)
        copy.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        copy.dispose()
        super.paintComponent(graphics)
    }
}

private fun detail(
    title: String,
    value: String,
): JComponent =
    verticalPanel().apply {
        add(sectionTitle(title))
        add(wrappedLabel(value).apply { font = codeFont() })
        add(Box.createVerticalStrut(JBUI.scale(12)))
    }

private fun codeRow(code: String): JComponent =
    RoundedRowPanel().apply {
        layout = BorderLayout()
        border = JBUI.Borders.empty(10, 12)
        alignmentX = JComponent.LEFT_ALIGNMENT
        add(wrappedLabel(code, CONTENT_WIDTH - 36).apply { font = codeFont() }, BorderLayout.CENTER)
    }

private fun sectionTitle(title: String): JComponent =
    JBLabel(title).apply {
        foreground = CARD_FOREGROUND
        font = font.deriveFont(Font.BOLD)
        border = JBUI.Borders.emptyBottom(7)
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

private class TaigaDocumentationBadge(
    text: String,
) : JBLabel(text) {
    init {
        foreground =
            when (text) {
                "Component" -> COMPONENT_COLOR
                "Directive", "Input" -> DIRECTIVE_COLOR
                "Pipe", "Output" -> PIPE_COLOR
                else -> UIUtil.getContextHelpForeground()
            }
        border = JBUI.Borders.empty(3, 8)
        font = font.deriveFont(Font.PLAIN, font.size2D - 1F)
    }

    override fun paintComponent(graphics: Graphics) {
        val copy = graphics.create() as Graphics2D
        val arc = JBUI.scale(16)

        copy.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        copy.color = Color(foreground.red, foreground.green, foreground.blue, 28)
        copy.fillRoundRect(0, 0, width - 1, height - 1, arc, arc)
        copy.color = foreground
        copy.drawRoundRect(0, 0, width - 1, height - 1, arc, arc)
        copy.dispose()
        super.paintComponent(graphics)
    }
}

private fun verticalPanel(): JPanel =
    JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = JComponent.LEFT_ALIGNMENT
    }

private fun link(
    title: String,
    action: () -> Unit,
): LinkLabel<Any> =
    LinkLabel<Any>(title, null) { _, _ -> action() }.apply {
        foreground = DESIGN_TOKEN_POPUP_LINK_COLOR
        font = font.deriveFont(Font.PLAIN)
        isFocusable = true
        bind("ENTER", "activate-taiga-link", action)
    }

private fun wrappedLabel(
    text: String,
    width: Int = CONTENT_WIDTH,
): JBLabel =
    JBLabel(
        "<html><div width='$width'>${StringUtil.escapeXmlEntities(text).replace("\n", "<br>")}</div></html>",
    ).apply {
        foreground = CARD_FOREGROUND
        alignmentX = JComponent.LEFT_ALIGNMENT
        font = font.deriveFont(Font.PLAIN)
    }

private fun codeFont(): Font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)

private val COMPONENT_COLOR = JBColor(Color(123, 62, 174), Color(190, 139, 245))
private val DIRECTIVE_COLOR = JBColor(Color(0, 115, 120), Color(97, 215, 208))
private val PIPE_COLOR = JBColor(Color(31, 105, 183), Color(102, 172, 247))
private val DEFAULT_COLOR = JBColor(Color(136, 91, 0), Color(234, 180, 68))
private val CARD_FOREGROUND = JBColor(Color(35, 38, 42), Color(225, 227, 233))
private val CARD_MUTED_FOREGROUND = JBColor(Color(91, 96, 105), Color(151, 158, 170))
private const val CONTENT_WIDTH = 508
private const val PROPERTY_TEXT_WIDTH = 310
private const val MAX_BODY_HEIGHT = 520
private const val MAX_DESCRIPTION_LENGTH = 450
private const val MAX_PROPERTY_DESCRIPTION_LENGTH = 240
private const val MAX_EXAMPLE_LENGTH = 1_200
private const val MAX_VISIBLE_API_PROPERTIES = 6
private const val MAX_VISIBLE_DEFAULTS = 2
