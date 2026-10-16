package org.taigaui.designtokens.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil

internal object TaigaQuickDocumentationRenderer {
    fun render(entity: TaigaEntityDoc): String =
        buildString {
            append(DocumentationMarkup.DEFINITION_START)
            append("<b>")
            append(entity.displaySymbol().html())
            append("</b>")

            entity.packageNames.sorted().takeIf { packages -> packages.isNotEmpty() }?.let { packages ->
                append("<br><code>")
                append(packages.joinToString(", ").html())
                append("</code>")
            }

            append(DocumentationMarkup.DEFINITION_END)

            entity.description?.takeIf(String::isNotBlank)?.let { description ->
                append(DocumentationMarkup.CONTENT_START)
                append(description.html())
                append(DocumentationMarkup.CONTENT_END)
            }

            append(DocumentationMarkup.SECTIONS_START)

            addSection(
                title = "Selector:",
                content =
                    entity.selectors
                        .sorted()
                        .joinToString(", ") { selector ->
                            "<code>${selector.html()}</code>"
                        },
            )

            entity.canonicalImport()?.let { statement ->
                addSection(
                    title = "Import:",
                    content = "<code>${statement.html()}</code>",
                )
            }

            addApiSection("Inputs:", entity.inputs)
            addApiSection("Outputs:", entity.outputs)

            entity.example?.let { example ->
                addSection(
                    title = "Example:",
                    content = "<pre><code>${example.code.html()}</code></pre>",
                )
            }

            addSection(
                title = "Documentation:",
                content =
                    "<a href=\"${entity.documentationUri.toString().html()}\">" +
                        "Open full Taiga UI documentation</a>",
            )

            append(DocumentationMarkup.SECTIONS_END)
        }

    private fun StringBuilder.addApiSection(
        title: String,
        properties: List<TaigaApiProperty>,
    ) {
        if (properties.isEmpty()) {
            return
        }

        addSection(
            title = title,
            content =
                properties.joinToString("<br>") { property ->
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
                },
        )
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
        append("<p>")
        append(content)
        append(DocumentationMarkup.SECTION_END)
    }

    private fun TaigaEntityDoc.displaySymbol(): String = publicSymbols.firstOrNull() ?: title

    private fun TaigaEntityDoc.canonicalImport(): String? {
        val symbol = publicSymbols.firstOrNull()
        val packageName = packageNames.singleOrNull()

        return if (symbol != null && packageName != null) {
            "import {$symbol} from '$packageName';"
        } else {
            null
        }
    }

    private fun String.html(): String = StringUtil.escapeXmlEntities(this)
}
