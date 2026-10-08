package org.taigaui.designtokens.index

import org.junit.Assert.assertThrows
import org.junit.Test

class DesignTokenVariantInvariantTest {
    @Test
    fun `logical variant requires at least one physical origin`() {
        assertThrows(IllegalArgumentException::class.java) {
            DesignTokenVariant(
                name = "--tui-test",
                context = DesignTokenContext.DEFAULT,
                rawValue = "#fff",
                origins = emptyList(),
            )
        }
    }
}
