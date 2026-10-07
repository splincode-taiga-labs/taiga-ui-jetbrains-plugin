package org.taigaui.designtokens.documentation

import com.intellij.openapi.Disposable
import com.intellij.openapi.editor.Document
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.util.PsiModificationTracker

/** Cheap checks only: installed API is extracted again by Refresh, never inside a write command. */
internal class TaigaDocumentationActionContext(
    private val project: Project,
    private val document: Document,
) {
    private val documentStamp = document.modificationStamp
    private val psiStamp = PsiModificationTracker.getInstance(project).modificationCount
    private val vfsStamp = VirtualFileManager.getInstance().modificationCount

    fun isCurrent(): Boolean =
        !project.isDisposed &&
            document.modificationStamp == documentStamp &&
            PsiModificationTracker.getInstance(project).modificationCount == psiStamp &&
            VirtualFileManager.getInstance().modificationCount == vfsStamp &&
            !PsiDocumentManager.getInstance(project).hasUncommittedDocuments()
}

/** Tracks the opening tag so Refresh survives edits before it and a binding rename. */
internal class TaigaDocumentationRefreshTarget(
    private val document: Document,
    request: TaigaDocumentationRequest,
) : Disposable {
    private val element =
        when (request) {
            is TaigaDocumentationRequest.Entity -> request.element
            is TaigaDocumentationRequest.Member -> request.element
        }
    private val marker =
        document.createRangeMarker(
            element?.startOffset ?: request.startOffset,
            element?.endOffset ?: request.endOffset,
        )
    var name: String =
        (request as? TaigaDocumentationRequest.Member)?.name
            ?: document.charsSequence
                .subSequence(
                    request.startOffset,
                    request.endOffset,
                ).toString()
                .trim('[', ']', '(', ')')

    @Suppress("ReturnCount")
    fun offset(): Int? {
        if (!marker.isValid) return null
        if (element == null) return marker.startOffset
        val text = document.charsSequence.subSequence(marker.startOffset, marker.endOffset).toString()
        val boundary = text.getOrNull(name.length + 1)
        val endsTagName = boundary?.isWhitespace() == true || boundary == '>' || boundary == '/'
        if (text.startsWith("<$name") && endsTagName) {
            return marker.startOffset + 1
        }
        val attribute = Regex("(?:^|\\s)([\\[(]*${Regex.escape(name)}[\\])]*)(?=\\s*=|\\s|/?>)").find(text)
        return attribute
            ?.groups
            ?.get(1)
            ?.range
            ?.first
            ?.let { marker.startOffset + it + if (text[it] == '[') 1 else 0 }
    }

    override fun dispose() = marker.dispose()
}

internal const val STALE_DOCUMENTATION_MESSAGE = "Code changed. Refresh this card before applying changes."
