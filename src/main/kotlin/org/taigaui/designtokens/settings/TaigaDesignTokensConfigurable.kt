package org.taigaui.designtokens.settings

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.options.SearchableConfigurable
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.options.ex.Settings
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bind
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel
import java.awt.Component

internal class TaigaDesignTokensConfigurable : BoundConfigurable("Taiga UI") {
    override fun createPanel(): DialogPanel {
        val settings = service<TaigaDesignTokensSettings>()

        return panel {
            group("Design tokens") {
                row {
                    checkBox("Show completion preview")
                        .bindSelected(settings::showCompletionPreview)
                        .comment("Show resolved values and color swatches beside the selected token suggestion.")
                }
                row {
                    checkBox("Show hover popup")
                        .bindSelected(settings::showHoverPopup)
                        .comment("Show token values and their sources when you hover over a var(--tui-…) reference.")
                }
            }

            group("Network access") {
                buttonsGroup {
                    row {
                        radioButton("Cache first (default)", TaigaNetworkMode.CACHE_FIRST)
                            .comment("Use local data. Download documentation only when you refresh it manually.")
                    }
                    row {
                        radioButton("Online", TaigaNetworkMode.ONLINE)
                            .comment("Allow automatic requests for documentation, CDN icons, and release information.")
                    }
                    row {
                        radioButton("Offline only", TaigaNetworkMode.OFFLINE_ONLY)
                            .comment("Use installed, bundled, and cached data, even when you refresh documentation.")
                    }
                }.bind(settings::networkMode)
            }

            group("Plugin and MCP") {
                row {
                    link(
                        "Open Plugins settings",
                    ) { event -> openSettings(event.source as Component, PLUGINS_SETTINGS_ID) }
                        .comment("Enable or disable Taiga xUI Community in the Installed tab.")
                }
                row {
                    link(
                        "Open MCP Server settings",
                    ) { event -> openSettings(event.source as Component, MCP_SETTINGS_ID) }
                        .comment(
                            "Manage AI client connections and, where supported, individual Taiga UI tools. " +
                                "If MCP Server is disabled, enable it in Plugins.",
                        )
                }
            }
        }
    }
}

private const val PLUGINS_SETTINGS_ID = "preferences.pluginManager"
private const val MCP_SETTINGS_ID = "com.intellij.mcpserver.settings"

private fun openSettings(
    component: Component,
    configurableId: String,
) {
    val context = DataManager.getInstance().getDataContext(component)
    val settings = Settings.KEY.getData(context)
    if (settings != null) {
        settings.select(settings.find(configurableId) ?: settings.find(PLUGINS_SETTINGS_ID))
    } else {
        ShowSettingsUtil.getInstance().showSettingsDialog(
            CommonDataKeys.PROJECT.getData(context),
            { configurable -> configurable is SearchableConfigurable && configurable.id == configurableId },
            null,
        )
    }
}
