package org.taigaui.designtokens.completion

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import org.taigaui.designtokens.documentation.DesignTokenReferenceAtOffset
import org.taigaui.designtokens.documentation.DesignTokenReferenceAtOffsetFinder
import org.taigaui.designtokens.index.DesignTokenDeprecation
import org.taigaui.designtokens.project.DesignTokenCatalogEntry

class DeprecatedDesignTokenInspection :
    LocalInspectionTool(),
    DumbAware {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                inspectDeprecatedDesignTokens(file, holder)
            }
        }
}

private fun inspectDeprecatedDesignTokens(
    file: PsiFile,
    holder: ProblemsHolder,
) {
    val catalog =
        file
            .designTokenCatalogForInspection()
            ?.associateBy(DesignTokenCatalogEntry::name)
            ?: return

    DesignTokenReferenceAtOffsetFinder
        .findAll(file.text)
        .forEach { reference ->
            catalog[reference.name]
                ?.deprecation
                ?.let { deprecation ->
                    holder.registerDeprecatedTokenProblem(file, reference, deprecation)
                }
        }
}

private fun ProblemsHolder.registerDeprecatedTokenProblem(
    file: PsiFile,
    reference: DesignTokenReferenceAtOffset,
    deprecation: DesignTokenDeprecation,
) {
    val problemBuilder =
        problem(file, deprecation.problemMessage())
            .highlight(ProblemHighlightType.LIKE_DEPRECATED)
            .range(TextRange(reference.startOffset, reference.endOffset))

    deprecation.replacement?.let { replacement ->
        problemBuilder.fix(ReplaceDesignTokenQuickFix(replacement))
    }
    problemBuilder.register()
}

private fun DesignTokenDeprecation.problemMessage(): String =
    message
        ?.takeIf(String::isNotBlank)
        ?.let { details -> "$DEPRECATED_TOKEN_MESSAGE: $details" }
        ?: DEPRECATED_TOKEN_MESSAGE

private const val DEPRECATED_TOKEN_MESSAGE = "Deprecated Taiga UI design token"
