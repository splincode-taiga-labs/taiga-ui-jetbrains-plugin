package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class TaigaDocsCacheCoverageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val source = requireNotNull(TaigaDocsSources.forMajor(5))

    @Test
    fun `cache handles missing blank write read overwrite and invalidation`() {
        val root = temporaryFolder.newFolder("docs-cache").toPath()
        val cache = TaigaDocsCache(root)

        assertNull(cache.read(source))
        assertFalse(cache.write(source, "   "))

        assertTrue(cache.write(source, "first"))
        assertEquals("first", cache.read(source))

        assertTrue(cache.write(source, "second"))
        assertEquals("second", cache.read(source))

        assertTrue(cache.invalidate(source))
        assertNull(cache.read(source))
        assertFalse(cache.invalidate(source))
    }

    @Test
    fun `blank cached file is treated as absent`() {
        val root = temporaryFolder.newFolder("blank-cache").toPath()
        val cacheFile = root.resolve("${source.cacheKey}-llms-full.txt")

        Files.writeString(cacheFile, "   ")

        assertNull(TaigaDocsCache(root).read(source))
    }

    @Test
    fun `falls back to replace move when atomic move is unsupported`() {
        val root = temporaryFolder.newFolder("non-atomic-cache").toPath()
        var attempts = 0
        val cache =
            TaigaDocsCache(root) { sourcePath, targetPath, atomic ->
                attempts++

                if (atomic) {
                    throw AtomicMoveNotSupportedException(
                        sourcePath.toString(),
                        targetPath.toString(),
                        "not supported by test file system",
                    )
                }

                Files.move(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }

        assertTrue(cache.write(source, "fallback"))
        assertEquals(2, attempts)
        assertEquals("fallback", cache.read(source))
    }

    @Test
    fun `default move strategy supports a non atomic replacement`() {
        val root = temporaryFolder.newFolder("default-non-atomic").toPath()
        val temporary = Files.writeString(root.resolve("pending.tmp"), "payload")
        val target = root.resolve("cached.txt")

        assertEquals(target, moveTaigaDocsCacheFile(temporary, target, atomic = false))
        assertEquals("payload", Files.readString(target))
    }

    @Test
    fun `repository exposes cached fresh refresh and invalidate paths`() {
        val root = temporaryFolder.newFolder("repository-cache").toPath()
        val cache = TaigaDocsCache(root)
        var remote = docs("Remote")
        val repository =
            TaigaDocsRepository(
                fetcher = TaigaDocsFetcher { remote },
                cache = cache,
            )

        assertNull(repository.loadCached(source))

        val fresh = requireNotNull(repository.loadFresh(source))

        assertEquals(TaigaDocsLoadOrigin.REMOTE, fresh.origin)
        assertEquals(
            "Remote",
            fresh.index.entities
                .single()
                .description,
        )

        remote = docs("Updated")
        val refreshed = requireNotNull(repository.refresh(source))

        assertEquals(TaigaDocsLoadOrigin.REMOTE, refreshed.origin)
        assertEquals(
            "Updated",
            refreshed.index.entities
                .single()
                .description,
        )
        assertEquals(
            TaigaDocsLoadOrigin.DISK_CACHE,
            requireNotNull(repository.loadCached(source)).origin,
        )

        assertTrue(repository.invalidate(source))
        assertNull(repository.loadCached(source))
    }

    @Test
    fun `fresh load rejects blank and malformed remote content without replacing cache`() {
        val root = temporaryFolder.newFolder("invalid-fresh").toPath()
        val cache = TaigaDocsCache(root)

        assertNull(
            TaigaDocsRepository(
                fetcher = TaigaDocsFetcher { " " },
                cache = cache,
            ).loadFresh(source),
        )
        assertNull(
            TaigaDocsRepository(
                fetcher = TaigaDocsFetcher { "not documentation" },
                cache = cache,
            ).loadFresh(source),
        )
        assertNull(cache.read(source))
    }

    private fun docs(description: String): String =
        listOf(
            "# components/Button",
            "- **Package**: `CORE`",
            description,
        ).joinToString("\n")
}
