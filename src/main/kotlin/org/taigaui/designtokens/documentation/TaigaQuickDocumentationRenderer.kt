package org.taigaui.designtokens.documentation

import com.intellij.lang.documentation.DocumentationMarkup
import com.intellij.openapi.util.text.StringUtil

internal object TaigaQuickDocumentationRenderer {
    fun render(resolved: TaigaResolvedDocumentation): String =
        buildString {
            appendDefinition(resolved)

            resolved.description?.takeIf(String::isNotBlank)?.let { description ->
                append(DocumentationMarkup.CONTENT_START)
                append(description.html())
                append(DocumentationMarkup.CONTENT_END)
            }

            append(DocumentationMarkup.SECTIONS_START)

            when (resolved) {
                is TaigaResolvedDocumentation.Entity -> appendEntitySections(resolved)
                is TaigaResolvedDocumentation.Member -> appendMemberSections(resolved)
            }

            append(DocumentationMarkup.SECTIONS_END)
        }

    private fun StringBuilder.appendDefinition(resolved: TaigaResolvedDocumentation) {
        append(DocumentationMarkup.DEFINITION_START)
        append("<b>")
        append(resolved.presentationName.html())
        append("</b>")
        append("&nbsp;&nbsp;<i>")
        append(resolved.badge.html())
        append("</i>")

        val meta =
            listOfNotNull(
                resolved.ownerName?.let { owner -> "of $owner" },
                resolved.packageName,
            ).joinToString(" · ")

        if (meta.isNotBlank()) {
            append("<br><code>")
            append(meta.html())
            append("</code>")
        }

        append(DocumentationMarkup.DEFINITION_END)
    }

    private fun StringBuilder.appendEntitySections(resolved: TaigaResolvedDocumentation.Entity) {
        resolved.localDocumentation.selector?.let { selector ->
            addSection("Selector:", "<code>${selector.html()}</code>")
        }
        resolved.localDocumentation.pipe?.let { appendPipeSections(it) }
        resolved.typeText?.takeIf(String::isNotBlank)?.let { type ->
            addSection("Type:", "<code>${type.html()}</code>")
        }

        resolved.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
            addSection("Usage:", usage.asCodeBlock())
        }

        resolved.canonicalImport()?.let { statement ->
            addSection("Import:", "<code>${statement.html()}</code>")
        }

        addApiSection("Inputs:", resolved.entity.inputs)
        addApiSection("Outputs:", resolved.entity.outputs)
        resolved.localDocumentation.defaults.forEach { default ->
            addSection(
                "If ${default.name} is omitted:",
                default.provider?.let {
                    "Read from <code>${it.html()}</code>. Project providers may change this value."
                }
                    ?: "Library default: <code>${default.value.orEmpty().html()}</code>",
            )
        }
    }

    private fun StringBuilder.appendPipeSections(pipe: TaigaPipeDocumentation) {
        pipe.invocation?.let { addSection("What it calls:", it.asCodeBlock()) }
        pipe.parameters.takeIf(List<TaigaPipeParameter>::isNotEmpty)?.let { parameters ->
            addSection(
                "Parameters:",
                parameters.joinToString("<br>") { parameter ->
                    listOf(
                        "<code>${parameter.presentationName.html()}</code>",
                        parameter.type.orEmpty().html(),
                        parameter.description.orEmpty().html(),
                    ).joinToString("&nbsp;&nbsp;")
                },
            )
        }
        pipe.resultType?.let { addSection("Result:", "<code>${it.html()}</code>") }
        pipe.pure?.let { pure ->
            addSection(
                if (pure) "Pure pipe:" else "Impure pipe:",
                if (pure) {
                    "Recomputed when the value or arguments change. Object mutations alone do not trigger this pipe."
                } else {
                    "Angular invokes this pipe during change detection."
                },
            )
        }
    }

    private fun StringBuilder.appendMemberSections(resolved: TaigaResolvedDocumentation.Member) {
        resolved.typeText?.takeIf(String::isNotBlank)?.let { type ->
            addSection("Type:", "<code>${type.html()}</code>")
        }

        resolved.effectiveUsage?.takeIf(String::isNotBlank)?.let { usage ->
            addSection("Usage:", usage.asCodeBlock())
        }

        resolved.possibleValues().takeIf(List<String>::isNotEmpty)?.let { values ->
            addSection(
                "Possible values:",
                values.joinToString("&nbsp; ") { value -> "<code>${value.html()}</code>" },
            )
        }

        resolved.relatedMembers().takeIf(List<String>::isNotEmpty)?.let { related ->
            addSection(
                "See also:",
                related.joinToString("&nbsp; ") { value -> "<code>${value.html()}</code>" },
            )
        }
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
                    visible.joinToString("<br>") { property ->
                        buildString {
                            append("<code>")
                            append(property.name.html())
                            property.documentedType?.let { type ->
                                append("</code>&nbsp;&nbsp;<code>")
                                append(type.html())
                            }
                            append("</code>")

                            property.description?.takeIf(String::isNotBlank)?.let { description ->
                                append("&nbsp;&nbsp;—&nbsp;")
                                append(description.html())
                            }
                        }
                    },
                )

                if (remaining > 0) {
                    append("<br><i>+$remaining more in full documentation</i>")
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

    private fun String.asCodeBlock(): String =
        if ('\n' in this) {
            "<pre><code>${html()}</code></pre>"
        } else {
            "<code>${html()}</code>"
        }

    private fun String.html(): String = StringUtil.escapeXmlEntities(this)

    private const val MAX_VISIBLE_API_PROPERTIES = 6
}
