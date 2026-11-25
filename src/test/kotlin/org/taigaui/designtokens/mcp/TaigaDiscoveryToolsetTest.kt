package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class TaigaDiscoveryToolsetTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testAllDiscoveryToolsAreRegisteredWithTheBundledMcpProvider() {
        val names = ReflectionToolsProvider().getTools().map { it.descriptor.name }

        for (name in listOf(
            "inspect_project",
            "check_release",
            "search_api",
            "read_api",
            "api_at",
            "search_tokens",
            "resolve_token",
            "match_color",
            "preview_folder_colors",
            "apply_folder_colors",
            "search_icons",
            "convert_rem",
            "inspect_migration",
            "run_migration",
            "inspect_nx_migration_plan",
            "run_nx_migration_plan",
        )) {
            assertTrue(name, "taiga_ui_$name" in names)
        }
    }

    fun testToolsReturnStructuredResultsThroughMcp() {
        val tool = tool("taiga_ui_convert_rem")
        val result = tool.callInProject(buildJsonObject { put("rem", "1.25") })

        assertFalse(result.isError)
        val data = requireNotNull(result.structuredContent)
        assertEquals("20px", data["css"]?.jsonPrimitive?.content)
        assertTrue(tool.callInProject(buildJsonObject { put("rem", "1e1000000") }).isError)
    }

    fun testProjectReadRejectsAnEscapingPath() {
        val result = tool("taiga_ui_inspect_project").callInProject(buildJsonObject { put("source", "../outside") })

        assertTrue(result.isError)
        assertTrue(result.toString().contains("inside the IDE project"))
    }

    fun testProjectAndIconToolsUseTheSelectedMonorepoApp() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        writeApp(path.resolve("legacy"), "4.110.0", "legacy-search")
        writeApp(path.resolve("modern"), "5.34.1", "modern-search")

        for ((app, version) in listOf("legacy" to "4.110.0", "modern" to "5.34.1")) {
            val args = buildJsonObject { put("source", app) }
            val doctor = tool("taiga_ui_inspect_project").callInProject(args).successData()
            assertEquals(version, doctor["version"]?.jsonPrimitive?.content)

            val iconArgs =
                buildJsonObject {
                    put("source", app)
                    put("query", "search")
                }
            val icons = tool("taiga_ui_search_icons").callInProject(iconArgs).successData()
            val match = icons["matches"]?.jsonArray?.single()?.jsonObject
            assertEquals("@tui.$app-search", match?.get("name")?.jsonPrimitive?.content)
        }
    }

    fun testNewMigrationChecksTheRevisionBeforeRequestingApproval() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val cdk = Files.createDirectories(path.resolve("node_modules/@taiga-ui/cdk"))
        val cli = Files.createDirectories(path.resolve("node_modules/@angular/cli/bin"))
        val manifest = path.resolve("package.json")
        Files.writeString(cdk.resolve("package.json"), """{"name":"@taiga-ui/cdk","version":"4.110.0"}""")
        Files.writeString(cli.resolve("ng.js"), "")
        Files.writeString(path.resolve("angular.json"), "{}")
        Files.writeString(manifest, "{}")

        val previewArgs = buildJsonObject { put("directory", ".") }
        val preview = tool("taiga_ui_inspect_migration").callInProject(previewArgs).successData()
        assertEquals("ng", preview["cli"]?.jsonPrimitive?.content)
        val run = tool("taiga_ui_run_migration")
        val runArgs = withRevision(preview)
        assertEquals(
            "confirmation_required",
            run
                .callInProject(runArgs)
                .successData()["status"]
                ?.jsonPrimitive
                ?.content,
        )

        Files.writeString(manifest, """{"name":"changed"}""")
        val stale = run.callInProject(runArgs)
        assertTrue(stale.isError)
        assertTrue(stale.toString().contains("Inspect the plan again"))
    }

    fun testFolderColorReplacementChecksTheDiffAndRequiresApproval() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val tokens = Files.createDirectories(path.resolve("node_modules/@taiga-ui/design-tokens"))
        Files.writeString(tokens.resolve("package.json"), """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""")
        Files.writeString(tokens.resolve("tokens.css"), ":root { --tui-white: #fff; }")
        val css = path.resolve("card.css")
        val original = ".card { color: #fff; }"
        Files.writeString(css, original)
        root.refresh(false, true)

        val previewArgs = buildJsonObject { put("directory", ".") }
        val preview = tool("taiga_ui_preview_folder_colors").callInProject(previewArgs).successData()
        assertEquals(
            "card.css",
            preview["files"]
                ?.jsonArray
                ?.single()
                ?.jsonPrimitive
                ?.content,
        )
        val apply = tool("taiga_ui_apply_folder_colors")
        val applyArgs = withRevision(preview)
        assertEquals(
            "confirmation_required",
            apply
                .callInProject(applyArgs)
                .successData()["status"]
                ?.jsonPrimitive
                ?.content,
        )
        val file = requireNotNull(LocalFileSystem.getInstance().findFileByNioFile(css))
        val document = requireNotNull(FileDocumentManager.getInstance().getDocument(file))
        assertEquals(original, document.text)

        WriteCommandAction.runWriteCommandAction(project) { document.setText(".card { color: #000; }") }
        val stale = apply.callInProject(applyArgs)
        assertTrue(stale.isError)
        assertTrue(stale.toString().contains("Preview the folder again"))
    }

    private fun withRevision(preview: JsonObject): JsonObject =
        buildJsonObject {
            put("directory", ".")
            put("revision", requireNotNull(preview["revision"]?.jsonPrimitive?.content))
        }

    private fun writeApp(
        path: Path,
        version: String,
        icon: String,
    ) {
        val scoped = path.resolve("node_modules/@taiga-ui")
        val core = Files.createDirectories(scoped.resolve("core"))
        val icons = Files.createDirectories(scoped.resolve("icons/src"))
        Files.writeString(core.resolve("package.json"), """{"name":"@taiga-ui/core","version":"$version"}""")
        Files.writeString(icons.parent.resolve("package.json"), """{"name":"@taiga-ui/icons","version":"$version"}""")
        Files.writeString(icons.resolve("$icon.svg"), """<svg xmlns="http://www.w3.org/2000/svg"/>""")
    }

    private fun McpToolCallResult.successData(): JsonObject {
        assertFalse(toString(), isError)

        return requireNotNull(structuredContent)
    }

    private fun tool(name: String): McpTool = ReflectionToolsProvider().getTools().single { it.descriptor.name == name }

    private fun McpTool.callInProject(args: JsonObject): McpToolCallResult {
        val info =
            McpCallInfo(
                callId = 1,
                clientInfo = ClientInfo("test", "1"),
                project = project,
                mcpToolDescriptor = descriptor,
                rawArguments = args,
                meta = buildJsonObject {},
                mcpSessionOptions = McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.ASK),
                headers = emptyMap(),
            )

        return PlatformTestUtil.waitForFuture(
            AppExecutorUtil.getAppExecutorService().submit(
                Callable { runBlocking(McpCallAdditionalDataElement(info)) { call(args) } },
            ),
        )
    }
}
