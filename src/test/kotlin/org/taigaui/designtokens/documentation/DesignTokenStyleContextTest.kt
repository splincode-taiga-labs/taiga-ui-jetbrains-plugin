package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.ReadAction
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.completion.designTokenCompletionContextAt

class DesignTokenStyleContextTest : LightPlatformCodeInsightFixture4TestCase() {
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
            export interface ComponentMetadata {
                selector?: string;
                template?: string;
                styles?: string | string[];
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
    }

    @Test
    fun `recognizes physical stylesheets`() {
        myFixture.configureByText(
            "styles.less",
            ".example { color: var(--tui-text-primary); }",
        )

        assertTrue(isDesignTokenStyleContext())
    }

    @Test
    fun `recognizes Angular inline styles as injected CSS`() {
        configureAngularFile(
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                template: '',
                styles: `
                    .example {
                        color: var(--tui-text-primary);
                    }
                `,
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertTrue(isDesignTokenStyleContext())
    }

    @Test
    fun `maps Angular inline styles to design token completion context`() {
        configureAngularFile(
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                template: '',
                styles: `
                    .example {
                        color: var(--tui-te);
                    }
                `,
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )

        PsiDocumentManager.getInstance(project).commitAllDocuments()

        val tokenEnd =
            myFixture.editor.document.text
                .indexOf("--tui-te")
                .plus("--tui-te".length)
        val prefix =
            ReadAction.compute<String?, RuntimeException> {
                myFixture.editor.designTokenCompletionContextAt(tokenEnd)?.prefix
            }

        assertEquals("--tui-te", prefix)
    }

    @Test
    fun `does not treat ordinary TypeScript strings as styles`() {
        myFixture.configureByText(
            "example.ts",
            "const value = 'var(--tui-text-primary)';",
        )

        PsiDocumentManager.getInstance(project).commitAllDocuments()

        assertFalse(isDesignTokenStyleContext())
    }

    @Test
    fun `exposes physical design token source file path`() {
        myFixture.configureByText(
            "source.scss",
            ".demo { color: var(--tui-text-primary); }",
        )

        val source =
            ReadAction.compute<java.nio.file.Path?, RuntimeException> {
                myFixture.editor.designTokenSourceFile()
            }

        assertNotNull(source)
        assertTrue(requireNotNull(source).toString().endsWith("source.scss"))
    }

    @Test
    fun `design token near scan respects radius and document boundaries`() {
        val nearStart = "--tui-token" + "x".repeat(200)
        val nearEnd = "x".repeat(200) + "--tui-token"
        val far = "--tui-token" + "x".repeat(200)

        assertTrue(nearStart.hasDesignTokenNear(0))
        assertTrue(nearEnd.hasDesignTokenNear(nearEnd.length))
        assertFalse(far.hasDesignTokenNear(far.length))
        assertFalse("plain text".hasDesignTokenNear(5))
    }

    private fun isDesignTokenStyleContext(): Boolean =
        ReadAction.compute<Boolean, RuntimeException> {
            myFixture.editor.isDesignTokenStyleContext(tokenOffset())
        }

    private fun tokenOffset(): Int =
        myFixture.editor.document.text
            .indexOf("--tui-text-primary")
            .takeIf { offset -> offset >= 0 }
            ?.plus(2)
            ?: error("Token was not found")

    private fun configureAngularFile(text: String) {
        val file = myFixture.addFileToProject("src/component.ts", text)

        myFixture.configureFromExistingVirtualFile(file.virtualFile)
    }
}
