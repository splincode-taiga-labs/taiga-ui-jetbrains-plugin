package org.taigaui.designtokens.documentation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import com.intellij.polySymbols.PolySymbol
import com.intellij.polySymbols.documentation.PolySymbolDocumentation
import com.intellij.polySymbols.documentation.PolySymbolDocumentationCustomizer
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import java.nio.file.Path

internal class TaigaQuickDocumentationCustomizer : PolySymbolDocumentationCustomizer {
    override fun customize(
        symbol: PolySymbol,
        location: PsiElement?,
        documentation: PolySymbolDocumentation,
    ): PolySymbolDocumentation {
        val subject = TaigaTemplateDocumentationResolver.find(symbol) ?: return documentation
        val contextFile = location?.containingFile ?: symbol.psiContext?.containingFile ?: return documentation
        val sourceFile = contextFile.sourcePath() ?: return documentation
        val service = contextFile.project.service<TaigaDocsService>()
        val snapshot = service.cachedSnapshotFor(sourceFile)

        if (snapshot == null) {
            service.warmUp(sourceFile)

            return documentation
        }

        val entity = snapshot.find(subject) ?: return documentation
        val completedSubject = subject.completedFrom(entity)
        val packageName = completedSubject.packageName ?: entity.packageNames.singleOrNull()
        val displayName = completedSubject.presentationName

        return documentation
            .withName(displayName)
            .withDefinition(displayName.html())
            .withDefinitionDetails(null)
            .withHeader(packageName?.let { "<code>${it.html()}</code>" })
            .withLibrary(null)
            .withDescription(
                entity.description
                    ?.takeIf(String::isNotBlank)
                    ?.html()
                    ?: documentation.description,
            ).withDescriptionSections(entity.quickDocumentationSections(completedSubject))
            .withFootnote(null)
            .withDocUrl(entity.documentationUri.toString())
    }
}

internal fun PsiFile.sourcePath(): Path? =
    InjectedLanguageManager
        .getInstance(project)
        .getTopLevelFile(this)
        .virtualFile
        ?.path
        ?.let { path -> runCatching { Path.of(path) }.getOrNull() }

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

private fun TaigaEntityDoc.quickDocumentationSections(
    subject: TaigaDocumentationSubject,
): Map<String, String> =
    buildMap {
        (subject.selector ?: selectors.singleOrNull())?.let { selector ->
            put("Selector", "<code>${selector.html()}</code>")
        }

        inputs.takeIf(List<TaigaApiProperty>::isNotEmpty)?.let { properties ->
            put("Inputs", properties.toApiHtml())
        }

        outputs.takeIf(List<TaigaApiProperty>::isNotEmpty)?.let { properties ->
            put("Outputs", properties.toApiHtml())
        }

        example
            ?.code
            ?.takeIf(::isCompactExample)
            ?.let { code ->
                put("Example", "<pre><code>${code.html()}</code></pre>")
            }
    }

private fun List<TaigaApiProperty>.toApiHtml(): String =
    joinToString("<br>") { property ->
        buildString {
            append("<code>")
            append(property.name.html())
            append("</code>")

            property.documentedType?.let { type ->
                append(": <code>")
                append(type.html())
                append("</code>")
            }
        }
    }

private fun isCompactExample(code: String): Boolean =
    code.length <= MAX_EXAMPLE_LENGTH &&
        code.lineSequence().take(MAX_EXAMPLE_LINES + 1).count() <= MAX_EXAMPLE_LINES

private fun String.html(): String = StringUtil.escapeXmlEntities(this)

private const val MAX_EXAMPLE_LENGTH = 240
private const val MAX_EXAMPLE_LINES = 3
