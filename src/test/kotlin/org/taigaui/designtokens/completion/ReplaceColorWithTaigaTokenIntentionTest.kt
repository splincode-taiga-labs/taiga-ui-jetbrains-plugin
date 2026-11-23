package org.taigaui.designtokens.completion

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModChooseAction
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.util.ProgressIndicatorBase
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.PsiTestUtil
import com.intellij.testFramework.TestActionEvent
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import org.taigaui.designtokens.resolution.CssLiteralColor
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class ReplaceColorWithTaigaTokenIntentionTest : BasePlatformTestCase() {
    private lateinit var root: Path
    private val intention = ReplaceColorWithTaigaTokenIntention()

    override fun setUp() {
        super.setUp()
        root = Files.createTempDirectory("taiga-color-intention")
        PsiTestUtil.addContentRoot(
            myFixture.module,
            requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(root)),
        )
        createFile(
            "node_modules/@taiga-ui/design-tokens/package.json",
            """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""",
        )
        createFile(
            "node_modules/@taiga-ui/design-tokens/tokens.css",
            """
            :root {
                --tui-const-white: #fff;
                --tui-const-private: #abc;
                --tui-white: var(--tui-const-white);
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
        awaitText(".card { color: var(--tui-white) !important; /* Keep this comment */ }")
        myFixture.performEditorAction("\$Undo")
        awaitText(original)
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

        val choice = command.actions().last()

        ModCommandExecutor.executeInteractively(
            context,
            choice.familyName,
            myFixture.editor,
        ) { choice.perform(context) }
        awaitText(".card { color: var(--tui-black-b); }")
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
        assertEmpty(matches("#abc"))

        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("#222"))
        assertNull(intention.getPresentation(context()))
    }

    fun testFolderActionUsesOnlyPublicExactMatchesAndKeepsAmbiguitiesForReview() {
        val css =
            createFile(
                "src/card.css",
                ".card { color: rgb(255 255 255) !important; background: #000; outline-color: #abc; }",
            )
        val less = createFile("src/nested/card.less", ".card { border: 1px solid #fff; }")
        val scss =
            createFile(
                "src/nested/card.scss",
                ".card { --app-color: #fff; color: var(--tui-const-white); border-color: #fff; }",
            )
        val outside = createFile("outside.css", ".card { color: #fff; }")
        val dependency = createFile("src/node_modules/dependency/theme.css", ".card { color: #fff; }")
        val directory = css.parent
        val action = ActionManager.getInstance().getAction("TaigaUI.ReplaceFolderColors")
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE_ARRAY, arrayOf(directory))
                .build()
        val event = TestActionEvent.createTestEvent(action, context)
        val expectedCss =
            ".card { color: var(--tui-white) !important; " +
                "background: #000 /* Taiga UI: consider var(--tui-black-a) or var(--tui-black-b) */; " +
                "outline-color: #abc; }"

        action.update(event)
        assertTrue(event.presentation.isEnabledAndVisible)
        action.actionPerformed(event)

        awaitValue(expectedCss) { text(css) }
        assertEquals(".card { border: 1px solid var(--tui-white); }", text(less))
        assertEquals(
            ".card { --app-color: #fff; color: var(--tui-const-white); border-color: var(--tui-white); }",
            text(scss),
        )
        assertEquals(".card { color: #fff; }", text(outside))
        assertEquals(".card { color: #fff; }", text(dependency))
        assertEquals("No stylesheet colors to update.", scan(directory).apply(project))
        assertEquals(expectedCss, text(css))
    }

    fun testFolderReplacementRejectsAnEditedStylesheetBeforeWritingAnyFiles() {
        val first = createFile("src/first.css", ".card { color: #fff; }")
        val second = createFile("src/second.css", ".card { color: #fff; }")
        val replacement = scan(first.parent)
        val changed = ".card { color: #abc; }"

        WriteCommandAction.runWriteCommandAction(project) {
            requireNotNull(FileDocumentManager.getInstance().getDocument(second)).setText(changed)
        }

        assertTrue(replacement.apply(project).contains("changed during the scan"))
        assertEquals(".card { color: #fff; }", text(first))
        assertEquals(changed, text(second))
    }

    private fun scan(directory: VirtualFile): FolderColorReplacement {
        val future =
            ApplicationManager.getApplication().executeOnPooledThread(
                Callable { FolderColorReplacement.scan(project, directory, ProgressIndicatorBase()) },
            )

        awaitValue(true) { future.isDone }

        return future.get()
    }

    private fun text(file: VirtualFile): String =
        requireNotNull(FileDocumentManager.getInstance().getDocument(file)).text

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
    ) = awaitValue(expected) { matches(color) }

    private fun awaitText(expected: String) = awaitValue(expected) { myFixture.editor.document.text }

    private fun <T> awaitValue(
        expected: T,
        current: () -> T,
    ) {
        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()

            if (current() == expected) return

            Thread.sleep(10)
        }

        assertEquals(expected, current())
    }
}
