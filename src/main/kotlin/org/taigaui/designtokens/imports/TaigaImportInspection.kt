package org.taigaui.designtokens.imports

import com.intellij.codeInspection.LocalInspectionTool
import com.intellij.codeInspection.ProblemsHolder
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportExportDeclaration.ImportExportPrefixKind
import com.intellij.lang.ecmascript6.psi.ES6ImportExportSpecifier.ImportExportSpecifierKind
import com.intellij.lang.ecmascript6.psi.ES6ImportSpecifier
import com.intellij.lang.ecmascript6.psi.impl.ES6ImportPsiUtil
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementVisitor
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiFileSystemItem
import com.intellij.psi.impl.source.PsiFileImpl
import com.intellij.psi.util.PsiTreeUtil

class TaigaImportInspection : LocalInspectionTool() {
    override fun buildVisitor(
        holder: ProblemsHolder,
        isOnTheFly: Boolean,
    ): PsiElementVisitor =
        object : PsiElementVisitor() {
            override fun visitFile(file: PsiFile) {
                val declarations = (file as? JSFile)?.taigaImports().orEmpty()

                if (declarations.isNotEmpty()) {
                    inspectImports(file, declarations, holder)
                }
            }
        }

    private fun inspectImports(
        file: PsiFile,
        declarations: List<ES6ImportDeclaration>,
        holder: ProblemsHolder,
    ) {
        val packages =
            file.originalFile.virtualFile
                ?.installedTaigaPackages()
                .orEmpty()

        if (packages.size < 2) {
            return
        }

        declarations.forEach { declaration ->
            val packageName = declaration.taigaPackageName ?: return@forEach
            val root = packages[packageName] ?: return@forEach

            if (declaration.resolvesWithin(root)) {
                declaration.importSpecifiers.forEach { specifier ->
                    inspectSpecifier(specifier, packageName, packages, holder)
                }
            }
        }
    }

    private fun inspectSpecifier(
        specifier: ES6ImportSpecifier,
        packageName: String,
        packages: Map<String, VirtualFile>,
        holder: ProblemsHolder,
    ) {
        ProgressManager.checkCanceled()

        val symbol =
            specifier.referenceName?.takeIf { name ->
                IDENTIFIER.matches(name) && !specifier.isDefault && !specifier.hasResolution()
            } ?: return
        val target = specifier.uniqueReplacement(symbol, packageName, packages) ?: return

        holder.registerProblem(
            specifier,
            "'$symbol' is exported by '$target' instead of '$packageName'",
            MoveTaigaImportQuickFix(specifier, target),
        )
    }
}

internal val ES6ImportDeclaration.taigaPackageName: String?
    get() = ES6ImportPsiUtil.getUnquotedFromClauseText(this)

internal fun JSFile.taigaImports(): List<ES6ImportDeclaration> =
    statements.filterIsInstance<ES6ImportDeclaration>().filter { declaration ->
        declaration.taigaPackageName?.let(TAIGA_PACKAGE::matches) == true &&
            declaration.withClause == null &&
            declaration.importExportPrefixKind in IMPORT_PREFIXES &&
            declaration.importSpecifiers.isNotEmpty() &&
            !PsiTreeUtil.hasErrorElements(declaration)
    }

private fun ES6ImportSpecifier.uniqueReplacement(
    symbol: String,
    currentPackage: String,
    packages: Map<String, VirtualFile>,
): String? {
    val file = containingFile
    val prefix = if (isTypeOnly()) "import type" else "import"
    // Give every probe its own local name and keep the original file's module resolution context.
    val probes =
        packages.keys
            .filter { name -> name != currentPackage }
            .mapIndexed { index, name -> "$prefix {$symbol as __TaigaImportProbe$index} from '$name';" }
            .joinToString("\n")
    val probeFile = PsiFileFactory.getInstance(project).createFileFromText(probes, file) as? JSFile ?: return null
    (probeFile as? PsiFileImpl)?.setOriginalFile(file.originalFile)

    return probeFile
        .taigaImports()
        .asSequence()
        .filter { probe ->
            ProgressManager.checkCanceled()
            val root = packages[probe.taigaPackageName]
            root != null && probe.resolvesWithin(root) && probe.importSpecifiers.singleOrNull()?.hasResolution() == true
        }.take(2)
        .singleOrNull()
        ?.taigaPackageName
}

internal fun ES6ImportSpecifier.isTypeOnly(): Boolean =
    declaration?.importExportPrefixKind == ImportExportPrefixKind.IMPORT_TYPE ||
        specifierKind == ImportExportSpecifierKind.IMPORT_TYPE

private fun ES6ImportSpecifier.hasResolution(): Boolean =
    resolveOverAliases().any { result -> result.isValidResult && result.element != null }

private fun ES6ImportDeclaration.resolvesWithin(root: VirtualFile): Boolean {
    val resolved = fromClause?.resolveReferencedElements().orEmpty()
    val canonicalRoot = root.canonicalFile ?: root

    return resolved.isNotEmpty() &&
        resolved.all { element ->
            val file = (element as? PsiFileSystemItem)?.virtualFile ?: element.containingFile?.virtualFile
            file != null && VfsUtilCore.isAncestor(canonicalRoot, file.canonicalFile ?: file, false)
        }
}

// Node chooses the nearest installation of each package independently, including hoisted siblings.
private fun VirtualFile.installedTaigaPackages(): Map<String, VirtualFile> =
    buildMap {
        generateSequence(parent) { directory -> directory.parent }.forEach { directory ->
            directory.findFileByRelativePath("node_modules/@taiga-ui")?.children?.forEach { root ->
                val name = "@taiga-ui/${root.name}"
                if (root.isDirectory && TAIGA_PACKAGE.matches(name) && root.findChild("package.json") != null) {
                    putIfAbsent(name, root)
                }
            }
        }
    }

private val TAIGA_PACKAGE = Regex("@taiga-ui/[a-z0-9][a-z0-9-]*")
private val IDENTIFIER = Regex("[A-Za-z_$][A-Za-z0-9_$]*")
private val IMPORT_PREFIXES = setOf(ImportExportPrefixKind.IMPORT, ImportExportPrefixKind.IMPORT_TYPE)
