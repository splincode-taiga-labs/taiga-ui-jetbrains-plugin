package org.taigaui.designtokens.documentation

import java.awt.BorderLayout
import javax.swing.JPanel

class TaigaDocumentationExampleStateTest : TaigaDocumentationPopupTestCase() {
    fun testLoadingTabShowsProgressAndEnablesFinishedExample() {
        val tabs = TaigaDocumentationTabs()
        tabs.addTab("Parameters", JPanel())
        var sizeChanges = 0
        val state = TaigaDocumentationExampleState(loading = true) { sizeChanges++ }

        state.addTab(tabs, "<button>Original</button>")
        assertTrue(state.isLoading)
        assertEquals(2, tabs.tabCount)
        assertFalse(tabs.isEnabledAt(1))

        state.showProgress()
        assertEquals(1, sizeChanges)
        assertEquals(
            "Examples: formatting in progress",
            tabs.getTabComponentAt(1).accessibleContext.accessibleName,
        )
        state.showProgress()
        assertEquals(1, sizeChanges)

        state.finish("<button>Formatted</button>")
        assertFalse(state.isLoading)
        assertTrue(tabs.isEnabledAt(1))
        assertNull(tabs.getTabComponentAt(1))
        assertEquals(2, sizeChanges)

        state.finish(null)
        assertEquals(2, sizeChanges)
    }

    fun testEmptyResultRemovesTheOnlyTabAndHidesContainer() {
        val tabs = TaigaDocumentationTabs()
        var sizeChanges = 0
        val state = TaigaDocumentationExampleState(loading = true) { sizeChanges++ }

        state.addTab(tabs, "<button>Original</button>")
        state.finish("  ")

        assertEquals(0, tabs.tabCount)
        assertFalse(tabs.isVisible)
        assertFalse(state.isLoading)
        assertEquals(1, sizeChanges)
    }

    fun testStandaloneProgressBecomesRenderedExample() {
        var sizeChanges = 0
        val state = TaigaDocumentationExampleState(loading = true) { sizeChanges++ }
        val container = state.standalone("<span>Original</span>")

        assertFalse(container.isOpaque)
        assertEquals(1, container.componentCount)

        state.showProgress()
        assertEquals(1, container.componentCount)
        assertEquals(2, (container.getComponent(0) as JPanel).componentCount)
        assertEquals(1, sizeChanges)

        state.finish("<span>Formatted</span>")
        assertTrue(container.isVisible)
        assertEquals(1, container.componentCount)
        assertNotNull((container.layout as BorderLayout).getLayoutComponent(BorderLayout.CENTER))
        assertEquals(2, sizeChanges)
    }

    fun testStandaloneEmptyResultHidesExample() {
        val state = TaigaDocumentationExampleState(loading = true) {}
        val container = state.standalone("<span>Original</span>")

        state.finish(null)

        assertFalse(container.isVisible)
        assertEquals(0, container.componentCount)
    }

    fun testAlreadyFormattedExamplesDoNotShowProgressOrFinishAgain() {
        var sizeChanges = 0
        val state = TaigaDocumentationExampleState(loading = false) { sizeChanges++ }
        val tabs = TaigaDocumentationTabs()

        state.addTab(tabs, "<button tuiButton>Save</button>")
        assertEquals(1, tabs.tabCount)
        assertTrue(tabs.isEnabledAt(0))
        assertFalse(state.isLoading)

        state.showProgress()
        state.finish(null)

        assertEquals(1, tabs.tabCount)
        assertNull(tabs.getTabComponentAt(0))
        assertEquals(0, sizeChanges)
    }
}
