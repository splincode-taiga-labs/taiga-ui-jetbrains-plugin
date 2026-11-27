package org.taigaui.designtokens.diagnostics

import com.intellij.codeInspection.InspectionEngine
import com.intellij.codeInspection.ProblemDescriptorUtil
import com.intellij.codeInspection.ex.LocalInspectionToolWrapper
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.coroutineToIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.roots.ProjectFileIndex
import com.intellij.openapi.vfs.VFileProperty
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import com.intellij.util.PairProcessor
import java.nio.file.Path

internal data class TaigaDoctorFinding(
    val file: String,
    val line: Int?,
    val inspection: String,
    val message: String,
)

internal data class TaigaDoctorFindings(
    val application: String,
    val findings: List<TaigaDoctorFinding>,
    val inspectedFiles: Int,
    val partial: Boolean,
)

internal suspend fun collectTaigaDoctorFindings(
    project: Project,
    source: Path,
    mode: TaigaDoctorMode,
    limit: Int,
): TaigaDoctorFindings? {
    val directory = resolveTaigaDoctorDirectory(project, source, mode) ?: return null
    val root = Path.of(checkNotNull(project.basePath))
    val application = root.relativize(directory.toNioPath()).joinToString("/").ifEmpty { "." }

    return coroutineToIndicator { indicator: ProgressIndicator ->
        val candidates = inspectionFiles(project, directory, indicator)
        val inspections = taigaDoctorInspections(mode)
        val findings = mutableListOf<TaigaDoctorFinding>()
        var inspected = 0

        for (file in candidates.take(MAX_FILES).filter { it.length <= MAX_FILE_BYTES }) {
            indicator.checkCanceled()
            val relative = root.relativize(file.toNioPath()).joinToString("/")
            val remaining = limit + 1 - findings.size

            findings += project.inspectDoctorFile(file, relative, inspections, remaining, indicator)
            inspected++

            if (findings.size > limit) break
        }

        TaigaDoctorFindings(
            application,
            findings.take(limit),
            inspected,
            inspected < candidates.size || findings.size > limit,
        )
    }
}

private fun Project.inspectDoctorFile(
    file: VirtualFile,
    relative: String,
    inspections: List<LocalInspectionToolWrapper>,
    limit: Int,
    indicator: ProgressIndicator,
): List<TaigaDoctorFinding> =
    ReadAction
        .nonBlocking<List<TaigaDoctorFinding>> {
            val psi = PsiManager.getInstance(this).findFile(file) ?: return@nonBlocking emptyList()
            InspectionEngine
                .inspectEx(
                    inspections,
                    psi,
                    psi.textRange,
                    psi.textRange,
                    false,
                    false,
                    true,
                    indicator,
                    PairProcessor.alwaysTrue(),
                ).toSortedMap(compareBy { it.shortName })
                .asSequence()
                .flatMap { (inspection, descriptors) ->
                    descriptors.asSequence().sortedBy { it.lineNumber }.map { descriptor ->
                        TaigaDoctorFinding(
                            relative,
                            descriptor.lineNumber.takeIf { it >= 0 }?.plus(1),
                            inspection.shortName,
                            ProblemDescriptorUtil
                                .renderDescriptionMessage(
                                    descriptor,
                                    descriptor.psiElement,
                                ).take(1_000),
                        )
                    }
                }.take(limit)
                .toList()
        }.withDocumentsCommitted(this)
        .inSmartMode(this)
        .expireWith(this)
        .executeSynchronously()

private fun inspectionFiles(
    project: Project,
    directory: VirtualFile,
    indicator: ProgressIndicator,
): List<VirtualFile> =
    ReadAction
        .nonBlocking<List<VirtualFile>> {
            val index = ProjectFileIndex.getInstance(project)
            check(index.isInContent(directory)) { "Select an application directory inside IDE project content." }

            buildList {
                VfsUtilCore.iterateChildrenRecursively(
                    directory,
                    { file ->
                        indicator.checkCanceled()
                        !file.`is`(VFileProperty.SYMLINK) &&
                            file.name !in EXCLUDED_DIRECTORIES &&
                            index.isInContent(file)
                    },
                    { file ->
                        if (!file.isDirectory && file.extension in SOURCE_EXTENSIONS) add(file)

                        size <= MAX_FILES
                    },
                )
            }
        }.expireWith(project)
        .executeSynchronously()

private const val MAX_FILES = 500
private const val MAX_FILE_BYTES = 1024 * 1024L
private val SOURCE_EXTENSIONS = setOf("ts", "html", "css", "scss", "less", "sass")
private val EXCLUDED_DIRECTORIES = setOf("node_modules", ".yarn", ".git")
