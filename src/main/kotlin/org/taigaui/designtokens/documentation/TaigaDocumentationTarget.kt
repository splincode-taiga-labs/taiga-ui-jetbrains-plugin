package org.taigaui.designtokens.documentation

internal enum class TaigaApiMemberKind {
    INPUT,
    OUTPUT,
}

internal sealed interface TaigaDocumentationRequest {
    val startOffset: Int
    val endOffset: Int
    val usage: String?

    data class Entity(
        val subjects: List<TaigaDocumentationSubject>,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String? = null,
        val typeDefinition: String? = null,
    ) : TaigaDocumentationRequest

    data class Member(
        val owners: List<TaigaDocumentationSubject>,
        val name: String,
        val kind: TaigaApiMemberKind,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String? = null,
    ) : TaigaDocumentationRequest
}

internal sealed interface TaigaResolvedDocumentation {
    val entity: TaigaEntityDoc
    val subject: TaigaDocumentationSubject
    val startOffset: Int
    val endOffset: Int
    val usage: String?

    data class Entity(
        override val entity: TaigaEntityDoc,
        override val subject: TaigaDocumentationSubject,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String?,
        val typeDefinition: String?,
    ) : TaigaResolvedDocumentation

    data class Member(
        override val entity: TaigaEntityDoc,
        override val subject: TaigaDocumentationSubject,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String?,
        val property: TaigaApiProperty,
        val kind: TaigaApiMemberKind,
    ) : TaigaResolvedDocumentation
}

internal fun TaigaDocsSnapshot.resolve(request: TaigaDocumentationRequest): TaigaResolvedDocumentation? =
    when (request) {
        is TaigaDocumentationRequest.Entity ->
            request.subjects.firstNotNullOfOrNull { subject ->
                find(subject)?.let { entity ->
                    TaigaResolvedDocumentation.Entity(
                        entity = entity,
                        subject = subject.completedFrom(entity),
                        startOffset = request.startOffset,
                        endOffset = request.endOffset,
                        usage = request.usage,
                        typeDefinition = request.typeDefinition,
                    )
                }
            }

        is TaigaDocumentationRequest.Member ->
            request.owners.firstNotNullOfOrNull { owner ->
                val entity = find(owner) ?: return@firstNotNullOfOrNull null
                val property =
                    when (request.kind) {
                        TaigaApiMemberKind.INPUT -> entity.inputs
                        TaigaApiMemberKind.OUTPUT -> entity.outputs
                    }.firstOrNull { property -> property.name == request.name }
                        ?: return@firstNotNullOfOrNull null

                TaigaResolvedDocumentation.Member(
                    entity = entity,
                    subject = owner.completedFrom(entity),
                    startOffset = request.startOffset,
                    endOffset = request.endOffset,
                    usage = request.usage,
                    property = property,
                    kind = request.kind,
                )
            }
    }

internal fun TaigaDocsSnapshot.find(subject: TaigaDocumentationSubject): TaigaEntityDoc? =
    buildList {
        subject.publicSymbol?.let { symbol ->
            addAll(findByPublicSymbol(symbol))
        }
        subject.selector?.let { selector ->
            addAll(findBySelector(selector))
        }
    }.distinctBy(TaigaEntityDoc::sectionId)
        .firstOrNull { entity ->
            subject.packageName == null ||
                entity.packageNames.isEmpty() ||
                subject.packageName in entity.packageNames
        }

internal fun TaigaDocumentationSubject.completedFrom(entity: TaigaEntityDoc): TaigaDocumentationSubject =
    copy(
        publicSymbol = publicSymbol ?: entity.publicSymbols.singleOrNull(),
        packageName = packageName ?: entity.packageNames.singleOrNull(),
    )

internal val TaigaResolvedDocumentation.documentationUri: java.net.URI
    get() = entity.documentationUri

internal val TaigaResolvedDocumentation.presentationName: String
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> subject.publicSymbol ?: entity.publicSymbols.firstOrNull() ?: entity.title
            is TaigaResolvedDocumentation.Member -> property.name
        }

internal val TaigaResolvedDocumentation.packageName: String?
    get() = subject.packageName ?: entity.packageNames.singleOrNull()

internal val TaigaResolvedDocumentation.badge: String
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Member ->
                when (kind) {
                    TaigaApiMemberKind.INPUT -> "Input"
                    TaigaApiMemberKind.OUTPUT -> "Output"
                }

            is TaigaResolvedDocumentation.Entity ->
                when {
                    entity.kind == TaigaDocKind.TYPE -> "Type"
                    subject.selector?.startsWith("tui-") == true -> "Component"
                    subject.selector != null -> "Directive"
                    else -> entity.kind.displayName()
                }
        }

internal val TaigaResolvedDocumentation.description: String?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> entity.description
            is TaigaResolvedDocumentation.Member -> property.description
        }

internal fun TaigaDocKind.displayName(): String =
    name
        .lowercase()
        .replaceFirstChar(Char::uppercase)
