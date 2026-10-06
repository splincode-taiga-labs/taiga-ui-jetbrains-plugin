package org.taigaui.designtokens.icons

import com.intellij.openapi.components.service
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class IconCompletionInsertionCoverageTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("icon-insertion-coverage")
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

    fun testContributorRemovesExistingIconSuffixOnInsert() {
        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.a-arrow-down\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val prefix = "@tui.a"
        val caretOffset = myFixture.editor.document.text.indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)

        val item =
            requireNotNull(myFixture.completeBasic())
                .first { candidate -> candidate.lookupString == "@tui.a-arrow-down" }

        myFixture.lookup.currentItem = item
        myFixture.finishLookup('\n')

        assertEquals(
            "<button iconStart=\"@tui.a-arrow-down\"></button>",
            myFixture.editor.document.text,
        )
    }

    fun testContributorReturnsWhileCatalogIsStillWarmingUp() {
        val sourcePath = workspaceRoot.resolve("src/cold-icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset = myFixture.editor.document.text.indexOf("@tui.") + "@tui.".length

        myFixture.editor.caretModel.moveToOffset(caretOffset)

        myFixture.completeBasic()

        assertTrue(myFixture.editor.document.text.contains("@tui."))
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
