package org.taigaui.designtokens.units

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemValueAtOffsetFinderTest {
    @Test
    fun `converts rem values to pixels using 16px root size`() {
        assertPresentation("1rem", "1rem = 16px")
        assertPresentation("1.5rem", "1.5rem = 24px")
        assertPresentation(".125rem", "0.125rem = 2px")
        assertPresentation("-0.5rem", "-0.5rem = -8px")
        assertPresentation("1e2rem", "100rem = 1600px")
    }

    @Test
    fun `returns null for empty content and invalid offsets`() {
        assertNull(RemValueAtOffsetFinder.find("", 0))
        assertNull(RemValueAtOffsetFinder.find("1rem", -1))
        assertNull(RemValueAtOffsetFinder.find("1rem", 5))
    }

    @Test
    fun `accepts caret immediately after a rem value`() {
        val text = "width: 1rem"
        val end = text.length

        assertEquals(
            "1rem = 16px",
            RemValueAtOffsetFinder.find(text, end)?.presentation(),
        )
    }

    @Test
    fun `finds rem value inside a stylesheet declaration`() {
        val text = ".button { padding: 0.75rem 1rem; }"
        val start = text.indexOf("0.75rem")
        val reference = RemValueAtOffsetFinder.find(text, start + 3)

        assertEquals("0.75rem = 12px", reference?.presentation())
        assertEquals(start, reference?.startOffset)
        assertEquals(start + "0.75rem".length, reference?.endOffset)
    }

    @Test
    fun `finds the rem value under the requested offset`() {
        val text = ".button { margin: 1rem 2.5rem; }"
        val second = text.indexOf("2.5rem")

        assertEquals(
            "2.5rem = 40px",
            RemValueAtOffsetFinder.find(text, second + 2)?.presentation(),
        )
    }

    @Test
    fun `ignores rem text inside strings and comments`() {
        val text =
            """
            .button::before {
                content: "1rem";
                /* padding: 2rem; */
                // margin: 2.5rem;
                margin: 3rem;
            }
            """.trimIndent()

        assertNull(RemValueAtOffsetFinder.find(text, text.indexOf("1rem") + 1))
        assertNull(RemValueAtOffsetFinder.find(text, text.indexOf("2rem") + 1))
        assertNull(RemValueAtOffsetFinder.find(text, text.indexOf("2.5rem") + 1))
        assertEquals(
            "3rem = 48px",
            RemValueAtOffsetFinder.find(text, text.indexOf("3rem") + 1)?.presentation(),
        )
    }

    @Test
    fun `does not match rem as part of an identifier`() {
        val text = ".one1rem { --size1rem: 1remValue; }"

        listOf("one1rem", "size1rem", "1remValue").forEach { value ->
            val offset = text.indexOf(value) + value.length / 2

            assertNull(RemValueAtOffsetFinder.find(text, offset))
        }
    }

    private fun assertPresentation(
        value: String,
        expected: String,
    ) {
        val text = ".test { width: $value; }"
        val start = text.indexOf(value)

        assertEquals(
            expected,
            RemValueAtOffsetFinder.find(text, start + value.length / 2)?.presentation(),
        )
    }
}
