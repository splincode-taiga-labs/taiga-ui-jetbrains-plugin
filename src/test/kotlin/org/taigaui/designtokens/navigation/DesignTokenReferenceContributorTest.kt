package org.taigaui.designtokens.navigation

import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiPolyVariantReference
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ProcessingContext
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenReferenceContributorTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var workspaceRoot: Path
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("design-token-navigation")
        workspaceRoot = tempRoot.resolve("workspace")
        indexService = project.getService(DesignTokenIndexService::class.java)
        indexService.clear()

        createFile(workspaceRoot.resolve("package.json"), "{}")
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
                template?: string;
                styles?: string | string[];
            }

            export declare function Component(metadata: ComponentMetadata): ClassDecorator;
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/package.json"),
            """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""",
        )
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/tokens.css"),
            ":root { --tui-installed: red; }",
        )
        createFile(
            workspaceRoot.resolve("project.json"),
            """
            {
              "targets": {
                "build": {
                  "options": {
                    "styles": ["src/styles.less"]
                  }
                }
              }
            }
            """.trimIndent(),
        )
        createFile(
            workspaceRoot.resolve("src/styles.less"),
            ":root { --tui-project: blue; }",
        )
    }

    override fun tearDown() {
        try {
            indexService.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testResolvesProjectTokenToProjectDeclaration() {
        val sourcePath = workspaceRoot.resolve("src/component.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { color: var(--tui-project); }",
            )
        val reference = reference(sourceFile, sourcePath, "--tui-project")
        val targets = reference.multiResolve(false).mapNotNull { result -> result.element }

        assertEquals(1, targets.size)
        assertEquals(
            workspaceRoot.resolve("src/styles.less").normalized(),
            targets
                .single()
                .containingFile
                .virtualFile
                .path
                .toPath()
                .normalized(),
        )
    }

    fun testResolvesInstalledTokenToPackageDeclaration() {
        val sourcePath = workspaceRoot.resolve("src/component.less")
        val sourceFile =
            createFile(
                sourcePath,
                ".demo { color: var(--tui-installed); }",
            )
        val reference = reference(sourceFile, sourcePath, "--tui-installed")
        val targets = reference.multiResolve(false).mapNotNull { result -> result.element }

        assertEquals(1, targets.size)
        assertEquals(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/tokens.css").normalized(),
            targets
                .single()
                .containingFile
                .virtualFile
                .path
                .toPath()
                .normalized(),
        )
    }

    fun testDoesNotCreateReferenceForUnknownOrDeclarationToken() {
        val unknownPath = workspaceRoot.resolve("src/unknown.less")
        val unknownFile =
            createFile(
                unknownPath,
                ".demo { color: var(--tui-unknown); }",
            )

        myFixture.configureFromExistingVirtualFile(unknownFile)
        indexService.completionTokenNames(unknownPath)

        assertEquals(
            0,
            referencesAtToken(myFixture.file, "--tui-unknown").size,
        )

        val declarationFile =
            createFile(
                workspaceRoot.resolve("src/declaration.less"),
                ":root { --tui-project: red; }",
            )

        myFixture.configureFromExistingVirtualFile(declarationFile)

        assertEquals(
            0,
            referencesAtToken(myFixture.file, "--tui-project").size,
        )
    }

    fun testReferenceRenameReturnsOriginalElement() {
        val sourcePath = workspaceRoot.resolve("src/rename.less")
        val sourceFile = createFile(sourcePath, ".demo { color: var(--tui-project); }")
        val reference = reference(sourceFile, sourcePath, "--tui-project")

        assertSame(reference.element, reference.handleElementRename("--tui-renamed"))
    }

    fun testOriginResolutionFallsBackToLeafWhenPsiHasNoNamedTokenElement() {
        val sourcePath = workspaceRoot.resolve("src/plain-token.txt")
        createFile(sourcePath, "--tui-plain")
        val origin =
            DesignTokenOrigin(
                sourceFile = sourcePath,
                line = 1,
                format = DesignTokenSourceFormat.CSS,
            )
        val method =
            Class
                .forName("org.taigaui.designtokens.navigation.DesignTokenReferenceContributorKt")
                .declaredMethods
                .single { candidate ->
                    candidate.name == "toPsiTarget" &&
                        candidate.parameterCount == 3
                }.apply { isAccessible = true }

        val target = method.invoke(null, origin, project, "--tui-plain") as PsiElement?

        assertNotNull(target)
        assertEquals("--tui-plain", target?.text)
    }

    fun testUsesTopLevelSourceFileForInjectedAngularStyles() {
        val sourcePath = workspaceRoot.resolve("src/component.ts")
        val sourceFile =
            createFile(
                sourcePath,
                """
                import {Component} from '@angular/core';

                @Component({
                    selector: 'demo',
                    template: '',
                    styles: `.demo { color: var(--tui-installed); }`,
                })
                export class Demo {}
                """.trimIndent(),
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        indexService.completionTokenNames(sourcePath)

        val injectedFiles = mutableListOf<com.intellij.psi.PsiFile>()

        myFixture.file.accept(
            object : com.intellij.psi.PsiRecursiveElementWalkingVisitor() {
                override fun visitElement(element: PsiElement) {
                    InjectedLanguageManager
                        .getInstance(project)
                        .getInjectedPsiFiles(element)
                        ?.mapNotNullTo(injectedFiles) { pair -> pair.first as? com.intellij.psi.PsiFile }
                    super.visitElement(element)
                }
            },
        )

        val injected =
            injectedFiles.firstOrNull { file -> "--tui-installed" in file.text }
                ?: error("Expected injected stylesheet PSI")
        val references = referencesAtToken(injected, "--tui-installed")
        val target =
            requireNotNull(
                references
                    .single()
                    .multiResolve(false)
                    .single()
                    .element,
            )

        assertEquals(1, references.size)
        assertEquals(
            workspaceRoot.resolve("node_modules/@taiga-ui/design-tokens/tokens.css").normalized(),
            target
                .containingFile
                .virtualFile
                .path
                .toPath()
                .normalized(),
        )
    }

    private fun reference(
        sourceFile: VirtualFile,
        sourcePath: Path,
        tokenName: String,
    ): PsiPolyVariantReference {
        myFixture.configureFromExistingVirtualFile(sourceFile)
        indexService.completionTokenNames(sourcePath)

        return referencesAtToken(myFixture.file, tokenName).single()
    }

    private fun referencesAtToken(
        file: com.intellij.psi.PsiFile,
        tokenName: String,
    ): List<PsiPolyVariantReference> {
        val offset = file.text.indexOf(tokenName) + 2
        val leaf = requireNotNull(file.findElementAt(offset))

        return DesignTokenReferenceProvider()
            .getReferencesByElement(leaf, ProcessingContext())
            .filterIsInstance<PsiPolyVariantReference>()
    }

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

    private fun String.toPath(): Path = Path.of(this)

    private fun Path.normalized(): Path = toAbsolutePath().normalize()
}
