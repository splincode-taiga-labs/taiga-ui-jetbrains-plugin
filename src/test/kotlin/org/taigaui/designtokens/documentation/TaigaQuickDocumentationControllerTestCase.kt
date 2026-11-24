package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.testFramework.replaceService
import com.intellij.ui.UiInterceptors
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.UIUtil
import kotlinx.coroutines.Job

abstract class TaigaQuickDocumentationControllerTestCase : TaigaDocumentationPopupTestCase() {
    internal lateinit var controller: TaigaQuickDocumentationHoverController
    internal val focus = TaigaDocumentationRecordingFocusManager()
    internal val shown = mutableListOf<JBPopup>()

    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
        ApplicationManager
            .getApplication()
            .replaceService(IdeFocusManager::class.java, focus, testRootDisposable)
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
                            it.size =
                                component.size?.takeIf { size -> size.width > 0 && size.height > 0 } ?: it.preferredSize
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

    internal fun open(
        text: String,
        needle: String,
    ) {
        configure(text, needle)
        controller.showFromCaret(myFixture.editor)
        await("installed API card") { panelOrNull() != null }
    }

    internal fun configure(
        text: String,
        needle: String,
    ) {
        val file = myFixture.tempDirFixture.createFile("src/component.html", text)
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
        myFixture.editor.caretModel.moveToOffset(text.indexOf(needle) + 1)
    }

    internal fun panelOrNull(): TaigaQuickDocumentationPopupPanel? = documentationField(controller, "popupContent")

    internal fun panel(): TaigaQuickDocumentationPopupPanel = requireNotNull(panelOrNull())

    internal fun view(): TaigaDocumentationView {
        val navigation = documentationField<TaigaDocumentationNavigationState>(controller, "navigation")
        return requireNotNull(navigation.currentView)
    }

    internal fun actions(): TaigaDocumentationPopupActions = documentationField(panel(), "actions")

    internal fun status(): TaigaDocumentationStatusPopup = documentationField(controller, "resolutionStatus")

    internal fun assertDismissed(job: Job) {
        await("cancelled resolution") { job.isCompleted }
        assertTrue(job.isCancelled)
        assertNull(panelOrNull())
        assertNull(documentationField<Any?>(controller, "pendingKey"))
        assertNull(documentationField<Editor?>(status(), "editor"))
        assertNull(documentationField<Any?>(controller, "refreshTarget"))
    }

    internal fun await(
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

}
