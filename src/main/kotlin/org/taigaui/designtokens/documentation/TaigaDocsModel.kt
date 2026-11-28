package org.taigaui.designtokens.documentation

import java.net.URI
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

internal enum class TaigaDocKind {
    COMPONENT,
    DIRECTIVE,
    PIPE,
    SERVICE,
    TYPE,
    TOKEN,
    UTILITY,
    CLASS,
    UNKNOWN,
    ;

    companion object {
        fun from(
            route: String,
            title: String,
            metadataType: String?,
        ): TaigaDocKind =
            if (title.equals("tokens", ignoreCase = true)) {
                TOKEN
            } else {
                BY_ROUTE[route] ?: metadataType?.let(BY_ROUTE::get) ?: UNKNOWN
            }

        private val BY_ROUTE =
            mapOf(
                "components" to COMPONENT,
                "directives" to DIRECTIVE,
                "pipes" to PIPE,
                "services" to SERVICE,
                "types" to TYPE,
                "tokens" to TOKEN,
                "utils" to UTILITY,
                "classes" to CLASS,
            )
    }
}

internal data class TaigaApiProperty(
    val name: String,
    val signature: String,
    val documentedType: String?,
    val description: String?,
)

internal data class TaigaExample(
    val language: String?,
    val code: String,
)

internal data class TaigaEntityDoc(
    val sectionId: String,
    val title: String,
    val packageNames: Set<String>,
    val kind: TaigaDocKind,
    val version: String?,
    val description: String?,
    val publicSymbols: Set<String>,
    val selectors: Set<String>,
    val inputs: List<TaigaApiProperty>,
    val outputs: List<TaigaApiProperty>,
    val example: TaigaExample?,
    val documentationUri: URI,
)

internal data class TaigaDocsProvenance(
    val origin: TaigaDocsLoadOrigin,
    val retrievedAt: Instant? = null,
    val sourceRevision: String? = null,
    val bundledDate: LocalDate? = null,
) {
    val isStale: Boolean?
        get() = retrievedAt?.isBefore(Instant.now().minus(Duration.ofHours(6)))
}

internal class TaigaDocsIndex(
    val source: TaigaDocsSource,
    entities: Collection<TaigaEntityDoc>,
    val provenance: TaigaDocsProvenance? = null,
    val guides: Map<TaigaDocsGuide, String> = emptyMap(),
) {
    val entities: List<TaigaEntityDoc> = entities.toList()
    private val byPublicSymbol: Map<String, List<TaigaEntityDoc>> =
        this.entities
            .flatMap { entity -> entity.publicSymbols.map { symbol -> symbol to entity } }
            .groupBy(
                keySelector = Pair<String, TaigaEntityDoc>::first,
                valueTransform = Pair<String, TaigaEntityDoc>::second,
            )
    private val bySelector: Map<String, List<TaigaEntityDoc>> =
        this.entities
            .flatMap { entity -> entity.selectors.map { selector -> selector to entity } }
            .groupBy(
                keySelector = Pair<String, TaigaEntityDoc>::first,
                valueTransform = Pair<String, TaigaEntityDoc>::second,
            )
    private val bySectionId: Map<String, TaigaEntityDoc> =
        this.entities.associateBy { entity -> entity.sectionId.lowercase() }

    fun findByPublicSymbol(symbol: String): List<TaigaEntityDoc> = byPublicSymbol[symbol].orEmpty()

    fun findBySelector(selector: String): List<TaigaEntityDoc> = bySelector[selector].orEmpty()

    fun findBySectionId(sectionId: String): TaigaEntityDoc? = bySectionId[sectionId.lowercase()]
}

internal enum class TaigaDocsGuide(
    val heading: String,
) {
    GENERATION_CHECKLIST("# Code Generation Checklist"),
    COMMON_MISTAKES("# Common Mistakes"),
    MIGRATION("# Migration Guide"),
}
