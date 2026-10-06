package org.taigaui.designtokens.units

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

class RemInlayHintsProviderCoverageTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        myFixture.addFileToProject(
            "node_modules/@angular/core/package.json",
            """{"name":"@angular/core","version":"22.0.0","types":"index.d.ts"}""",
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/index.d.ts",
            """
            export interface ComponentMetadata {
                selector?: string;
                template?: string;
                styles?: string | string[];
                host?: Record<string, string>;
            }
            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    fun testStylesheetCollectorPublishesHintPresentation() {
        val file = myFixture.configureByText("styles.css", ".demo { gap: 1rem; }")
        val sink = RecordingSink()
        val collector =
            RemInlayHintsProvider()
                .createCollector(file, myFixture.editor) as SharedBypassCollector

        collector.collectFromElement(file, sink)

        assertContainsElements(sink.texts, " 16px")
        assertContainsElements(sink.tooltips, "1rem = 16px")
    }

    fun testHtmlCollectorPublishesAngularStyleBindingHint() {
        val file =
            myFixture.configureByText(
                "template.html",
                """<div [style.gap.rem]="1"></div>""",
            )
        val sink = RecordingSink()
        val collector =
            RemInlayHintsProvider()
                .createCollector(file, myFixture.editor) as SharedBypassCollector

        collector.collectFromElement(file, sink)

        assertContainsElements(sink.texts, " 16px")
    }

    fun testInjectedCollectorPublishesHostAndInjectedTemplateAndStylesheetHints() {
        val file =
            myFixture.configureByText(
                "component.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'demo',
                    template: `<div [style.gap.rem]="1"></div>`,
                    styles: ['.demo { padding: 2rem; }'],
                    host: {'[style.margin.rem]': '3'},
                })
                export class Demo {}
                """.trimIndent(),
            )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val sink = RecordingSink()
        val collector =
            RemInlayHintsProvider()
                .createCollector(file, myFixture.editor) as SharedBypassCollector

        file.accept(
            object : PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    collector.collectFromElement(element, sink)
                    super.visitElement(element)
                }
            },
        )

        assertTrue(sink.texts.any { text -> text.contains("16px") })
        assertTrue(sink.texts.any { text -> text.contains("32px") })
        assertTrue(sink.texts.any { text -> text.contains("48px") })
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
