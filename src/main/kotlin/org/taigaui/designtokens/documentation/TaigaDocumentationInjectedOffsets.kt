package org.taigaui.designtokens.documentation

/** Host editor adapters receive host offsets, while all installed declaration offsets stay untouched. */
internal fun TaigaDocumentationRequest.mapTemplateOffsets(map: (Int) -> Int): TaigaDocumentationRequest =
    when (this) {
        is TaigaDocumentationRequest.Entity -> copy(
            startOffset = map(startOffset), endOffset = map(endOffset),
            bindings = bindings.map { it.mapOffsets(map) },
            icons = icons.map { it.mapOffsets(map) },
            element = element?.mapOffsets(map),
        )
        is TaigaDocumentationRequest.Member -> copy(
            startOffset = map(startOffset), endOffset = map(endOffset),
            binding = binding?.mapOffsets(map), bindings = bindings.map { it.mapOffsets(map) },
            icons = icons.map { it.mapOffsets(map) }, element = element?.mapOffsets(map),
        )
    }

private fun TaigaDocumentationBinding.mapOffsets(map: (Int) -> Int): TaigaDocumentationBinding = copy(
    startOffset = map(startOffset), endOffset = map(endOffset),
    context = context.map { it.copy(startOffset = map(it.startOffset), endOffset = map(it.endOffset)) },
)

private fun TaigaDocumentationIcon.mapOffsets(map: (Int) -> Int): TaigaDocumentationIcon = copy(startOffset = map(startOffset), endOffset = map(endOffset))

private fun TaigaDocumentationElement.mapOffsets(map: (Int) -> Int): TaigaDocumentationElement = copy(
    startOffset = map(startOffset), endOffset = map(endOffset),
    attributes = attributes.map { it.copy(startOffset = map(it.startOffset), endOffset = map(it.endOffset)) },
)
