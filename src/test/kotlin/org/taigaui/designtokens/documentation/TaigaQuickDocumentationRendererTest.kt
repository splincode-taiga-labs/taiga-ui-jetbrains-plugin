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
                usage = "<button appearance=\"secondary\" tuiButton>Current</button>",
                typeDefinition = null,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved).replace(Regex("</?span[^>]*>"), "")

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
        assertTrue(html.contains(StringUtil.escapeXmlEntities("<button tuiButton>Save</button>")))
        assertFalse(html.contains("Current"))
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

        val html = TaigaQuickDocumentationRenderer.render(resolved).replace(Regex("</?span[^>]*>"), "")

        assertTrue(html.contains("size"))
        assertTrue(html.contains("Input"))
        assertTrue(html.contains("of TuiButton"))
        assertTrue(html.contains("Controls the button size"))
        assertTrue(html.contains("Possible values"))
        assertTrue(html.contains("xs"))
        assertTrue(html.contains("xl"))
        assertFalse(html.contains("See also"))
        assertFalse(html.contains("iconEnd"))
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

        val html = TaigaQuickDocumentationRenderer.render(resolved).replace(Regex("</?span[^>]*>"), "")

        assertTrue(html.contains("TuiAppearance"))
        assertTrue(html.contains("Type"))
        assertTrue(html.contains("primary"))
        assertTrue(html.contains("secondary"))
        assertTrue(html.contains("border-radius:5px"))
        assertFalse(html.contains("&#39;primary&#39; | &#39;secondary&#39;"))
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

        val html = TaigaQuickDocumentationRenderer.render(resolved).replace(Regex("</?span[^>]*>"), "")

        assertTrue(html.contains("input1"))
        assertTrue(html.contains("input6"))
        assertFalse(html.contains("input7"))
        assertTrue(html.contains("+2 more"))
    }


    @Test
    fun `renders pipe invocation parameters result and purity without a package`() {
        val local =
            TaigaLocalDocumentation(
                pipe =
                    TaigaPipeDocumentation(
                        name = "tuiMapper",
                        parameters =
                            listOf(
                                TaigaPipeParameter("mapper", "(value: string) => string", false, "Transforms the value"),
                                TaigaPipeParameter("args", null, true, variadic = true),
                            ),
                        resultType = "string",
                        pure = false,
                        invocation = "mapper(value)",
                    ),
            )
        val entity =
            buttonEntity().copy(
                title = "Mapper",
                kind = TaigaDocKind.PIPE,
                description = null,
                packageNames = emptySet(),
                publicSymbols = emptySet(),
                inputs = emptyList(),
                outputs = emptyList(),
                example = null,
            )
        val resolved =
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject = TaigaDocumentationSubject(null, null, null, local),
                startOffset = 0,
                endOffset = 10,
                usage = null,
                typeDefinition = null,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("Impure pipe:"))
        assertTrue(html.contains("Angular invokes this pipe during change detection"))
        assertTrue(html.contains("What it calls:"))
        assertTrue(html.contains("mapper(value)"))
        assertTrue(html.contains("Parameters:"))
        assertTrue(html.contains("...args?"))
        assertTrue(html.contains("Transforms the value"))
        assertTrue(html.contains("Result:"))
        assertFalse(html.contains("@taiga-ui/core"))
    }

    @Test
    fun `renders defaults, selectors, and missing property metadata`() {
        val entity =
            buttonEntity(
                inputs = listOf(TaigaApiProperty("appearance", "[appearance]", null, null)),
            ).copy(
                example = null,
                outputs = emptyList(),
                description = null,
            )
        val local =
            TaigaLocalDocumentation(
                selector = "button[tuiButton]",
                defaults =
                    listOf(
                        TaigaInputDefault("size", provider = "TUI_BUTTON_OPTIONS"),
                        TaigaInputDefault("appearance", value = "'primary'"),
                    ),
            )
        val resolved =
            TaigaResolvedDocumentation.Entity(
                entity = entity,
                subject = TaigaDocumentationSubject("tuiButton", "TuiButton", "@taiga-ui/core", local),
                startOffset = 0,
                endOffset = 9,
                usage = null,
                typeDefinition = null,
            )

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("Selector:"))
        assertTrue(html.contains("button[tuiButton]"))
        assertTrue(html.contains("If size is omitted:"))
        assertTrue(html.contains("TUI_BUTTON_OPTIONS"))
        assertTrue(html.contains("If appearance is omitted:"))
        assertTrue(html.contains("Library default:"))
        assertTrue(html.contains("appearance"))
        assertFalse(html.contains("Outputs:"))
    }

    @Test
    fun `renders member metadata, receiver, and multiline usage`() {
        val entity = buttonEntity()
        val owner = TaigaDocumentationSubject("tuiButton", "TuiButton", "@taiga-ui/core")
        val property =
            requireNotNull(entity.inputs.find { it.name == "size" })
                .copy(description = "Size can be combined with iconEnd")
        val local =
            TaigaLocalApiMember(
                name = "size",
                kind = TaigaApiMemberKind.INPUT,
                type = "'s' | 'l'",
                declaration = owner,
                required = true,
                expandedType = "'s' | 'm' | 'l'",
                valueType = "string",
                transform = "coerceSize",
                deprecated = "Use appearance instead",
            )
        val member =
            TaigaResolvedDocumentation.Member(
                entity = entity,
                subject = owner,
                startOffset = 0,
                endOffset = 4,
                usage = "<button\n  size=\"s\">Save</button>",
                property = property,
                kind = TaigaApiMemberKind.INPUT,
                localMember = local,
            )
        val resolved = member.copy(receivers = listOf(member.copy(declaration = owner)))

        val html = TaigaQuickDocumentationRenderer.render(resolved)

        assertTrue(html.contains("accepts:"))
        assertTrue(html.contains("Declared by:"))
        assertTrue(html.contains("TuiButton"))
        assertTrue(html.contains("Expanded type:"))
        assertTrue(html.contains("Stored value type:"))
        assertTrue(html.contains("Input transform:"))
        assertTrue(html.contains("coerceSize"))
        assertTrue(html.contains("Deprecated:"))
        assertTrue(html.contains("Requirement:"))
        assertTrue(html.contains("Required input"))
        assertTrue(html.contains("See also:"))
        assertTrue(html.contains("iconEnd"))
        assertTrue(html.contains("<pre><code>"))
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
