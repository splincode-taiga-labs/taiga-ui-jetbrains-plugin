package org.taigaui.designtokens.documentation

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.LightPlatformCodeInsightFixture4TestCase
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TaigaQuickDocumentationIntegrationTest : LightPlatformCodeInsightFixture4TestCase() {
    private val docsSource = requireNotNull(TaigaDocsSources.forMajor(5))
    private val docsCache = TaigaDocsCache()
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("taiga-quick-docs")
        docsCache.invalidate(docsSource)
        configureAngularProject()
        configureTaigaPackage()
    }

    override fun tearDown() {
        try {
            docsCache.invalidate(docsSource)
            project.service<TaigaDocsService>().clearMemory()
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    @Test
    fun `renders directive documentation for Taiga selector`() {
        val file = configureTemplate("<button tuiButton>Save</button>")
        warmDocumentation(file)
        val html = renderDocumentation(file, "tuiButton")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("TuiButton"))
        assertTrue(requireNotNull(html).contains("Directive"))
        assertTrue(requireNotNull(html).contains("@taiga-ui/core"))
        assertTrue(requireNotNull(html).contains("&lt;button tuiButton&gt;Save&lt;/button&gt;"))
    }

    @Test
    fun `renders focused input documentation`() {
        val file = configureTemplate("<button tuiButton [iconEnd]=\"icon\">Save</button>")
        warmDocumentation(file)
        val html = renderDocumentation(file, "[iconEnd]")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("iconEnd"))
        assertTrue(requireNotNull(html).contains("Input"))
        assertTrue(requireNotNull(html).contains("of TuiButton"))
        assertTrue(requireNotNull(html).contains("TuiIcon"))
        assertTrue(requireNotNull(html).contains("Icon displayed at the end"))
        assertTrue(requireNotNull(html).contains("[iconEnd]"))
    }

    @Test
    fun `renders literal possible values for input`() {
        val file = configureTemplate("<button tuiButton [size]=\"size\">Save</button>")
        warmDocumentation(file)
        val html = renderDocumentation(file, "[size]")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("Possible values"))
        assertTrue(requireNotNull(html).contains("xs"))
        assertTrue(requireNotNull(html).contains("xl"))
    }

    @Test
    fun `renders focused Taiga output documentation`() {
        val file = configureTemplate("<button tuiButton (valueChange)=\"onValue(\$event)\">Save</button>")
        warmDocumentation(file)
        val html = renderDocumentation(file, "(valueChange)")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("valueChange"))
        assertTrue(requireNotNull(html).contains("Output"))
        assertTrue(requireNotNull(html).contains("MouseEvent"))
        assertTrue(requireNotNull(html).contains("Emitted when the button value changes"))
    }

    @Test
    fun `does not claim native click as Taiga output`() {
        val file = configureTemplate("<button tuiButton (click)=\"onClick()\">Save</button>")
        warmDocumentation(file)
        val offset = file.text.indexOf("(click)") + 2

        val targets = TaigaQuickDocumentationTargetProvider().documentationTargets(file, offset)

        assertTrue(targets.isEmpty())
    }

    @Test
    fun `renders Taiga element documentation`() {
        val file = configureTemplate("<tui-calendar></tui-calendar>")
        warmDocumentation(file)
        val html = renderDocumentation(file, "tui-calendar")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("TuiCalendar"))
        assertTrue(requireNotNull(html).contains("Component"))
    }

    @Test
    fun `renders TypeScript public symbol and type documentation`() {
        val file =
            configureTypeScript(
                """
                import {TuiAppearance, TuiButton} from '@taiga-ui/core';

                const button = TuiButton;
                const appearance: TuiAppearance = 'primary';
                """.trimIndent(),
            )
        warmDocumentation(file)

        val buttonHtml = renderDocumentation(file, "TuiButton", occurrence = 2)
        val typeHtml = renderDocumentation(file, "TuiAppearance", occurrence = 2)

        assertNotNull(buttonHtml)
        assertTrue(requireNotNull(buttonHtml).contains("TuiButton"))
        assertTrue(requireNotNull(buttonHtml).contains("@taiga-ui/core"))
        assertNotNull(typeHtml)
        assertTrue(requireNotNull(typeHtml).contains("TuiAppearance"))
        assertTrue(requireNotNull(typeHtml).contains("Type"))
        assertTrue(requireNotNull(typeHtml).contains("primary"))
        assertTrue(requireNotNull(typeHtml).contains("secondary"))
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
    fun `resolver ignores Taiga-looking text in ordinary TypeScript string`() {
        val file = myFixture.configureByText("plain.ts", "const example = 'TuiButton';")
        val offset = file.text.indexOf("TuiButton") + 3

        assertTrue(TaigaDocumentationResolver.findRequest(file, offset) == null)
    }

    private fun renderDocumentation(
        file: PsiFile,
        needle: String,
        occurrence: Int = 1,
    ): String? {
        val offset = nthIndexOf(file.text, needle, occurrence) + (needle.length / 2)

        var html: String? = null
        runInEdtAndWait {
            val target =
                TaigaQuickDocumentationTargetProvider()
                    .documentationTargets(file, offset)
                    .singleOrNull()

            html = target?.let { value -> computeDocumentationBlocking(value.createPointer())?.html }
        }

        return html
    }

    private fun nthIndexOf(
        text: String,
        needle: String,
        occurrence: Int,
    ): Int {
        var fromIndex = 0
        var index = -1

        repeat(occurrence) {
            index = text.indexOf(needle, fromIndex)
            require(index >= 0) { "Cannot find occurrence $occurrence of $needle" }
            fromIndex = index + needle.length
        }

        return index
    }

    private fun configureTemplate(template: String): PsiFile {
        val file = createFile(workspaceRoot.resolve("src/component.html"), template)

        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()

        return myFixture.file
    }

    private fun configureTypeScript(source: String): PsiFile {
        val file = createFile(workspaceRoot.resolve("src/consumer.ts"), source)

        myFixture.configureFromExistingVirtualFile(file)
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
        createFile(
            workspaceRoot.resolve("angular.json"),
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
        createFile(
            workspaceRoot.resolve("package.json"),
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
        createFile(
            workspaceRoot.resolve("node_modules/@angular/core/package.json"),
            """
            {
              "name": "@angular/core",
              "version": "17.3.0",
              "types": "index.d.ts"
            }
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("node_modules/@angular/core/index.d.ts"),
            """
            export interface ComponentMetadata {
                selector?: string;
                templateUrl?: string;
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("src/component.ts"),
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
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/core/package.json"),
            """
            {
              "name": "@taiga-ui/core",
              "version": "5.18.0",
              "types": "index.d.ts",
              "web-types": "web-types.json"
            }
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/core/index.d.ts"),
            """
            export declare const TuiButton: unique symbol;
            export type TuiAppearance = 'primary' | 'secondary' | 'accent' | 'neutral';
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/core/web-types.json"),
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
            "**Types:**",
            "",
            "### appearance",
            FENCE + "text",
            "TuiAppearance",
            FENCE,
            "",
            "# components/Button",
            "- **Package**: " + tick("CORE"),
            "- **Type**: components",
            "- **Version**: 5.0.0",
            "",
            "Styles native buttons using Taiga UI appearances, sizes and loading states.",
            "",
            "### API - Inputs",
            "| Property | Type | Description |",
            "| --- | --- | --- |",
            "| " +
                tick("[iconEnd]") +
                " | " +
                tick("TuiIcon") +
                " | Icon displayed at the end of the button content. |",
            "| " +
                tick("[size]") +
                " | " +
                tick("'xs' \\| 's' \\| 'm' \\| 'l' \\| 'xl'") +
                " | Controls the button size. |",
            "| " +
                tick("[appearance]") +
                " | " +
                tick("TuiAppearance") +
                " | Visual style of the button. |",
            "",
            "### API - Outputs",
            "| Event | Type | Description |",
            "| --- | --- | --- |",
            "| " +
                tick("(valueChange)") +
                " | " +
                tick("MouseEvent") +
                " | Emitted when the button value changes. |",
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
            "",
            "# types/Appearance",
            "- **Package**: " + tick("CORE"),
            "- **Type**: types",
            "- **Version**: 5.0.0",
            "",
            "Available button appearances.",
        ).joinToString("\n")

    private fun createFile(
        path: Path,
        content: String,
    ): VirtualFile {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)

        return requireNotNull(
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path),
        )
    }

    private fun tick(value: String): String = BACKTICK + value + BACKTICK

    private companion object {
        const val BACKTICK = "\u0060"
        const val FENCE = "\u0060\u0060\u0060"
    }
}
