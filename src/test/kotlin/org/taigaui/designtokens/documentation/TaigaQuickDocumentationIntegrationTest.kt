package org.taigaui.designtokens.documentation

import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.components.service
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class TaigaQuickDocumentationIntegrationTest : LightPlatformCodeInsightFixture4TestCase() {
    private val docsSource = requireNotNull(TaigaDocsSources.forMajor(5))
    private val docsCache = TaigaDocsCache()

    override fun setUp() {
        super.setUp()
        docsCache.invalidate(docsSource)
        configureAngularProject()
        configureTaigaPackage()
    }

    override fun tearDown() {
        try {
            docsCache.invalidate(docsSource)
            project.service<TaigaDocsService>().clearMemory()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun `registered provider renders local Taiga directive documentation`() {
        val file = configureTemplate("<button tuiButton>Save</button>")
        warmDocumentation(file)
        val offset = file.text.indexOf("tuiButton") + 3

        var html: String? = null
        runInEdtAndWait {
            val targets =
                IdeDocumentationTargetProvider
                    .getInstance(project)
                    .documentationTargets(myFixture.editor, file, offset)
            val documentation =
                targets
                    .asSequence()
                    .mapNotNull { target -> computeDocumentationBlocking(target.createPointer()) }
                    .firstOrNull { data -> "TuiButton" in data.html }

            html = documentation?.html
        }

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("@taiga-ui/core"))
        assertTrue(requireNotNull(html).contains("Open full Taiga UI documentation"))
    }

    @Test
    fun `registered provider renders local Taiga element documentation`() {
        val file = configureTemplate("<tui-calendar></tui-calendar>")
        warmDocumentation(file)
        val offset = file.text.indexOf("tui-calendar") + 4

        var html: String? = null
        runInEdtAndWait {
            val targets =
                IdeDocumentationTargetProvider
                    .getInstance(project)
                    .documentationTargets(myFixture.editor, file, offset)
            val documentation =
                targets
                    .asSequence()
                    .mapNotNull { target -> computeDocumentationBlocking(target.createPointer()) }
                    .firstOrNull { data -> "TuiCalendar" in data.html }

            html = documentation?.html
        }

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("tui-calendar"))
    }

    @Test
    fun `provider stays fail open while documentation cache is cold`() {
        val file = configureTemplate("<button tuiButton>Save</button>")
        val offset = file.text.indexOf("tuiButton") + 3

        val targets = TaigaQuickDocumentationTargetProvider().documentationTargets(file, offset)

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `provider stays fail open for local Taiga-looking symbol missing from docs`() {
        val file = configureTemplate("<div tuiUnknown></div>")
        warmDocumentation(file)
        val offset = file.text.indexOf("tuiUnknown") + 3

        val targets = TaigaQuickDocumentationTargetProvider().documentationTargets(file, offset)

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `resolver ignores Taiga-looking text outside HTML compatible files`() {
        val file = myFixture.configureByText("plain.ts", "const example = '<button tuiButton></button>';")
        val offset = file.text.indexOf("tuiButton") + 3

        assertTrue(TaigaTemplateDocumentationResolver.find(file, offset) == null)
    }

    private fun configureTemplate(template: String): PsiFile {
        val file = myFixture.addFileToProject("src/component.html", template)

        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        return myFixture.file
    }

    private fun warmDocumentation(file: PsiFile) {
        docsCache.write(docsSource, docsFixture())
        val path = Path.of(requireNotNull(file.virtualFile).path)

        runBlocking {
            assertNotNull(project.service<TaigaDocsService>().snapshotFor(path))
        }
    }

    private fun configureAngularProject() {
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
            "package.json",
            """
            {
              "name": "quick-docs-fixture",
              "private": true,
              "dependencies": {
                "@angular/core": "17.3.0",
                "@taiga-ui/core": "5.18.0"
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
                templateUrl?: string;
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "src/component.ts",
            """
            import {Component} from '@angular/core';

            @Component({
                selector: 'example',
                templateUrl: './component.html',
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )
    }

    private fun configureTaigaPackage() {
        myFixture.addFileToProject(
            "node_modules/@taiga-ui/core/package.json",
            """
            {
              "name": "@taiga-ui/core",
              "version": "5.18.0",
              "web-types": "web-types.json"
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "node_modules/@taiga-ui/core/web-types.json",
            """
            {
              "name": "@taiga-ui/core",
              "framework": "angular",
              "version": "5.18.0",
              "contributions": {
                "html": {
                  "elements": [
                    {"name": "tui-calendar"}
                  ],
                  "attributes": [
                    {"name": "tuiButton"},
                    {"name": "tuiUnknown"}
                  ]
                }
              }
            }
            """.trimIndent(),
        )
    }

    private fun docsFixture(): String =
        listOf(
            "# Import Map - Package Exports Reference",
            "",
            "## @taiga-ui/core",
            "",
            "**Components:**",
            "",
            "### button",
            FENCE + "text",
            "TuiButton",
            FENCE,
            "",
            "### calendar",
            FENCE + "text",
            "TuiCalendar",
            FENCE,
            "",
            "# components/Button",
            "- **Package**: " + tick("CORE"),
            "- **Type**: components",
            "- **Version**: 5.0.0",
            "",
            "Button is a basic component.",
            "",
            "### Example",
            FENCE + "html",
            "<button tuiButton>Save</button>",
            FENCE,
            "",
            "# components/Calendar",
            "- **Package**: " + tick("CORE"),
            "- **Type**: components",
            "- **Version**: 5.0.0",
            "",
            "Calendar displays a month.",
            "",
            "### Example",
            FENCE + "html",
            "<tui-calendar></tui-calendar>",
            FENCE,
        ).joinToString("\n")

    private fun tick(value: String): String = BACKTICK + value + BACKTICK

    private companion object {
        const val BACKTICK = "\u0060"
        const val FENCE = "\u0060\u0060\u0060"
    }
}
