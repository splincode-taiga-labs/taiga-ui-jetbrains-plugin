package org.taigaui.designtokens.migration

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiElementVisitor
import org.taigaui.designtokens.imports.taigaPackageName

class TaigaLegacyImportInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitElement(element: PsiElement) {
                val declaration = element as? ES6ImportDeclaration ?: return

                val name = declaration.taigaPackageName ?: return

                if (name == "@taiga-ui/legacy" || name.startsWith("@taiga-ui/legacy/")) {
                    declaration.fromClause?.lastChild?.let { specifier ->
                        holder.registerProblem(specifier, "Review this Taiga UI legacy import")
                    }
                }
            }
        }

    override fun getStaticDescription(): String =
        """Taiga UI v5 keeps older APIs in its transitional legacy package.
            |Review whether this import has a suitable replacement.
            |<a href="$TAIGA_V5_MIGRATION_GUIDE">Taiga UI v4 to v5 migration guide</a>.
        """.trimMargin()
}

internal const val TAIGA_V5_MIGRATION_GUIDE = "https://taiga-ui.dev/migration-guide/"
