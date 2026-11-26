package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.mcpserver.impl.ReflectionToolsProvider
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.components.service
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
import org.taigaui.designtokens.documentation.TaigaDocsCache
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocsSources
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

    fun testTokenSearchResolutionAndColorMatching() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val tokens = Files.createDirectories(path.resolve("node_modules/@taiga-ui/design-tokens"))
        Files.writeString(tokens.resolve("package.json"), """{"name":"@taiga-ui/design-tokens","version":"0.310.0"}""")
        Files.writeString(tokens.resolve("tokens.css"), ":root { --tui-fixture-white: #fff; }")
        Files.writeString(path.resolve("card.css"), ".card { color: #fff; }")
        root.refresh(false, true)

        val search = tool("taiga_ui_search_tokens")
        val searchArgs =
            buildJsonObject {
                put("source", "card.css")
                put("query", "FIXTURE-WHITE")
                put("limit", 1)
            }
        val found = search.callInProject(searchArgs).successData()
        val match = found["matches"]?.jsonArray?.single()?.jsonObject
        assertEquals("1", found["total"]?.jsonPrimitive?.content)
        assertEquals("--tui-fixture-white", match?.get("name")?.jsonPrimitive?.content)
        assertEquals("false", match?.get("deprecated")?.jsonPrimitive?.content)

        for (limit in listOf(0, 51)) {
            val invalid =
                buildJsonObject {
                    put("source", "card.css")
                    put("query", "white")
                    put("limit", limit)
                }
            assertTrue(search.callInProject(invalid).isError)
        }

        val resolve = tool("taiga_ui_resolve_token")
        val resolved =
            resolve
                .callInProject(
                    buildJsonObject {
                        put("source", "card.css")
                        put("name", "--tui-fixture-white")
                    },
                ).successData()
        assertEquals(match, resolved["token"]?.jsonObject)
        assertTrue(requireNotNull(resolved["sections"]).jsonArray.isNotEmpty())
        val missing =
            resolve
                .callInProject(
                    buildJsonObject {
                        put("source", "card.css")
                        put("name", "--tui-fixture-missing")
                    },
                )
            assertTrue(missing.isError)

            val color = tool("taiga_ui_match_color")
            val matches =
                color.callInProject(
                    buildJsonObject {
                        put("source", "card.css")
                        put("color", " #ffffff ")
                    },
                ).successData()
        val matchedColor = matches["matches"]?.jsonArray?.single()
        assertEquals("--tui-fixture-white", matchedColor?.jsonPrimitive?.content)
        assertEquals("false", matches["ambiguous"]?.jsonPrimitive?.content)
        for ((source, literal) in listOf("." to "#fff", "card.css" to "not-a-color")) {
            val invalid =
                color.callInProject(
                    buildJsonObject {
                        put("source", source)
                        put("color", literal)
                    },
                )
            assertTrue(invalid.isError)
        }
    }

    fun testApiToolsReadCachedDocumentationForTheSelectedApp() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        writeApp(root.toNioPath(), "5.34.1", "search")
        val source = requireNotNull(TaigaDocsSources.forMajor(5))
        val cache = TaigaDocsCache()
        val original = cache.read(source)
        val service = project.service<TaigaDocsService>()

        try {
            assertTrue(
                cache.write(
                    source,
                    """
                    # components/Input
                    - **Package**: CORE

                    Input description.

                    ### API - Inputs
                    | Property | Type | Description |
                    | --- | --- | --- |
                    | value | string | Current value |

                    ### API - Outputs
                    | Property | Type | Description |
                    | --- | --- | --- |
                    | valueChange | string | Changed value |
                    """.trimIndent(),
                ),
            )
            service.clearMemory()

            val search =
                tool("taiga_ui_search_api")
                    .callInProject(
                        buildJsonObject {
                            put("source", ".")
                            put("query", "Input")
                        },
                    ).successData()
            assertEquals("5.34.1", search["installedVersion"]?.jsonPrimitive?.content)
            val entry = requireNotNull(search["matches"]?.jsonArray?.single()?.jsonObject)
            val section = requireNotNull(entry["sectionId"]?.jsonPrimitive?.content)
            assertEquals("Input", entry["title"]?.jsonPrimitive?.content)

            val read = tool("taiga_ui_read_api")
            val data =
                read
                    .callInProject(
                        buildJsonObject {
                            put("source", ".")
                            put("sectionId", section)
                        },
                    ).successData()
            assertEquals("Input description.", data["description"]?.jsonPrimitive?.content)
            val input = data["documentedInputs"]?.jsonArray?.single()?.jsonObject
            val output = data["documentedOutputs"]?.jsonArray?.single()?.jsonObject
            assertEquals("value", input?.get("name")?.jsonPrimitive?.content)
            assertEquals("valueChange", output?.get("name")?.jsonPrimitive?.content)
            assertEquals("string", input?.get("type")?.jsonPrimitive?.content)

            val missing =
                read
                    .callInProject(
                        buildJsonObject {
                            put("source", ".")
                            put("sectionId", "components/missing")
                        },
                    )
                assertTrue(missing.isError)
            } finally {
                service.clearMemory()
                if (original == null) {
                    cache.invalidate(source)
                } else {
                    cache.write(source, original)
                }
            }
        }

        fun testSourceValidationAndApiLocations() {
            val root = getOrCreateProjectBaseDir()
            PsiTestUtil.addContentRoot(module, root)
            val path = root.toNioPath()
            Files.writeString(path.resolve("plain.ts"), "export const value = 1;")
            root.refresh(false, true)

            val inspect = tool("taiga_ui_inspect_project")
            for (source in listOf("", path.toString(), "missing.ts", "../outside")) {
                assertTrue(inspect.callInProject(buildJsonObject { put("source", source) }).isError)
            }

            val api = tool("taiga_ui_api_at")
            for ((line, column) in listOf(0 to 1, 1 to 0, 1 to 100, 1 to 1)) {
                val result =
                    api.callInProject(
                        buildJsonObject {
                            put("source", "plain.ts")
                            put("line", line)
                            put("column", column)
                        },
                    )
                assertTrue(result.isError)
            }
        }

        fun testFolderColorPreviewForAnEmptyDirectoryHasNoChanges() {
            val root = getOrCreateProjectBaseDir()
            PsiTestUtil.addContentRoot(module, root)
            Files.createDirectories(root.toNioPath().resolve("empty"))
            root.refresh(false, true)

            val preview =
                tool("taiga_ui_preview_folder_colors")
                    .callInProject(buildJsonObject { put("directory", "empty") })
                    .successData()
            assertEquals("0", preview["totalFiles"]?.jsonPrimitive?.content)
            assertTrue(requireNotNull(preview["files"]).jsonArray.isEmpty())

            val result =
                tool("taiga_ui_apply_folder_colors")
                    .callInProject(
                        buildJsonObject {
                            put("directory", "empty")
                            put("revision", requireNotNull(preview["revision"]?.jsonPrimitive?.content))
                        },
                    ).successData()
        assertEquals("no_changes", result["status"]?.jsonPrimitive?.content)
    }

    fun testRemConversionValidatesDecimalInput() {
        val convert = tool("taiga_ui_convert_rem")
        for ((rem, css) in listOf("-0.5" to "-8px", ".25" to "4px", "0" to "0px")) {
            val result = convert.callInProject(buildJsonObject { put("rem", rem) }).successData()
            assertEquals(css, result["css"]?.jsonPrimitive?.content)
        }

        for (rem in listOf("", "1px", "NaN", "1".repeat(41))) {
            assertTrue(convert.callInProject(buildJsonObject { put("rem", rem) }).isError)
        }
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
