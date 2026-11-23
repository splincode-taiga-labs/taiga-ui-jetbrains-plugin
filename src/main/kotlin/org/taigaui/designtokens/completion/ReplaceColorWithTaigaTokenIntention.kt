package org.taigaui.designtokens.completion

import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCommandAction
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.modcommand.Presentation
import com.intellij.openapi.components.service
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.css.CssDeclaration
import com.intellij.psi.css.CssTerm
import com.intellij.psi.util.PsiTreeUtil
import org.taigaui.designtokens.resolution.CssLiteralColor

internal class ReplaceColorWithTaigaTokenIntention :
    ModCommandAction,
    DumbAware {
    override fun getFamilyName(): String = "Replace color with Taiga UI token"

    override fun getPresentation(context: ActionContext): Presentation? {
        val colors = context.colorTarget()?.matches().orEmpty()

        return when (colors.size) {
            0 -> null
            1 -> Presentation.of("Replace with var(${colors.single()})")
            else -> Presentation.of("Choose Taiga UI token…")
        }
    }

    override fun perform(context: ActionContext): ModCommand {
        val target = context.colorTarget() ?: return ModCommand.nop()
        val tokens = target.matches()

        return when (tokens.size) {
            0 -> ModCommand.nop()
            1 -> target.replaceWith(tokens.single())
            else ->
                ModCommand.chooseAction(
                    "Choose Taiga UI token",
                    tokens.map { token -> target.choice(token) },
                )
        }
    }
}

private fun ActionContext.colorTarget(): LiteralColorTarget? {
    val file = file()

    if (file.language.id !in STYLE_LANGUAGES || !file.manager.isInProject(file)) return null

    return sequenceOf(findLeaf(), findLeafOnTheLeft())
        .mapNotNull { leaf -> PsiTreeUtil.getParentOfType(leaf, CssDeclaration::class.java) }
        .mapNotNull(CssDeclaration::literalColorTarget)
        .firstOrNull { target -> offset() in target.range.startOffset..target.range.endOffset }
}

private fun CssDeclaration.literalColorTarget(): LiteralColorTarget? =
    literalColorTerms().singleOrNull()?.literalColorTarget(this)

internal fun CssDeclaration.literalColorTerms(): Sequence<CssTerm> =
    if (propertyName.startsWith("--")) emptySequence() else value?.terms?.asSequence().orEmpty()

private fun CssTerm.literalColorTarget(declaration: CssDeclaration): LiteralColorTarget? {
    val file = containingFile.virtualFile?.takeIf(VirtualFile::isInLocalFileSystem) ?: return null
    val literal = text

    return CssLiteralColor
        .parse(
            literal,
        )?.let { color -> LiteralColorTarget(declaration, file, textRange, literal, color) }
}

private data class LiteralColorTarget(
    val declaration: CssDeclaration,
    val file: VirtualFile,
    val range: TextRange,
    val text: String,
    val color: CssLiteralColor,
) {
    fun matches(): List<String> = declaration.project.service<DesignTokenColorLookup>().matches(file, color)

    fun choice(token: String): ModCommandAction =
        ModCommand.psiBasedStep(
            declaration,
            "var($token)",
            { current ->
                val target = current.literalColorTarget()

                if (target?.text == text && token in target.matches()) target.replaceWith(token) else ModCommand.nop()
            },
            { current -> current.value?.textRange ?: current.textRange },
        )

    fun replaceWith(token: String): ModCommand {
        val before = declaration.containingFile.text
        val replacement = "var($token)"

        return ModUpdateFileText(
            file,
            before,
            before.replaceRange(range.startOffset, range.endOffset, replacement),
            listOf(ModUpdateFileText.Fragment(range.startOffset, range.length, replacement.length)),
        )
    }
}

private val STYLE_LANGUAGES = setOf("CSS", "SCSS", "LESS")
