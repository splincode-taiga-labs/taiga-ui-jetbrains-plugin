package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class DesignTokenOriginCoverageTest {
    @Test
    fun `detects source format from extension`() {
        assertEquals(DesignTokenSourceFormat.CSS, DesignTokenSourceFormat.from(Path.of("token.css")))
        assertEquals(DesignTokenSourceFormat.CSS, DesignTokenSourceFormat.from(Path.of("token.CSS")))
        assertEquals(DesignTokenSourceFormat.LESS, DesignTokenSourceFormat.from(Path.of("token.less")))
        assertEquals(DesignTokenSourceFormat.SCSS, DesignTokenSourceFormat.from(Path.of("token.scss")))
        assertEquals(DesignTokenSourceFormat.UNKNOWN, DesignTokenSourceFormat.from(Path.of("token")))
        assertEquals(DesignTokenSourceFormat.UNKNOWN, DesignTokenSourceFormat.from(Path.of("token.ts")))
    }

    @Test
    fun `origin keeps optional metadata and supports data class operations`() {
        val origin =
            DesignTokenOrigin(
                sourceFile = Path.of("token.css"),
                line = 7,
                format = DesignTokenSourceFormat.CSS,
                selectorChain = listOf(":root"),
                packageName = "@taiga-ui/core",
                packageVersion = "5.0.0",
                sharedAcrossPlatforms = true,
                cascadeOrder = 3,
                localOverride = true,
            )
        val copy = origin.copy(line = 8)

        assertEquals(7, origin.line)
        assertEquals(8, copy.line)
        assertEquals(origin.packageName, copy.packageName)
        assertEquals(origin, origin.copy())
        assertEquals(origin.hashCode(), origin.copy().hashCode())
        assertEquals(origin.toString(), origin.copy().toString())
    }
}
