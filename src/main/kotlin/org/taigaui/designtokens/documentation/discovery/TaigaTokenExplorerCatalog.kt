package org.taigaui.designtokens.documentation.discovery

import org.taigaui.designtokens.documentation.DesignTokenHoverPopupModel
import org.taigaui.designtokens.project.DesignTokenCatalogEntry
import org.taigaui.designtokens.project.DesignTokenIndexService
import org.taigaui.designtokens.project.DesignTokenResolutionSnapshot
import java.nio.file.Path

internal class TaigaTokenExplorerCatalog private constructor(
    private val snapshot: DesignTokenResolutionSnapshot,
) {
    fun search(query: String): List<DesignTokenCatalogEntry> {
        val term = query.trim()

        return snapshot.tokenCatalog.filter { entry -> entry.name.contains(term, ignoreCase = true) }
    }

    fun details(entry: DesignTokenCatalogEntry): DesignTokenHoverPopupModel =
        DesignTokenHoverPopupModel.create(
            tokenName = entry.name,
            groups = snapshot.resolver?.resolveGrouped(entry.name).orEmpty(),
        )

    companion object {
        fun load(
            service: DesignTokenIndexService,
            source: Path,
        ): TaigaTokenExplorerCatalog =
            TaigaTokenExplorerCatalog(service.resolutionSnapshot(source, requireCompleteNameCatalog = true))
    }
}
