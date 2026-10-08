package org.taigaui.designtokens.resolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DesignTokenColorDetectorCoverageTest {
    @Test
    fun `normalizes short and long hex colors`() {
        assertEquals("#aabbcc", DesignTokenColorDetector.detect("#AbC")?.canonicalValue)
        assertEquals("#aabbccdd", DesignTokenColorDetector.detect("#AbCd")?.canonicalValue)
        assertEquals("#aabbcc", DesignTokenColorDetector.detect("#AaBbCc")?.canonicalValue)
        assertEquals("#aabbccdd", DesignTokenColorDetector.detect("#AaBbCcDd")?.canonicalValue)
    }

    @Test
    fun `normalizes named and functional colors`() {
        assertEquals(
            "rebeccapurple",
            DesignTokenColorDetector
                .detect(" RebeccaPurple ")
                ?.canonicalValue,
        )
        assertEquals(
            "transparent",
            DesignTokenColorDetector
                .detect("TRANSPARENT")
                ?.canonicalValue,
        )
        assertEquals(
            "rgb(1,2,3)",
            DesignTokenColorDetector
                .detect(" RGB( 1 , 2 , 3 ) ")
                ?.canonicalValue,
        )
        assertEquals(
            "color(display-p3 1 0 0)",
            DesignTokenColorDetector
                .detect("COLOR( display-p3 1 0 0 )")
                ?.canonicalValue,
        )
    }

    @Test
    fun `rejects malformed functions and non-colors`() {
        assertNull(DesignTokenColorDetector.detect(""))
        assertNull(DesignTokenColorDetector.detect("#12"))
        assertNull(DesignTokenColorDetector.detect("not-a-color"))
        assertNull(DesignTokenColorDetector.detect("rgb"))
        assertNull(DesignTokenColorDetector.detect("foo(1 2 3)"))
        assertNull(DesignTokenColorDetector.detect("rgb(1 2 3"))
        assertNull(DesignTokenColorDetector.detect("rgb(1 2 3))"))
        assertNull(DesignTokenColorDetector.detect("rgb((1 2 3)) trailing"))
    }
}
