package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.Lookup
import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import java.nio.file.Files
import java.nio.file.Path

class IconCompletionAutoPopupHandlerCoverageTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("icon-auto-popup")
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/a-arrow-up.svg")
    }

    override fun tearDown() {
        try {
            project.service<IconCompletionService>().clear()
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testRequestIconCompletionShowsLookupFromFreshCatalog() {
        val sourcePath = configure("@tui.")

        project.service<IconCompletionService>().loadNow(sourcePath)
        requestIconCompletion(project, myFixture.editor, sourcePath)

        val lookup = requireNotNull(waitForLookup())

        assertTrue(lookup.items.any { item -> item.lookupString == "@tui.a-arrow-down" })
        assertTrue(lookup.items.any { item -> item.lookupString == "@tui.a-arrow-up" })
    }

    fun testRefreshCallbackCanRequestCompletionAgain() {
        val sourcePath = configure("@tui.")

        project.service<IconCompletionService>().loadNow(sourcePath)

        val callback = iconCompletionRefresh(project, myFixture.editor, sourcePath)

        callback.invokeIfActive()

        assertNotNull(waitForLookup())
    }

    fun testSchedulerDebouncesAndEventuallyShowsLookup() {
        val sourcePath = configure("@tui.")

        project.service<IconCompletionService>().loadNow(sourcePath)
        project
            .service<IconCompletionAutoPopupScheduler>()
            .schedule(myFixture.editor, sourcePath)

        assertNotNull(waitForLookup())
    }

    fun testSelectingCompletionRemovesExistingIconSuffix() {
        val sourcePath = configure("@tui.a", suffix = "-arrow-down")

        project.service<IconCompletionService>().loadNow(sourcePath)
        requestIconCompletion(project, myFixture.editor, sourcePath)

        val lookup = requireNotNull(waitForLookup())
        val item = lookup.items.first { candidate -> candidate.lookupString == "@tui.a-arrow-down" }

        runInEdtAndGet {
            myFixture.lookup.currentItem = item
        }
        myFixture.finishLookup('\n')

        assertTrue(
            myFixture.editor.document
                .text
                .contains("@tui.a-arrow-down"),
        )
        assertFalse(
            myFixture.editor.document
                .text
                .contains("@tui.a-arrow-down-arrow-down"),
        )
    }

    fun testRequestWithoutResolvableTaigaScopeDoesNotOpenLookup() {
        val root = Files.createTempDirectory("icon-no-scope")

        try {
            val source = root.resolve("plain.html")
            val file = createFile(source, """<div iconStart="@tui."></div>""")

            myFixture.configureFromExistingVirtualFile(file)
            myFixture.editor.caretModel.moveToOffset(
                myFixture.editor.document.text
                    .indexOf("@tui.") + 5,
            )

            requestIconCompletion(project, myFixture.editor, source)

            assertNull(waitForLookup(short = true))
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    private fun configure(
        prefix: String,
        suffix: String = "",
    ): Path {
        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"$prefix$suffix\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)

        return sourcePath
    }

    private fun waitForLookup(short: Boolean = false): Lookup? {
        val attempts = if (short) 20 else 300

        repeat(attempts) {
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

    private fun createIcon(relativePath: String) {
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/$relativePath"),
            "<svg viewBox=\"0 0 24 24\"><path d=\"M4 12h16\"/></svg>",
        )
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
}
