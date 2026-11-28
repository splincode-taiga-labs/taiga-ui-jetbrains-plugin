package org.taigaui.designtokens.mcp

import com.intellij.openapi.components.service
import com.intellij.testFramework.PsiTestUtil
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.taigaui.designtokens.documentation.TaigaDocsCache
import org.taigaui.designtokens.documentation.TaigaDocsService
import org.taigaui.designtokens.documentation.TaigaDocsSources
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings
import org.taigaui.designtokens.settings.TaigaNetworkMode
import java.nio.file.Files

class TaigaOfflineMcpTest : TaigaMcpTestCase() {
    fun testOfflineApiSearchUsesMatchingBundleOnColdAndWarmStarts() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val docs = project.service<TaigaDocsService>()
        val settings = service<TaigaDesignTokensSettings>()
        val previousMode = settings.networkMode
        val cache = TaigaDocsCache()
        val sources = listOf(4, 5).map { major -> requireNotNull(TaigaDocsSources.forMajor(major)) }
        val originals = sources.associateWith(cache::read)

        try {
            settings.networkMode = TaigaNetworkMode.OFFLINE_ONLY
            docs.clearMemory()
            sources.forEach(cache::invalidate)
            writeApp(root.toNioPath().resolve("legacy"), "4.110.0", "legacy")
            writeApp(root.toNioPath().resolve("modern"), "5.34.1", "modern")
            for ((app, version) in listOf("legacy" to "4.110.0", "modern" to "5.34.1")) {
                val appPath = root.toNioPath().resolve(app)
                Files.writeString(appPath.resolve("app.ts"), "export {};")
                Files.writeString(
                    appPath.resolve("package.json"),
                    """{"name":"$app","dependencies":{"@taiga-ui/core":"$version"}}""",
                )
                Files.writeString(
                    appPath.resolve("tsconfig.json"),
                    """{"compilerOptions":{"module":"ESNext","moduleResolution":"bundler"}}""",
                )
            }
            Files.writeString(
                root.toNioPath().resolve("modern/node_modules/@taiga-ui/core/package.json"),
                """{"name":"@taiga-ui/core","version":"5.34.1","types":"./index.d.ts"}""",
            )
            Files.writeString(
                root.toNioPath().resolve("modern/node_modules/@taiga-ui/core/index.d.ts"),
                "export declare class TuiButton {}",
            )
            root.refresh(false, true)
            val search = tool("taiga_ui_search_api")

            repeat(2) {
                for ((app, version) in listOf("legacy" to "4.110.0", "modern" to "5.34.1")) {
                    val result =
                        search
                            .callInProject(
                                buildJsonObject {
                                    put("source", app)
                                    put("query", "tuiButton")
                                },
                            ).successData()
                    val provenance = result["documentation"]?.jsonObject

                    assertEquals(version, result["installedVersion"]?.jsonPrimitive?.content)
                    assertEquals(version.substringBefore('.'), provenance?.get("major")?.jsonPrimitive?.content)
                    assertEquals("BUNDLED", provenance?.get("origin")?.jsonPrimitive?.content)
                    assertTrue(result["matches"]?.jsonArray?.isNotEmpty() == true)
                    assertOfflineExamplesAndContext(app, version)
                }
            }

            val doctor = tool("taiga_ui_inspect_project").callInProject(buildJsonObject { put("source", "legacy") })
            assertEquals("OFFLINE_ONLY", doctor.successData()["networkPolicy"]?.jsonPrimitive?.content)
            val guide =
                tool("taiga_ui_get_migration_guide")
                    .callInProject(buildJsonObject { put("source", "legacy/app.ts") })
                    .successData()
            assertEquals("4.110.0", guide["installedVersion"]?.jsonPrimitive?.content)
            assertEquals(
                "5",
                guide["documentation"]
                    ?.jsonObject
                    ?.get("major")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals(
                "BUNDLED",
                guide["documentation"]
                    ?.jsonObject
                    ?.get("origin")
                    ?.jsonPrimitive
                    ?.content,
            )
            assertEquals("available", guide["status"]?.jsonPrimitive?.content)
            assertTrue(requireNotNull(guide["markdown"]).jsonPrimitive.content.contains("Troubleshooting"))
        } finally {
            settings.networkMode = previousMode
            docs.clearMemory()
            originals.forEach { (source, original) ->
                if (original != null) {
                    cache.write(source, original)
                }
            }
        }
    }

    private fun assertOfflineExamplesAndContext(
        app: String,
        version: String,
    ) {
        val source = "$app/app.ts"
        val context =
            tool("taiga_ui_get_generation_context")
                .callInProject(buildJsonObject { put("source", source) })
                .successData()
        assertEquals(version, context["installedVersion"]?.jsonPrimitive?.content)
        assertTrue(JsonPrimitive("@taiga-ui/core") in requireNotNull(context["installedPackages"]).jsonArray)
        assertTrue(requireNotNull(context["checklist"]).jsonPrimitive.content.contains("import", ignoreCase = true))
        assertTrue(requireNotNull(context["commonMistakes"]).jsonPrimitive.content.contains("Wrong Import"))

        val examples =
            tool("taiga_ui_get_examples")
                .callInProject(
                    buildJsonObject {
                        put("source", source)
                        put("names", listOf("components/button", "tuiLink", "buttn", "zzzzzz").jsonStrings())
                    },
                ).successData()
        assertEquals(version, examples["installedVersion"]?.jsonPrimitive?.content)
        assertEquals(
            version.substringBefore('.'),
            examples["documentation"]
                ?.jsonObject
                ?.get("major")
                ?.jsonPrimitive
                ?.content,
        )
        assertEquals("2", examples["matched"]?.jsonPrimitive?.content)
        val results = requireNotNull(examples["results"]).jsonArray.map { it.jsonObject }
        assertEquals(
            listOf("available", "available", "choose_section", "not_found"),
            results.map { it["status"]?.jsonPrimitive?.content },
        )
        val (button, link) = results
        assertEquals("components/button", button["sectionId"]?.jsonPrimitive?.content)
        assertEquals("components/link", link["sectionId"]?.jsonPrimitive?.content)
        assertTrue(requireNotNull(button["example"]).jsonPrimitive.content.contains("tuiButton"))
        val exports = requireNotNull(button["verifiedExports"]).jsonArray
        if (app == "modern") {
            val verified = exports.single().jsonObject
            assertEquals("TuiButton", verified["symbol"]?.jsonPrimitive?.content)
            assertEquals("@taiga-ui/core", verified["package"]?.jsonPrimitive?.content)
        } else {
            assertTrue(exports.isEmpty())
        }
    }

    fun testMigrationDiagnosticsUseOnlyTheSelectedApp() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val legacy = root.toNioPath().resolve("legacy")
        val modern = root.toNioPath().resolve("modern")
        writeApp(legacy, "4.110.0", "legacy")
        writeApp(modern, "5.34.1", "modern")
        for (app in listOf(legacy, modern)) {
            Files.writeString(app.resolve("project.json"), "{}")
            Files.writeString(app.resolve("styles.css"), "/* TODO: (Taiga UI migration) */")
        }
        root.refresh(false, true)

        val report =
            tool("taiga_ui_inspect_app")
                .callInProject(
                    buildJsonObject {
                        put("source", "modern/styles.css")
                        put("mode", "migration")
                    },
                ).successData()

        assertEquals("modern", report["application"]?.jsonPrimitive?.content)
        assertEquals("false", report["partial"]?.jsonPrimitive?.content)
        val finding = requireNotNull(report["findings"]?.jsonArray).single().jsonObject
        assertEquals("modern/styles.css", finding["file"]?.jsonPrimitive?.content)
        assertEquals("TaigaV5MigrationTodo", finding["inspection"]?.jsonPrimitive?.content)
        assertEquals("1", finding["line"]?.jsonPrimitive?.content)
    }
}
