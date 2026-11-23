package org.taigaui.designtokens.migration

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiComment
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.css.CssString
import com.intellij.psi.util.PsiTreeUtil

class TaigaMigrationTodoInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitComment(comment: PsiComment) {
                TODO.findAll(comment.text).forEach { marker ->
                    holder.reportTodo(comment, marker)
                }
            }

            override fun visitFile(file: PsiFile) {
                if (file.language.id == "CSS") {
                    inspectCssLineComments(file, holder)
                }
            }
        }

    override fun getStaticDescription(): String =
        """Review TODO comments left by the Taiga UI v5 schematics.
            |<a href="$TAIGA_V5_MIGRATION_GUIDE">Taiga UI v4 to v5 migration guide</a>.
        """.trimMargin()
}

private fun inspectCssLineComments(
    file: PsiFile,
    holder: ProblemsHolder,
) {
    // The schematics insert // TODO even in plain CSS, where // is not a comment token.
    CSS_LINE_TODO.findAll(file.text).forEach { marker ->
        val offset = requireNotNull(marker.groups[1]).range.first
        val leaf = file.findElementAt(offset) ?: return@forEach

        if (PsiTreeUtil.getParentOfType(leaf, false, PsiComment::class.java, CssString::class.java) == null) {
            holder.reportTodo(file, marker)
        }
    }
}

private fun ProblemsHolder.reportTodo(
    element: PsiElement,
    marker: MatchResult,
) {
    val todo = requireNotNull(marker.groups[1])

    problem(element, "Review this Taiga UI migration TODO")
        .range(TextRange(todo.range.first, todo.range.last + 1))
        .register()
}

private const val MARKER = """(TODO: \(Taiga UI migration\))"""
private val TODO = Regex("""(?m)^\h*(?://|/\*+|<!--|\*)?\h*$MARKER""")
private val CSS_LINE_TODO = Regex("""(?m)^\h*//\h*$MARKER""")
