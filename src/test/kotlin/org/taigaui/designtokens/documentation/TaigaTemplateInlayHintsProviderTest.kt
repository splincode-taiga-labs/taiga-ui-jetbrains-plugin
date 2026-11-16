package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.hints.declarative.CollapseState
import com.intellij.codeInsight.hints.declarative.CollapsiblePresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayActionData
import com.intellij.codeInsight.hints.declarative.InlayPayload
import com.intellij.codeInsight.hints.declarative.InlayPosition
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.PresentationTreeBuilder
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiRecursiveElementWalkingVisitor
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaTemplateInlayHintsProviderTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
    }

    fun testInstalledAngularTemplateCreatesDirectiveAndSizeHints() {
        val source = """<button tuiButton size="m">Save</button>"""
        val file = myFixture.tempDirFixture.createFile("src/component.html", source)
        myFixture.configureFromExistingVirtualFile(file)
        val sink = collect(myFixture.file)

        assertContainsElements(sink.texts, " Taiga UI directive", " medium")
        assertContainsElements(
            sink.offsets,
            source.indexOf("tuiButton") + "tuiButton".length,
            source.indexOf("size=\"m\"") + "size=\"m\"".length,
        )
        assertEquals(2, sink.tooltips.size)
    }

    fun testUnrelatedAttributesAndInvalidSizesDoNotCreateHints() {
        val source = """<button tuiButton size="invalid" title="m">Save</button>"""
        val file = myFixture.tempDirFixture.createFile("src/component.html", source)
        myFixture.configureFromExistingVirtualFile(file)
        val sink = collect(myFixture.file)

        assertContainsElements(sink.texts, " Taiga UI directive")
        assertFalse(sink.texts.any { it.contains("medium") })
        assertEquals(1, sink.texts.size)
    }

    fun testInjectedAngularTemplateMapsHintsToTypeScriptHostOffsets() {
        val template = """<button tuiButton size="m">Save</button>"""
        val source =
            TaigaDocumentationAngularFixture.CONSUMER.replace(
                "templateUrl: './component.html'",
                "template: `$template`",
            )
        val file = myFixture.tempDirFixture.createFile("src/inline.ts", source)
        myFixture.configureFromExistingVirtualFile(file)
        val sink = collect(myFixture.file)

        assertContainsElements(sink.texts, " Taiga UI directive", " medium")
        val templateOffset = source.indexOf(template)
        assertTrue(sink.offsets.all { it >= templateOffset && it <= templateOffset + template.length })
    }

    private fun collect(file: PsiFile): RecordingSink {
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
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
        val offsets = mutableListOf<Int>()
        val tooltips = mutableListOf<String>()

        override fun addPresentation(
            position: InlayPosition,
            payloads: List<InlayPayload>?,
            tooltip: String?,
            hintFormat: HintFormat,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            offsets += (position as InlineInlayPosition).offset
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

    private class RecordingBuilder(
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
            error("Simple Taiga hints do not contain collapsible trees")
        }

        override fun text(
            text: String,
            actionData: InlayActionData?,
        ) {
            texts += text
        }

        override fun clickHandlerScope(
            actionData: InlayActionData,
            builder: PresentationTreeBuilder.() -> Unit,
        ) {
            builder()
        }
    }
}
