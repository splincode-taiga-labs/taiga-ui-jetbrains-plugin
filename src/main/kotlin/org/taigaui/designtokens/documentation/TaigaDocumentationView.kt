package org.taigaui.designtokens.documentation

import java.awt.Point

/** Presentation state is separate from the installed API snapshot replaced by Refresh. */
internal data class TaigaDocumentationView(
    val resolved: TaigaResolvedDocumentation,
    val showExample: Boolean = false,
    val fullApi: Boolean = false,
    val query: String = "",
    val scrollPosition: Point = Point(),
    val selectedMember: String? = null,
) {
    @Suppress("ReturnCount")
    fun refreshed(
        target: TaigaResolvedDocumentation,
        snapshot: TaigaDocsSnapshot?,
    ): TaigaDocumentationView? {
        val subjects =
            when (target) {
                is TaigaResolvedDocumentation.Entity -> target.contextSubjects
                is TaigaResolvedDocumentation.Member -> target.contextSubjects
            }.ifEmpty { listOf(target.subject) }
        val subject =
            subjects.firstOrNull {
                it.publicSymbol == resolved.subject.publicSymbol &&
                    it.packageName == resolved.subject.packageName &&
                    it.presentationName == resolved.subject.presentationName
            } ?: return null
        val bindings =
            when (target) {
                is TaigaResolvedDocumentation.Entity -> target.bindings
                is TaigaResolvedDocumentation.Member -> target.bindings
            }
        val owner =
            resolveDocumentation(
                TaigaDocumentationRequest.Entity(
                    subjects = listOf(subject),
                    startOffset = target.startOffset,
                    endOffset = target.endOffset,
                    bindings = bindings,
                    element = target.templateElement,
                    contextSubjects = subjects,
                    icons =
                        when (target) {
                            is TaigaResolvedDocumentation.Entity -> target.icons
                            is TaigaResolvedDocumentation.Member -> target.icons
                        },
                ),
                snapshot,
            ) as? TaigaResolvedDocumentation.Entity ?: return null
        val refreshed =
            when (val previous = resolved) {
                is TaigaResolvedDocumentation.Entity -> owner
                is TaigaResolvedDocumentation.Member -> {
                    val properties =
                        if (previous.kind == TaigaApiMemberKind.INPUT) owner.entity.inputs else owner.entity.outputs
                    val property = properties.firstOrNull { it.name == previous.property.name } ?: return null
                    owner.focusedMember(property, previous.kind)
                }
            }
        return copy(resolved = refreshed)
    }
}
