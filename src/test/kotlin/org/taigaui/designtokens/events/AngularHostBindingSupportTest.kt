package org.taigaui.designtokens.events

import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.daemon.impl.HighlightInfoType
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.completion.designTokenCompletionContextAt
import org.taigaui.designtokens.units.AngularHostRemStyleBindingHintCollector
import org.taigaui.designtokens.units.RemInlayHint

@Suppress("LargeClass")
class AngularHostBindingSupportTest : LightPlatformCodeInsightFixture4TestCase() {
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

            export interface ComponentMetadata extends DirectiveMetadata {
                template?: string;
                styles?: string | string[];
            }

            export declare function Directive(metadata: DirectiveMetadata): ClassDecorator;
            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    @Test
    fun `resolves design token completion context in Angular inline styles`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                template: '',
                styles: `
                    .example {
                        color: var(--tui-back);
                    }
                `,
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val token = "--tui-back"
        val tokenEnd =
            myFixture.editor.document.text
                .indexOf(token) + token.length

        myFixture.editor.caretModel.moveToOffset(tokenEnd)

        assertEquals(
            token,
            myFixture.editor.designTokenCompletionContextAt(tokenEnd)?.prefix,
        )
    }

    @Test
    fun `finds event plugin bindings in directive host metadata`() {
        val file =
            configureAngularFile(
                "directive.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {
                        '(mousemove.zoneless)': 'onMove(${DOLLAR}event)',
                        '(keydown.enter.stop)': 'onKey(${DOLLAR}event)',
                    },
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )

        val bindings = AngularHostBindingSupport.findAll(file)

        assertEquals(
            listOf("(mousemove.zoneless)", "(keydown.enter.stop)"),
            bindings.map(AngularHostEventBinding::source),
        )
        bindings.forEach { binding ->
            assertEquals(binding.source, file.text.substring(binding.startOffset, binding.endOffset))
        }
    }

    @Test
    fun `finds host binding at modifier offset`() {
        val file =
            configureAngularFile(
                "component.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'example',
                    host: {'(click.zoneless.capture)': 'onClick()'},
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )
        val offset = file.text.indexOf("zoneless") + 2
        val binding = AngularHostBindingSupport.findAt(file, offset)

        assertNotNull(binding)
        assertEquals("(click.zoneless.capture)", binding?.source)
    }

    @Test
    fun `completes native browser events in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(cl<caret>)': 'onClick()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("click", ignoreCase = true) })
    }

    @Test
    fun `completes bare HTML attributes in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'input[example]',
                host: {'val<caret>': ''},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue("value" in variants)
        assertFalse(variants.any { variant -> variant.startsWith('[') })
    }

    @Test
    fun `schedules host completion for typed letters`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'input[example]',
                host: {'val<caret>': ''},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val result =
            TypeScriptHostEventPluginCompletionAutoPopupHandler().checkAutoPopup(
                'u',
                project,
                myFixture.editor,
                myFixture.file,
            )

        assertEquals(
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun `does not duplicate closing parenthesis when accepting host event completion`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'form[example]',
                host: {'(su<caret>)': 'onSubmit()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val submit =
            myFixture
                .completeBasic()
                .orEmpty()
                .first { element -> element.lookupString.contains("submit", ignoreCase = true) }

        myFixture.lookup.currentItem = submit
        myFixture.finishLookup('\n')

        assertTrue(myFixture.file.text.contains("'(submit)': 'onSubmit()'"))
        assertFalse(myFixture.file.text.contains("(submit))"))
    }

    @Test
    fun `completes Angular style bindings in host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'[style.w<caret>]': 'width'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("width", ignoreCase = true) })
    }

    @Test
    fun `completes Angular style binding prefix in host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'[st<caret>]': 'width'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("style", ignoreCase = true) })
    }

    @Test
    fun `completes CSS classes in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                styles: ['.active {} .disabled {}'],
                host: {'[class.<caret>]': 'active'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("active", ignoreCase = true) })
        assertTrue(variants.any { variant -> variant.contains("disabled", ignoreCase = true) })
    }

    @Test
    fun `completes HTML attributes in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'button[example]',
                host: {'[attr.<caret>]': 'label'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("aria-label", ignoreCase = true) })
        assertTrue(variants.any { variant -> variant.contains("title", ignoreCase = true) })
    }

    @Test
    fun `completes CSS units after style property in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'[style.width.<caret>]': 'width'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("px", ignoreCase = true) })
        assertTrue(variants.any { variant -> variant.contains("rem", ignoreCase = true) })
        assertTrue(variants.any { variant -> variant.contains("em", ignoreCase = true) })
        assertTrue(variants.any { variant -> variant.contains('%') })
    }

    @Test
    fun `does not duplicate closing parenthesis when accepting Taiga modifier completion`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'button[example]',
                host: {'(click.ca<caret>)': 'onClick()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val capture =
            myFixture
                .completeBasic()
                .orEmpty()
                .first { element -> element.lookupString == "capture" }

        myFixture.lookup.currentItem = capture
        myFixture.finishLookup('\n')

        assertTrue(myFixture.file.text.contains("'(click.capture)': 'onClick()'"))
        assertFalse(myFixture.file.text.contains("(click.capture))"))
    }

    @Test
    fun `completes Taiga modifiers in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(click.<caret>)': 'onClick()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue("zoneless" in variants)
        assertTrue("stop" in variants)
        assertTrue("debounce~300ms" in variants)
    }

    @Test
    fun `completes Taiga custom events in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(r<caret>)': 'onResize()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue("resize" in variants)
    }

    @Test
    fun `completes global event target in Angular host metadata`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(vis<caret>)': 'onResize()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue("visualViewport" in variants)
    }

    @Test
    fun `completes events after global event target`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(visualViewport>r<caret>)': 'onResize()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertTrue(variants.any { variant -> variant.contains("resize", ignoreCase = true) })
    }

    @Test
    fun `suppresses global event binding warning in Angular host metadata`() {
        val binding = "(visualViewport>resize)"
        val file =
            configureAngularFile(
                "component.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'example',
                    host: {'$binding': 'onResize()'},
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )
        val startOffset = file.text.indexOf(binding)
        val endOffset = startOffset + binding.length
        val highlight =
            HighlightInfo
                .newHighlightInfo(HighlightInfoType.WARNING)
                .range(startOffset, endOffset)
                .descriptionAndTooltip("Unknown host event binding")
                .createUnconditionally()

        assertFalse(EventPluginKeyEventHighlightInfoFilter().accept(highlight, file))
    }

    @Test
    fun `builds popup context before typed character is inserted`() {
        val eventContext =
            HostEventPluginCompletionContext.findAfterTyping(
                text = "(",
                caretOffset = 1,
                charTyped = 'r',
            )
        val modifierContext =
            HostEventPluginCompletionContext.findAfterTyping(
                text = "(click",
                caretOffset = 6,
                charTyped = '.',
            )

        assertEquals(HostEventPluginCompletionContext.Kind.EVENT, eventContext?.kind)
        assertEquals("r", eventContext?.prefix)
        assertEquals(HostEventPluginCompletionContext.Kind.MODIFIER, modifierContext?.kind)
        assertEquals("", modifierContext?.prefix)
    }

    @Test
    fun `schedules auto popup for Taiga custom event while typing host key`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(<caret>)': 'onResize()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val result =
            TypeScriptHostEventPluginCompletionAutoPopupHandler().checkAutoPopup(
                'r',
                project,
                myFixture.editor,
                myFixture.file,
            )

        assertEquals(
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun `schedules auto popup for Taiga modifiers after event separator`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(click<caret>)': 'onClick()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val result =
            TypeScriptHostEventPluginCompletionAutoPopupHandler().checkAutoPopup(
                '.',
                project,
                myFixture.editor,
                myFixture.file,
            )

        assertEquals(
            com.intellij.codeInsight.editorActions.TypedHandlerDelegate.Result.STOP,
            result,
        )
    }

    @Test
    fun `does not repeat already used Taiga modifier in host completion`() {
        configureAngularFile(
            "component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                host: {'(click.zoneless.<caret>)': 'onClick()'},
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertFalse("zoneless" in variants)
        assertTrue("stop" in variants)
    }

    @Test
    fun `ignores similar keys outside Angular host metadata`() {
        val file =
            configureAngularFile(
                "plain.ts",
                """
                const ordinary = {'(click.zoneless)': 'value'};

                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    options: {
                        host: {'(click.stop)': 'notAngularHost'},
                    },
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )

        assertTrue(AngularHostBindingSupport.findAll(file).isEmpty())
    }

    @Test
    fun `does not complete Taiga modifiers in ordinary TypeScript objects`() {
        configureAngularFile(
            "plain.ts",
            "const ordinary = {'(click.<caret>)': 'value'};",
        )

        val variants = myFixture.completeBasic().orEmpty().map { element -> element.lookupString }

        assertFalse("zoneless" in variants)
        assertFalse("stop" in variants)
    }

    @Test
    fun `uses host binding offsets for modifier validation`() {
        val file =
            configureAngularFile(
                "invalid.ts",
                """
                import {Directive} from '@angular/core';

                @Directive({
                    selector: '[example]',
                    host: {
                        '(click.captre)': 'onClick()',
                        '(click.zoneless.zoneless)': 'onClick()',
                    },
                })
                export class ExampleDirective {}
                """.trimIndent(),
            )
        val bindings = AngularHostBindingSupport.findAll(file)
        val unknown =
            EventPluginUnknownModifierFinder
                .findInEventName(
                    bindings[0].eventName,
                    bindings[0].eventNameStartOffset,
                ).single()
        val duplicate =
            EventPluginDuplicateModifierFinder
                .findInEventName(
                    bindings[1].eventName,
                    bindings[1].eventNameStartOffset,
                ).single()

        assertEquals("captre", file.text.substring(unknown.startOffset, unknown.endOffset))
        assertEquals("zoneless", file.text.substring(duplicate.startOffset, duplicate.endOffset))
    }

    @Test
    fun `collects rem hints only from Angular host style bindings`() {
        val file =
            configureAngularFile(
                "rem-hints.ts",
                """
                import {Component} from '@angular/core';

                const ordinary = {'[style.height.rem]': '3'};

                @Component({
                    selector: 'example',
                    host: {
                        '[style.width.rem]': '2',
                        '[style.margin-left.rem]': '-0.5',
                    },
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )

        val hints = AngularHostRemStyleBindingHintCollector.collect(file)

        assertEquals(
            listOf(" 32px", " -8px"),
            hints.map(RemInlayHint::text),
        )
        assertEquals(
            listOf("2rem = 32px", "-0.5rem = -8px"),
            hints.map(RemInlayHint::tooltip),
        )
    }

    private fun configureAngularFile(
        fileName: String,
        text: String,
    ): PsiFile {
        val file = myFixture.addFileToProject("src/$fileName", text)

        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        return myFixture.file
    }

    private companion object {
        const val DOLLAR = '$'
    }
}
