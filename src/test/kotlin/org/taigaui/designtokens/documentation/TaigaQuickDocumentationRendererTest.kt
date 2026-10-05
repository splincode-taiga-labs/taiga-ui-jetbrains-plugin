package org.taigaui.designtokens.documentation

import com.intellij.openapi.util.text.StringUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaQuickDocumentationRendererTest {
    @Test
    fun `renders compact directive documentation without duplicate external link`() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val entity =
            TaigaEntityDoc(
                sectionId = "components/button",
                title = "Button",
                packageNames = setOf("@taiga-ui/core"),
                kind = TaigaDocKind.COMPONENT,
                version = "5.0.0",
                description = "Button is a basic component.",
                publicSymbols = setOf("TuiButton"),
                selectors = setOf("tuiButton"),
                inputs =
                    listOf(
                        TaigaApiProperty(
                            name = "size",
                            signature = "[size]",
                            documentedType = "TuiSizeXS | TuiSizeL",
                            description = "Button size",
                        ),
                    ),
                outputs = emptyList(),
                example = TaigaExample("html", "<button tuiButton>Save</button>"),
                documentationUri = source.documentationUri("components/button"),
            )

        val html =
            TaigaQuickDocumentationRenderer.render(
                entity,
                TaigaDocumentationSubject(
                    selector = "tuiButton",
                    publicSymbol = "TuiButton",
                    packageName = "@taiga-ui/core",
                ),
            )

        assertTrue(html.contains("TuiButton"))
        assertTrue(html.contains("@taiga-ui/core"))
        assertTrue(html.contains("tuiButton"))
        assertTrue(html.contains("TuiSizeXS | TuiSizeL"))
        assertTrue(
            html.contains(
                StringUtil.escapeXmlEntities("import {TuiButton} from '@taiga-ui/core';"),
            ),
        )
        assertTrue(html.contains("&lt;button tuiButton&gt;Save&lt;/button&gt;"))
        assertFalse(html.contains("Open full Taiga UI documentation"))
        assertFalse(html.contains("taiga-ui.dev"))
    }

    @Test
    fun `omits large example and truncates long api lists`() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val inputs =
            (1..7).map { index ->
                TaigaApiProperty(
                    name = "input$index",
                    signature = "[input$index]",
                    documentedType = "string",
                    description = null,
                )
            }
        val entity =
            TaigaEntityDoc(
                sectionId = "components/example",
                title = "Example",
                packageNames = setOf("@taiga-ui/core"),
                kind = TaigaDocKind.COMPONENT,
                version = "5.0.0",
                description = "Compact docs.",
                publicSymbols = setOf("TuiExample"),
                selectors = setOf("tui-example"),
                inputs = inputs,
                outputs = emptyList(),
                example = TaigaExample("html", "x".repeat(300)),
                documentationUri = source.documentationUri("components/example"),
            )

        val html =
            TaigaQuickDocumentationRenderer.render(
                entity,
                TaigaDocumentationSubject(
                    selector = "tui-example",
                    publicSymbol = "TuiExample",
                    packageName = "@taiga-ui/core",
                ),
            )

        assertTrue(html.contains("input1"))
        assertTrue(html.contains("input5"))
        assertFalse(html.contains("input6"))
        assertTrue(html.contains("+2 more"))
        assertFalse(html.contains("x".repeat(40)))
    }
}
