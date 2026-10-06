package org.taigaui.designtokens.psi

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme
import java.nio.file.Path

class PsiDesignTokenSourceExtractorTest : BasePlatformTestCase() {
    private lateinit var extractor: PsiDesignTokenSourceExtractor
    private lateinit var packageRoot: Path

    override fun setUp() {
        super.setUp()
        extractor = PsiDesignTokenSourceExtractor(project)
        packageRoot =
            Path
                .of("build", "psi-fixtures", "design-tokens")
                .toAbsolutePath()
                .normalize()
    }

    fun testExtractsCssDeclarationsWithSelectorChains() {
        val declarations =
            extract(
                fileName = "tokens.css",
                content =
                    """
                    :root {
                        --tui-desktop: #fff;
                    }

                    [tuiPlatform='android'],
                    [tuiPlatform='ios'] {
                        --tui-mobile: #000;
                    }
                    """.trimIndent(),
            )

        assertEquals(2, declarations.size)
        assertDeclaration(
            declaration = declarations[0],
            name = "--tui-desktop",
            value = "#fff",
            line = 2,
            selectors = listOf(":root"),
        )
        assertDeclaration(
            declaration = declarations[1],
            name = "--tui-mobile",
            value = "#000",
            line = 7,
            selectors = listOf("[tuiPlatform='android'],\n[tuiPlatform='ios']"),
        )
    }

    fun testExtractsNestedScssSelectorChainFromOuterToInner() {
        val declaration =
            extract(
                fileName = "tokens.scss",
                content =
                    """
                    :root {
                        .theme {
                            [tuiPlatform='android'] &,
                            [tuiPlatform='ios'] & {
                                --tui-mobile: var(--tui-const-black);
                            }
                        }
                    }
                    """.trimIndent(),
            ).single()

        assertDeclaration(
            declaration = declaration,
            name = "--tui-mobile",
            value = "var(--tui-const-black)",
            line = 5,
            selectors =
                listOf(
                    ":root",
                    ".theme",
                    "[tuiPlatform='android'] &,\n[tuiPlatform='ios'] &",
                ),
        )
    }

    fun testExtractsNestedLessSelectorChainFromOuterToInner() {
        val declaration =
            extract(
                fileName = "tokens.less",
                content =
                    """
                    :root {
                        .theme {
                            [tuiPlatform='android'] & {
                                --tui-mobile: var(--tui-const-black);
                            }
                        }
                    }
                    """.trimIndent(),
            ).single()

        assertDeclaration(
            declaration = declaration,
            name = "--tui-mobile",
            value = "var(--tui-const-black)",
            line = 4,
            selectors =
                listOf(
                    ":root",
                    ".theme",
                    "[tuiPlatform='android'] &",
                ),
        )
    }

    fun testPreservesMultilineRawValue() {
        val declaration =
            extract(
                fileName = "shadow.css",
                content =
                    """
                    :root {
                        --tui-shadow:
                            0 1px 2px rgb(0 0 0 / 10%),
                            0 2px 4px rgb(0 0 0 / 20%);
                    }
                    """.trimIndent(),
            ).single()

        assertEquals(
            "0 1px 2px rgb(0 0 0 / 10%),\n        0 2px 4px rgb(0 0 0 / 20%)",
            declaration.value,
        )
    }

    fun testIgnoresCommentsStringsReferencesAndUnrelatedProperties() {
        val declarations =
            extract(
                fileName = "ignored.scss",
                content =
                    """
                    :root {
                        color: var(--tui-reference);
                        --company-token: red;
                        /* --tui-commented: blue; */
                        content: "--tui-string: green;";
                    }
                    """.trimIndent(),
            )

        assertEmpty(declarations)
    }

    fun testIgnoresMalformedTaigaTokenNames() {
        val declarations =
            extract(
                fileName = "malformed.css",
                content =
                    """
                    :root {
                        --tui-radius: 1rem;
                        --tui-radius.%: 2rem;
                        --tui-radius.foo: 3rem;
                    }
                    """.trimIndent(),
            )

        assertEquals(listOf("--tui-radius"), declarations.map(DesignTokenDeclaration::name))
    }

    fun testSelectorContextDoesNotLeakToSiblingRuleset() {
        val declarations =
            extract(
                fileName = "siblings.css",
                content =
                    """
                    [tuiPlatform='android'] {
                        --tui-mobile: #000;
                    }

                    :root {
                        --tui-desktop: #fff;
                    }
                    """.trimIndent(),
            )

        assertEquals(listOf("[tuiPlatform='android']"), declarations[0].selectorChain)
        assertEquals(listOf(":root"), declarations[1].selectorChain)
    }

    fun testBuildsIosAndDesktopVariantsFromSelectorsInSameDesktopFile() {
        val sourceFile = packageRoot.resolve("palette/light.css")
        val declarations =
            extract(
                fileName = "light.css",
                sourceFile = sourceFile,
                content =
                    """
                    :root {
                        --tui-background: #fff;

                        [tuiPlatform='ios'] & {
                            --tui-background: #eee;
                        }
                    }
                    """.trimIndent(),
            )

        val variants =
            DesignTokenIndex
                .build(packageRoot, declarations)
                .find("--tui-background")

        assertEquals(2, variants.size)
        assertEquals(DesignTokenPlatform.DESKTOP, variants[0].context.platform)
        assertEquals(DesignTokenPlatform.IOS, variants[1].context.platform)
        assertEquals(listOf(":root"), variants[0].origins.single().selectorChain)
        assertEquals(
            listOf(":root", "[tuiPlatform='ios'] &"),
            variants[1].origins.single().selectorChain,
        )
    }

    fun testBuildsSingleMobileDarkVariantFromEquivalentSelectorList() {
        val sourceFile = packageRoot.resolve("tokens.css")
        val selectorList =
            """
            [data-platform='ios'][tuiTheme='dark'],
            [data-platform='android'][tuiTheme='dark'],
            [data-platform='ios'] [tuiTheme='dark'],
            [data-platform='android'] [tuiTheme='dark'],
            [tuiTheme='dark'] [data-platform='ios'],
            [tuiTheme='dark'] [data-platform='android']
            """.trimIndent()
        val declarations =
            extract(
                fileName = "tokens.css",
                sourceFile = sourceFile,
                content =
                    """
                    $selectorList {
                        --tui-background-base: var(--tui-const-black);
                    }
                    """.trimIndent(),
            )

        assertEquals(1, declarations.size)
        assertDeclaration(
            declaration = declarations.single(),
            name = "--tui-background-base",
            value = "var(--tui-const-black)",
            line = 7,
            selectors = listOf(selectorList),
        )

        val variant =
            DesignTokenIndex
                .build(packageRoot, declarations)
                .find("--tui-background-base")
                .single()
        val originSelectors =
            variant.origins
                .single()
                .selectorChain
                .map(::normalizeSelector)

        assertEquals(DesignTokenPlatform.MOBILE, variant.context.platform)
        assertEquals(DesignTokenTheme.DARK, variant.context.theme)
        assertEquals("var(--tui-const-black)", variant.rawValue)
        assertEquals(1, variant.origins.size)
        assertEquals(listOf(normalizeSelector(selectorList)), originSelectors)
    }

    fun testExtractsDeprecationFromFollowingCommentSeparatedByWhitespace() {
        val declaration =
            extract(
                fileName = "deprecated.css",
                content =
                    """
                    :root {
                        --tui-old: #fff;

                        /** @deprecated use --tui-new instead */
                    }
                    """.trimIndent(),
            ).single()

        assertNotNull(declaration.deprecation)
        assertEquals("--tui-new", declaration.deprecation?.replacement)
    }

    fun testNormalizesStoredSourcePath() {
        val sourceFile = packageRoot.resolve("palette/../palette/light.css")
        val declaration =
            extract(
                fileName = "light.css",
                sourceFile = sourceFile,
                content = ":root { --tui-background: #fff; }",
            ).single()

        assertEquals(packageRoot.resolve("palette/light.css"), declaration.sourceFile)
    }

    fun testReturnsEmptyListForStylesheetWithoutTokenDeclarations() {
        assertEmpty(
            extract(
                fileName = "empty.less",
                content = ".button { color: red; }",
            ),
        )
    }

    private fun extract(
        fileName: String,
        content: String,
        sourceFile: Path = packageRoot.resolve(fileName),
    ): List<DesignTokenDeclaration> =
        extractor.extract(
            psiFile = createPsiFile(fileName, content),
            sourceFile = sourceFile,
        )

    private fun createPsiFile(
        fileName: String,
        content: String,
    ): PsiFile {
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

        return PsiFileFactory.getInstance(project).createFileFromText(
            fileName,
            fileType,
            content,
        )
    }

    private fun assertDeclaration(
        declaration: DesignTokenDeclaration,
        name: String,
        value: String,
        line: Int,
        selectors: List<String>,
    ) {
        assertEquals(name, declaration.name)
        assertEquals(value, declaration.value)
        assertEquals(line, declaration.line)
        assertEquals(
            selectors.map(::normalizeSelector),
            declaration.selectorChain.map(::normalizeSelector),
        )
    }

    private fun normalizeSelector(selector: String): String = selector.replace(WHITESPACE, " ").trim()

    private companion object {
        val WHITESPACE = Regex("""\s+""")
    }
}
