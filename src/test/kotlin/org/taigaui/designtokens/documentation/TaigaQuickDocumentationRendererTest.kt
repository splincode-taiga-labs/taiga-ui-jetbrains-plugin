package org.taigaui.designtokens.documentation

import com.intellij.openapi.util.text.StringUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaQuickDocumentationRendererTest {
    private val source = requireNotNull(TaigaDocsSources.forMajor(5))

    @Test
    fun `renders directive entity documentation`() {
        val entity = buttonEntity()
        val resolved =
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject =
                    TaigaDocumentationSubject(
                        selector = "tuiButton",
                        publicSymbol = "TuiButton",
                        packageName = "@taiga-ui/core",
                    ),
                startOffset = 0,
                endOffset = 9,
                usage = null,
                typeDefinition = null,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("TuiButton"))
        assertTrue(html.contains("Directive"))
        assertTrue(html.contains("@taiga-ui/core"))
        assertTrue(
            html.contains(
                StringUtil.escapeXmlEntities("import {TuiButton} from '@taiga-ui/core';"),
            ),
        )
        assertTrue(html.contains("iconEnd"))
        assertTrue(html.contains("valueChange"))
        assertFalse(html.contains("Open full Taiga UI documentation"))
        assertFalse(html.contains("taiga-ui.dev"))
    }

    @Test
    fun `renders focused input documentation and possible values`() {
        val entity = buttonEntity()
        val resolved =
            TaigaResolvedDocumentation.Member(
                entity = entity,
                subject =
                    TaigaDocumentationSubject(
                        selector = "tuiButton",
                        publicSymbol = "TuiButton",
                        packageName = "@taiga-ui/core",
                    ),
                startOffset = 0,
                endOffset = 6,
                usage = "<button tuiButton [size]=\"value\">...</button>",
                property = entity.inputs.first { property -> property.name == "size" },
                kind = TaigaApiMemberKind.INPUT,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("size"))
        assertTrue(html.contains("Input"))
        assertTrue(html.contains("of TuiButton"))
        assertTrue(html.contains("Controls the button size"))
        assertTrue(html.contains("Possible values"))
        assertTrue(html.contains("xs"))
        assertTrue(html.contains("xl"))
        assertTrue(html.contains("See also"))
        assertTrue(html.contains("iconEnd"))
    }

    @Test
    fun `renders type entity definition`() {
        val entity =
            TaigaEntityDoc(
                sectionId = "types/appearance",
                title = "Appearance",
                packageNames = setOf("@taiga-ui/core"),
                kind = TaigaDocKind.TYPE,
                version = "5.0.0",
                description = "Available button appearances.",
                publicSymbols = setOf("TuiAppearance"),
                selectors = emptySet(),
                inputs = emptyList(),
                outputs = emptyList(),
                example = null,
                documentationUri = source.documentationUri("types/appearance"),
            )
        val resolved =
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject =
                    TaigaDocumentationSubject(
                        selector = null,
                        publicSymbol = "TuiAppearance",
                        packageName = "@taiga-ui/core",
                    ),
                startOffset = 0,
                endOffset = 13,
                usage = null,
                typeDefinition = "'primary' | 'secondary' | 'accent'",
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("TuiAppearance"))
        assertTrue(html.contains("Type"))
        assertTrue(html.contains("primary"))
        assertTrue(html.contains("secondary"))
    }

    @Test
    fun `truncates long api lists`() {
        val inputs =
            (1..8).map { index ->
                TaigaApiProperty(
                    name = "input$index",
                    signature = "[input$index]",
                    documentedType = "string",
                    description = null,
                )
            }
        val entity =
            buttonEntity(
                inputs = inputs,
            )
        val resolved =
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject =
                    TaigaDocumentationSubject(
                        selector = "tuiButton",
                        publicSymbol = "TuiButton",
                        packageName = "@taiga-ui/core",
                    ),
                startOffset = 0,
                endOffset = 9,
                usage = null,
                typeDefinition = null,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("input1"))
        assertTrue(html.contains("input6"))
        assertFalse(html.contains("input7"))
        assertTrue(html.contains("+2 more"))
    }

    private fun buttonEntity(
        inputs: List<TaigaApiProperty> =
            listOf(
                TaigaApiProperty(
                    name = "iconEnd",
                    signature = "[iconEnd]",
                    documentedType = "TuiIcon",
                    description = "Icon displayed at the end.",
                ),
                TaigaApiProperty(
                    name = "size",
                    signature = "[size]",
                    documentedType = "'xs' | 's' | 'm' | 'l' | 'xl'",
                    description = "Controls the button size.",
                ),
            ),
    ): TaigaEntityDoc =
        TaigaEntityDoc(
            sectionId = "components/button",
            title = "Button",
            packageNames = setOf("@taiga-ui/core"),
            kind = TaigaDocKind.COMPONENT,
            version = "5.0.0",
            description = "Button is a basic component.",
            publicSymbols = setOf("TuiButton"),
            selectors = setOf("tuiButton"),
            inputs = inputs,
            outputs =
                listOf(
                    TaigaApiProperty(
                        name = "valueChange",
                        signature = "(valueChange)",
                        documentedType = "MouseEvent",
                        description = "Emitted when the value changes.",
                    ),
                ),
            example = TaigaExample("html", "<button tuiButton>Save</button>"),
            documentationUri = source.documentationUri("components/button"),
        )
}
