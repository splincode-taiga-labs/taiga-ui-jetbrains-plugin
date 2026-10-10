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
        val icons: List<TaigaDocumentationIcon> = emptyList(),
        val bindings: List<TaigaDocumentationBinding> = emptyList(),
        val element: TaigaDocumentationElement? = null,
        val contextSubjects: List<TaigaDocumentationSubject> = emptyList(),
    ) : TaigaDocumentationRequest

    data class Member(
        val owners: List<TaigaDocumentationSubject>,
        val name: String,
        val kind: TaigaApiMemberKind,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String? = null,
        val declaration: TaigaDocumentationSubject? = null,
        val binding: TaigaDocumentationBinding? = null,
        val element: TaigaDocumentationElement? = null,
        val icons: List<TaigaDocumentationIcon> = emptyList(),
        val bindings: List<TaigaDocumentationBinding> = emptyList(),
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
        val icons: List<TaigaDocumentationIcon> = emptyList(),
        val bindings: List<TaigaDocumentationBinding> = emptyList(),
        val element: TaigaDocumentationElement? = null,
        val contextSubjects: List<TaigaDocumentationSubject> = emptyList(),
    ) : TaigaResolvedDocumentation

    data class Member(
        override val entity: TaigaEntityDoc,
        override val subject: TaigaDocumentationSubject,
        override val startOffset: Int,
        override val endOffset: Int,
        override val usage: String?,
        val property: TaigaApiProperty,
        val kind: TaigaApiMemberKind,
        val declaration: TaigaDocumentationSubject? = null,
        val binding: TaigaDocumentationBinding? = null,
        val element: TaigaDocumentationElement? = null,
        val receivers: List<Member> = emptyList(),
        val localMember: TaigaLocalApiMember? = null,
        val icons: List<TaigaDocumentationIcon> = emptyList(),
        val bindings: List<TaigaDocumentationBinding> = emptyList(),
        val contextSubjects: List<TaigaDocumentationSubject> = emptyList(),
    ) : TaigaResolvedDocumentation
}

internal val TaigaResolvedDocumentation.documentationIcons: List<TaigaDocumentationIcon>
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> icons
            is TaigaResolvedDocumentation.Member ->
                icons.filter {
                    kind == TaigaApiMemberKind.INPUT &&
                        it.attribute == property.name
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
            is TaigaResolvedDocumentation.Entity ->
                subject.publicSymbol
                    ?: entity.publicSymbols.firstOrNull()
                    ?: entity.title
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
                    entity.kind == TaigaDocKind.PIPE -> "Pipe"
                    entity.kind == TaigaDocKind.TYPE -> "Type"
                    localDocumentation.angularResolved -> entity.kind.displayName()
                    subject.selector?.startsWith("tui-") == true -> "Component"
                    subject.selector != null -> "Directive"
                    entity.selectors.any { selector -> selector.startsWith("tui-") } -> "Component"
                    entity.selectors.isNotEmpty() -> "Directive"
                    else -> entity.kind.displayName()
                }
        }

internal val TaigaResolvedDocumentation.description: String?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> entity.description
            is TaigaResolvedDocumentation.Member -> property.description
        }

internal val TaigaResolvedDocumentation.ownerName: String?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> null
            is TaigaResolvedDocumentation.Member ->
                (receivers.ifEmpty { listOf(this) }).joinToString(", ") {
                    it.subject.publicSymbol
                        ?: it.entity.publicSymbols.firstOrNull()
                        ?: it.entity.title
                }
        }

internal val TaigaResolvedDocumentation.typeText: String?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity -> typeDefinition
            is TaigaResolvedDocumentation.Member ->
                localMember?.type ?: declaration?.localDocumentation?.inputTypes?.get(property.name)
                    ?: subject.localDocumentation.inputTypes[property.name]
                    ?: property.documentedType
        }

internal val TaigaResolvedDocumentation.effectiveUsage: String?
    get() =
        when (this) {
            is TaigaResolvedDocumentation.Entity ->
                entity.example
                    ?.code
                    ?.takeIf { code -> code.length <= MAX_INLINE_USAGE_LENGTH }

            is TaigaResolvedDocumentation.Member -> usage
        }

internal fun TaigaResolvedDocumentation.canonicalImport(): String? {
    if (this !is TaigaResolvedDocumentation.Entity) {
        return null
    }

    val symbol = subject.publicSymbol
    val resolvedPackageName = packageName

    return if (symbol != null && resolvedPackageName != null) {
        "import {$symbol} from '$resolvedPackageName';"
    } else {
        null
    }
}

internal fun TaigaResolvedDocumentation.possibleValues(): List<String> {
    val type = typeText ?: return emptyList()
    val values =
        STRING_LITERAL
            .findAll(type)
            .map { match -> match.groupValues[1] }
            .distinct()
            .toList()

    return values.takeIf { items -> items.size > 1 }.orEmpty()
}

internal fun TaigaResolvedDocumentation.relatedMembers(limit: Int = 4): List<String> =
    when (this) {
        is TaigaResolvedDocumentation.Entity -> emptyList()
        is TaigaResolvedDocumentation.Member ->
            (entity.inputs + entity.outputs)
                .asSequence()
                .map(TaigaApiProperty::name)
                .filter { name -> name != property.name }
                .filter { name -> property.description?.contains(Regex("\\b${Regex.escape(name)}\\b")) == true }
                .distinct()
                .take(limit)
                .toList()
    }

internal val TaigaResolvedDocumentation.source: TaigaDocumentationSource?
    get() =
        (this as? TaigaResolvedDocumentation.Member)?.declaration?.localDocumentation?.source
            ?: subject.localDocumentation.source

internal val TaigaResolvedDocumentation.localDocumentation: TaigaLocalDocumentation
    get() = subject.localDocumentation

internal fun TaigaDocKind.displayName(): String =
    name
        .lowercase()
        .replaceFirstChar { char -> char.uppercase() }

private val STRING_LITERAL = Regex("""['"]([^'"]+)['"]""")
private const val MAX_INLINE_USAGE_LENGTH = 260
