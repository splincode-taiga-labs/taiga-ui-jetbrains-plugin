package org.taigaui.designtokens.icons

import com.intellij.codeInsight.lookup.LookupManager
import com.intellij.lang.documentation.ide.IdeDocumentationTargetProvider
import com.intellij.openapi.application.runReadAction
import com.intellij.openapi.components.service
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.testFramework.runInEdtAndGet
import com.intellij.util.ui.UIUtil
import org.junit.Assert.assertFalse
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.ImageIcon

class IconCompletionContributorTest : BasePlatformTestCase() {
    private lateinit var workspaceRoot: Path

    override fun setUp() {
        super.setUp()
        workspaceRoot = Files.createTempDirectory("taiga-ui-icon-completion")
    }

    override fun tearDown() {
        try {
            workspaceRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testCompletesOnlyPublicIconsWhenProprietaryPackageIsAbsent() {
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/flags/ab.svg")
        createIcon("tds-icons/src/fancy/medium/info-circle.svg")

        val suggestions = complete("@tui.")

        assertContainsElements(
            suggestions,
            "@tui.a-arrow-down",
            "@tui.flags.ab",
        )
        assertFalse("@tui.fancy.medium.info-circle" in suggestions)
    }

    fun testColdIconCatalogDoesNotOfferUninitializedResults() {
        createIcon("icons/src/a-arrow-down.svg")
        val sourcePath = workspaceRoot.resolve("src/cold-icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset = myFixture.editor.document.text.indexOf("@tui.") + "@tui.".length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        val coldResults = myFixture.completeBasic()?.map { item -> item.lookupString }.orEmpty()

        assertFalse("@tui.a-arrow-down" in coldResults)

        project.service<IconCompletionService>().loadNow(sourcePath)
        val warmedResults = myFixture.completeBasic()?.map { item -> item.lookupString }.orEmpty()

        assertContainsElements(warmedResults, "@tui.a-arrow-down")
    }

    fun testIconCompletionSuppressesNativeDocumentationPopups() {
        val item = createIconLookupElement("@tui.a-arrow-down")
        val autoPopupKey = Key.create<Boolean>("LookupManagerImpl.suppressAutopopupJavadoc")
        val quickDocumentationKey = Key.create<Boolean>("lookup.suppress.quick.documentation")

        assertEquals(true, item.getUserData(autoPopupKey))
        assertEquals(true, item.getUserData(quickDocumentationKey))
    }

    fun testIconCompletionPreviewControllerBuildsPreviewForActiveLookup() {
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/a-arrow-up.svg")

        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("@tui.") + "@tui.".length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)

        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        val controller = project.service<IconCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        invokePrivate(controller, "attach", lookup)

        val previewKey = requireNotNull(waitForPrivateField(controller, "previewKey"))
        exerciseDataClass(previewKey)

        invokePrivate(
            controller,
            "showIcon",
            lookup,
            requireNotNull(lookup.currentItem).lookupString,
            ImageIcon(BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB)),
        )

        assertNotNull(waitForPrivateField(controller, "previewPanel"))
    }

    fun testIconCompletionPreviewControllerResolvesSvgAsynchronously() {
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/a-arrow-up.svg")

        val sourcePath = workspaceRoot.resolve("src/icons-async.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("@tui.") + "@tui.".length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)

        val variants = requireNotNull(myFixture.completeBasic())

        assertTrue(variants.size > 1)

        val controller = project.service<IconCompletionPreviewController>()
        val lookup =
            requireNotNull(
                runInEdtAndGet { LookupManager.getActiveLookup(myFixture.editor) },
            )

        invokePrivate(controller, "attach", lookup)

        val panel =
            requireNotNull(
                waitForPrivateField(controller, "previewPanel"),
            ) as IconCompletionPreviewPanel

        assertTrue(
            panel.accessibleContext
                ?.accessibleDescription
                ?.startsWith("Visual preview of @tui.") == true,
        )
    }

    fun testIconCompletionDoesNotExposeNativeDocumentationTarget() {
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/flags/ab.svg")

        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"@tui.\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf("@tui.") + "@tui.".length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)

        val item =
            requireNotNull(myFixture.completeBasic())
                .first { element -> element.lookupString == "@tui.a-arrow-down" }
        val targets =
            runReadAction {
                IdeDocumentationTargetProvider
                    .getInstance(project)
                    .documentationTargets(myFixture.editor, myFixture.file, item)
            }

        assertEmpty(targets)
    }

    fun testCompletesOnlyInstalledProprietaryIconsWhenProprietaryPackageIsPresent() {
        createPackage("proprietary")
        createIcon("icons/src/a-arrow-down.svg")
        createIcon("icons/src/flags/ab.svg")
        createIcon("tds-icons/src/fancy/medium/info-circle.svg")
        createIcon("tds-icons/src/fancy/medium/alert.svg")
        createIcon("tds-icons/src/fancy/medium/check-circle.svg")

        val suggestions = complete("@tui.")

        assertContainsElements(
            suggestions,
            "@tui.fancy.medium.info-circle",
            "@tui.fancy.medium.alert",
            "@tui.fancy.medium.check-circle",
        )
        assertFalse("@tui.a-arrow-down" in suggestions)
        assertFalse("@tui.flags.ab" in suggestions)
    }

    private fun complete(prefix: String): List<String> {
        val sourcePath = workspaceRoot.resolve("src/icons.html")
        val sourceFile =
            createFile(
                sourcePath,
                "<button iconStart=\"$prefix\"></button>",
            )

        myFixture.configureFromExistingVirtualFile(sourceFile)
        val caretOffset =
            myFixture.editor.document.text
                .indexOf(prefix) + prefix.length

        myFixture.editor.caretModel.moveToOffset(caretOffset)
        project.service<IconCompletionService>().loadNow(sourcePath)
        myFixture.completeBasic()

        return myFixture.lookupElementStrings.orEmpty()
    }

    private fun invokePrivate(
        target: Any,
        methodName: String,
        vararg arguments: Any?,
    ) {
        val method =
            target.javaClass.declaredMethods
                .single { candidate ->
                    candidate.name == methodName &&
                        candidate.parameterCount == arguments.size
                }.apply { isAccessible = true }

        runInEdtAndGet { method.invoke(target, *arguments) }
    }

    private fun waitForPrivateField(
        target: Any,
        fieldName: String,
    ): Any? {
        val field =
            target.javaClass
                .getDeclaredField(fieldName)
                .apply { isAccessible = true }

        repeat(500) {
            UIUtil.dispatchAllInvocationEvents()
            val value = runInEdtAndGet { field.get(target) }

            if (value != null) {
                return value
            }

            Thread.sleep(10)
        }

        return runInEdtAndGet { field.get(target) }
    }

    private fun exerciseDataClass(value: Any) {
        value.toString()
        value.hashCode()
        assertEquals(value, value)

        val components =
            value.javaClass.declaredMethods
                .filter { method ->
                    method.name.startsWith("component") &&
                        method.parameterCount == 0
                }.sortedBy { method -> method.name }
                .onEach { method -> method.isAccessible = true }
                .map { method -> method.invoke(value) }
        val copy =
            value.javaClass.declaredMethods
                .singleOrNull { method ->
                    method.name == "copy" &&
                        method.parameterCount == components.size
                }?.apply { isAccessible = true }

        copy?.invoke(value, *components.toTypedArray())
    }

    private fun createPackage(name: String) {
        createFile(
            workspaceRoot.resolve("node_modules/@taiga-ui/$name/package.json"),
            "{\"name\":\"@taiga-ui/$name\"}",
        )
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
