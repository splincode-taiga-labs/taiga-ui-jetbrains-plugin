package org.taigaui.designtokens.documentation

internal fun TaigaDocsSnapshot.resolve(request: TaigaDocumentationRequest): TaigaResolvedDocumentation? =
    resolveDocumentation(request, this)

/** Installed API can produce a complete target while enrichment is unavailable or still loading. */
internal fun resolveDocumentation(
    request: TaigaDocumentationRequest,
    snapshot: TaigaDocsSnapshot? = null,
): TaigaResolvedDocumentation? =
    when (request) {
        is TaigaDocumentationRequest.Entity -> request.resolveEntity(snapshot)
        is TaigaDocumentationRequest.Member -> request.resolveMember(snapshot)
    }

private fun TaigaDocumentationRequest.Entity.resolveEntity(
    snapshot: TaigaDocsSnapshot?,
): TaigaResolvedDocumentation.Entity? =
    subjects.firstNotNullOfOrNull { subject ->
        subject.documentationEntity(snapshot)?.let { entity ->
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject = subject.completedFrom(entity),
                startOffset = startOffset,
                endOffset = endOffset,
                usage = usage,
                typeDefinition = typeDefinition,
                bindings = bindings,
                element = element,
                contextSubjects = contextSubjects,
                icons = icons.filter { icon -> entity.inputs.any { it.name == icon.attribute } },
            )
        }
    }

private fun TaigaDocumentationRequest.Member.resolveMember(
    snapshot: TaigaDocsSnapshot?,
): TaigaResolvedDocumentation.Member? {
    val matches =
        owners.mapNotNull { owner ->
            val entity = owner.documentationEntity(snapshot) ?: return@mapNotNull null
            val properties = if (kind == TaigaApiMemberKind.INPUT) entity.inputs else entity.outputs
            val property = properties.firstOrNull { it.name == name } ?: return@mapNotNull null
            val localMember = owner.localDocumentation.members.firstOrNull { it.name == name && it.kind == kind }
            TaigaResolvedDocumentation.Member(
                entity = entity,
                subject = owner.completedFrom(entity),
                startOffset = startOffset,
                endOffset = endOffset,
                usage = usage,
                property = property,
                kind = kind,
                declaration = localMember?.declaration ?: declaration?.takeIf { it.packageName != null },
                binding = binding,
                element = element,
                localMember = localMember,
                icons = icons,
                bindings = bindings,
                contextSubjects = owners,
            )
        }
    val preferred = matches.firstOrNull { it.subject.packageName != null } ?: matches.firstOrNull()
    return preferred?.copy(receivers = matches.takeIf { it.size > 1 }.orEmpty())
}

@Suppress("ReturnCount")
private fun TaigaDocumentationSubject.documentationEntity(snapshot: TaigaDocsSnapshot?): TaigaEntityDoc? {
    val documented = snapshot?.find(this)
    val local = localDocumentation
    if (!local.angularResolved && documented != null) return documented
    if (!local.angularResolved && packageName == null) return null
    if (local.source == null && documented == null) return null
    val kind = local.kind ?: if (local.pipe != null) TaigaDocKind.PIPE else TaigaDocKind.CLASS
    val base =
        documented ?: TaigaEntityDoc(
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

    return base.copy(
        kind = kind,
        inputs = base.installedProperties(local, TaigaApiMemberKind.INPUT),
        outputs = base.installedProperties(local, TaigaApiMemberKind.OUTPUT),
    )
}

private fun TaigaEntityDoc.installedProperties(
    local: TaigaLocalDocumentation,
    kind: TaigaApiMemberKind,
): List<TaigaApiProperty> {
    val documented = if (kind == TaigaApiMemberKind.INPUT) inputs else outputs
    return local.members.filter { it.kind == kind }.map { member ->
        val old = documented.firstOrNull { it.name == member.name }
        val signature = if (kind == TaigaApiMemberKind.INPUT) "[${member.name}]" else "(${member.name})"
        TaigaApiProperty(member.name, signature, member.type, member.description ?: old?.description)
    }
}

internal fun TaigaResolvedDocumentation.Member.ownerDocumentation(): TaigaResolvedDocumentation.Entity =
    TaigaResolvedDocumentation.Entity(
        entity,
        subject,
        startOffset,
        endOffset,
        null,
        null,
        icons = icons.filter { icon -> entity.inputs.any { it.name == icon.attribute } },
        bindings = bindings,
        element = element,
        contextSubjects = contextSubjects,
    )

@Suppress("ReturnCount")
internal fun TaigaResolvedDocumentation.Entity.focusedMember(
    property: TaigaApiProperty,
    kind: TaigaApiMemberKind,
): TaigaResolvedDocumentation.Member {
    val binding = bindings.firstOrNull { kind == TaigaApiMemberKind.INPUT && it.name == property.name }
    val localMember = subject.localDocumentation.members.firstOrNull { it.name == property.name && it.kind == kind }
    val base =
        TaigaResolvedDocumentation.Member(
            entity,
            subject,
            startOffset,
            endOffset,
            null,
            property,
            kind,
            declaration = localMember?.declaration ?: binding?.declaration,
            binding = binding,
            element = element,
            localMember = localMember,
            icons = icons,
            bindings = bindings,
            contextSubjects = contextSubjects,
        )
    val existing =
        element?.attributes?.any { attribute ->
            attribute.bindingName == property.name &&
                (kind == TaigaApiMemberKind.OUTPUT) ==
                (attribute.rawName.startsWith('(') || attribute.rawName.startsWith("on-"))
        } == true
    if ((binding == null && !existing) || contextSubjects.isEmpty()) return base
    val request =
        TaigaDocumentationRequest.Member(
            contextSubjects,
            property.name,
            kind,
            startOffset,
            endOffset,
            binding = binding,
            element = element,
            icons = icons,
            bindings = bindings,
        )
    val resolved = resolveDocumentation(request) as? TaigaResolvedDocumentation.Member ?: return base
    return base.copy(
        receivers =
            resolved.receivers.map {
                if (it.subject.presentationName ==
                    subject.presentationName
                ) {
                    base
                } else {
                    it
                }
            },
    )
}
