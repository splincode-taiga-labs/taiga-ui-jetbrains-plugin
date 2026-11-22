package org.taigaui.designtokens.diagnostics

import com.intellij.analysis.AnalysisScope
import com.intellij.codeInspection.InspectionManager
import com.intellij.codeInspection.LocalInspectionEP
import com.intellij.codeInspection.ex.GlobalInspectionContextBase
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.codeInspection.ex.InspectionToolsSupplier
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.openapi.application.readAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.psi.PsiFileFactory
import com.intellij.psi.PsiManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path

internal class TaigaDoctorAnalysis(
    private val scope: AnalysisScope,
    private val profile: InspectionProfileImpl,
) {
    fun run(project: Project) {
        val context = InspectionManager.getInstance(project).createNewGlobalContext() as GlobalInspectionContextBase

        context.setExternalProfile(profile)
        context.doInspections(scope)
    }
}

internal suspend fun prepareTaigaDoctorAnalysis(
    project: Project,
    source: Path,
): TaigaDoctorAnalysis? {
    val workspace = project.basePath?.let(Path::of)
    val directory =
        withContext(Dispatchers.IO) {
            workspace
                ?.let { findTaigaDoctorApplication(source, it) { config -> readAngularProjectRoots(project, config) } }
                ?.let(LocalFileSystem.getInstance()::refreshAndFindFileByNioFile)
        } ?: return null

    return readAction {
        PsiManager
            .getInstance(project)
            .findDirectory(directory)
            ?.takeIf { ProjectFileIndex.getInstance(project).isInContent(directory) }
            ?.let { TaigaDoctorAnalysis(AnalysisScope(it), taigaDoctorInspectionProfile(project)) }
    }
}

internal fun taigaDoctorInspectionProfile(project: Project): InspectionProfileImpl {
    val profileManager = InspectionProjectProfileManager.getInstance(project).currentProfile.profileManager
    val tools =
        LocalInspectionEP.LOCAL_INSPECTION.extensionList
            .filter { inspection -> inspection.shortName in DOCTOR_INSPECTIONS }
            .map(::LocalInspectionToolWrapper)
    val profile =
        InspectionProfileImpl("Taiga UI Doctor", InspectionToolsSupplier.Simple(tools), profileManager, null, null)

    profile.initInspectionTools(project)

    return profile
}

internal suspend fun findTaigaDoctorApplication(
    source: Path,
    workspace: Path,
    angularProjectRoots: suspend (Path) -> List<String>,
): Path? {
    val directory = source.projectDirectory(workspace) ?: return null
    val ancestors = generateSequence(directory, Path::getParent).takeWhile { it.startsWith(workspace) }.toList()
    val nxProject = ancestors.firstOrNull { Files.isRegularFile(it.resolve("project.json")) }
    val angularConfig = ancestors.map { it.resolve("angular.json") }.firstOrNull(Files::isRegularFile)
    val angularRoots =
        angularConfig
            ?.let { config -> angularProjectRoots(config).mapNotNull { root -> config.resolveProjectRoot(root) } }
            .orEmpty()
            .filter { it.startsWith(workspace) }
    val angularProject = angularRoots.filter(directory::startsWith).maxByOrNull(Path::getNameCount)
    val packageRoot = ancestors.firstOrNull { Files.isRegularFile(it.resolve("package.json")) }
    val isMonorepo = Files.isRegularFile(workspace.resolve("nx.json")) || angularRoots.any { it != workspace }
    val fallback = (packageRoot ?: workspace).takeUnless { it == workspace && isMonorepo }

    return listOfNotNull(nxProject, angularProject).maxByOrNull(Path::getNameCount) ?: fallback
}

private fun Path.projectDirectory(workspace: Path): Path? =
    (if (Files.isDirectory(this)) this else parent)
        ?.takeIf { it.startsWith(workspace) }
        ?.takeUnless { directory -> workspace.relativize(directory).any { it.toString() in DEPENDENCY_DIRECTORIES } }

private fun Path.resolveProjectRoot(root: String): Path? =
    try {
        parent.resolve(root).normalize()
    } catch (_: InvalidPathException) {
        null
    }

private suspend fun readAngularProjectRoots(
    project: Project,
    config: Path,
): List<String> {
    val content =
        try {
            Files.readString(config)
        } catch (_: IOException) {
            return emptyList()
        }

    return readAction {
        val factory = PsiFileFactory.getInstance(project)
        val file = factory.createFileFromText("angular.json", JsonFileType.INSTANCE, content) as JsonFile
        val projectEntries = (file.topLevelValue as? JsonObject)?.findProperty("projects")?.value as? JsonObject

        projectEntries?.propertyList.orEmpty().mapNotNull { entry ->
            val root = (entry.value as? JsonObject)?.findProperty("root")?.value as? JsonStringLiteral

            root?.value
        }
    }
}

private val DEPENDENCY_DIRECTORIES = setOf("node_modules", ".yarn")

private val DOCTOR_INSPECTIONS =
    listOf(
        "UnknownTaigaUIDesignToken",
        "DeprecatedTaigaUIDesignToken",
        "UnknownTaigaUIEventModifier",
        "DuplicateTaigaUIEventModifier",
        "InvalidTaigaUIEventModifierInHostBinding",
        "WrongTaigaUIImport",
    )
