package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.ex.EditorSettingsExternalizable
import com.intellij.psi.PsiDocumentManager
import java.awt.Point

class TaigaQuickDocumentationHoverRequestCoverageTest : TaigaDocumentationPopupTestCase() {
    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
    }

    fun testOnlyMembersAndEntitiesWithSelectorsRequestUnderlining() {
        val selector = TaigaDocumentationSubject("tuiButton", "TuiButton", "@taiga-ui/core")
        val type = TaigaDocumentationSubject(null, "TuiSize", "@taiga-ui/core")
        val member =
            TaigaDocumentationRequest.Member(
                owners = listOf(selector),
                name = "size",
                kind = TaigaApiMemberKind.INPUT,
                startOffset = 0,
                endOffset = 4,
            )

        assertTrue(TaigaDocumentationRequest.Entity(listOf(selector), 0, 9).shouldHighlight)
        assertTrue(TaigaDocumentationRequest.Entity(listOf(type, selector), 0, 9).shouldHighlight)
        assertFalse(TaigaDocumentationRequest.Entity(listOf(type), 0, 9).shouldHighlight)
        assertFalse(TaigaDocumentationRequest.Entity(emptyList(), 0, 9).shouldHighlight)
        assertTrue(member.shouldHighlight)
    }

    fun testCandidateResolvesAngularAttributeAndKeepsItsEditorAndKey() {
        configureTemplate()
        val candidate = candidate("size")
        val request = requireNotNull(candidate.resolveRequest())

        assertSame(myFixture.editor, request.editor)
        assertEquals(candidate.anchor, request.anchor)
        assertEquals(myFixture.editor.document.modificationStamp, request.modificationStamp)
        assertFalse(request.explicit)
        assertEquals("size", (request.documentationRequest as TaigaDocumentationRequest.Member).name)
        assertEquals(candidate.key.modificationStamp, request.key.modificationStamp)
        assertTrue(request.documentationRequest.shouldHighlight)
        assertNotNull(request.sourceFile)

        val outOfRange = candidate.copy(offset = myFixture.editor.document.textLength + 10)
        assertNull(outOfRange.resolveRequest())
    }

    fun testCommittedTargetRepositionsCandidateAfterUnrelatedEdit() {
        configureTemplate()
        val candidate = candidate("size")
        val request = requireNotNull(candidate.resolveRequest())
        val target = TaigaDocumentationRefreshTarget(myFixture.editor.document, request.documentationRequest)
        try {
            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, "<!-- prefix -->\n")
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()

            val updated = requireNotNull(candidate.withCommittedTarget(target))
            assertEquals(myFixture.editor.document.modificationStamp, updated.key.modificationStamp)
            assertEquals(candidate.editor, updated.editor)
            assertTrue(updated.offset > candidate.offset)
            assertNotNull(updated.resolveRequest())
        } finally {
            target.dispose()
        }
    }

    fun testExplicitRequestRejectsDocumentChangesButIgnoresMouseHoverSetting() {
        configureTemplate()
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            val request = requireNotNull(candidate("tuiButton").copy(explicit = true).resolveRequest())
            settings.isShowQuickDocOnMouseOverElement = false
            myFixture.editor.selectionModel.setSelection(0, 2)
            assertTrue(request.isStillCurrent(project))

            WriteCommandAction.runWriteCommandAction(project) {
                myFixture.editor.document.insertString(0, " ")
            }
            assertFalse(request.isStillCurrent(project))
        } finally {
            myFixture.editor.selectionModel.removeSelection()
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testAutomaticRequestNeedsNoSelectionAndEnabledMouseHover() {
        configureTemplate()
        val settings = EditorSettingsExternalizable.getInstance()
        val previous = settings.isShowQuickDocOnMouseOverElement
        try {
            val request = requireNotNull(candidate("size").resolveRequest())
            settings.isShowQuickDocOnMouseOverElement = true
            assertTrue(request.isStillCurrent(project))

            myFixture.editor.selectionModel.setSelection(0, 2)
            assertFalse(request.isStillCurrent(project))
            myFixture.editor.selectionModel.removeSelection()

            settings.isShowQuickDocOnMouseOverElement = false
            assertFalse(request.isStillCurrent(project))
        } finally {
            myFixture.editor.selectionModel.removeSelection()
            settings.isShowQuickDocOnMouseOverElement = previous
        }
    }

    fun testCompletePresentationMarksAChangedSourceStale() {
        configureTemplate()
        val request = requireNotNull(candidate("size").resolveRequest())
        val panel = TaigaQuickDocumentationPopupPanel(directive())

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        request.completePresentation(panel, refreshing = false)

        assertTrue(labelText(panel).contains(STALE_DOCUMENTATION_MESSAGE))
    }

    fun testRefreshingPresentationShowsProgressRegardlessOfStaleSource() {
        configureTemplate()
        val request = requireNotNull(candidate("size").resolveRequest())
        val panel = TaigaQuickDocumentationPopupPanel(directive())

        WriteCommandAction.runWriteCommandAction(project) {
            myFixture.editor.document.insertString(0, " ")
        }
        request.completePresentation(panel, refreshing = true)

        assertTrue(labelText(panel).contains("Refreshing Taiga UI API"))
    }

    private fun configureTemplate() {
        val file =
            myFixture.tempDirFixture.createFile(
                "src/component.html",
                """<button tuiButton size="m">Save</button>""",
            )
        myFixture.configureFromExistingVirtualFile(file)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
    }

    private fun candidate(name: String): TaigaDocumentationHoverCandidate {
        val offset = myFixture.file.text.indexOf(name) + 1
        val document = myFixture.editor.document
        return TaigaDocumentationHoverCandidate(
            myFixture.file,
            myFixture.editor,
            Point(25, 35),
            offset,
            TaigaQuickDocumentationHoverKey(myFixture.editor, offset, offset, document.modificationStamp),
        )
    }
}
