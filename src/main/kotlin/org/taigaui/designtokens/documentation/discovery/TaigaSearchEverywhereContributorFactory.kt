@file:Suppress("DEPRECATION")

package org.taigaui.designtokens.documentation.discovery

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.ide.actions.searcheverywhere.SearchEverywhereContributorFactory
import com.intellij.ide.actions.searcheverywhere.WeightedSearchEverywhereContributor
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.ProgressIndicatorUtils
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.Processor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.future.future
import java.nio.file.Path
import javax.swing.JList
import javax.swing.ListCellRenderer

internal class TaigaSearchEverywhereContributorFactory : SearchEverywhereContributorFactory<TaigaApiEntry> {
    override fun isAvailable(project: Project?): Boolean = project != null

    override fun createContributor(initEvent: AnActionEvent): WeightedSearchEverywhereContributor<TaigaApiEntry> {
        val project = requireNotNull(initEvent.project)

        return TaigaSearchEverywhereContributor(project, taigaApiSource(project))
    }
}

// The legacy adapter supplies dedicated tabs on all supported IDE builds, including split Search Everywhere.
private class TaigaSearchEverywhereContributor(
    project: Project,
    source: Path?,
) : WeightedSearchEverywhereContributor<TaigaApiEntry> {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val catalog = scope.future { source?.let { TaigaApiCatalog.load(project, it) } }

    override fun getSearchProviderId(): String = "TaigaApiSearch"

    override fun getGroupName(): String = "Taiga UI"

    override fun getSortWeight(): Int = 1_000

    override fun isDumbAware(): Boolean = true

    override fun showInFindResults(): Boolean = false

    override fun isShownInSeparateTab(): Boolean = true

    override fun fetchWeightedElements(
        pattern: String,
        progressIndicator: ProgressIndicator,
        consumer: Processor<in FoundItemDescriptor<TaigaApiEntry>>,
    ) {
        if (pattern.isBlank()) {
            return
        }

        val snapshot = ProgressIndicatorUtils.awaitWithCheckCanceled(catalog, progressIndicator) ?: return

        for (match in snapshot.search(pattern, progressIndicator::checkCanceled)) {
            progressIndicator.checkCanceled()

            if (!consumer.process(FoundItemDescriptor(match.entry, match.weight))) {
                break
            }
        }
    }

    override fun getElementsRenderer(): ListCellRenderer<in TaigaApiEntry> =
        object : ColoredListCellRenderer<TaigaApiEntry>() {
            override fun customizeCellRenderer(
                list: JList<out TaigaApiEntry>,
                value: TaigaApiEntry,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean,
            ) {
                append(value.entity.title)
                append("  ${value.metadata}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                value.summary?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
            }
        }

    override fun processSelectedItem(
        selected: TaigaApiEntry,
        modifiers: Int,
        searchText: String,
    ): Boolean {
        selected.openDocumentation()

        return true
    }

    override fun dispose() = scope.cancel()
}
