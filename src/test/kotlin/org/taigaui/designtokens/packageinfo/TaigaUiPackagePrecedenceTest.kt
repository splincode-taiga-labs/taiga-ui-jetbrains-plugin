package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE

class TaigaUiPackagePrecedenceTest {
    @Test
    fun `ranks known Taiga UI packages and project styles`() {
        assertEquals(0, TaigaUiPackagePrecedence.rank("@taiga-ui/design-tokens"))
        assertEquals(1, TaigaUiPackagePrecedence.rank("@taiga-ui/styles"))
        assertEquals(2, TaigaUiPackagePrecedence.rank("@taiga-ui/core"))
        assertEquals(3, TaigaUiPackagePrecedence.rank("@taiga-ui/proprietary"))
        assertEquals(4, TaigaUiPackagePrecedence.rank(PROJECT_STYLES_PACKAGE))
    }

    @Test
    fun `returns null for unknown or absent package`() {
        assertNull(TaigaUiPackagePrecedence.rank("@taiga-ui/kit"))
        assertNull(TaigaUiPackagePrecedence.rank(null))
    }
}
