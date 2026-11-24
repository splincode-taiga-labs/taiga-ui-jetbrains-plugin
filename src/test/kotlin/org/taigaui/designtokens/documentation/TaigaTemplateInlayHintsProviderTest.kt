package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaTemplateInlayHintsProviderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
    }

    fun testExternalTemplateShowsOnlyInstalledTaigaBindings() {
        val template =
            """
            <button tuiButton size="m" title="Save">Save</button>
            <button localSized size="m">Local</button>
            <button tuiButton size="invalid">Invalid</button>
            """.trimIndent()
        val file = myFixture.tempDirFixture.createFile("src/component.html", template)
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()

        val sink = collect()

        assertEquals(2, sink.texts.count { it == " Taiga UI directive" })
        assertEquals(1, sink.texts.count { it == " medium" })
        assertEquals(3, sink.texts.size)
        assertTrue(sink.tooltips.all { it == "Taiga UI · installed API" })
    }

    fun testInlineTemplateShowsInstalledTaigaBindings() {
        val source =
            TaigaDocumentationAngularFixture.CONSUMER.replace(
                "templateUrl: './component.html'",
                "template: `<button tuiButton size=\"l\">Save</button>`",
            )
        val file = myFixture.tempDirFixture.createFile("src/inline.ts", source)
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()

        val sink = collect()

        assertEquals(listOf(" Taiga UI directive", " large"), sink.texts)
    }

    fun testNonAngularMarkupAndTypeScriptDoNotProduceHints() {
        myFixture.configureByText("plain.html", """<div size="m">Text</div>""")
        assertEmpty(collect().texts)

        myFixture.configureByText("plain.ts", """export const size = "m";""")
        assertEmpty(collect().texts)
    }

    private fun collect(): RecordingSink {
        val file = myFixture.file
        val collector =
            TaigaTemplateInlayHintsProvider()
                .createCollector(file, myFixture.editor) as SharedBypassCollector
        val sink = RecordingSink()
        file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    collector.collectFromElement(element, sink)
                    super.visitElement(element)
                }
            },
        )
        return sink
    }

    private class RecordingSink : InlayTreeSink {
        val texts = mutableListOf<String>()
        val tooltips = mutableListOf<String>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            tooltip?.let(tooltips::add)
            RecordingBuilder(texts).builder()
        }

        override fun whenOptionEnabled(
            optionId: String,
            block: () -> Unit,
        ) {
            block()
        }
    }

    private open class RecordingBuilder(
        private val texts: MutableList<String>,
    ) : PresentationTreeBuilder {
        override fun list(builder: PresentationTreeBuilder.() -> Unit) {
            builder()
        }

        override fun collapsibleList(
            state: CollapseState,
            expandedState: CollapsiblePresentationTreeBuilder.() -> Unit,
            collapsedState: CollapsiblePresentationTreeBuilder.() -> Unit,
        ) {
            val builder = RecordingCollapsibleBuilder(texts)

            builder.expandedState()
        }

        override fun text(
            text: String,
            actionData: InlayActionData?,
        ) {
            texts.add(text)
        }

        override fun clickHandlerScope(
            actionData: InlayActionData,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            builder()
        }
    }

    private class RecordingCollapsibleBuilder(
        texts: MutableList<String>,
    ) : RecordingBuilder(texts),
        CollapsiblePresentationTreeBuilder {
        override fun toggleButton(builder: PresentationTreeBuilder.() -> Unit) {
            builder()
        }
    }
}
