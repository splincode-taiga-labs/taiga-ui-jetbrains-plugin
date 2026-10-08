package org.taigaui.designtokens.units

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class RemInlayHintsProviderTest : BasePlatformTestCase() {
    fun testCreatesStylesheetCollectorForCss() {
        val file = myFixture.configureByText("styles.css", ".demo { gap: 1rem; }")
        val collector = RemInlayHintsProvider().createCollector(file, myFixture.editor)

        assertEquals("StylesheetCollector", collector.javaClass.simpleName)
    }

    fun testCreatesHtmlTemplateCollectorForHtml() {
        val file =
            myFixture.configureByText(
                "template.html",
                """<div [style.gap.rem]="1"></div>""",
            )
        val collector = RemInlayHintsProvider().createCollector(file, myFixture.editor)

        assertEquals("HtmlTemplateCollector", collector.javaClass.simpleName)
    }

    fun testCreatesInjectedContentCollectorForTypeScript() {
        val file =
            myFixture.configureByText(
                "component.ts",
                "export class Example {}",
            )
        val collector = RemInlayHintsProvider().createCollector(file, myFixture.editor)

        assertEquals("InjectedContentCollector", collector.javaClass.simpleName)
    }
}
