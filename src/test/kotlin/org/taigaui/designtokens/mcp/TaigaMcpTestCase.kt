package org.taigaui.designtokens.mcp

import com.intellij.mcpserver.ClientInfo
import com.intellij.mcpserver.McpCallAdditionalDataElement
import com.intellij.mcpserver.McpCallInfo
import com.intellij.mcpserver.McpTool
import com.intellij.mcpserver.McpToolCallResult
import com.intellij.mcpserver.McpToolsProvider
import com.intellij.mcpserver.impl.McpServerService
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

abstract class TaigaMcpTestCase : HeavyPlatformTestCase() {
    protected fun writeApp(
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

    protected fun McpToolCallResult.successData(): JsonObject {
        assertFalse(toString(), isError)

        return requireNotNull(structuredContent)
    }

    protected fun tool(name: String): McpTool = registeredMcpTools().single { it.descriptor.name == name }

    protected fun McpTool.callInProject(args: JsonObject): McpToolCallResult {
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

internal fun registeredMcpTools(): List<McpTool> = McpToolsProvider.EP.extensionList.flatMap { it.getTools() }
