package org.taigaui.designtokens.migration

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PathEnvironmentVariableUtil
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.OSProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputTypes
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.ide.trustedProjects.TrustedProjects.isProjectTrusted
import com.intellij.javascript.nodejs.interpreter.NodeJsInterpreterManager
import com.intellij.javascript.nodejs.interpreter.local.NodeJsLocalInterpreter
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogBuilder
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.platform.ide.progress.withBackgroundProgress
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.file.Path

@Service(Service.Level.PROJECT)
internal class TaigaMigrationRunner(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val migration = Mutex()

    fun run(
        selected: Path,
        projectRoot: Path,
        existingPlan: Boolean,
    ) {
        scope.launch(Dispatchers.EDT) {
            if (!migration.tryLock()) {
                Messages.showInfoMessage(project, "A Taiga UI migration is already running.", TITLE)
                return@launch
            }

            try {
                val plan = withContext(Dispatchers.IO) { prepareMigration(selected, projectRoot, existingPlan) }
                val node =
                    withContext(Dispatchers.IO) { requireLocalNode(project) }

                if (confirm(plan)) {
                    execute(plan, node) { prepareMigration(selected, projectRoot, existingPlan) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: MigrationCommandFailed) {
                showError(IllegalStateException("${error.message} See the Run window for details.", error))
            } catch (error: IllegalStateException) {
                showError(error)
            } catch (error: IOException) {
                showError(error)
            } catch (error: ExecutionException) {
                showError(error)
            } finally {
                migration.unlock()
            }
        }
    }

    suspend fun runSavedNxPlan(
        revision: String,
        prepare: suspend () -> ExistingNxMigrationPlan,
    ): NxMigrationResult {
        if (!migration.tryLock()) {
            return NxMigrationResult("busy", "A Taiga UI migration is already running.")
        }

        val output = MigrationOutput()

        return try {
            check(isProjectTrusted(project)) { "Trust this WebStorm project before running migrations." }
            val plan = prepare()
            plan.requireRevision(revision)

            if (ApplicationManager.getApplication().isHeadlessEnvironment) {
                output.result("confirmation_required", "Confirm the migration in an open WebStorm window.")
            } else {
                confirmAndRunSavedPlan(plan, prepare, output)
            }
        } catch (_: CancellationException) {
            currentCoroutineContext().ensureActive()
            output.result("cancelled", "Migration cancelled. Review changes already written to the workspace.")
        } catch (error: MigrationCommandFailed) {
            output.result("failed", error.message, error.exitCode)
        } catch (error: IllegalStateException) {
            output.result("failed", error.message)
        } catch (error: IOException) {
            output.result("failed", error.message)
        } catch (error: ExecutionException) {
            output.result("failed", error.message)
        } finally {
            migration.unlock()
        }
    }

    private suspend fun confirmAndRunSavedPlan(
        plan: ExistingNxMigrationPlan,
        prepare: suspend () -> ExistingNxMigrationPlan,
        output: MigrationOutput,
    ): NxMigrationResult {
        val node = withContext(Dispatchers.IO) { requireLocalNode(project) }
        val confirmed =
            withContext(Dispatchers.EDT) {
                (!project.isDisposed && confirmExistingPlan(plan)).also { accepted ->
                    if (accepted) {
                        check(
                            isProjectTrusted(project) && !project.isDisposed,
                        ) { "The WebStorm project is no longer trusted or open." }
                        saveWorkspaceDocuments(plan.workspace.root)
                    }
                }
            }

        if (!confirmed) {
            return output.result("cancelled", "The user declined the migration.")
        }

        withBackgroundProgress(project, TITLE) {
            withContext(Dispatchers.IO) {
                check(
                    isProjectTrusted(project) && !project.isDisposed,
                ) { "The WebStorm project is no longer trusted or open." }
                check(prepare() == plan) { "The workspace or migration plan changed. Inspect the plan again." }
                try {
                    plan.run { arguments ->
                        runMigrationCommand(node, plan.workspace, arguments) { text, _ -> output.append(text) }
                    }
                } finally {
                    VirtualFileManager.getInstance().asyncRefresh()
                }
            }
        }

        return output.result("success", "Migration completed. Review the changes and migration TODOs.", 0)
    }

    private suspend fun execute(
        plan: PreparedMigration,
        node: File,
        prepare: () -> PreparedMigration,
    ) {
        FileDocumentManager.getInstance().saveAllDocuments()
        val console = openConsole()

        try {
            withBackgroundProgress(project, TITLE) {
                withContext(Dispatchers.IO) {
                    check(prepare() == plan) {
                        "The workspace or migration plan changed. Open the migration action again."
                    }
                    plan.run { arguments ->
                        runMigrationCommand(node, plan.workspace, arguments) { text, key ->
                            console.print(text, ConsoleViewContentType.getConsoleViewType(key))
                        }
                    }
                }
            }
            console.print(
                "Migration completed. Review the changes and migration TODOs.\n",
                ConsoleViewContentType.SYSTEM_OUTPUT,
            )
        } finally {
            VirtualFileManager.getInstance().asyncRefresh()
        }
    }

    private fun confirm(plan: PreparedMigration): Boolean =
        plan.existing?.let(::confirmExistingPlan) ?: confirmNewMigration(plan.workspace)

    private fun confirmNewMigration(plan: TaigaMigrationPlan): Boolean =
        Messages.showOkCancelDialog(
            project,
            "Run the official Taiga UI v4 to v5 migration in:\n${plan.root}\n\n" +
                "The migration can update dependencies and source files across this workspace.",
            TITLE,
            "Run Migration",
            "Cancel",
            Messages.getQuestionIcon(),
        ) == Messages.OK

    private fun confirmExistingPlan(plan: ExistingNxMigrationPlan): Boolean {
        val preview =
            JBTextArea(14, 72).apply {
                text =
                    buildString {
                        appendLine(plan.workspace.root)
                        appendLine()
                        appendLine("Nx will run the entire saved plan. Steps that already ran may run again.")
                        appendLine()
                        plan.migrations.forEach { appendLine(it) }
                    }
                isEditable = false
                lineWrap = true
                wrapStyleWord = true
                caretPosition = 0
            }

        return DialogBuilder(project)
            .apply {
                setTitle("Run Existing Nx Migration Plan")
                setCenterPanel(JBScrollPane(preview))
                addOkAction().setText("Run Plan")
                addCancelAction()
            }.showAndGet()
    }

    private fun openConsole(): ConsoleView {
        val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
        val descriptor = RunContentDescriptor(console, null, console.component, TITLE)

        RunContentManager.getInstance(project).showRunContent(DefaultRunExecutor.getRunExecutorInstance(), descriptor)

        return console
    }

    private fun showError(error: Exception) {
        if (!project.isDisposed) {
            Messages.showErrorDialog(project, error.message ?: "The migration could not be completed.", TITLE)
        }
    }
}

private fun prepareMigration(
    selected: Path,
    projectRoot: Path,
    existingPlan: Boolean,
): PreparedMigration =
    if (existingPlan) {
        val existing = prepareExistingNxMigration(selected, projectRoot)
        PreparedMigration(existing.workspace, existing)
    } else {
        PreparedMigration(prepareTaigaMigration(selected, projectRoot))
    }

private data class PreparedMigration(
    val workspace: TaigaMigrationPlan,
    val existing: ExistingNxMigrationPlan? = null,
) {
    suspend fun run(execute: suspend (List<String>) -> Unit) {
        if (existing == null) {
            workspace.run(execute)
        } else {
            existing.run(execute)
        }
    }
}

private fun requireLocalNode(project: Project): File {
    val configured = NodeJsInterpreterManager.getInstance(project).getInterpreter(false) as? NodeJsLocalInterpreter

    return configured?.interpreterSystemDependentPath?.let(::File)?.takeIf(File::isFile)
        ?: PathEnvironmentVariableUtil.findExecutableInPathOnAnyOS("node")
        ?: error("Select a local Node.js runtime in WebStorm or add Node.js to its PATH.")
}

private fun saveWorkspaceDocuments(root: Path) {
    val manager = FileDocumentManager.getInstance()

    for (document in manager.unsavedDocuments) {
        val file = manager.getFile(document)

        if (file?.isInLocalFileSystem == true && file.toNioPath().normalize().startsWith(root)) {
            check(file.toNioPath() != root.resolve("migrations.json")) {
                "Save migrations.json and inspect the plan again before running it."
            }
            manager.saveDocument(document)
        }
    }
}

internal suspend fun runMigrationCommand(
    node: File,
    plan: TaigaMigrationPlan,
    arguments: List<String>,
    onOutput: (String, Key<*>) -> Unit,
) {
    currentCoroutineContext().ensureActive()

    val command =
        GeneralCommandLine(listOf(node.absolutePath, plan.script.toString()) + arguments)
            .withWorkDirectory(plan.root.toFile())
            .withEnvironment("NO_COLOR", "1")
    val path = PathEnvironmentVariableUtil.getPathVariableValue()

    command.withEnvironment("PATH", listOfNotNull(node.absoluteFile.parent, path).joinToString(File.pathSeparator))
    val display = (listOf(plan.cli.command) + arguments).joinToString(" ")
    val handler = OSProcessHandler(command).apply { setShouldDestroyProcessRecursively(true) }

    onOutput("> $display\n", ProcessOutputTypes.SYSTEM)
    handler.addProcessListener(
        object : ProcessListener {
            override fun onTextAvailable(
                event: ProcessEvent,
                outputType: Key<*>,
            ) {
                onOutput(event.text, outputType)
            }
        },
    )
    handler.startNotify()

    try {
        handler.processInput.close()

        while (!handler.waitFor(200)) {
            currentCoroutineContext().ensureActive()
        }
    } finally {
        if (!handler.isProcessTerminated) {
            handler.destroyProcess()
            handler.waitFor(5_000)
        }
    }

    val exitCode = checkNotNull(handler.exitCode)

    if (exitCode != 0) {
        throw MigrationCommandFailed(display, exitCode)
    }
}

private class MigrationCommandFailed(
    command: String,
    val exitCode: Int,
) : IllegalStateException("$command failed (exit code $exitCode).")

private const val TITLE = "Taiga UI Migration"
