package org.taigaui.designtokens.icons

import com.intellij.openapi.editor.Editor

internal fun insertIconSnippet(
    editor: Editor,
    snippet: String,
) {
    val selection = editor.selectionModel
    val from = if (selection.hasSelection()) selection.selectionStart else editor.caretModel.offset
    val to = if (selection.hasSelection()) selection.selectionEnd else from

    editor.document.replaceString(from, to, snippet)
    editor.caretModel.moveToOffset(from + snippet.length)
    selection.removeSelection()
}
