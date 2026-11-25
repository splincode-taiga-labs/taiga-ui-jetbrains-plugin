package org.taigaui.designtokens.migration

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path

internal data class ExistingNxMigrationPlan(
    val workspace: TaigaMigrationPlan,
    val snapshot: String,
    val entries: List<NxMigrationEntry>,
) {
    val migrations: List<String>
        get() = entries.map { "${it.packageName}: ${it.name}" }

    val revision: String
        get() = migrationRevision(workspace.root.toString(), snapshot)

    fun requireRevision(expected: String) {
        check(revision == expected) { "The workspace or migration plan changed. Inspect the plan again." }
    }

    suspend fun run(execute: suspend (List<String>) -> Unit) {
        execute(RUN_NX_MIGRATIONS)
        workspace.checkInstalledV5()
    }
}

internal fun prepareExistingNxMigration(
    selected: Path,
    projectRoot: Path,
): ExistingNxMigrationPlan {
    val workspace = resolveTaigaMigrationWorkspace(selected, projectRoot)
    val file = workspace.migrationsFile

    check(workspace.cli == TaigaMigrationCli.NX && file.isRegularFileWithoutFollowingLinks()) {
        "No existing migrations.json was found in this Nx workspace."
    }

    val snapshot = Files.readString(file)
    val entries = readMigrationEntries(snapshot)
    check(
        entries.any {
            it.packageName == "@taiga-ui/cdk" &&
                it.name == "updateToV5" &&
                it.version?.substringBefore('.') == "5"
        },
    ) {
        "migrations.json does not contain the Taiga UI v4 to v5 migration."
    }

    return ExistingNxMigrationPlan(workspace, snapshot, entries)
}

private fun readMigrationEntries(snapshot: String): List<NxMigrationEntry> {
    val root =
        try {
            JsonParser.parseString(snapshot) as? JsonObject
        } catch (error: JsonParseException) {
            throw IllegalStateException("Cannot read migrations.json: ${error.message}", error)
        }
    val entries = root?.get("migrations")?.takeIf(JsonElement::isJsonArray)?.asJsonArray

    return checkNotNull(entries) { "migrations.json must contain a migrations array." }.map { entry ->
        val migration = checkNotNull(entry as? JsonObject) { "Every entry in migrations.json must be an object." }
        val packageName = migration.readString("package")
        val name = migration.readString("name")

        check(packageName != null && name != null) { "Every entry in migrations.json must have a package and name." }
        NxMigrationEntry(packageName, name, migration.readString("version"))
    }
}

private fun JsonObject.readString(key: String): String? =
    get(key)
        ?.takeIf(JsonElement::isJsonPrimitive)
        ?.asJsonPrimitive
        ?.takeIf { it.isString }
        ?.asString
        ?.takeIf(String::isNotBlank)

internal data class NxMigrationEntry(
    val packageName: String,
    val name: String,
    val version: String?,
)
