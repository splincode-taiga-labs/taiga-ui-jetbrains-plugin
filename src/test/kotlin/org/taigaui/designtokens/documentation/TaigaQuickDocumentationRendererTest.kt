package org.taigaui.designtokens.documentation

import com.intellij.openapi.util.text.StringUtil
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaQuickDocumentationRendererTest {
    @Test
    fun rendersDirectiveDocumentation() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val entity =
            TaigaEntityDoc(
                sectionId = "components/button",
                title = "Button",
                packageNames = setOf("@taiga-ui/core"),
                kind = TaigaDocKind.COMPONENT,
                version = "5.0.0",
                description = "Button is a basic component.",
                publicSymbols = linkedSetOf("TuiButton", "TuiButtonOptions"),
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

        val html = TaigaQuickDocumentationRenderer.render(entity)

        assertTrue(html.contains("TuiButton"))
        assertTrue(html.contains("@taiga-ui/core"))
        assertTrue(html.contains(StringUtil.escapeXmlEntities("import {TuiButton} from '@taiga-ui/core';")))
        assertTrue(html.contains("tuiButton"))
        assertTrue(html.contains("TuiSizeXS | TuiSizeL"))
        assertTrue(html.contains("&lt;button tuiButton&gt;Save&lt;/button&gt;"))
        assertTrue(html.contains("https://taiga-ui.dev/components/button"))
    }

    @Test
    fun rendersElementComponentAndEscapesDocumentationText() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val entity =
            TaigaEntityDoc(
                sectionId = "components/calendar",
                title = "Calendar",
                packageNames = setOf("@taiga-ui/core"),
                kind = TaigaDocKind.COMPONENT,
                version = "5.0.0",
                description = "Calendar <month> & navigation.",
                publicSymbols = setOf("TuiCalendar"),
                selectors = setOf("tui-calendar"),
                inputs = emptyList(),
                outputs = emptyList(),
                example = null,
                documentationUri = source.documentationUri("components/calendar"),
            )

        val html = TaigaQuickDocumentationRenderer.render(entity)

        assertTrue(html.contains("TuiCalendar"))
        assertTrue(html.contains("tui-calendar"))
        assertTrue(html.contains("Calendar &lt;month&gt; &amp; navigation."))
        assertFalse(html.contains("Calendar <month> & navigation."))
    }
}
