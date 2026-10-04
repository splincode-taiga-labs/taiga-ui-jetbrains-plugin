package org.taigaui.designtokens.events

import com.intellij.codeInsight.editorActions.TypedHandlerDelegate
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.junit.Test

class TypeScriptHostEventPluginAutoPopupCoverageTest : LightPlatformCodeInsightFixture4TestCase() {
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
            export interface ComponentMetadata {
                selector?: string;
                host?: Record<string, string>;
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    @Test
    fun testCharTypedWithoutPreparedCaretContinues() {
        val file = configureHost("(<caret>)")
        val handler = TypeScriptHostEventPluginCompletionAutoPopupHandler()

        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.charTyped(
                'r',
                project,
                myFixture.editor,
                file,
            ),
        )
    }

    @Test
    fun testPreparedCaretMismatchContinuesWithoutSchedulingCompletion() {
        val file = configureHost("(<caret>)")
        val handler = TypeScriptHostEventPluginCompletionAutoPopupHandler()

        assertEquals(
            TypedHandlerDelegate.Result.STOP,
            handler.checkAutoPopup(
                'r',
                project,
                myFixture.editor,
                file,
            ),
        )

        myFixture.editor.caretModel.moveToOffset(myFixture.editor.caretModel.offset + 2)

        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.charTyped(
                'r',
                project,
                myFixture.editor,
                file,
            ),
        )
    }

    @Test
    fun testPreparedTriggerSchedulesBasicCompletionAfterTyping() {
        val file = configureHost("(<caret>)")
        val handler = TypeScriptHostEventPluginCompletionAutoPopupHandler()
        val before = myFixture.editor.caretModel.offset

        assertEquals(
            TypedHandlerDelegate.Result.STOP,
            handler.checkAutoPopup(
                'r',
                project,
                myFixture.editor,
                file,
            ),
        )

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(before, "r")
            myFixture.editor.caretModel.moveToOffset(before + 1)
        }

        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.charTyped(
                'r',
                project,
                myFixture.editor,
                file,
            ),
        )

        assertNotNull(waitForLookup())
    }

    @Test
    fun testNonTriggerDoesNotPrepareCompletion() {
        val file = configureHost("(<caret>)")
        val handler = TypeScriptHostEventPluginCompletionAutoPopupHandler()

        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.checkAutoPopup(
                '!',
                project,
                myFixture.editor,
                file,
            ),
        )
        assertEquals(
            TypedHandlerDelegate.Result.CONTINUE,
            handler.charTyped(
                '!',
                project,
                myFixture.editor,
                file,
            ),
        )
    }

    private fun configureHost(binding: String): PsiFile {
        val file =
            myFixture.addFileToProject(
                "src/component.ts",
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'example',
                    host: {'$binding': 'onEvent()'},
                })
                export class ExampleComponent {}
                """.trimIndent(),
            )

        myFixture.configureFromExistingVirtualFile(file.virtualFile)

        return myFixture.file
    }

    private fun waitForLookup(): Any? {
        repeat(300) {
            UIUtil.dispatchAllInvocationEvents()
            val lookup =
                runInEdtAndGet {
                    LookupManager.getActiveLookup(myFixture.editor)
                }

            if (lookup != null) {
                return lookup
            }

            Thread.sleep(10)
        }

        return runInEdtAndGet {
            LookupManager.getActiveLookup(myFixture.editor)
        }
    }
}
