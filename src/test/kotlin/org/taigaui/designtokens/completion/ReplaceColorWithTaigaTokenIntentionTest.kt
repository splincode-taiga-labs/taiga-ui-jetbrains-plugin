package org.taigaui.designtokens.completion

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.resolution.CssLiteralColor
import java.nio.file.Files
import java.nio.file.Path

class ReplaceColorWithTaigaTokenIntentionTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private val intention = ReplaceColorWithTaigaTokenIntention()

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("taiga-color-intention")
        createFile(
            "node_modules/@taiga-ui/design-tokens/package.json",
            """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""",
        )
        createFile(
            "node_modules/@taiga-ui/design-tokens/tokens.css",
            """
            :root {
                --tui-white: #fff;
                --tui-black-a: #000;
                --tui-black-b: rgb(0 0 0);
                --tui-overridden: #777;
                --tui-local: #444;
                --tui-local-alias: var(--tui-local);
                --tui-theme-color: #555;
            }
            [tuiTheme='dark'] { --tui-theme-color: #666; }
            """.trimIndent(),
        )
    }

    override fun tearDown() {
        try {
            root.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testNativeIntentionReplacesRgbAndPreservesImportantAndUndo() {
        val original = ".card { color: rgb(255 255 255) !important; /* Keep this comment */ }"
        configure("app.css", original, "255")
        awaitMatches("#fff", listOf("--tui-white"))

        myFixture.launchAction(myFixture.findSingleIntention("Replace with var(--tui-white)"))
        myFixture.checkResult(".card { color: var(--tui-white) !important; /* Keep this comment */ }")
        myFixture.performEditorAction("\$Undo")
        myFixture.checkResult(original)
    }

    fun testMultipleMatchesOfferAChoiceInLess() {
        configure("app.less", ".card { color: #000; }", "#000")
        awaitMatches("#000", listOf("--tui-black-a", "--tui-black-b"))

        val context = context()
        val command = assertInstanceOf(intention.perform(context), ModChooseAction::class.java)

        assertEquals("Choose Taiga UI token…", intention.getPresentation(context)?.name())
        assertEquals(
            listOf("var(--tui-black-a)", "var(--tui-black-b)"),
            command.actions().map { action -> action.getPresentation(context)?.name() },
        )
    }

    fun testOnlyUsesEffectiveProjectColorsAndIgnoresSelectorLocalAndThemeDependentValues() {
        configure(
            "app.scss",
            """
            :root { --tui-overridden: rgba(0, 255, 0, 1); }
            .card { --tui-local: #222; color: rgb(0 255 0); }
            """.trimIndent(),
            "rgb(0 255 0)",
        )
        awaitMatches("#0f0", listOf("--tui-overridden"))
        assertEquals("Replace with var(--tui-overridden)", intention.getPresentation(context())?.name())
        assertEmpty(matches("#777"))
        assertEmpty(matches("#444"))
        assertEmpty(matches("#555"))
        assertEmpty(matches("#123456"))

        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("#222"))
        assertNull(intention.getPresentation(context()))
    }

    private fun configure(
        filename: String,
        text: String,
        caret: String,
    ) {
        myFixture.configureFromExistingVirtualFile(createFile(filename, text))
        myFixture.editor.caretModel.moveToOffset(text.indexOf(caret) + 1)
    }

    private fun createFile(
        name: String,
        text: String,
    ): VirtualFile {
        val path = root.resolve(name)

        Files.createDirectories(path.parent)
        Files.writeString(path, text)

        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path))
    }

    private fun context(): ActionContext = ActionContext.from(myFixture.editor, myFixture.file)

    private fun matches(color: String): List<String> =
        project.service<DesignTokenColorLookup>().matches(
            myFixture.file.virtualFile,
            requireNotNull(CssLiteralColor.parse(color)),
        )

    private fun awaitMatches(
        color: String,
        expected: List<String>,
    ) {
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (matches(color) == expected) return

            Thread.sleep(10)
        }

        assertEquals(expected, matches(color))
    }
}
