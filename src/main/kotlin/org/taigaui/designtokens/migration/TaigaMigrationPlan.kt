package org.taigaui.designtokens.migration

import org.taigaui.designtokens.packageinfo.PackageJsonReader
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal data class TaigaMigrationPlan(
    val root: Path,
    val cli: TaigaMigrationCli,
) {
    val script: Path = root.resolve("node_modules").resolve(cli.script)
    val migrationsFile: Path = root.resolve("migrations.json")

    suspend fun run(execute: suspend (List<String>) -> Unit) {
        checkNoExistingPlan()
        execute(cli.updateArguments)

        if (cli == TaigaMigrationCli.NX) {
            check(migrationsFile.isRegularFileWithoutFollowingLinks()) {
                "Nx did not generate migrations.json. Review its output and package.json before retrying."
            }

            execute(listOf("migrate", "--run-migrations=migrations.json"))
        }

        check(installedCdkMajor(root) == "5") {
            "The CLI finished, but the workspace does not have Taiga UI CDK v5 installed. Review the output."
        }
    }

    fun checkNoExistingPlan() {
        check(cli != TaigaMigrationCli.NX || Files.notExists(migrationsFile, LinkOption.NOFOLLOW_LINKS)) {
            "This workspace already has a migrations.json file. Finish or archive that migration plan first."
        }
    }
}

internal enum class TaigaMigrationCli(
    val command: String,
    val script: String,
    val updateArguments: List<String>,
) {
    ANGULAR("ng", "@angular/cli/bin/ng.js", listOf("update", CDK_TARGET)),
    NX("nx", "nx/bin/nx.js", listOf("migrate", CDK_TARGET, "--interactive=false")),
}

internal fun prepareTaigaMigration(
    selected: Path,
    projectRoot: Path,
): TaigaMigrationPlan {
    val root = projectRoot.toRealPath()
    val folder = selected.toRealPath()

    check(folder.startsWith(root) && Files.isDirectory(folder)) {
        "Select a folder inside this project."
    }

    val workspace =
        generateSequence(folder, Path::getParent)
            .takeWhile { it.startsWith(root) }
            .firstOrNull {
                Files.isRegularFile(it.resolve("nx.json")) || Files.isRegularFile(it.resolve("angular.json"))
            } ?: error("No Angular or Nx workspace was found above the selected folder.")
    val cli = if (Files.isRegularFile(workspace.resolve("nx.json"))) TaigaMigrationCli.NX else TaigaMigrationCli.ANGULAR
    val plan = TaigaMigrationPlan(workspace, cli)

    check(Files.isRegularFile(workspace.resolve("package.json")) && Files.isRegularFile(plan.script)) {
        "Install the workspace dependencies first. The local ${cli.command} CLI is required."
    }
    check(
        generateSequence(folder, Path::getParent).takeWhile { it != workspace }.none {
            Files.exists(it.resolve("node_modules/@taiga-ui")) || Files.exists(it.resolve(".pnp.cjs"))
        },
    ) {
        "The selected folder has its own Taiga UI installation. Select the root of its Angular or Nx workspace."
    }
    check(installedCdkMajor(workspace) == "4") {
        "Install Taiga UI CDK v4 in this workspace before running the v4 to v5 migration."
    }

    plan.checkNoExistingPlan()

    return plan
}

private fun Path.isRegularFileWithoutFollowingLinks(): Boolean = Files.isRegularFile(this, LinkOption.NOFOLLOW_LINKS)

private fun installedCdkMajor(root: Path): String? =
    PackageJsonReader().readVersion(root.resolve("node_modules/@taiga-ui/cdk/package.json"))?.substringBefore('.')

private const val CDK_TARGET = "@taiga-ui/cdk@5"
