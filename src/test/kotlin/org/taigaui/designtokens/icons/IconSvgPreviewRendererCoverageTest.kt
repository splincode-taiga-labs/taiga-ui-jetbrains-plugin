package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class IconSvgPreviewRendererCoverageTest {
    private val supportClass =
        Class.forName("org.taigaui.designtokens.icons.IconSvgPreviewRendererKt")

    @Test
    fun `svg size reader supports explicit numeric px and viewBox sizes`() {
        assertNotNull(
            invoke(
                "readSvgSize",
                """<svg width="24px" height="12.5"></svg>""",
            ),
        )
        assertNotNull(
            invoke(
                "readSvgSize",
                """<svg viewBox="0, 0, 32, 16"></svg>""",
            ),
        )
        assertNotNull(
            invoke(
                "readSvgSize",
                """<SVG WIDTH=".5" HEIGHT="1e2"></SVG>""",
            ),
        )
    }

    @Test
    fun `svg size reader rejects missing malformed and non positive dimensions`() {
        listOf(
            "<div></div>",
            """<svg width="24"></svg>""",
            """<svg width="wat" height="10"></svg>""",
            """<svg width="0" height="10"></svg>""",
            """<svg width="-1" height="10"></svg>""",
            """<svg viewBox="0 0 10"></svg>""",
            """<svg viewBox="0 0 x 10"></svg>""",
            """<svg viewBox="0 0 0 10"></svg>""",
        ).forEach { svg ->
            assertNull(invoke("readSvgSize", svg))
        }
    }

    @Test
    fun `resize replaces existing attributes and inserts absent ones`() {
        val replaced =
            invoke(
                "resizeSvgRoot",
                """<svg width="24" height='12'><path/></svg>""",
                64,
                32,
            ) as String

        assertTrue(replaced.contains("""width="64""""))
        assertTrue(replaced.contains("""height="32""""))

        val inserted =
            invoke(
                "resizeSvgRoot",
                """<svg viewBox="0 0 24 24"><path/></svg>""",
                48,
                48,
            ) as String

        assertTrue(inserted.contains("""width="48""""))
        assertTrue(inserted.contains("""height="48""""))

        val selfClosing =
            invoke(
                "resizeSvgRoot",
                """<svg viewBox="0 0 24 24"/>""",
                16,
                8,
            ) as String

        assertTrue(selfClosing.contains("""width="16""""))
        assertTrue(selfClosing.contains("""height="8""""))

        assertEquals("plain", invoke("resizeSvgRoot", "plain", 10, 10))
    }

    @Test
    fun `read and preview file helpers round trip svg text`() {
        val source = Files.createTempFile("icon-render-source", ".svg")
        var preview: Path? = null

        try {
            val svg = """<svg width="10" height="10"><path/></svg>"""

            Files.writeString(source, svg)

            assertEquals(svg, invoke("readSvg", source.toUri()))

            preview = invoke("writePreviewSvg", svg) as Path

            assertTrue(Files.isRegularFile(preview))
            assertEquals(svg, Files.readString(preview))
        } finally {
            Files.deleteIfExists(source)
            preview?.let(Files::deleteIfExists)
        }
    }

    @Test
    fun `renderer fails closed for unreadable and sizeless svg sources`() {
        val missing = Path.of("build/definitely-missing-icon.svg")
        val sizeless = Files.createTempFile("sizeless-icon", ".svg")

        try {
            Files.writeString(sizeless, "<svg><path/></svg>")

            assertNull(IconSvgPreviewRenderer().render(IconSvgSource.Local(missing), 64))
            assertNull(IconSvgPreviewRenderer().render(IconSvgSource.Local(sizeless), 64))
        } finally {
            Files.deleteIfExists(sizeless)
        }
    }

    private fun invoke(
        name: String,
        vararg arguments: Any,
    ): Any? {
        val method =
            supportClass.declaredMethods
                .single { candidate ->
                    candidate.name == name &&
                        candidate.parameterCount == arguments.size
                }.apply { isAccessible = true }

        return method.invoke(null, *arguments)
    }
}
