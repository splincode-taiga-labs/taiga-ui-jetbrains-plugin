package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.xml.XmlTag

internal data class TaigaDocumentationAttributeName(
    val text: String,
    val startOffset: Int,
    val endOffset: Int,
    val rawName: String = text,
) {
    val bindingName: String
        get() =
            rawName
                .removeSurrounding("[(", ")]")
                .removeSurrounding("[", "]")
                .removeSurrounding("(", ")")
                .removePrefix("bind-")
                .removePrefix("on-")
}

internal data class TaigaDocumentationElement(
    val startOffset: Int,
    val endOffset: Int,
    val text: String,
    val attributes: List<TaigaDocumentationAttributeName>,
    val attributeQuote: Char = '"',
)

/** Snapshot only the opening tag, not its body or any evaluated application expression. */
internal fun XmlTag.documentationElement(): TaigaDocumentationElement? {
    val opening = text.take(MAX_OPENING_TAG)
    var quote: Char? = null
    val end =
        opening.indexOfFirst { char ->
            if (quote != null) {
                if (char == quote) quote = null
                false
            } else if (char == '\'' || char == '"') {
                quote = char
                false
            } else {
                char == '>'
            }
        }
    if (end < 0) return null
    return TaigaDocumentationElement(
        textRange.startOffset,
        textRange.startOffset + end + 1,
        opening.substring(0, end + 1),
        attributes.mapNotNull { attribute ->
            attribute.nameElement?.let { name ->
                TaigaDocumentationAttributeName(
                    name.text,
                    name.textRange.startOffset,
                    name.textRange.endOffset,
                    attribute.bindingName(),
                )
            }
        },
        attributeQuote =
            if (com.intellij.lang.injection.InjectedLanguageManager
                    .getInstance(project)
                    .getInjectionHost(containingFile)
                    ?.text
                    ?.trim()
                    ?.firstOrNull() == '"'
            ) {
                '\''
            } else {
                '"'
            },
    )
}

internal enum class TaigaTemplateEditKind { ADD_REQUIRED, RENAME_BINDING }

internal data class TaigaDocumentationTemplateEdit(
    val kind: TaigaTemplateEditKind,
    val name: String,
    val replacement: String? = null,
) {
    val label: String
        get() = if (kind == TaigaTemplateEditKind.ADD_REQUIRED) "Add [$name]" else "Replace $name with $replacement"
}

internal val TaigaResolvedDocumentation.templateElement: TaigaDocumentationElement?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> element
            is TaigaResolvedDocumentation.Member -> element
        }

internal fun TaigaResolvedDocumentation.templateEdits(): List<TaigaDocumentationTemplateEdit> {
    val element = templateElement ?: return emptyList()
    val names = element.attributes.map(TaigaDocumentationAttributeName::bindingName).toSet()
    val subjects =
        if (this is TaigaResolvedDocumentation.Member) {
            receivers
                .ifEmpty {
                    listOf(this)
                }.map { it.subject }
        } else {
            listOf(subject)
        }
    if (subjects.any { !it.localDocumentation.receiversComplete }) return emptyList()
    val required =
        subjects
            .flatMap { it.localDocumentation.members }
            .filter {
                it.kind == TaigaApiMemberKind.INPUT &&
                    it.required &&
                    it.name !in names &&
                    PUBLIC_BINDING_NAME.matches(it.name)
            }.distinctBy(TaigaLocalApiMember::name)
            .map { TaigaDocumentationTemplateEdit(TaigaTemplateEditKind.ADD_REQUIRED, it.name) }
    val rename = (this as? TaigaResolvedDocumentation.Member)?.deprecatedEdit(names)
    return required + listOfNotNull(rename)
}

private fun TaigaResolvedDocumentation.Member.deprecatedEdit(existing: Set<String>): TaigaDocumentationTemplateEdit? {
    if (element?.attributes?.any { it.bindingName == property.name && it.rawName.startsWith("[(") } == true) return null
    val members = receivers.ifEmpty { listOf(this) }
    if (members.any { !it.subject.localDocumentation.receiversComplete }) return null
    val replacements =
        members.map { member ->
            val documentedReplacement = member.localMember?.replacement
            member.subject.localDocumentation.members
                .firstOrNull {
                    (it.name == documentedReplacement || it.fieldName == documentedReplacement) && it.kind == kind
                }?.name
        }
    val replacement = replacements.firstOrNull() ?: return null
    if (replacements.any { it != replacement } ||
        replacement in existing ||
        !PUBLIC_BINDING_NAME.matches(replacement)
    ) {
        return null
    }
    val compatible =
        members.all { member ->
            val current = member.localMember ?: return@all false
            member.subject.localDocumentation.members.any {
                it.name == replacement && it.kind == kind && it.type != null && it.type == current.type
            }
        }
    return if (compatible) {
        TaigaDocumentationTemplateEdit(
            TaigaTemplateEditKind.RENAME_BINDING,
            property.name,
            replacement,
        )
    } else {
        null
    }
}

/** Each action is one command, verifies the exact opening tag and tracks unrelated document edits. */
internal class TaigaDocumentationTemplateEditor(
    private val project: Project,
    private val document: Document,
    element: TaigaDocumentationElement,
    private val moveCaret: (Int) -> Unit = {},
) : Disposable {
    private var marker = document.createRangeMarker(element.startOffset, element.endOffset)
    private var expected = element.text
    private val names =
        element.attributes
            .associateWith {
                document.createRangeMarker(it.startOffset, it.endOffset)
            }.toMutableMap()
    private val existing = element.attributes.map(TaigaDocumentationAttributeName::bindingName).toMutableSet()
    private val quote = element.attributeQuote

    @Suppress("ReturnCount")
    fun apply(edit: TaigaDocumentationTemplateEdit): String {
        if (project.isDisposed || !document.isWritable) return "File is read-only or closed"
        if (!PUBLIC_BINDING_NAME.matches(edit.name)) return "Unsupported binding name"
        var result = "Element changed; reopen its card before applying"
        WriteCommandAction.writeCommandAction(project).withName(edit.label).run<RuntimeException> {
            if (matches(marker, expected)) {
                result =
                    when (edit.kind) {
                        TaigaTemplateEditKind.ADD_REQUIRED -> addRequired(edit.name)
                        TaigaTemplateEditKind.RENAME_BINDING -> renameBinding(edit)
                    }
                expected = document.charsSequence.subSequence(marker.startOffset, marker.endOffset).toString()
                PsiDocumentManager.getInstance(project).commitDocument(document)
            }
        }
        return result
    }

    private fun addRequired(name: String): String {
        if (name in existing) return "Binding already exists"
        val offset = marker.endOffset - if (expected.endsWith("/>")) 2 else 1
        document.insertString(offset, " [$name]=$quote$quote")
        existing += name
        moveCaret(offset + name.length + 5)
        return "Added [$name]; enter its expression · Undo available"
    }

    @Suppress("ReturnCount")
    private fun renameBinding(edit: TaigaDocumentationTemplateEdit): String {
        val replacement = edit.replacement ?: return "No replacement is known"
        if (replacement in existing ||
            !PUBLIC_BINDING_NAME.matches(replacement)
        ) {
            return "Replacement binding already exists"
        }
        val (original, range) =
            names.entries.firstOrNull { it.key.bindingName == edit.name }
                ?: return "Binding no longer exists"
        if (original.rawName.startsWith("[(") ||
            !matches(range, original.text)
        ) {
            return "Binding changed; reopen its card"
        }
        val rewritten = original.text.replace(edit.name, replacement)
        val offset = range.startOffset
        document.replaceString(offset, range.endOffset, rewritten)
        range.dispose()
        names[original] = document.createRangeMarker(offset, offset + rewritten.length)
        existing -= edit.name
        existing += replacement
        return "Replaced ${edit.name} with $replacement · Undo available"
    }

    private fun matches(
        range: RangeMarker,
        text: String,
    ): Boolean =
        range.isValid && document.charsSequence.subSequence(range.startOffset, range.endOffset).toString() == text

    override fun dispose() {
        marker.dispose()
        names.values.forEach(RangeMarker::dispose)
    }
}

private val PUBLIC_BINDING_NAME = Regex("[A-Za-z_$][\\w$-]*")
private const val MAX_OPENING_TAG = 8_000
