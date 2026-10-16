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
        val resolvedOwner = resolveDocumentation(target.ownerRequest(subject, subjects), snapshot)
        val owner = resolvedOwner as? TaigaResolvedDocumentation.Entity ?: return null
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

private fun TaigaResolvedDocumentation.ownerRequest(
    subject: TaigaDocumentationSubject,
    context: List<TaigaDocumentationSubject>,
): TaigaDocumentationRequest.Entity =
    TaigaDocumentationRequest.Entity(
        subjects = listOf(subject),
        startOffset = startOffset,
        endOffset = endOffset,
        bindings =
            when (this) {
                is TaigaResolvedDocumentation.Entity -> bindings
                is TaigaResolvedDocumentation.Member -> bindings
            },
        element = templateElement,
        contextSubjects = context,
        icons =
            when (this) {
                is TaigaResolvedDocumentation.Entity -> icons
                is TaigaResolvedDocumentation.Member -> icons
            },
    )
