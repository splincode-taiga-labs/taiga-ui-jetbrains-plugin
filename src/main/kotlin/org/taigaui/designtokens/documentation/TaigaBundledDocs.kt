package org.taigaui.designtokens.documentation

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.HexFormat
import java.util.Properties
import java.util.zip.GZIPInputStream

internal object TaigaBundledDocs {
    private const val ROOT = "/taiga-docs"
    private const val MAX_BYTES = 4 * 1024 * 1024
    private val manifest =
        read {
            resource("manifest.properties")?.use { stream ->
                Properties().apply { load(stream) }.takeIf { it.getProperty("schema") == "1" }
            }
        }

    fun hasSnapshot(source: TaigaDocsSource): Boolean =
        manifest?.containsKey("${source.cacheKey}.sha256") == true &&
            javaClass.getResource("$ROOT/${source.cacheKey}.txt.gz") != null

    fun load(source: TaigaDocsSource): TaigaDocsLoadResult? =
        read {
            val metadata = manifest ?: return@read null
            val expectedHash = metadata.getProperty("${source.cacheKey}.sha256") ?: return@read null
            val content =
                resource("${source.cacheKey}.txt.gz")?.use { compressed ->
                    GZIPInputStream(compressed).use { it.readNBytes(MAX_BYTES + 1) }
                } ?: return@read null

            if (content.size > MAX_BYTES || sha256(content) != expectedHash) {
                return@read null
            }

            val retrieved = metadata.getProperty("retrieved") ?: return@read null
            val provenance =
                TaigaDocsProvenance(
                    TaigaDocsLoadOrigin.BUNDLED,
                    sourceRevision = metadata.getProperty("revision"),
                    bundledDate = LocalDate.parse(retrieved),
                )

            TaigaDocsParser()
                .parse(source, String(content, StandardCharsets.UTF_8), provenance)
                ?.let { TaigaDocsLoadResult(it, provenance.origin, refreshRecommended = true) }
        }

    private fun resource(name: String) = javaClass.getResourceAsStream("$ROOT/$name")

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    private fun <T> read(block: () -> T): T? =
        try {
            block()
        } catch (_: IOException) {
            null
        } catch (_: DateTimeParseException) {
            null
        }
}
