package org.taigaui.designtokens.psi

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.psi.PsiFileFactory
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class PsiDesignTokenDeprecationTest : BasePlatformTestCase() {
    fun testExtractsLeadingDeprecationCommentAndReplacement() {
        val declaration =
            extract(
                """
                :root {
                    /** @deprecated use --tui-new instead */
                    --tui-old: #fff;
                    --tui-new: #fff;
                }
                """.trimIndent(),
            ).first { declaration -> declaration.name == "--tui-old" }

        assertEquals("use --tui-new instead", declaration.deprecation?.message)
        assertEquals("--tui-new", declaration.deprecation?.replacement)
    }

    fun testDoesNotLeakDeprecationToAdjacentDeclaration() {
        val declarations =
            extract(
                """
                :root {
                    /** @deprecated use --tui-new instead */
                    --tui-old: #fff;
                    --tui-new: #000;
                }
                """.trimIndent(),
            ).associateBy { declaration -> declaration.name }

        assertNotNull(declarations.getValue("--tui-old").deprecation)
        assertNull(declarations.getValue("--tui-new").deprecation)
    }

    fun testKeepsInlineDeprecationWithOwningLessDeclaration() {
        val declarations =
            extract(
                content =
                    """
                    :root {
                        --tui-background-engaging-pressed: #456; // @deprecated: Replaced with accent-pressed
                        --tui-background-brand-tinkoff: #ffd22d; // @deprecated: Moved to brand group
                    }
                    """.trimIndent(),
                fileName = "tokens.less",
            ).associateBy { declaration -> declaration.name }

        assertEquals(
            "Replaced with accent-pressed",
            declarations.getValue("--tui-background-engaging-pressed").deprecation?.message,
        )
        assertEquals(
            "Moved to brand group",
            declarations.getValue("--tui-background-brand-tinkoff").deprecation?.message,
        )
    }

    private fun extract(content: String, fileName: String = "tokens.css") =
        PsiDesignTokenSourceExtractor(project).extract(
            psiFile =
                PsiFileFactory
                    .getInstance(project)
                    .createFileFromText(
                        fileName,
                        FileTypeManager.getInstance().getFileTypeByFileName(fileName),
                        content,
                    ),
            sourceFile = Path.of("build", "deprecated-token-fixture", fileName),
        )
}
