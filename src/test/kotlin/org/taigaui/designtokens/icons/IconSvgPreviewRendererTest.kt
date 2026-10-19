package org.taigaui.designtokens.icons

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.awt.Rectangle
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime

class IconSvgPreviewRendererTest : BasePlatformTestCase() {
    fun testRendersSvgAtRequestedLogicalSize() {
        withSvg(
            width = 16,
            height = 16,
            body = """<rect width="16" height="16" fill="#000"/>""",
        ) { svg ->
            val icon = render(IconSvgSource.Local(svg))

            assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconWidth)
            assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconHeight)
            assertEquals(
                Rectangle(0, 0, ICON_PREVIEW_LOGICAL_SIZE, ICON_PREVIEW_LOGICAL_SIZE),
                paint(icon).paintedBounds(),
            )
        }
    }

    fun testScalesLargestDimensionAndPreservesSvgAspectRatio() {
        withSvg(
            width = 16,
            height = 8,
            body = """<rect width="16" height="8" fill="#000"/>""",
        ) { svg ->
            val icon = render(IconSvgSource.Local(svg))

            assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconWidth)
            assertEquals(ICON_PREVIEW_LOGICAL_SIZE / 2, icon.iconHeight)
        }
    }

    fun testRendersGlyphAtTargetSizeInsteadOfLeavingOriginalGlyphSize() {
        withSvg(
            width = 16,
            height = 16,
            body = """<path d="M4 4 H12 V12 H4 Z" fill="#000"/>""",
        ) { svg ->
            val paintedBounds = paint(render(IconSvgSource.Local(svg))).paintedBounds()

            assertTrue(
                "Expected glyph itself to grow with the preview, got $paintedBounds",
                paintedBounds.width >= 30 && paintedBounds.height >= 30,
            )
        }
    }

    fun testVectorPreviewDoesNotDependOnSourceRasterSize() {
        val body =
            """
            <circle cx="8" cy="8" r="5.25" fill="#000"/>
            <path d="M2 13 L13 2 L14 3 L3 14 Z" fill="#fff"/>
            """.trimIndent()
        val smallSource =
            renderSvgToImage(
                width = 16,
                height = 16,
                viewBoxWidth = 16,
                viewBoxHeight = 16,
                body = body,
            )
        val largeSource =
            renderSvgToImage(
                width = 64,
                height = 64,
                viewBoxWidth = 16,
                viewBoxHeight = 16,
                body = body,
            )

        assertSamePixels(
            expected = smallSource,
            actual = largeSource,
        )
    }

    fun testRemoteSourceUsesTheSameTargetSizeRenderingContract() {
        withSvg(
            width = 16,
            height = 16,
            body = """<circle cx="8" cy="8" r="5" fill="#000"/>""",
        ) { svg ->
            val icon = render(IconSvgSource.Remote(svg.toUri()))

            assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconWidth)
            assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconHeight)
            assertTrue(paint(icon).paintedBounds().width >= 38)
        }
    }

    fun testProprietaryRemoteSvgUsesMirrorsWhenPrimaryFails() {
        val requested = mutableListOf<URI>()
        val remotePath = "/fancy/small/check.svg"
        val renderer =
            IconSvgPreviewRenderer(
                svgReader = { uri ->
                    requested += uri

                    when (uri.host) {
                        "cdn.tbank.ru" -> error("Network unavailable")
                        "cdn.tbank-online.com" -> "<html>Invalid SVG</html>"
                        else ->
                            """<svg xmlns="http://www.w3.org/2000/svg" width="16" height="16">
                                <rect width="16" height="16"/>
                            </svg>"""
                    }
                },
            )

        val icon = render(IconSvgSource.Remote(URI.create("$ICONS_BASE_URL$remotePath")), renderer)

        assertEquals(ICON_PREVIEW_LOGICAL_SIZE, icon.iconWidth)
        assertEquals(
            ICONS_BASE_URLS.map { URI.create("$it$remotePath") },
            requested,
        )
    }

    fun testUnrelatedRemoteSvgDoesNotUseProprietaryMirrors() {
        val requested = mutableListOf<URI>()
        val uri = URI.create("https://example.test/clock.svg")
        val renderer =
            IconSvgPreviewRenderer(
                svgReader = { candidate ->
                    requested += candidate
                    """<svg width="16" height="16"><rect width="16" height="16"/></svg>"""
                },
            )

        render(IconSvgSource.Remote(uri), renderer)

        assertEquals(listOf(uri), requested)
    }

    fun testLocalPreviewIsRefreshedWhenTheSameFileChanges() {
        withSvg(
            width = 16,
            height = 16,
            body = """<rect width="16" height="16" fill="#000"/>""",
        ) { svg ->
            val renderer = IconSvgPreviewRenderer()
            val initial = paint(render(IconSvgSource.Local(svg), renderer)).paintedBounds()
            val previousTimestamp = Files.getLastModifiedTime(svg)

            Files.writeString(
                svg,
                """
                <svg xmlns="http://www.w3.org/2000/svg" width="16" height="16" viewBox="0 0 16 16">
                    <circle cx="8" cy="8" r="4" fill="#000"/>
                </svg>
                """.trimIndent(),
            )
            Files.setLastModifiedTime(
                svg,
                FileTime.fromMillis(previousTimestamp.toMillis() + 1_000L),
            )

            val updated = paint(render(IconSvgSource.Local(svg), renderer)).paintedBounds()

            assertFalse(
                "Expected a changed SVG at the same path to invalidate its cached preview",
                initial == updated,
            )
            assertEquals(1, renderer.cachedPreviewCount)
        }
    }

    fun testRenderedPreviewCacheIsBounded() {
        val renderer = IconSvgPreviewRenderer(maxCacheEntries = 2)

        withSvg(16, 16, """<rect width="16" height="16" fill="#000"/>""") { first ->
            withSvg(16, 16, """<circle cx="8" cy="8" r="5" fill="#000"/>""") { second ->
                withSvg(16, 16, """<path d="M2 2 H14 V14 H2 Z" fill="#000"/>""") { third ->
                    render(IconSvgSource.Local(first), renderer)
                    render(IconSvgSource.Local(second), renderer)
                    render(IconSvgSource.Local(third), renderer)

                    assertEquals(2, renderer.cachedPreviewCount)
                }
            }
        }
    }

    private fun render(
        source: IconSvgSource,
        renderer: IconSvgPreviewRenderer = IconSvgPreviewRenderer(),
    ) = requireNotNull(
        renderer.render(
            source = source,
            logicalSize = ICON_PREVIEW_LOGICAL_SIZE,
        ),
    )

    private fun renderSvgToImage(
        width: Int,
        height: Int,
        viewBoxWidth: Int,
        viewBoxHeight: Int,
        body: String,
    ): BufferedImage {
        lateinit var image: BufferedImage

        withSvg(
            width = width,
            height = height,
            viewBoxWidth = viewBoxWidth,
            viewBoxHeight = viewBoxHeight,
            body = body,
        ) { svg ->
            image = paint(render(IconSvgSource.Local(svg)))
        }

        return image
    }

    private fun paint(icon: javax.swing.Icon): BufferedImage {
        val image =
            BufferedImage(
                icon.iconWidth,
                icon.iconHeight,
                BufferedImage.TYPE_INT_ARGB,
            )
        val graphics = image.createGraphics()

        try {
            icon.paintIcon(null, graphics, 0, 0)
        } finally {
            graphics.dispose()
        }

        return image
    }

    private fun assertSamePixels(
        expected: BufferedImage,
        actual: BufferedImage,
    ) {
        assertEquals(expected.width, actual.width)
        assertEquals(expected.height, actual.height)

        for (y in 0 until expected.height) {
            for (x in 0 until expected.width) {
                assertEquals(
                    "Pixel mismatch at ($x, $y)",
                    expected.getRGB(x, y),
                    actual.getRGB(x, y),
                )
            }
        }
    }

    private fun BufferedImage.paintedBounds(): Rectangle {
        var minX = width
        var minY = height
        var maxX = -1
        var maxY = -1

        for (y in 0 until height) {
            for (x in 0 until width) {
                if (getRGB(x, y).ushr(24) != 0) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                }
            }
        }

        return if (maxX < minX || maxY < minY) {
            Rectangle()
        } else {
            Rectangle(
                minX,
                minY,
                maxX - minX + 1,
                maxY - minY + 1,
            )
        }
    }

    private fun withSvg(
        width: Int,
        height: Int,
        body: String,
        viewBoxWidth: Int = width,
        viewBoxHeight: Int = height,
        block: (Path) -> Unit,
    ) {
        val svg =
            Files.createTempFile("taiga-ui-icon-preview", ".svg").also { path ->
                Files.writeString(
                    path,
                    """
                    <svg xmlns="http://www.w3.org/2000/svg" width="$width" height="$height" viewBox="0 0 $viewBoxWidth $viewBoxHeight">
                        $body
                    </svg>
                    """.trimIndent(),
                )
            }

        try {
            block(svg)
        } finally {
            Files.deleteIfExists(svg)
        }
    }
}
