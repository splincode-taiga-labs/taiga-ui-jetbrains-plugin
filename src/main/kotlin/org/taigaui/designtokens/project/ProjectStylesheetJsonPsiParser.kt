package org.taigaui.designtokens.project

import com.intellij.json.JsonFileType
import com.intellij.json.psi.JsonArray
import com.intellij.json.psi.JsonFile
import com.intellij.json.psi.JsonObject
import com.intellij.json.psi.JsonStringLiteral
import com.intellij.json.psi.JsonValue
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiFileFactory

internal class ProjectStylesheetJsonPsiParser(
    private val project: Project,
) {
    fun parseStyleGroups(content: String): List<List<String>> =
        ReadAction.compute<List<List<String>>, RuntimeException> {
            val jsonFile =
                PsiFileFactory
                    .getInstance(project)
                    .createFileFromText(CONFIG_FILE_NAME, JsonFileType.INSTANCE, content) as JsonFile

            buildList {
                jsonFile.topLevelValue?.collectStyleGroups(this)
            }
        }

    private fun JsonValue.collectStyleGroups(groups: MutableList<List<String>>) {
        when (this) {
            is JsonObject ->
                propertyList.forEach { property ->
                    val value = property.value ?: return@forEach

                    if (property.name == STYLES_PROPERTY && value is JsonArray) {
                        value
                            .collectStylesheetPaths()
                            .takeIf { paths -> paths.isNotEmpty() }
                            ?.let(groups::add)
                    }

                    value.collectStyleGroups(groups)
                }
            is JsonArray -> valueList.forEach { value -> value.collectStyleGroups(groups) }
        }
    }

    private fun JsonValue.collectStylesheetPaths(): List<String> =
        buildList {
            collectStylesheetPaths(this)
        }

    private fun JsonValue.collectStylesheetPaths(paths: MutableList<String>) {
        when (this) {
            is JsonStringLiteral ->
                value
                    .takeIf { path -> path.isStylesheetPath() }
                    ?.let(paths::add)
            is JsonObject ->
                propertyList
                    .mapNotNull { property -> property.value }
                    .forEach { value -> value.collectStylesheetPaths(paths) }
            is JsonArray -> valueList.forEach { value -> value.collectStylesheetPaths(paths) }
        }
    }

    private fun String.isStylesheetPath(): Boolean {
        val lowercase = lowercase()

        return STYLESHEET_EXTENSIONS.any(lowercase::endsWith)
    }

    private companion object {
        const val CONFIG_FILE_NAME = "project-styles.json"
        const val STYLES_PROPERTY = "styles"
        val STYLESHEET_EXTENSIONS = listOf(".css", ".less", ".scss")
    }
}
