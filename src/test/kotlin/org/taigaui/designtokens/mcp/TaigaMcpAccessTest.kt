package org.taigaui.designtokens.mcp

import com.intellij.openapi.components.service
import com.intellij.testFramework.PsiTestUtil
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings

class TaigaMcpAccessTest : TaigaMcpTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testDisablingExposureHidesTaigaToolsAndRejectsCachedCalls() {
        val settings = service<TaigaDesignTokensSettings>()
        val previouslyExposed = settings.exposeMcpTools
        settings.exposeMcpTools = true

        try {
            val allTools = registeredMcpTools().map { it.descriptor.name }.toSet()
            val otherTools = allTools.filterNot { it.startsWith("taiga_ui_") }.toSet()
            val cached = tool("taiga_ui_convert_rem")
            val args = buildJsonObject { put("rem", "1.25") }

            settings.exposeMcpTools = false

            assertEquals(otherTools, registeredMcpTools().map { it.descriptor.name }.toSet())
            val result = cached.callInProject(args)
            assertTrue(result.isError)
            assertTrue(result.toString().contains("Taiga UI MCP tools are disabled"))

            settings.exposeMcpTools = true

            assertEquals(allTools, registeredMcpTools().map { it.descriptor.name }.toSet())
            assertEquals(
                "20px",
                cached
                    .callInProject(args)
                    .successData()["css"]
                    ?.jsonPrimitive
                    ?.content,
            )
        } finally {
            settings.exposeMcpTools = previouslyExposed
        }
    }

    fun testDisablingChangesKeepsPreviewAndRejectsCachedMutationTools() {
        val settings = service<TaigaDesignTokensSettings>()
        val previouslyAllowed = settings.allowMcpChanges
        settings.allowMcpChanges = true

        try {
            val root = getOrCreateProjectBaseDir()
            PsiTestUtil.addContentRoot(module, root)
            val mutations =
                listOf(
                    "taiga_ui_apply_folder_colors",
                    "taiga_ui_run_migration",
                    "taiga_ui_run_nx_migration_plan",
                ).map(::tool)

            settings.allowMcpChanges = false

            val preview =
                tool("taiga_ui_preview_folder_colors")
                    .callInProject(buildJsonObject { put("directory", ".") })
                    .successData()
            val args =
                buildJsonObject {
                    put("directory", ".")
                    put("revision", requireNotNull(preview["revision"]))
                }
            val available = registeredMcpTools().map { it.descriptor.name }.toSet()
            assertTrue("taiga_ui_inspect_migration" in available)
            assertTrue("taiga_ui_search_api" in available)

            for (mutation in mutations) {
                assertFalse(mutation.descriptor.name in available)
                val result = mutation.callInProject(args)
                assertTrue(result.isError)
                assertTrue(result.toString().contains("Project changes via Taiga UI MCP are disabled"))
            }

            settings.allowMcpChanges = true
            assertEquals(
                "no_changes",
                mutations
                    .first()
                    .callInProject(args)
                    .successData()["status"]
                    ?.jsonPrimitive
                    ?.content,
            )
        } finally {
            settings.allowMcpChanges = previouslyAllowed
        }
    }
}
