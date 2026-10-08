package org.taigaui.designtokens.icons

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test
import java.awt.image.BufferedImage
import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import javax.swing.ImageIcon

class IconPreviewCacheTest {
    @Test
    fun `requires positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) {
            IconPreviewCache(0)
        }
    }

    @Test
    fun `stores entries removes stale fingerprint and keeps lru order`() {
        val cache = IconPreviewCache(2)
        val icon1 = ImageIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
        val icon2 = ImageIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
        val icon3 = ImageIcon(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB))
        val uri1 = URI("file:///one.svg")
        val uri2 = URI("file:///two.svg")
        val key1 = key(uri1, 1)
        val staleKey1 = key(uri1, 2)
        val key2 = key(uri2, 1)

        assertNull(cache.get(key1))

        cache.put(key1, icon1)
        assertSame(icon1, cache.get(key1))

        cache.put(staleKey1, icon2)
        assertEquals(1, cache.size)
        assertNull(cache.get(key1))
        assertSame(icon2, cache.get(staleKey1))

        cache.put(key2, icon3)
        assertEquals(2, cache.size)

        cache.get(staleKey1)
        val key3 = key(URI("file:///three.svg"), 1)
        cache.put(key3, icon1)

        assertEquals(2, cache.size)
        assertSame(icon2, cache.get(staleKey1))
        assertNull(cache.get(key2))
        assertSame(icon1, cache.get(key3))
    }

    @Test
    fun `creates remote key without local fingerprint`() {
        val source = IconSvgSource.Remote(URI("https://example.com/icon.svg"))
        val key = requireNotNull(source.previewRenderKey(64))

        assertEquals(source.uri, key.uri)
        assertEquals(64, key.logicalSize)
        assertNull(key.localFingerprint)
    }

    @Test
    fun `creates local fingerprint and changes it after file modification`() {
        val file = Files.createTempFile("icon-preview-cache", ".svg")

        try {
            Files.writeString(file, "<svg/>")
            Files.setLastModifiedTime(file, FileTime.fromMillis(1_000L))
            val source = IconSvgSource.Local(file)
            val first = requireNotNull(source.previewRenderKey(32))

            Files.writeString(file, "<svg>changed</svg>")
            Files.setLastModifiedTime(file, FileTime.fromMillis(2_000L))
            val second = requireNotNull(source.previewRenderKey(32))

            assertEquals(source.uri, first.uri)
            assertNotNull(first.localFingerprint)
            assertNotEquals(first.localFingerprint, second.localFingerprint)
        } finally {
            Files.deleteIfExists(file)
        }
    }

    @Test
    fun `returns null local key when file metadata cannot be read`() {
        val missing = Files.createTempDirectory("icon-preview-missing").resolve("missing.svg")

        try {
            assertNull(IconSvgSource.Local(missing).previewRenderKey(64))
        } finally {
            missing.parent.toFile().deleteRecursively()
        }
    }

    private fun key(
        uri: URI,
        fingerprintSize: Long,
    ): IconPreviewRenderKey =
        IconPreviewRenderKey(
            uri = uri,
            logicalSize = 64,
            localFingerprint =
                LocalIconPreviewFingerprint(
                    fileKey = null,
                    lastModifiedTime = FileTime.fromMillis(fingerprintSize),
                    size = fingerprintSize,
                ),
        )
}
