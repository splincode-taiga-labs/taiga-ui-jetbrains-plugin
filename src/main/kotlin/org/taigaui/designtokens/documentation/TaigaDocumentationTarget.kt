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

internal fun TaigaDocsSnapshot.resolve(request: TaigaDocumentationRequest): TaigaResolvedDocumentation? =
    resolveDocumentation(request, this)

/** Installed API can produce a complete target while enrichment is unavailable or still loading. */
internal fun resolveDocumentation(
    request: TaigaDocumentationRequest,
    snapshot: TaigaDocsSnapshot? = null,
): TaigaResolvedDocumentation? =
    when (request) {
        is TaigaDocumentationRequest.Entity ->
            request.subjects.firstNotNullOfOrNull { subject ->
                subject.documentationEntity(snapshot)?.let { entity ->
                    TaigaResolvedDocumentation.Entity(
                        entity = entity,
                        subject = subject.completedFrom(entity),
                        startOffset = request.startOffset,
                        endOffset = request.endOffset,
                        usage = request.usage,
                        typeDefinition = request.typeDefinition,
                        bindings = request.bindings,
                        element = request.element,
                        contextSubjects = request.contextSubjects,
                        icons =
                            request.icons.filter { icon ->
                                entity.inputs.any { property ->
                                    property.name ==
                                        icon.attribute
                                }
                            },
                    )
                }
            }

        is TaigaDocumentationRequest.Member -> {
            val matches = request.owners.mapNotNull { owner ->
                val entity = owner.documentationEntity(snapshot) ?: return@mapNotNull null
                val property =
                    when (request.kind) {
                        TaigaApiMemberKind.INPUT -> entity.inputs
                        TaigaApiMemberKind.OUTPUT -> entity.outputs
                    }.firstOrNull { property -> property.name == request.name }
                        ?: return@mapNotNull null
                val localMember = owner.localDocumentation.members.firstOrNull { it.name == request.name && it.kind == request.kind }

                TaigaResolvedDocumentation.Member(
                    entity = entity,
                    subject = owner.completedFrom(entity),
                    startOffset = request.startOffset,
                    endOffset = request.endOffset,
                    usage = request.usage,
                    property = property,
                    kind = request.kind,
                    declaration = localMember?.declaration ?: request.declaration?.takeIf { subject -> subject.packageName != null },
                    binding = request.binding,
                    element = request.element,
                    localMember = localMember,
                    icons = request.icons,
                    bindings = request.bindings,
                    contextSubjects = request.owners,
                )
            }
            val preferred = matches.firstOrNull { it.subject.packageName != null } ?: matches.firstOrNull()
            preferred?.copy(receivers = matches.takeIf { it.size > 1 }.orEmpty())
        }
    }

private fun TaigaDocumentationSubject.documentationEntity(snapshot: TaigaDocsSnapshot?): TaigaEntityDoc? {
    val documented = snapshot?.find(this)
    val local = localDocumentation
    if (!local.angularResolved && documented != null) return documented
    if ((!local.angularResolved && packageName == null) || (local.source == null && documented == null)) return null
    val kind = local.kind ?: if (local.pipe != null) TaigaDocKind.PIPE else TaigaDocKind.CLASS
    val base = documented ?: TaigaEntityDoc(
        sectionId = "local/$presentationName",
        title = presentationName,
        packageNames = listOfNotNull(packageName).toSet(),
        kind = kind,
        version = null,
        description = null,
        publicSymbols = listOfNotNull(publicSymbol).toSet(),
        selectors = listOfNotNull(local.selector).toSet(),
        inputs = emptyList(),
        outputs = emptyList(),
        example = null,
        documentationUri = java.net.URI.create("https://taiga-ui.dev"),
    )
    if (!local.angularResolved) return base
    fun properties(memberKind: TaigaApiMemberKind): List<TaigaApiProperty> =
        local.members.filter { it.kind == memberKind }.map { member ->
            val old = (if (memberKind == TaigaApiMemberKind.INPUT) base.inputs else base.outputs).firstOrNull { it.name == member.name }
            TaigaApiProperty(member.name, if (memberKind == TaigaApiMemberKind.INPUT) "[${member.name}]" else "(${member.name})", member.type, member.description ?: old?.description)
        }
    return base.copy(kind = kind, inputs = properties(TaigaApiMemberKind.INPUT), outputs = properties(TaigaApiMemberKind.OUTPUT))
}

internal fun TaigaResolvedDocumentation.Member.ownerDocumentation(): TaigaResolvedDocumentation.Entity =
    TaigaResolvedDocumentation.Entity(entity, subject, startOffset, endOffset, null, null,
        icons = icons.filter { icon -> entity.inputs.any { it.name == icon.attribute } },
        bindings = bindings, element = element, contextSubjects = contextSubjects)

internal fun TaigaResolvedDocumentation.Entity.focusedMember(
    property: TaigaApiProperty,
    kind: TaigaApiMemberKind,
): TaigaResolvedDocumentation.Member {
    val binding = bindings.firstOrNull { it.name == property.name }
    val localMember = subject.localDocumentation.members.firstOrNull { it.name == property.name && it.kind == kind }
    val base = TaigaResolvedDocumentation.Member(entity, subject, startOffset, endOffset, null, property, kind,
        declaration = localMember?.declaration ?: binding?.declaration, binding = binding, element = element, localMember = localMember,
        icons = icons, bindings = bindings, contextSubjects = contextSubjects)
    if (binding == null || contextSubjects.isEmpty()) return base
    val request = TaigaDocumentationRequest.Member(contextSubjects, property.name, kind, startOffset, endOffset, binding = binding, element = element,
        icons = icons, bindings = bindings)
    val resolved = resolveDocumentation(request) as? TaigaResolvedDocumentation.Member ?: return base
    return base.copy(receivers = resolved.receivers.map { if (it.subject.presentationName == subject.presentationName) base else it })
}

internal val TaigaResolvedDocumentation.documentationIcons: List<TaigaDocumentationIcon>
    get() = when (this) {
        is TaigaResolvedDocumentation.Entity -> icons
        is TaigaResolvedDocumentation.Member -> icons.filter { it.attribute == property.name }
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
                (receivers.ifEmpty { listOf(this) }).joinToString(", ") { it.subject.publicSymbol ?: it.entity.publicSymbols.firstOrNull() ?: it.entity.title }
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
