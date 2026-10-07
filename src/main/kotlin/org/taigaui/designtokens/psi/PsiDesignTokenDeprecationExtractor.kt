package org.taigaui.designtokens.psi

import com.intellij.psi.PsiComment
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.css.CssDeclaration
import org.taigaui.designtokens.index.DesignTokenDeprecation
import org.taigaui.designtokens.index.DesignTokenDeprecationParser

internal object PsiDesignTokenDeprecationExtractor {
    fun extract(
        declaration: CssDeclaration,
        tokenName: String,
    ): DesignTokenDeprecation? =
        declaration.trailingComment()?.toDeprecation(tokenName)
            ?: declaration.leadingComment()?.toDeprecation(tokenName)

    private fun PsiComment.toDeprecation(tokenName: String): DesignTokenDeprecation? =
        DesignTokenDeprecationParser.parse(text, tokenName)

    private fun CssDeclaration.trailingComment(): PsiComment? {
        var sibling = nextSibling

        while (sibling is PsiWhiteSpace) {
            if ('\n' in sibling.text) {
                return null
            }

            sibling = sibling.nextSibling
        }

        return sibling as? PsiComment
    }

    private fun CssDeclaration.leadingComment(): PsiComment? {
        var sibling = prevSibling

        while (sibling is PsiWhiteSpace) {
            sibling = sibling.prevSibling
        }

        return (sibling as? PsiComment)?.takeUnless { comment -> comment.isTrailingComment() }
    }

    private fun PsiComment.isTrailingComment(): Boolean {
        var sibling = prevSibling

        while (sibling is PsiWhiteSpace) {
            if ('\n' in sibling.text) {
                return false
            }

            sibling = sibling.prevSibling
        }

        return sibling is CssDeclaration
    }
}
