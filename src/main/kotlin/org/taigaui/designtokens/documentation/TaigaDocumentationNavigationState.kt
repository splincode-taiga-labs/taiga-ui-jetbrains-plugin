package org.taigaui.designtokens.documentation

import java.awt.Point

/** Tracks the active card and immutable navigation history independently of popup lifecycle. */
internal class TaigaDocumentationNavigationState {
    var currentView: TaigaDocumentationView? = null
    private val history = ArrayDeque<TaigaDocumentationView>()

    val canGoBack: Boolean
        get() = history.isNotEmpty()

    fun capture(scrollPosition: Point?): TaigaDocumentationView? =
        currentView?.let { view -> view.copy(scrollPosition = scrollPosition ?: view.scrollPosition) }

    fun replaceHistory(views: List<TaigaDocumentationView>) {
        history.clear()
        history.addAll(views)
    }

    fun refreshedHistory(
        target: TaigaResolvedDocumentation,
        snapshot: TaigaDocsSnapshot?,
    ): List<TaigaDocumentationView> = history.mapNotNull { it.refreshed(target, snapshot) }

    fun visit(
        view: TaigaDocumentationView,
        scrollPosition: Point?,
    ): TaigaDocumentationView {
        capture(scrollPosition)?.let(history::addLast)
        return view.copy(expanded = true)
    }

    fun back(): TaigaDocumentationView? = history.removeLastOrNull()

    fun setQuery(query: String) {
        currentView = currentView?.copy(query = query)
    }

    fun setSelection(selected: String) {
        currentView = currentView?.copy(selectedMember = selected)
    }

    fun clear() {
        history.clear()
        currentView = null
    }
}
