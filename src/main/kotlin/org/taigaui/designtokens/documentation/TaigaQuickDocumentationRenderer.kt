package org.taigaui.designtokens.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil

internal object TaigaQuickDocumentationRenderer {
    fun render(
        entity: TaigaEntityDoc,
        subject: TaigaDocumentationSubject,
    ): String =
        buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append("<b>")
            append((subject.publicSymbol ?: entity.displaySymbol()).html())
            append("</b>")

            (subject.packageName ?: entity.packageNames.singleOrNull())?.let { packageName ->
                append("<br><code>")
                append(packageName.html())
                append("</code>")
            }

            append(DocumentationMarkup.DEFINITION_END)

            entity.description?.takeIf(String::isNotBlank)?.let { description ->
                append(DocumentationMarkup.CONTENT_START)
                append(description.html())
                append(DocumentationMarkup.CONTENT_END)
            }

            append(DocumentationMarkup.SECTIONS_START)

            (subject.selector ?: entity.selectors.singleOrNull())?.let { selector ->
                addSection("Selector:", "<code>${selector.html()}</code>")
            }

            subject.canonicalImport()?.let { statement ->
                addSection("Import:", "<code>${statement.html()}</code>")
            }

            addApiSection("Inputs:", entity.inputs)
            addApiSection("Outputs:", entity.outputs)

            entity.example
                ?.code
                ?.takeIf(::isCompactExample)
                ?.let { code ->
                    val content =
                        if ('\n' in code) {
                            "<pre><code>${code.html()}</code></pre>"
                        } else {
                            "<code>${code.html()}</code>"
                        }

                    addSection("Example:", content)
                }

            append(DocumentationMarkup.SECTIONS_END)
        }

    private fun StringBuilder.addApiSection(
        title: String,
        properties: List<TaigaApiProperty>,
    ) {
        if (properties.isEmpty()) {
            return
        }

        val visible = properties.take(MAX_VISIBLE_API_PROPERTIES)
        val remaining = properties.size - visible.size
        val content =
            buildString {
                append(
                    visible.joinToString(",&nbsp; ") { property ->
                        buildString {
                            append("<code>")
                            append(property.name.html())
                            property.documentedType?.let { type ->
                                append(": ")
                                append(type.html())
                            }
                            append("</code>")
                        }
                    },
                )

                if (remaining > 0) {
                    append("&nbsp; <span style='color:gray'>+$remaining more</span>")
                }
            }

        addSection(title, content)
    }

    private fun StringBuilder.addSection(
        title: String,
        content: String,
    ) {
        if (content.isBlank()) {
            return
        }

        append(DocumentationMarkup.SECTION_HEADER_START)
        append(title.html())
        append(DocumentationMarkup.SECTION_SEPARATOR)
        append(content)
        append(DocumentationMarkup.SECTION_END)
    }

    private fun TaigaEntityDoc.displaySymbol(): String = publicSymbols.firstOrNull() ?: title

    private fun TaigaDocumentationSubject.canonicalImport(): String? {
        val symbol = publicSymbol
        val resolvedPackageName = packageName

        return if (symbol != null && resolvedPackageName != null) {
            "import {$symbol} from '$resolvedPackageName';"
        } else {
            null
        }
    }

    private fun isCompactExample(code: String): Boolean =
        code.length <= MAX_EXAMPLE_LENGTH &&
            code.lineSequence().take(MAX_EXAMPLE_LINES + 1).count() <= MAX_EXAMPLE_LINES

    private fun String.html(): String = StringUtil.escapeXmlEntities(this)

    private const val MAX_VISIBLE_API_PROPERTIES = 5
    private const val MAX_EXAMPLE_LENGTH = 220
    private const val MAX_EXAMPLE_LINES = 3
}
