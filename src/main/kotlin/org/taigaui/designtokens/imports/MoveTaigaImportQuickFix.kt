package org.taigaui.designtokens.imports

import com.intellij.lang.ecmascript6.intentions.ES6ReplaceImportFromClauseFix
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration.ImportExportPrefixKind
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil.CreateImportExportInfo
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil.ImportExportType
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.modcommand.ModPsiUpdater
import com.intellij.modcommand.PsiUpdateModCommandQuickFix
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiParserFacade
import com.intellij.psi.util.PsiModificationTracker

internal class MoveTaigaImportQuickFix(
    original: ES6ImportSpecifier,
    private val targetPackage: String,
) : PsiUpdateModCommandQuickFix() {
    private val originalImport = original.declaration?.text
    private val originalOffset = original.startOffsetInParent
    private val vfsStamp = VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount
    private val psiStamp = PsiModificationTracker.getInstance(original.project).modificationCount

    override fun getFamilyName(): String = "Move import to '$targetPackage'"

    // The inspection resolves packages on the daemon thread. Only apply the edit to the same snapshot.
    override fun applyFix(
        project: Project,
        element: PsiElement,
        updater: ModPsiUpdater,
    ) {
        val specifier = element as? ES6ImportSpecifier ?: return
        val source = specifier.declaration ?: return

        val sourceMatches = source.text == originalImport && specifier.startOffsetInParent == originalOffset
        val packagesMatch =
            vfsStamp == VirtualFileManager.VFS_STRUCTURE_MODIFICATIONS.modificationCount &&
                psiStamp == PsiModificationTracker.getInstance(project).modificationCount

        if (sourceMatches && packagesMatch) {
            moveSpecifier(source, specifier, targetPackage)
        }
    }
}

private fun moveSpecifier(
    source: ES6ImportDeclaration,
    specifier: ES6ImportSpecifier,
    targetPackage: String,
) {
    val file = source.containingFile as? JSFile ?: return
    val targetImports = file.taigaImports().filter { target -> target.taigaPackageName == targetPackage }
    val existing =
        targetImports
            .asSequence()
            .flatMap { target -> target.importSpecifiers.asSequence() }
            .firstOrNull { candidate -> candidate.declaredName == specifier.declaredName }

    if (existing != null) {
        if (existing.referenceName == specifier.referenceName && (!existing.isTypeOnly() || specifier.isTypeOnly())) {
            specifier.delete()
        }
        return
    }

    val compatibleTarget = targetImports.firstOrNull { target -> target.canMerge(specifier) }

    when {
        compatibleTarget?.addSpecifier(specifier) == true -> {
            specifier.delete()
        }

        source.importSpecifiers.size == 1 && source.importedBindings.isEmpty() -> {
            source.fromClause?.let { from -> ES6ReplaceImportFromClauseFix.changeImportFromText(from, targetPackage) }
        }

        else -> {
            addSeparateImport(file, source, specifier, targetPackage)
        }
    }
}

private fun addSeparateImport(
    file: JSFile,
    source: ES6ImportDeclaration,
    specifier: ES6ImportSpecifier,
    targetPackage: String,
) {
    val prefix = if (source.isTypeOnly()) "import type" else "import"
    val statement = "$prefix {${specifier.text}} from '$targetPackage';"
    val parsed = PsiFileFactory.getInstance(file.project).createFileFromText(statement, file) as? JSFile
    val declaration = parsed?.statements?.singleOrNull() as? ES6ImportDeclaration ?: return
    val added = file.addAfter(declaration, source)
    file.addBefore(PsiParserFacade.getInstance(file.project).createWhiteSpaceFromText("\n"), added)
    specifier.delete()
}

private fun ES6ImportDeclaration.canMerge(specifier: ES6ImportSpecifier): Boolean =
    (!isTypeOnly() || specifier.isTypeOnly()) &&
        importSpecifiers.none { candidate -> candidate.referenceName == specifier.referenceName }

private fun ES6ImportDeclaration.isTypeOnly(): Boolean = importExportPrefixKind == ImportExportPrefixKind.IMPORT_TYPE

private fun ES6ImportDeclaration.addSpecifier(specifier: ES6ImportSpecifier): Boolean {
    val prefix =
        if (!isTypeOnly() && specifier.isTypeOnly()) {
            ImportExportPrefixKind.IMPORT_TYPE
        } else {
            ImportExportPrefixKind.IMPORT
        }

    val added =
        ES6ImportPsiUtil.insertImportSpecifier(
            this,
            CreateImportExportInfo(specifier.referenceName, specifier.declaredName, ImportExportType.SPECIFIER, prefix),
        )

    val matches =
        added != null &&
            added.declaredName == specifier.declaredName &&
            added.isTypeOnly() == specifier.isTypeOnly()

    if (!matches) {
        added?.delete()
    }

    return matches
}
