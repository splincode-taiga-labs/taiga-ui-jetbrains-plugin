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
        declaration.trailingCommentText()?.toDeprecation(tokenName)
            ?: declaration.leadingComment()?.toDeprecation(tokenName)

    private fun String.toDeprecation(tokenName: String): DesignTokenDeprecation? =
        DesignTokenDeprecationParser.parse(this, tokenName)

    private fun PsiComment.toDeprecation(tokenName: String): DesignTokenDeprecation? = text.toDeprecation(tokenName)

    private fun CssDeclaration.trailingCommentText(): String? {
        val content = containingFile.text
        val start = textRange.endOffset
        val end = content.indexOf('\n', start).takeUnless { it < 0 } ?: content.length
        val trailingText =
            content
                .substring(start, end)
                .trimStart()
                .removePrefix(";")
                .trimStart()

        return trailingText.takeIf { text ->
            text.startsWith("//") || text.startsWith("/*")
        }
    }

    private fun CssDeclaration.leadingComment(): PsiComment? {
        var sibling = prevSibling

        while (sibling is PsiWhiteSpace) {
            sibling = sibling.prevSibling
        }

        return (sibling as? PsiComment)?.takeUnless { comment -> comment.isTrailingComment() }
    }

    private fun PsiComment.isTrailingComment(): Boolean {
        val content = containingFile.text
        val commentStart = textRange.startOffset
        val previousLineBreak = content.lastIndexOf('\n', commentStart - 1)
        val lineStart = if (previousLineBreak < 0) 0 else previousLineBreak + 1

        return content.substring(lineStart, commentStart).isNotBlank()
    }
}
