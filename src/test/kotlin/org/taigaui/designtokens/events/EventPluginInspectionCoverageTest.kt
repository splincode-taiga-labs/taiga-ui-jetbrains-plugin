package org.taigaui.designtokens.events

import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Test

class EventPluginInspectionCoverageTest : LightPlatformCodeInsightFixture4TestCase() {
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
            """{"name":"@angular/core","version":"17.3.0","types":"index.d.ts"}""",
        )
        myFixture.addFileToProject(
            "node_modules/@angular/core/index.d.ts",
            """
            export interface DirectiveMetadata {
                selector?: string;
                host?: Record<string, string>;
            }

            export declare function Directive(metadata: DirectiveMetadata): ClassDecorator;
            """.trimIndent(),
        )

        myFixture.enableInspections(
            UnknownEventPluginModifierInspection(),
            DuplicateEventPluginModifierInspection(),
            TypeScriptEventPluginModifierInspection(),
        )
    }

    @Test
    fun testHtmlInspectionsReportUnknownDuplicateAndAliasDuplicates() {
        myFixture.configureByText(
            "events.html",
            """
            <button
                (click.captre.stop.stop)="onClick()"
                (mousemove.silent.zoneless)="onMove()"
                (input.debounce~100ms.debounce~200ms)="onInput()"
                (scroll.throttle~1s.throttle~2s)="onScroll()"
            ></button>
            """.trimIndent(),
        )

        val descriptions =
            myFixture
                .doHighlighting()
                .mapNotNull { info -> info.description }

        assertTrue(descriptions.any { it == "Unknown Taiga UI event modifier 'captre'" })
        assertTrue(descriptions.any { it == "Duplicate Taiga UI event modifier 'stop'" })
        assertTrue(descriptions.any { it == "Duplicate Taiga UI event modifier 'zoneless'" })
        assertTrue(descriptions.any { it == "Duplicate Taiga UI event modifier 'debounce~200ms'" })
        assertTrue(descriptions.any { it == "Duplicate Taiga UI event modifier 'throttle~2s'" })
    }

    @Test
    fun testHostInspectionReportsUnknownAndDuplicateModifiersWithExactRanges() {
        val file =
            myFixture.addFileToProject(
                "src/directive.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {
                        '(click.captre.stop.stop)': 'onClick()',
                    },
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )

        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val problems =
            myFixture
                .doHighlighting()
                .filter { info ->
                    info.description?.startsWith("Unknown Taiga UI event modifier") == true ||
                        info.description?.startsWith("Duplicate Taiga UI event modifier") == true
                }

        assertTrue(
            problems.any { info ->
                info.description == "Unknown Taiga UI event modifier 'captre'" &&
                    myFixture.editor.document
                        .text
                        .substring(info.startOffset, info.endOffset) == "captre"
            },
        )
        assertTrue(
            problems.any { info ->
                info.description == "Duplicate Taiga UI event modifier 'stop'" &&
                    myFixture.editor.document
                        .text
                        .substring(info.startOffset, info.endOffset) == "stop"
            },
        )
    }

    @Test
    fun testTypeScriptHostInspectionSuppressorCoversPluginGlobalAndNegativePaths() {
        val file =
            myFixture.addFileToProject(
                "src/suppressor.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {
                        '(click.stop)': 'onClick()',
                        '(visualViewport>resize)': 'onResize()',
                    },
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )

        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        val suppressor = TypeScriptHostEventPluginInspectionSuppressor()

        listOf("click.stop", "visualViewport>resize").forEach { binding ->
            val element =
                requireNotNull(
                    file.findElementAt(file.text.indexOf(binding) + 2),
                )

            assertTrue(
                suppressor.isSuppressedFor(
                    element,
                    "SpellCheckingInspection",
                ),
            )
            assertFalse(
                suppressor.isSuppressedFor(
                    element,
                    "DifferentInspection",
                ),
            )
        }

        val ordinaryElement =
            requireNotNull(
                file.findElementAt(file.text.indexOf("ExampleDirective")),
            )

        assertFalse(
            suppressor.isSuppressedFor(
                ordinaryElement,
                "SpellCheckingInspection",
            ),
        )
        assertTrue(
            suppressor
                .getSuppressActions(null, "SpellCheckingInspection")
                .isEmpty(),
        )
    }

    @Test
    fun testFindersIgnorePlainEventsAndDetectModifierLikeTyposAfterTaigaModifier() {
        assertTrue(EventPluginUnknownModifierFinder.findInEventName("click", 10).isEmpty())
        assertTrue(EventPluginUnknownModifierFinder.findInEventName("click.custom", 10).isEmpty())
        assertTrue(EventPluginDuplicateModifierFinder.findInEventName("click.custom", 10).isEmpty())

        val typo =
            EventPluginUnknownModifierFinder
                .findInEventName(
                    "click.stop.custom",
                    100,
                ).single()

        assertEquals("custom", typo.modifier)
        assertEquals(111, typo.startOffset)
        assertEquals(117, typo.endOffset)

        assertEquals(
            listOf("debounce~200ms"),
            EventPluginDuplicateModifierFinder
                .findAll("""<div (click.debounce~100ms.debounce~200ms)="x"></div>""")
                .map(EventPluginDuplicateModifier::modifier),
        )
    }
}
