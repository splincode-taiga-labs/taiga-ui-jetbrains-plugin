package org.taigaui.designtokens.documentation

import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.impl.text.TextEditorProvider
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaQuickDocumentationDxTest : BasePlatformTestCase() {
    override fun setUp() {
        super.setUp()
        TaigaDocumentationAngularFixture.install(myFixture)
    }

    fun testOfflineInstalledInputsPreserveAliasesAndDeclaringClasses() {
        val file = template("""<button tuiButton size="m" [iconEnd]="'@tui.eye'" baseInput="test">Save</button>""")
        val size = member(file, "size")
        assertEquals("TuiButton", size.subject.publicSymbol)
        assertTrue(size.subject.localDocumentation.angularResolved)
        assertTrue(size.localMember?.required == true)
        assertEquals(setOf("s", "m", "l"), size.localValues().toSet())
        assertEquals("TuiButton", size.declaration?.publicSymbol)
        val icon = member(file, "iconEnd")
        assertEquals("TuiButton", icon.subject.publicSymbol)
        assertEquals("TuiWithIcons", icon.declaration?.publicSymbol)
        assertEquals("@tui.eye", icon.documentationIcons.single().name)
        assertEquals("TuiBase", member(file, "baseInput").declaration?.publicSymbol)
        assertTrue(TaigaQuickDocumentationRenderer.render(size).contains("Required input"))
    }

    fun testAllReceiversParticipateInValueChoicesAndOutOfScopeDirectivesAreExcluded() {
        val file = template("""<button tuiButton tuiAux tuiUnimported localSized size="m">Save</button>""")
        val size = member(file, "size")
        assertEquals(setOf("TuiButton", "TuiAux", "LocalSized"), size.receivers.map { it.subject.publicSymbol }.toSet())
        assertEquals(listOf("m"), size.localValues())
        assertTrue(size.ownerName.orEmpty().contains("LocalSized"))
        val owner = size.receivers.first { it.subject.publicSymbol == "TuiAux" }
        assertTrue(
            owner
                .ownerDocumentation()
                .entity.inputs
                .any { it.name == "size" },
        )
    }

    fun testTransformedInputShowsWhatTheTemplateMayPass() {
        val file = template("""<button tuiButton [enabled]="flag">Save</button>""")
        val enabled = member(file, "enabled")
        assertTrue(enabled.typeText.orEmpty().contains("string"))
        assertTrue(enabled.typeText.orEmpty().contains("boolean"))
        assertFalse(enabled.typeText.orEmpty().contains("InputSignal"))
        assertEquals("boolean", enabled.localMember?.valueType)
        assertTrue(enabled.localValues().isEmpty())
    }

    fun testEmptyIconInputsExposeChooserRangesWithoutInventingAPreview() {
        val file = template("""<button tuiButton iconStart="" [iconEnd]="''">Save</button>""")
        val end = member(file, "iconEnd").documentationIcons.single()
        assertEquals("", end.name)
        assertEquals(end.startOffset, end.endOffset)
        assertEquals(file.text.indexOf("\"''\"") + 2, end.startOffset)
        val start = member(file, "iconStart").documentationIcons.single()
        assertEquals(file.text.indexOf("\"\"") + 1, start.startOffset)
        assertEquals(start.startOffset, start.endOffset)
    }

    fun testSelectorThatIsAlsoAnInputHasAnOfflineCard() {
        val file = template("""<div [tuiHint]="hint">Content</div>""")
        assertEquals("TuiHint", member(file, "tuiHint").subject.publicSymbol)
    }

    fun testPlainSelectorInputHasAFocusedCard() {
        val file = template("""<div tuiHint="hint">Content</div>""")
        assertEquals("tuiHint", member(file, "tuiHint").property.name)
    }

    fun testUnimportedDirectiveHasNoCard() {
        val unimported = template("""<button tuiUnimported size="l">Save</button>""")
        assertNull(TaigaDocumentationResolver.findRequest(unimported, unimported.text.indexOf("tuiUnimported") + 2))
        assertNull(TaigaDocumentationResolver.findRequest(unimported, unimported.text.indexOf("size") + 1))
    }

    fun testTypeScriptOwnerApiIsAvailableWithoutOnlineDocumentation() {
        val virtual =
            myFixture.tempDirFixture.createFile(
                "src/owner-api.ts",
                "import {TuiButton} from '@taiga-ui/core';",
            )
        myFixture.configureFromExistingVirtualFile(virtual)
        val file = myFixture.file
        val request = requireNotNull(TaigaDocumentationResolver.findRequest(file, file.text.indexOf("TuiButton") + 3))
        val owner = resolveDocumentation(request) as? TaigaResolvedDocumentation.Entity
        assertTrue(owner?.entity?.inputs?.any { it.name == "iconEnd" } == true)
        assertEquals("Directive", owner?.badge)
    }

    fun testOnlineIndexCannotChangeInstalledTypesOrHideUndocumentedInputs() {
        val file = template("""<button tuiButton size="m" [enabled]="flag">Save</button>""")
        val request = requireNotNull(TaigaDocumentationResolver.findRequest(file, file.text.indexOf("size") + 1))
        val installed = member(file, "size")
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val documented =
            installed.entity.copy(
                inputs =
                    listOf(
                        TaigaApiProperty("size", "[size]", "number", "Remote description"),
                        TaigaApiProperty("removedInput", "[removedInput]", "boolean", "Absent locally"),
                    ),
            )
        val snapshot =
            TaigaDocsSnapshot(
                TaigaUiProjectContext("5.18.0", 5, "@taiga-ui/core", setOf("@taiga-ui/core"), "fixture"),
                TaigaDocsIndex(source, listOf(documented)),
            )
        val enriched = snapshot.resolve(request) as? TaigaResolvedDocumentation.Member
        assertEquals(installed.typeText, enriched?.typeText)
        assertEquals("Remote description", enriched?.description)
        assertTrue(enriched?.entity?.inputs?.any { it.name == "enabled" } == true)
        assertTrue(enriched?.entity?.inputs?.none { it.name == "removedInput" } == true)
    }

    fun testRequiredInputInsertionDoesNotInventAValueAndSupportsUndo() {
        val file = template("""<button tuiButton>Save</button>""")
        val documentation =
            requireNotNull(
                resolveDocumentation(
                    requireNotNull(
                        TaigaDocumentationResolver.findRequest(
                            file,
                            file.text.indexOf("tuiButton") + 2,
                        ),
                    ),
                ),
            )
        val edit = documentation.templateEdits().single { it.name == "size" }
        val before = myFixture.editor.document.text
        var caret = -1
        val adapter =
            TaigaDocumentationTemplateEditor(
                project,
                myFixture.editor.document,
                requireNotNull(documentation.templateElement),
            ) {
                caret =
                    it
            }
        try {
            assertTrue(adapter.apply(edit).startsWith("Added"))
            assertEquals("""<button tuiButton [size]="">Save</button>""", myFixture.editor.document.text)
            assertEquals(
                myFixture.editor.document.text
                    .indexOf("\"\"") + 1,
                caret,
            )
            assertEquals("Binding already exists", adapter.apply(edit))
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
            assertEquals(before, myFixture.editor.document.text)
        } finally {
            adapter.dispose()
        }
    }

    fun testDeprecatedReplacementPreservesDynamicExpressionAndHasSingleUndo() {
        val file = template("""<button tuiButton size="m" [oldSize]="condition ? first : second">Save</button>""")
        val old = member(file, "oldSize")
        assertNotNull(old.localMember?.deprecated)
        val edit = old.templateEdits().single { it.kind == TaigaTemplateEditKind.RENAME_BINDING }
        val document = myFixture.editor.document
        val before = document.text
        val adapter = TaigaDocumentationTemplateEditor(project, document, requireNotNull(old.element))
        try {
            assertTrue(adapter.apply(edit).startsWith("Replaced"))
            assertTrue(document.text.contains("[newSize]=\"condition ? first : second\""))
            assertFalse(document.text.contains("[oldSize]"))
            UndoManager.getInstance(project).undo(TextEditorProvider.getInstance().getTextEditor(myFixture.editor))
            assertEquals(before, document.text)
        } finally {
            adapter.dispose()
        }
    }

    fun testTemplateActionsRejectChangedOwnerReadOnlyFileAndExistingReplacement() {
        val file = template("""<button tuiButton size="m" oldSize="value" newSize="other">Save</button>""")
        val old = member(file, "oldSize")
        assertTrue(old.templateEdits().none { it.kind == TaigaTemplateEditKind.RENAME_BINDING })
        val document = myFixture.editor.document
        val adapter = TaigaDocumentationTemplateEditor(project, document, requireNotNull(old.element))
        val edit = TaigaDocumentationTemplateEdit(TaigaTemplateEditKind.ADD_REQUIRED, "another")
        try {
            document.setReadOnly(true)
            assertTrue(adapter.apply(edit).startsWith("File is read-only"))
            document.setReadOnly(false)
            WriteCommandAction.runWriteCommandAction(project) {
                val offset = document.text.indexOf("tuiButton")
                document.replaceString(offset, offset + "tuiButton".length, "tuiOther")
            }
            val before = document.text
            assertTrue(adapter.apply(edit).startsWith("Element changed"))
            assertEquals(before, document.text)
        } finally {
            document.setReadOnly(false)
            adapter.dispose()
        }
    }

    fun testTemplateActionTracksUnrelatedEditsBeforeTheElement() {
        val file = template("""<button tuiButton size="m" oldSize="value">Save</button>""")
        val old = member(file, "oldSize")
        val document = myFixture.editor.document
        val adapter = TaigaDocumentationTemplateEditor(project, document, requireNotNull(old.element))
        try {
            WriteCommandAction.runWriteCommandAction(project) { document.insertString(0, "<!-- unrelated -->\n") }
            val edit = old.templateEdits().single { it.kind == TaigaTemplateEditKind.RENAME_BINDING }
            assertTrue(adapter.apply(edit).startsWith("Replaced"))
            assertTrue(document.text.startsWith("<!-- unrelated -->\n"))
            assertTrue(document.text.contains("newSize=\"value\""))
        } finally {
            adapter.dispose()
        }
    }

    fun testDeprecatedActionUsesThePublicAliasOfTheReplacement() {
        val file = template("""<button tuiButton legacyAppearance="primary">Save</button>""")
        val legacy = member(file, "legacyAppearance")
        val edit = legacy.templateEdits().single { it.kind == TaigaTemplateEditKind.RENAME_BINDING }
        assertEquals("appearance", edit.replacement)
    }

    fun testJSDocMetadataBelongsOnlyToItsDeclaringInput() {
        val file = template("""<button tuiButton oldSize="value" newSize="other">Save</button>""")
        val old = member(file, "oldSize")
        assertEquals("Use newSize instead.", old.localMember?.deprecated)
        assertEquals("newSize", old.localMember?.replacement)
        assertNull(member(file, "newSize").localMember?.deprecated)
    }

    fun testInlineTemplateInputOffsetsPointIntoTheHostEditor() {
        val virtual =
            myFixture.tempDirFixture.createFile(
                "src/inline.ts",
                TaigaDocumentationAngularFixture.CONSUMER.replace(
                    "templateUrl: './component.html'",
                    "template: `<button tuiButton size=\"m\">Save</button>`",
                ),
            )
        myFixture.configureFromExistingVirtualFile(virtual)
        val file = myFixture.file
        myFixture.doHighlighting()
        val offset = file.text.indexOf("size=\"m\"")
        val request = requireNotNull(TaigaDocumentationResolver.findRequest(file, offset + 1))
        assertTrue(request.startOffset >= offset)
        val resolved = resolveDocumentation(request) as? TaigaResolvedDocumentation.Member
        assertEquals("TuiButton", resolved?.subject?.publicSymbol)
        assertEquals("m", resolved?.binding?.literal)
        val binding = requireNotNull(resolved?.binding)
        assertEquals(
            binding.text,
            myFixture.editor.document.text
                .substring(binding.startOffset, binding.endOffset),
        )
    }

    fun testPinnedValueActionRejectsAddedLocalReceiverAndRefreshNarrowsChoices() {
        val file = template("""<button tuiButton size="m">Save</button>""")
        val original = member(file, "size")
        val document = myFixture.editor.document
        val context = TaigaDocumentationActionContext(project, document)
        val adapter =
            TaigaDocumentationBindingEditor(
                project,
                document,
                requireNotNull(original.binding),
                original.localValues(),
                context::isCurrent,
            )
        val request = requireNotNull(TaigaDocumentationResolver.findRequest(file, file.text.indexOf("size") + 1))
        val target = TaigaDocumentationRefreshTarget(document, request)
        try {
            assertTrue(original.localValues().contains("l"))
            WriteCommandAction.runWriteCommandAction(project) {
                document.insertString(document.text.indexOf(" size="), " localSized")
                document.insertString(0, "<!-- note -->\n")
            }
            assertEquals(STALE_DOCUMENTATION_MESSAGE, adapter.apply("l"))
            assertTrue(document.text.contains("size=\"m\""))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            myFixture.doHighlighting()
            val fresh =
                requireNotNull(
                    resolveDocumentation(
                        requireNotNull(TaigaDocumentationResolver.findRequest(file, requireNotNull(target.offset()))),
                    ) as? TaigaResolvedDocumentation.Member,
                )
            assertEquals(setOf("s", "m"), fresh.localValues().toSet())
            val refreshedContext = TaigaDocumentationActionContext(project, document)
            val refreshed =
                TaigaDocumentationBindingEditor(
                    project,
                    document,
                    requireNotNull(fresh.binding),
                    fresh.localValues(),
                    refreshedContext::isCurrent,
                )
            try {
                assertTrue(refreshed.apply("s").startsWith("Applied"))
                assertTrue(document.text.contains("size=\"s\""))
            } finally {
                refreshed.dispose()
            }
        } finally {
            adapter.dispose()
            target.dispose()
        }
    }

    fun testPinnedValueActionRejectsUncommittedImportChanges() {
        val file = template("""<button tuiButton size="m">Save</button>""")
        val original = member(file, "size")
        val document = myFixture.editor.document
        val context = TaigaDocumentationActionContext(project, document)
        val adapter =
            TaigaDocumentationBindingEditor(
                project,
                document,
                requireNotNull(original.binding),
                original.localValues(),
                context::isCurrent,
            )
        try {
            val owner =
                requireNotNull(
                    FileDocumentManager.getInstance().getDocument(myFixture.findFileInTempDir("src/component.ts")),
                )
            WriteCommandAction.runWriteCommandAction(project) {
                owner.setText(
                    owner.text.replace(
                        "imports: [TuiButton, TuiAux, TuiHint, LocalSized]",
                        "imports: [TuiAux, TuiHint, LocalSized]",
                    ),
                )
            }
            assertEquals(STALE_DOCUMENTATION_MESSAGE, adapter.apply("l"))
            assertTrue(document.text.contains("size=\"m\""))
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            myFixture.doHighlighting()
            assertNull(TaigaDocumentationResolver.findRequest(file, file.text.indexOf("size") + 1))
        } finally {
            adapter.dispose()
        }
    }

    fun testPinnedValueActionRejectsInstalledTypeChangesAndRefreshReadsNewApi() {
        val file = template("""<button tuiButton size="m">Save</button>""")
        val original = member(file, "size")
        val document = myFixture.editor.document
        val context = TaigaDocumentationActionContext(project, document)
        val adapter =
            TaigaDocumentationBindingEditor(
                project,
                document,
                requireNotNull(original.binding),
                original.localValues(),
                context::isCurrent,
            )
        try {
            val declarations =
                requireNotNull(
                    FileDocumentManager.getInstance().getDocument(
                        myFixture.findFileInTempDir("node_modules/@taiga-ui/core/index.d.ts"),
                    ),
                )
            WriteCommandAction.runWriteCommandAction(project) {
                declarations.setText(declarations.text.replace("'s' | 'm' | 'l'", "'m'"))
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(STALE_DOCUMENTATION_MESSAGE, adapter.apply("l"))
            myFixture.doHighlighting()
            assertEquals(listOf("m"), member(file, "size").localValues())
        } finally {
            adapter.dispose()
        }
    }

    fun testPinnedDeprecatedActionRejectsChangedDeclarationMetadata() {
        val file = template("""<button tuiButton oldSize="value">Save</button>""")
        val old = member(file, "oldSize")
        val document = myFixture.editor.document
        val context = TaigaDocumentationActionContext(project, document)
        val adapter =
            TaigaDocumentationTemplateEditor(project, document, requireNotNull(old.element), context::isCurrent)
        val edit = old.templateEdits().single { it.kind == TaigaTemplateEditKind.RENAME_BINDING }
        try {
            val declarations =
                requireNotNull(
                    FileDocumentManager.getInstance().getDocument(
                        myFixture.findFileInTempDir("node_modules/@taiga-ui/core/index.d.ts"),
                    ),
                )
            WriteCommandAction.runWriteCommandAction(project) {
                declarations.setText(
                    declarations.text.replace("@deprecated Use newSize instead.", "This input is still supported."),
                )
            }
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            assertEquals(STALE_DOCUMENTATION_MESSAGE, adapter.apply(edit))
            assertTrue(document.text.contains("oldSize=\"value\""))
            myFixture.doHighlighting()
            assertTrue(member(file, "oldSize").templateEdits().none { it.kind == TaigaTemplateEditKind.RENAME_BINDING })
        } finally {
            adapter.dispose()
        }
    }

    fun testUndoRedoInvalidatesPinnedSnapshotAndRefreshReadsCurrentLiteral() {
        val file = template("""<button tuiButton size="m">Save</button>""")
        val original = member(file, "size")
        val document = myFixture.editor.document
        val context = TaigaDocumentationActionContext(project, document)
        val adapter =
            TaigaDocumentationBindingEditor(
                project,
                document,
                requireNotNull(original.binding),
                original.localValues(),
                context::isCurrent,
            )
        val editor = TextEditorProvider.getInstance().getTextEditor(myFixture.editor)
        try {
            assertTrue(adapter.apply("s").startsWith("Applied"))
            UndoManager.getInstance(project).undo(editor)
            assertEquals(STALE_DOCUMENTATION_MESSAGE, adapter.apply("l"))
            assertTrue(document.text.contains("size=\"m\""))
            UndoManager.getInstance(project).redo(editor)
            PsiDocumentManager.getInstance(project).commitAllDocuments()
            myFixture.doHighlighting()
            assertEquals("s", member(file, "size").binding?.literal)
        } finally {
            adapter.dispose()
        }
    }

    fun testRefreshTargetTracksDeprecatedRename() {
        val file = template("""<button tuiButton oldSize="value">Save</button>""")
        val request = requireNotNull(TaigaDocumentationResolver.findRequest(file, file.text.indexOf("oldSize") + 1))
        val old = requireNotNull(resolveDocumentation(request) as? TaigaResolvedDocumentation.Member)
        val document = myFixture.editor.document
        val target = TaigaDocumentationRefreshTarget(document, request)
        val adapter = TaigaDocumentationTemplateEditor(project, document, requireNotNull(old.element))
        try {
            val edit = old.templateEdits().single { it.kind == TaigaTemplateEditKind.RENAME_BINDING }
            assertTrue(adapter.apply(edit).startsWith("Replaced"))
            target.name = requireNotNull(edit.replacement)
            myFixture.doHighlighting()
            val fresh =
                resolveDocumentation(
                    requireNotNull(TaigaDocumentationResolver.findRequest(file, requireNotNull(target.offset()))),
                ) as? TaigaResolvedDocumentation.Member
            assertEquals("newSize", fresh?.property?.name)
            assertEquals("value", fresh?.binding?.literal)
        } finally {
            target.dispose()
            adapter.dispose()
        }
    }

    private fun template(text: String): PsiFile {
        val virtual = myFixture.tempDirFixture.createFile("src/component.html", text)
        myFixture.configureFromExistingVirtualFile(virtual)
        PsiDocumentManager.getInstance(project).commitAllDocuments()
        myFixture.doHighlighting()
        return myFixture.file
    }

    private fun member(
        file: PsiFile,
        name: String,
    ): TaigaResolvedDocumentation.Member {
        val offset = file.text.indexOf(name)
        val request = TaigaDocumentationResolver.findRequest(file, offset + 1)
        assertNotNull("Missing installed request for $name", request)
        return requireNotNull(resolveDocumentation(requireNotNull(request)) as? TaigaResolvedDocumentation.Member)
    }

    private fun create(
        path: String,
        text: String,
    ) {
        myFixture.tempDirFixture.createFile(path, text.trimIndent())
    }

}
