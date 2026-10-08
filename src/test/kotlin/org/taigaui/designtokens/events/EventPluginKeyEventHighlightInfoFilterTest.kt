package org.taigaui.designtokens.events

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.lang.annotation.HighlightSeverity
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EventPluginKeyEventHighlightInfoFilterTest : LightPlatformCodeInsightFixture4TestCase() {
    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject(
            "angular.json",
            """
            {
              "projects": {
                "test": {
                  "projectType": "application",
                  "root": "",
                  "sourceRoot": "src"
                }
              }
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/package.json",
            """
            {
              "name": "@angular/core",
              "version": "17.3.0",
              "types": "index.d.ts"
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/index.d.ts",
            """
            export interface DirectiveMetadata {
                selector?: string;
                host?: Record<string, string>;
            }

            export interface ComponentMetadata extends DirectiveMetadata {}
            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    @Test
    fun `suppresses low severity diagnostics only on host modifier ranges`() {
        val binding = "(click.stop.prevent)"
        val file =
            configureAngularFile(
                "modifier-filter.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'example',
                    host: {'$binding': 'onClick()'},
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )
        val modifierStart = file.text.indexOf("stop")
        val eventStart = file.text.indexOf("click")
        val filter = EventPluginKeyEventHighlightInfoFilter()
        val modifierHighlight =
            highlight(
                start = modifierStart,
                end = modifierStart + "stop".length,
                severity = HighlightSeverity.INFORMATION,
            )
        val eventHighlight =
            highlight(
                start = eventStart,
                end = eventStart + "click".length,
                severity = HighlightSeverity.INFORMATION,
            )

        assertFalse(filter.accept(modifierHighlight, file))
        assertTrue(filter.accept(eventHighlight, file))
        assertTrue(filter.accept(eventHighlight, null))
    }

    @Test
    fun `suppresses conflicting warning for event plugin host binding`() {
        val binding = "(click.stop)"
        val file =
            configureAngularFile(
                "event-filter.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'example',
                    host: {'$binding': 'onClick()'},
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )
        val start = file.text.indexOf(binding)

        assertFalse(
            EventPluginKeyEventHighlightInfoFilter()
                .accept(
                    highlight(start, start + binding.length),
                    file,
                ),
        )
    }

    @Test
    fun `suppresses valid extended key event diagnostic in template binding`() {
        val file =
            myFixture.configureByText(
                "extended-key.html",
                """<input (keydown.alt.code.keya.stop)="onKey()" />""",
            )
        val event = "keydown.alt.code.keya"
        val eventStart = file.text.indexOf(event)

        assertFalse(
            EventPluginKeyEventHighlightInfoFilter()
                .accept(
                    highlight(eventStart, eventStart + event.length),
                    file,
                ),
        )
    }

    @Test
    fun `keeps invalid extended key event diagnostic`() {
        val file =
            myFixture.configureByText(
                "invalid-extended-key.html",
                """<input (keydown.code.keyaa.stop)="onKey()" />""",
            )
        val event = "keydown.code.keyaa"
        val eventStart = file.text.indexOf(event)

        assertTrue(
            EventPluginKeyEventHighlightInfoFilter()
                .accept(
                    highlight(eventStart, eventStart + event.length),
                    file,
                ),
        )
    }

    private fun configureAngularFile(
        name: String,
        text: String,
    ): PsiFile {
        val file = myFixture.addFileToProject("src/$name", text)

        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        return myFixture.file
    }

    private fun highlight(
        start: Int,
        end: Int,
        severity: HighlightSeverity = HighlightSeverity.WARNING,
    ): HighlightInfo =
        HighlightInfo
            .newHighlightInfo(HighlightInfoType.WARNING)
            .range(start, end)
            .severity(severity)
            .descriptionAndTooltip("Test diagnostic")
            .createUnconditionally()
}
