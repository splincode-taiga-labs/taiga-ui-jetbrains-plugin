package org.taigaui.designtokens.mcp

import com.intellij.openapi.components.service
import org.taigaui.designtokens.settings.TaigaDesignTokensSettings

internal fun taigaMcpDenial(changes: Boolean): String? {
    val settings = service<TaigaDesignTokensSettings>()

    return when {
        !settings.exposeMcpTools -> "Taiga UI MCP tools are disabled in Settings | Tools | Taiga UI."
        changes && !settings.allowMcpChanges ->
            "Project changes via Taiga UI MCP are disabled in Settings | Tools | Taiga UI."
        else -> null
    }
}

internal fun requireTaigaMcpChanges() {
    taigaMcpDenial(changes = true)?.let { error(it) }
}
