package org.taigaui.designtokens.documentation

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.taigaui.designtokens.icons.IconCompletionService
import java.nio.file.Files
import java.nio.file.Path

class TaigaDocumentationIconsCoverageTest : BasePlatformTestCase() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val icons by lazy { TaigaDocumentationIcons(project, scope) }

    override fun tearDown() {
        try {
            scope.cancel()
        } finally {
            super.tearDown()
        }
    }

    fun testGuardedReplacementChangesOnlyTheOriginalIconRange() {
        val original = """<tui-icon icon="@tui.eye" aria-label="Search" />"""
        myFixture.configureByText("example.html", original)
        val start = original.indexOf("@tui.eye")
        val reference = TaigaDocumentationIcon("icon", "@tui.eye", start, start + "@tui.eye".length)

        replace(reference, "@tui.search") { false }
        assertEquals(original, myFixture.editor.document.text)

        replace(reference.copy(name = "@tui.other"), "@tui.search") { true }
        assertEquals(original, myFixture.editor.document.text)

        replace(reference.copy(startOffset = original.length + 1), "@tui.search") { true }
        assertEquals(original, myFixture.editor.document.text)

        replace(reference, "@tui.search") { true }
        assertEquals(
            """<tui-icon icon="@tui.search" aria-label="Search" />""",
            myFixture.editor.document.text,
        )

        replace(reference, "@tui.close") { true }
        assertEquals(
            """<tui-icon icon="@tui.search" aria-label="Search" />""",
            myFixture.editor.document.text,
        )
    }

    fun testPreviewLoadingSkipsUnavailableIconsAndPublishesInstalledSvgOnUiThread() {
        val workspace = Files.createTempDirectory("taiga-documentation-icons")
        try {
            val icon = workspace.resolve("node_modules/@taiga-ui/icons/src/eye.svg")
            Files.createDirectories(icon.parent)
            Files.writeString(
                icon,
                """<svg width="24" height="24" viewBox="0 0 24 24"><path d="M2 12L12 2L22 12Z"/></svg>""",
            )
            val source = workspace.resolve("src/example.html")
            Files.createDirectories(source.parent)
            Files.writeString(source, "<tui-icon />")
            val virtualFile = requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(source))
            myFixture.configureFromExistingVirtualFile(virtualFile)
            assertEquals(
                listOf("@tui.eye"),
                project.service<IconCompletionService>().loadNow(source),
            )

            val reference = TaigaDocumentationIcon("icon", "@tui.eye", 0, 8)
            val missing = TaigaDocumentationIcon("icon", "@tui.missing", 10, 22)
            var result: List<TaigaDocumentationIconPreview>? = null
            val job = icons.loadPreviews(source, listOf(missing, reference)) { result = it }

            repeat(300) {
                UIUtil.dispatchAllInvocationEvents()
                if (job.isCompleted && result != null) return@repeat
                Thread.sleep(10)
            }
            assertTrue("Preview job should complete", job.isCompleted)
            assertEquals(listOf(reference), result?.map(TaigaDocumentationIconPreview::reference))
            assertNotNull(result?.single()?.icon)
        } finally {
            workspace.toFile().deleteRecursively()
        }
    }

    private fun replace(
        reference: TaigaDocumentationIcon,
        replacement: String,
        isCurrent: () -> Boolean,
    ) {
        val method =
            icons.javaClass.declaredMethods.single {
                it.name == "replaceIcon" && it.parameterCount == 4
            }
        method.isAccessible = true
        method.invoke(icons, myFixture.editor as Editor, reference, replacement, isCurrent)
    }
}
