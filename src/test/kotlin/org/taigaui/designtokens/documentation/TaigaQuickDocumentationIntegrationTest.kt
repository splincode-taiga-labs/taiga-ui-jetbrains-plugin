package org.taigaui.designtokens.documentation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.components.service
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import com.intellij.testFramework.runInEdtAndWait
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Path

@RunWith(JUnit4::class)
class TaigaQuickDocumentationIntegrationTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    private val docsSource = requireNotNull(TaigaDocsSources.forMajor(5))
    private val docsCache = TaigaDocsCache()
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Path.of(myFixture.tempDirPath)
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
    fun `renders directive documentation for Taiga selector`() {
        val file =
            configureTemplate(
                "<button appearance=\"secondary\" size=\"xs\" tuiButton [disabled]=\"disabled\">Current</button>",
            )
        warmDocumentation(file)
        val html = renderDocumentation(file, "tuiButton")

        assertNotNull(html)
        assertTrue(requireNotNull(html).contains("TuiButton"))
        assertTrue(requireNotNull(html).contains("Directive"))
        assertTrue(requireNotNull(html).contains("@taiga-ui/core"))
        assertTrue(
            "Expected the canonical documentation example: $html",
            requireNotNull(html).contains(StringUtil.escapeXmlEntities("<button tuiButton>Save</button>")),
        )
        assertTrue(!requireNotNull(html).contains("Current"))
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

    @Test
    fun testShowsInstalledPipeInAngularInterpolation() {
        val file = configureTemplate("<div>{{ value | tuiMapper : mapper }}</div>")
        warmDocumentation(file)
        myFixture.doHighlighting()

        val html = renderDocumentation(file, "tuiMapper")

        assertNotNull(if (html == null) pipeDiagnostics(file) else "", html)
        assertTrue(requireNotNull(html).contains("TuiMapperPipe"))
        assertTrue(requireNotNull(html).contains("Pipe"))
        assertTrue(requireNotNull(html).contains("@taiga-ui/cdk"))
        assertTrue(requireNotNull(html).contains("Parameters"))
        assertTrue(requireNotNull(html).contains("Result"))
        assertTrue(requireNotNull(html).contains("...args"))
        assertFalse(requireNotNull(html).contains("Pure pipe"))
        assertPipeRange(file)
    }

    @Test
    fun testShowsInstalledPipeInInlineTemplate() {
        val file =
            configureTypeScript(
                """
                import {Component} from '@angular/core';
                import {TuiMapperPipe} from '@taiga-ui/cdk';
                @Component({
                    selector: 'inline-example',
                    standalone: true,
                    imports: [TuiMapperPipe],
                    template: '<div>{{ value | tuiMapper : mapper }}</div>',
                })
                export class InlineExampleComponent {}
                """.trimIndent(),
            )
        warmDocumentation(file)
        myFixture.doHighlighting()

        val html = renderDocumentation(file, "tuiMapper")

        assertNotNull(if (html == null) pipeDiagnostics(file) else "", html)
        assertTrue(requireNotNull(html).contains("TuiMapperPipe"))
        assertTrue(requireNotNull(html).contains("Parameters"))
        assertPipeRange(file)
    }

    private fun assertPipeRange(file: PsiFile) {
        runInEdtAndWait {
            val start = file.text.indexOf("tuiMapper")
            val request = TaigaDocumentationResolver.findRequest(file, start + 3)

            assertNotNull(request)
            assertEquals(start, requireNotNull(request).startOffset)
            assertEquals(start + "tuiMapper".length, request.endOffset)
        }
    }

    private fun pipeDiagnostics(file: PsiFile): String {
        val offset = file.text.indexOf("tuiMapper") + 3
        val element = requireNotNull(file.findElementAt(offset))
        val candidate =
            InjectedLanguageManager
                .getInstance(project)
                .findInjectedElementAt(file, offset) ?: element
        val candidateFile = candidate.containingFile
        val candidateOffset = if (candidateFile == file) offset else candidate.textOffset
        val declaration = candidate.resolveTaigaDeclaration(candidateFile, candidateOffset, incompleteCode = true)
        val resolved =
            candidate.candidateReferences(candidateFile, candidateOffset).flatMap { reference ->
                if (reference is PsiPolyVariantReference) {
                    reference.multiResolve(true).mapNotNull { it.element }
                } else {
                    listOfNotNull(reference.resolve())
                }
            }

        return "Host=${element.javaClass.name}, candidate=${candidate.javaClass.name}, " +
            "references=${candidate.candidateReferences(candidateFile, candidateOffset)}, " +
            "declaration=${declaration?.javaClass?.name}: ${declaration?.text?.take(1_500)}, " +
            "local=${declaration?.localDocumentation(null)}, " +
            "resolved=${resolved.map { it.containingFile?.virtualFile?.path to it.text.take(500) }}"
    }

    @Test
    fun testDoesNotClaimUnknownOrNativePipes() {
        val file = configureTemplate("<div>{{ value | tuiUnknownPipe }} {{ value | async }}</div>")
        warmDocumentation(file)

        assertNull(renderDocumentation(file, "tuiUnknownPipe"))
        assertNull(renderDocumentation(file, "async"))
    }

    @Test
    fun testPreviewsOnlyStaticIconBindings() {
        val file = configureTemplate("<button tuiButton [iconEnd]=\"'@tui.eye'\">Save</button>")
        warmDocumentation(file)
        val request = TaigaDocumentationResolver.findRequest(file, file.text.indexOf("tuiButton") + 2)

        assertEquals("@tui.eye", (request as? TaigaDocumentationRequest.Entity)?.icons?.singleOrNull()?.name)

        val dynamic =
            configureTemplate("<button tuiButton [iconEnd]=\"shown ? '@tui.eye' : '@tui.eye-off'\">Save</button>")
        val dynamicRequest = TaigaDocumentationResolver.findRequest(dynamic, dynamic.text.indexOf("tuiButton") + 2)

        assertTrue((dynamicRequest as? TaigaDocumentationRequest.Entity)?.icons?.isEmpty() == true)
    }

    @Test
    fun testPlainInputAndEntityCardsCaptureExistingBinding() {
        val file = configureTemplate("<button tuiButton size=\"s\">Save</button>")
        warmDocumentation(file)
        val member = TaigaDocumentationResolver.findRequest(file, file.text.indexOf("size") + 1)
        assertEquals("s", (member as? TaigaDocumentationRequest.Member)?.binding?.literal)
        assertNotNull(renderDocumentation(file, "size"))
        val entity = TaigaDocumentationResolver.findRequest(file, file.text.indexOf("tuiButton") + 2)
        assertEquals("s", (entity as? TaigaDocumentationRequest.Entity)?.bindings?.firstOrNull { it.name == "size" }?.literal)
    }

    @Test
    fun testInstalledInputAliasesResolveToFiniteValuesAndCyclesRemainUnknown() {
        val virtualFile = createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/core/binding.d.ts"),
            """
            export type TuiSmall = 's' | 'm';
            export type TuiLarge = 'l';
            export type TuiSizes = TuiSmall | TuiLarge;
            export type TuiCycleA = TuiCycleB;
            export type TuiCycleB = TuiCycleA;
            export declare class TuiBinding {
                size: InputSignal<TuiSizes>;
                cycle: InputSignal<TuiCycleA>;
            }
            """.trimIndent(),
        )
        myFixture.configureFromExistingVirtualFile(virtualFile)
        val file = myFixture.file
        val element = requireNotNull(file.findElementAt(file.text.indexOf("TuiBinding")))
        val local = element.localDocumentation("TuiBinding")
        val contexts = generateSequence(element) { it.parent }.take(6)
            .joinToString { "${it.javaClass.simpleName}:${it.textRange}:${it.text.take(80)}" }
        assertEquals("Types: ${local.inputTypes}; contexts: $contexts", listOf("s", "m", "l"), local.inputValues["size"])
        assertTrue(local.inputValues["cycle"].orEmpty().isEmpty())
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
        project.service<TaigaDocsService>().invalidate(docsSource.majorVersion)
        assertTrue("Test documentation cache must be writable", docsCache.write(docsSource, docsFixture()))
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
                "@taiga-ui/cdk": "5.18.0",
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
                template?: string;
                standalone?: boolean;
                imports?: unknown[];
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            export interface PipeMetadata {
                name: string;
                pure?: boolean;
                standalone?: boolean;
            }
            export declare function Pipe(metadata: PipeMetadata): ClassDecorator;
            export interface ɵɵPipeDeclaration<T, Name extends string, Standalone extends boolean> {}
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("src/component.ts"),
            """
            import {Component} from '@angular/core';
            import {TuiMapperPipe} from '@taiga-ui/cdk';

            @Component({
                selector: 'example',
                templateUrl: './component.html',
                standalone: true,
                imports: [TuiMapperPipe],
            })
            export class ExampleComponent {}
            """.trimIndent(),
        )
    }

    private fun configureTaigaPackage() {
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/cdk/package.json"),
            """{"name":"@taiga-ui/cdk","version":"5.18.0","types":"index.d.ts"}""",
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/cdk/index.d.ts"),
            """
            import * as i0 from '@angular/core';
            export declare class TuiMapperPipe {
                transform<T extends unknown[], U, G>(value: U, mapper: (value: U, ...args: T) => G, ...args: T): G;
                static ɵpipe: i0.ɵɵPipeDeclaration<TuiMapperPipe, "tuiMapper", true>;
            }
            """.trimIndent(),
        )
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
            "## @taiga-ui/cdk",
            "**Pipes:**",
            "### mapper",
            FENCE + "text",
            "TuiMapperPipe",
            FENCE,
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
            "",
            "# pipes/Mapper",
            "- **Package**: " + tick("CDK"),
            "- **Type**: pipes",
            "- **Version**: 5.0.0",
            "",
            "Maps a value through a function.",
            "",
            "### Example",
            FENCE + "html",
            "{{ value | tuiMapper : mapper }}",
            FENCE,
        ).joinToString("\n")

    private fun createFile(
        path: Path,
        content: String,
    ): VirtualFile {
        val relativePath =
            workspaceRoot
                .relativize(path)
                .toString()
                .replace('\\', '/')

        return myFixture.tempDirFixture.createFile(relativePath, content)
    }

    private fun tick(value: String): String = BACKTICK + value + BACKTICK

    private companion object {
        const val BACKTICK = "\u0060"
        const val FENCE = "\u0060\u0060\u0060"
    }
}
