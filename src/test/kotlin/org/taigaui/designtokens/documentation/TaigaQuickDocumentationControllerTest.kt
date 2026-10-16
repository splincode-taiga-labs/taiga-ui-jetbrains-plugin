package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.RangeMarker
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.platform.backend.documentation.impl.computeDocumentationBlocking
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.DumbModeTestUtils
import com.intellij.testFramework.ServiceContainerUtil
import com.intellij.ui.UiInterceptors
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBTextField
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.ui.popup.AbstractPopup
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job
import java.awt.Dimension
import java.awt.Point
import javax.swing.JButton

class TaigaQuickDocumentationControllerTest : TaigaDocumentationPopupTestCase() {
    private lateinit var controller: TaigaQuickDocumentationHoverController
    private val focus = TaigaDocumentationRecordingFocusManager()
    private val shown = mutableListOf<JBPopup>()

    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
        ServiceContainerUtil.replaceService(
            ApplicationManager.getApplication(),
            IdeFocusManager::class.java,
            focus,
            testRootDisposable,
        )
        UiInterceptors.registerPersistent(
            testRootDisposable,
            object : UiInterceptors.PersistentUiInterceptor<JBPopup>(JBPopup::class.java) {
                override fun shouldIntercept(component: JBPopup): Boolean = true

                override fun doIntercept(
                    component: JBPopup,
                    owner: RelativePoint?,
                ) {
                    shown += component
                    documentationComponents(component.content)
                        .filterIsInstance<TaigaQuickDocumentationPopupPanel>()
                        .forEach {
                            it.size = it.preferredSize
                            layoutDocumentation(it)
                        }
                }
            },
        )
        // Keep network/cache timing out of lifecycle tests while using the real installed-API resolver.
        val store = documentationField<TaigaDocsIndexStore>(project.service<TaigaDocsService>(), "indexStore")
        val snapshots = documentationField<MutableMap<TaigaDocsSource, TaigaDocsIndex>>(store, "snapshots")
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        snapshots[source] = TaigaDocsIndex(source, emptyList())
        controller = project.service()
    }

    override fun tearDown() {
        try {
            if (::controller.isInitialized) controller.dismissHover(force = true)
            project.service<TaigaDocsService>().clearMemory()
        } finally {
            super.tearDown()
        }
    }

    fun testRequiredInputReturnsFocusAndCaretAfterAutomaticRefreshAndOneUndo() {
        open("<button tuiButton>Save</button>", "tuiButton")
        val before = panel()
        button("Add [size]").doClick()
        val text = myFixture.editor.document.text
        val expression = text.indexOf("\"\"") + 1
        assertTrue(expression > 0)
        assertEquals(expression, myFixture.editor.caretModel.offset)
        assertSame(myFixture.editor.contentComponent, focus.requests.last())
        await("automatic required-input refresh") { panelOrNull()?.let { it !== before } == true }
        assertEquals(expression, myFixture.editor.caretModel.offset)
        assertSame(myFixture.editor.contentComponent, focus.requests.last())
        assertTrue("Automatic refresh must focus the editor twice", focus.requests.size >= 2)
        val refreshedPopup = shown.last() as AbstractPopup
        assertFalse("Automatic refresh must not request popup focus", refreshedPopup.shouldRequestFocus())
        assertEquals("<button tuiButton [size]=\"\">Save</button>", myFixture.editor.document.text)
        UndoManager
            .getInstance(project)
            .undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
        assertEquals("<button tuiButton>Save</button>", myFixture.editor.document.text)
    }

    fun testImmediateTypingDuringAutomaticRefreshKeepsTheNewExpression() {
        open("<button tuiButton>Save</button>", "tuiButton")
        var job: Job? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            button("Add [size]").doClick()
            job = documentationField(controller, "resolutionJob")
            myFixture.type("flag")
            assertSame(myFixture.editor.contentComponent, focus.requests.last())
        }
        await("refresh after immediate typing") { requireNotNull(job).isCompleted }
        assertEquals("<button tuiButton [size]=\"flag\">Save</button>", myFixture.editor.document.text)
        assertEquals(
            myFixture.editor.document.text
                .indexOf("flag") + 4,
            myFixture.editor.caretModel.offset,
        )
    }

    fun testF5RebasesBrowsedMemberHistoryQuerySelectionAndPinnedGeometry() {
        val declarations = document("node_modules/@taiga-ui/core/index.d.ts")
        val longType = (1..60).joinToString(" | ") { "'choice$it'" }
        WriteCommandAction.runWriteCommandAction(project) {
            declarations.setText(declarations.text.replace("newSize: string", "newSize: $longType"))
        }
        open("<button tuiButton size=\"m\">Save</button>", "tuiButton")
        button("Pin").doClick()
        link("Browse API →").doClick()
        descendants(panel())
            .filterIsInstance<JBTextField>()
            .single()
            .text = "Size"
        val list = descendants(panel()).filterIsInstance<JBList<*>>().single()
        list.selectedIndex =
            (0 until list.model.size).first {
                (list.model.getElementAt(it) as TaigaDocumentationApiRow).key == "INPUT:newSize"
            }
        activate(list, "ENTER")
        assertEquals("newSize", (view().resolved as TaigaResolvedDocumentation.Member).property.name)
        panel().restoreScrollPosition(Point(0, 40))
        await("scrolled member") { panel().scrollPosition.y > 0 }
        val scrollPosition = panel().scrollPosition
        val size = Dimension(570, 630)
        val location = Point(180, 120)
        val popup = documentationField<JBPopup>(controller, "popup")
        setField(controller, "popup", documentationPopupWithGeometry(popup, size, location))
        WriteCommandAction.runWriteCommandAction(project) {
            declarations.setText(declarations.text.replace("choice60", "changed60"))
        }
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        val before = panel()
        activate(before, "F5")
        await("refreshed browsed member") { panelOrNull()?.let { it !== before } == true }
        val refreshed = view().resolved as TaigaResolvedDocumentation.Member
        assertEquals("newSize", refreshed.property.name)
        assertTrue(refreshed.typeText.orEmpty().contains("changed60"))
        assertEquals(scrollPosition, panel().scrollPosition)
        assertEquals(size, documentationField<Dimension>(controller, "pinnedSize"))
        assertEquals(location, documentationField<Point>(controller, "pinnedLocation"))
        assertEquals(size, documentationField<JBPopup>(controller, "popup").size)
        assertEquals(
            location,
            documentationField<Point>(documentationField<JBPopup>(controller, "popup"), "myForcedLocation"),
        )
        activate(panel(), "alt LEFT")
        assertTrue(view().fullApi)
        assertEquals(
            "Size",
            descendants(panel())
                .filterIsInstance<JBTextField>()
                .single()
                .text,
        )
        val selected =
            descendants(panel())
                .filterIsInstance<JBList<*>>()
                .single()
                .selectedValue as TaigaDocumentationApiRow
        assertEquals("INPUT:newSize", selected.key)
        assertTrue(
            selected.property.documentedType
                .orEmpty()
                .contains("changed60"),
        )
        activate(panel(), "alt LEFT")
        assertFalse(view().fullApi)
        assertTrue((view().resolved as TaigaResolvedDocumentation.Entity).entity.inputs.any { it.name == "newSize" })
    }

    fun testRepeatedF5WhileIndexingKeepsOneRefreshAndEscapeCancelsIt() {
        open("<button tuiButton size=\"m\">Save</button>", "size")
        button("Pin").doClick()
        var job: Job? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            activate(panel(), "F5")
            job = documentationField(controller, "resolutionJob")
            assertTrue(labelText(panel()).contains("Waiting for indexing"))
            assertFalse(button("Refresh").isEnabled)
            activate(panel(), "F5")
            assertSame(job, documentationField<Job>(controller, "resolutionJob"))
            activate(panel(), "ESCAPE")
        }
        assertDismissed(requireNotNull(job))
    }

    fun testCloseDuringIndexingCannotPublishALateCard() {
        configure("<button tuiButton>Save</button>", "tuiButton")
        var job: Job? = null
        DumbModeTestUtils.runInDumbModeSynchronously(project) {
            controller.showFromCaret(myFixture.editor)
            job = documentationField(controller, "resolutionJob")
            assertTrue(labelText(statusPanel()).contains("Waiting for indexing"))
            statusPanel().closeButton.doClick()
        }
        assertDismissed(requireNotNull(job))
    }

    fun testEditorReleaseDuringIndexingCannotPublishALateCard() {
        configure("<button tuiButton>Save</button>", "tuiButton")
        val editor = EditorFactory.getInstance().createEditor(myFixture.editor.document, project)
        var job: Job? = null
        try {
            editor.caretModel.moveToOffset(myFixture.editor.caretModel.offset)
            DumbModeTestUtils.runInDumbModeSynchronously(project) {
                controller.showFromCaret(editor)
                job = documentationField(controller, "resolutionJob")
                EditorFactory.getInstance().releaseEditor(editor)
            }
            assertDismissed(requireNotNull(job))
        } finally {
            if (!editor.isDisposed) EditorFactory.getInstance().releaseEditor(editor)
        }
    }

    fun testUnavailableTargetKeepsStatusUntilEscape() {
        configure("<button tuiUnimported>Save</button>", "tuiUnimported")
        controller.showFromCaret(myFixture.editor)
        await("unavailable target") { labelText(statusPanel()).contains("No installed Taiga UI API") }
        assertNull(panelOrNull())
        val fixes = descendants(statusPanel()).filterIsInstance<JButton>().first { it.text == "Angular quick fixes" }
        assertTrue(fixes.isVisible)
        activate(statusPanel(), "ESCAPE")
        assertNull(documentationField<TaigaDocumentationStatusPanel?>(status(), "panel"))
        assertNull(documentationField<Any?>(controller, "pendingKey"))
    }

    fun testChangedImportsDisableButtonsAndRejectPreviouslyCapturedAction() {
        open("<button tuiButton size=\"m\">Save</button>", "size")
        button("Pin").doClick()
        val apply = requireNotNull(actions().applyValue)
        val consumer = document("src/component.ts")
        WriteCommandAction.runWriteCommandAction(project) {
            consumer.setText(consumer.text.replace("imports: [TuiButton,", "imports: ["))
        }
        await("stale import context") { !button("Apply 'l'").isEnabled }
        assertEquals(STALE_DOCUMENTATION_MESSAGE, apply("l"))
        assertTrue(
            myFixture.editor.document.text
                .contains("size=\"m\""),
        )
        assertTrue(button("Copy 'l'").isEnabled)
    }

    fun testUndoRedoCannotReuseTheRefreshedBindingAction() {
        open("<button tuiButton size=\"m\">Save</button>", "size")
        button("Pin").doClick()
        val before = panel()
        button("Apply 's'").doClick()
        await("value refresh") { panelOrNull()?.let { it !== before } == true }
        val apply = requireNotNull(actions().applyValue)
        val undo = UndoManager.getInstance(project)
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        undo.undo(editor)
        assertEquals(STALE_DOCUMENTATION_MESSAGE, apply("l"))
        assertTrue(
            myFixture.editor.document.text
                .contains("size=\"m\""),
        )
        undo.redo(editor)
        assertEquals(STALE_DOCUMENTATION_MESSAGE, apply("l"))
        assertTrue(
            myFixture.editor.document.text
                .contains("size=\"s\""),
        )
    }

    fun testDeletedTargetCannotRedirectAnOldActionToTheNextElement() {
        open("<button tuiButton size=\"m\">First</button>\n<button tuiButton size=\"l\">Second</button>", "size")
        button("Pin").doClick()
        val apply = requireNotNull(actions().applyValue)
        WriteCommandAction.runWriteCommandAction(project) {
            val document = myFixture.editor.document
            document.deleteString(0, document.text.indexOf("\n") + 1)
        }
        val remaining = myFixture.editor.document.text
        assertEquals(STALE_DOCUMENTATION_MESSAGE, apply("s"))
        activate(panel(), "F5")
        assertTrue(labelText(panel()).contains("source target changed"))
        assertEquals(remaining, myFixture.editor.document.text)
    }

    fun testPinUnpinAndCloseRestoreNativeHoverAndDisposeEditorAdapters() {
        open("<button tuiButton size=\"m\">Save</button>", "size")
        val suppression =
            documentationField<DesignTokenNativeHoverPopupSuppression>(controller, "nativeHoverSuppression")
        val adapters = documentationField<TaigaDocumentationCardEditors>(controller, "cardEditors")
        assertSame(myFixture.editor, documentationField<Editor?>(suppression, "editor"))
        assertNotNull(adapters.binding)
        assertNotNull(adapters.template)
        val bindingRange = documentationField<RangeMarker>(requireNotNull(adapters.binding), "marker")
        val elementRange = documentationField<RangeMarker>(requireNotNull(adapters.template), "marker")
        button("Pin").doClick()
        assertNull(documentationField<Editor?>(suppression, "editor"))
        button("Unpin").doClick()
        assertSame(myFixture.editor, documentationField<Editor?>(suppression, "editor"))
        button("Close").doClick()
        assertNull(adapters.binding)
        assertNull(adapters.template)
        assertFalse(bindingRange.isValid)
        assertFalse(elementRange.isValid)
        assertNull(documentationField<Editor?>(suppression, "editor"))
        assertNull(documentationField<Any?>(controller, "refreshTarget"))
        assertNull(panelOrNull())
    }

    fun testNativeCompletionProvidesInstalledDocumentationWithoutOpeningAnInteractiveCard() {
        configure("<button tuiB>Save</button>", "tuiB")
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("tuiB") + "tuiB".length)
        val items = myFixture.completeBasic().orEmpty()
        val item = items.firstOrNull { it.lookupString == "tuiButton" }
        assertNotNull("Expected native Angular completion: ${items.map { it.lookupString }}", item)
        val target =
            TaigaQuickDocumentationTargetProvider()
                .documentationTarget(
                    myFixture.file,
                    requireNotNull(item),
                    myFixture.editor.caretModel.offset,
                )
        assertNotNull(target)
        val html = requireNotNull(computeDocumentationBlocking(requireNotNull(target).createPointer())).html
        assertTrue(html.contains("TuiButton"))
        assertTrue(html.contains("@taiga-ui/core"))
        assertNull(panelOrNull())
        assertTrue(shown.isEmpty())
    }

    fun testInlineSharedBindingAndHostDirectiveUseTheHostEditor() {
        val text =
            TaigaDocumentationAngularFixture.CONSUMER.replace(
                "templateUrl: './component.html'",
                "template: `<button tuiButton tuiAux localSized size=\"m\" iconEnd=\"\">Save</button>`",
            )
        val file = myFixture.tempDirFixture.createFile("src/inline.ts", text)
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(text.indexOf("size=\"m\"") + 1)
        controller.showFromCaret(myFixture.editor)
        await("inline shared input") { panelOrNull() != null }
        val shared = view().resolved as TaigaResolvedDocumentation.Member
        assertEquals(
            setOf("TuiButton", "TuiAux", "LocalSized"),
            shared.receivers.map { it.subject.publicSymbol }.toSet(),
        )
        assertEquals(listOf("m"), shared.localValues())
        val binding = requireNotNull(shared.binding)
        assertEquals(
            binding.text,
            myFixture.editor.document.text
                .substring(binding.startOffset, binding.endOffset),
        )
        myFixture.editor.caretModel.moveToOffset(text.indexOf("iconEnd=\"\"") + 1)
        controller.showFromCaret(myFixture.editor)
        await("inline host-directive input") { panelOrNull() != null }
        val icon = view().resolved as TaigaResolvedDocumentation.Member
        assertEquals("TuiButton", icon.subject.publicSymbol)
        assertEquals("TuiWithIcons", icon.declaration?.publicSymbol)
        assertEquals("iconEnd", icon.property.name)
        assertEquals("Choose icon", button("Choose icon").text)
    }

    private fun open(
        text: String,
        needle: String,
    ) {
        configure(text, needle)
        controller.showFromCaret(myFixture.editor)
        await("installed API card") { panelOrNull() != null }
    }

    private fun configure(
        text: String,
        needle: String,
    ) {
        val file = myFixture.tempDirFixture.createFile("src/component.html", text)
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(text.indexOf(needle) + 1)
    }

    private fun panelOrNull(): TaigaQuickDocumentationPopupPanel? = documentationField(controller, "popupContent")

    private fun panel(): TaigaQuickDocumentationPopupPanel = requireNotNull(panelOrNull())

    private fun view(): TaigaDocumentationView = documentationField(controller, "currentView")

    private fun actions(): TaigaDocumentationPopupActions = documentationField(panel(), "actions")

    private fun status(): TaigaDocumentationStatusPopup = documentationField(controller, "resolutionStatus")

    private fun statusPanel(): TaigaDocumentationStatusPanel = documentationField(status(), "panel")

    private fun button(text: String): JButton =
        descendants(panel()).filterIsInstance<JButton>().first { it.text == text }

    private fun link(text: String): LinkLabel<*> =
        descendants(panel()).filterIsInstance<LinkLabel<*>>().first { it.text == text }

    private fun document(path: String) =
        requireNotNull(FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir(path)))

    private fun assertDismissed(job: Job) {
        await("cancelled resolution") { job.isCompleted }
        assertTrue(job.isCancelled)
        assertNull(panelOrNull())
        assertNull(documentationField<Any?>(controller, "pendingKey"))
        assertNull(documentationField<Editor?>(status(), "editor"))
        assertNull(documentationField<Any?>(controller, "refreshTarget"))
    }

    private fun await(
        description: String,
        condition: () -> Boolean,
    ) {
        repeat(1_000) {
            UIUtil.dispatchAllInvocationEvents()
            if (condition()) return
            Thread.sleep(10)
        }
        fail("Timed out waiting for $description")
    }

    private fun setField(
        target: Any,
        name: String,
        value: Any,
    ) {
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .set(target, value)
    }
}
