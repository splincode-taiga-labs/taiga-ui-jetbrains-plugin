package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE

class TaigaUiPackagePrecedenceCoverageTest {
    @Test
    fun `ranks every known package layer`() {
        assertEquals(0, TaigaUiPackagePrecedence.rank("@taiga-ui/design-tokens"))
        assertEquals(1, TaigaUiPackagePrecedence.rank("@taiga-ui/styles"))
        assertEquals(2, TaigaUiPackagePrecedence.rank("@taiga-ui/core"))
        assertEquals(3, TaigaUiPackagePrecedence.rank("@taiga-ui/proprietary"))
        assertEquals(4, TaigaUiPackagePrecedence.rank(PROJECT_STYLES_PACKAGE))
        assertNull(TaigaUiPackagePrecedence.rank("@custom/package"))
        assertNull(TaigaUiPackagePrecedence.rank(null))
    }
}
