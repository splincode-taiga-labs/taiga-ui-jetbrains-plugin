package org.taigaui.designtokens.documentation

import com.intellij.openapi.application.EDT
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.psi.PsiDocumentManager
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.icons.ICON_PREVIEW_LOGICAL_SIZE
import org.taigaui.designtokens.icons.IconCompletionService
import org.taigaui.designtokens.icons.IconSvgPreviewRenderer
import java.nio.file.Path

/** Loads previews and performs guarded icon edits without retaining the popup controller. */
internal class TaigaDocumentationIcons(
    private val project: Project,
    private val coroutineScope: CoroutineScope,
) {
    private val renderer = IconSvgPreviewRenderer()

    fun loadPreviews(
        sourceFile: Path,
        references: List<TaigaDocumentationIcon>,
        onReady: (List<TaigaDocumentationIconPreview>) -> Unit,
    ): Job =
        coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI documentation icon preview")) {
            val service = project.service<IconCompletionService>()
            service.loadNow(sourceFile)
            val previews =
                references.mapNotNull { reference ->
                    service
                        .svgSourceFor(sourceFile, reference.name)
                        ?.let { source -> renderer.render(source, ICON_PREVIEW_LOGICAL_SIZE) }
                        ?.let { icon -> TaigaDocumentationIconPreview(reference, icon) }
                }
            withContext(Dispatchers.EDT) { onReady(previews) }
        }

    fun showChooser(
        editor: Editor,
        sourceFile: Path,
        reference: TaigaDocumentationIcon,
        isCurrent: () -> Boolean,
    ) {
        coroutineScope.launch(Dispatchers.IO + CoroutineName("Taiga UI documentation icon chooser")) {
            val names = project.service<IconCompletionService>().loadNow(sourceFile)
            withContext(Dispatchers.EDT) {
                if (isCurrent()) {
                    if (names.isEmpty()) {
                        Messages.showInfoMessage(project, "No Taiga UI icons were found in this project.", "Taiga UI")
                    } else {
                        JBPopupFactory
                            .getInstance()
                            .createPopupChooserBuilder(names)
                            .setTitle("Choose Taiga UI icon")
                            .setNamerForFiltering { it }
                            .setItemChosenCallback { name -> replaceIcon(editor, reference, name, isCurrent) }
                            .createPopup()
                            .showInBestPositionFor(editor)
                    }
                }
            }
        }
    }

    private fun replaceIcon(
        editor: Editor,
        reference: TaigaDocumentationIcon,
        name: String,
        isCurrent: () -> Boolean,
    ) {
        if (!isCurrent() || !editor.document.isWritable) return
        WriteCommandAction
            .writeCommandAction(project)
            .withName("Change Taiga UI ${reference.attribute} icon")
            .run<RuntimeException> {
                if (!isCurrent()) return@run
                val document = editor.document
                val current = document.charsSequence
                if (reference.endOffset <= current.length &&
                    current.subSequence(reference.startOffset, reference.endOffset).toString() == reference.name
                ) {
                    document.replaceString(reference.startOffset, reference.endOffset, name)
                    PsiDocumentManager.getInstance(project).commitDocument(document)
                }
            }
    }
}
