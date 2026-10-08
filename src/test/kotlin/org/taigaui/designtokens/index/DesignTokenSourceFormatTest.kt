package org.taigaui.designtokens.index

import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

class DesignTokenSourceFormatTest {
    @Test
    fun `detects CSS format`() {
        assertEquals(
            DesignTokenSourceFormat.CSS,
            DesignTokenSourceFormat.from(Path.of("palette/light.css")),
        )
    }

    @Test
    fun `detects Less format`() {
        assertEquals(
            DesignTokenSourceFormat.LESS,
            DesignTokenSourceFormat.from(Path.of("palette/less/light.less")),
        )
    }

    @Test
    fun `detects SCSS format`() {
        assertEquals(
            DesignTokenSourceFormat.SCSS,
            DesignTokenSourceFormat.from(Path.of("palette/scss/light.scss")),
        )
    }

    @Test
    fun `format detection is case insensitive`() {
        assertEquals(
            DesignTokenSourceFormat.CSS,
            DesignTokenSourceFormat.from(Path.of("palette/LIGHT.CSS")),
        )
    }

    @Test
    fun `returns unknown for unsupported extension`() {
        assertEquals(
            DesignTokenSourceFormat.UNKNOWN,
            DesignTokenSourceFormat.from(Path.of("palette/light.txt")),
        )
    }

    @Test
    fun `returns unknown for filesystem root without file name`() {
        assertEquals(
            DesignTokenSourceFormat.UNKNOWN,
            DesignTokenSourceFormat.from(Path.of("/")),
        )
    }

    @Test
    fun `returns unknown for file without extension`() {
        assertEquals(
            DesignTokenSourceFormat.UNKNOWN,
            DesignTokenSourceFormat.from(Path.of("palette/light")),
        )
    }
}
