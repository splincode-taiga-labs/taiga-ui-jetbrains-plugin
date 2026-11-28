package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class TaigaNxMigrationToolsetTest : BasePlatformTestCase() {
    fun testRegisteredMcpToolsRejectPathsOutsideTheProject() {
        val tools = registeredMcpTools().associateBy { it.descriptor.name }
        val inspect = requireNotNull(tools["taiga_ui_inspect_nx_migration_plan"])
        val run = requireNotNull(tools["taiga_ui_run_nx_migration_plan"])
        val args = buildJsonObject { put("directory", "../outside") }
        val info =
            McpCallInfo(
                callId = 1,
                clientInfo = ClientInfo("test", "1"),
                project = project,
                mcpToolDescriptor = inspect.descriptor,
                rawArguments = args,
                meta = buildJsonObject {},
                mcpSessionOptions = McpServerService.McpSessionOptions(McpServerService.AskCommandExecutionMode.ASK),
                headers = emptyMap(),
            )

        assertEquals("taiga_ui_run_nx_migration_plan", run.descriptor.name)
        runBlocking {
            val result = withContext(McpCallAdditionalDataElement(info)) { inspect.call(args) }

            assertTrue(result.isError)
            assertTrue(result.toString().contains("inside the IDE project"))
        }
    }
}
