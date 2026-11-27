package org.taigaui.designtokens.settings

import com.intellij.openapi.components.service
import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

internal class TaigaDesignTokensConfigurable : BoundConfigurable("Taiga UI") {
    override fun createPanel(): DialogPanel {
        val settings = service<TaigaDesignTokensSettings>()

        return panel {
            row("Network policy:") {
                comboBox(TaigaNetworkMode.entries)
                    .bindItem({ settings.networkMode }, { value -> value?.let { settings.networkMode = it } })
                    .comment("Cache first allows explicit refreshes. Offline only uses local data.")
            }
            group("Design tokens") {
                row {
                    checkBox("Show design token completion preview")
                        .bindSelected(
                            { settings.showCompletionPreview },
                            { value -> settings.showCompletionPreview = value },
                        )
                }
                row {
                    checkBox("Show design token hover popup")
                        .bindSelected(
                            { settings.showHoverPopup },
                            { value -> settings.showHoverPopup = value },
                        )
                }
            }
        }
    }
}
