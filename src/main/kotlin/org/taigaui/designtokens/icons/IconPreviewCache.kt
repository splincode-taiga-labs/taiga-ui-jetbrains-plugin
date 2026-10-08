package org.taigaui.designtokens.icons

import java.net.URI
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime
import java.util.LinkedHashMap
import javax.swing.Icon

internal class IconPreviewCache(
    private val maxEntries: Int,
) {
    private val entries = LinkedHashMap<IconPreviewRenderKey, Icon>(16, 0.75F, true)

    init {
        require(maxEntries > 0) { "maxEntries must be greater than zero" }
    }

    val size: Int
        get() = synchronized(entries) { entries.size }

    fun get(key: IconPreviewRenderKey): Icon? =
        synchronized(entries) {
            entries[key]
        }

    fun put(
        key: IconPreviewRenderKey,
        icon: Icon,
    ) {
        synchronized(entries) {
            removeStaleEntryFor(key)
            entries[key] = icon
            trim()
        }
    }

    private fun removeStaleEntryFor(key: IconPreviewRenderKey) {
        val iterator = entries.keys.iterator()

        while (iterator.hasNext()) {
            val cachedKey = iterator.next()

            if (
                cachedKey.uri == key.uri &&
                cachedKey.logicalSize == key.logicalSize &&
                cachedKey != key
            ) {
                iterator.remove()
            }
        }
    }

    private fun trim() {
        while (entries.size > maxEntries) {
            val iterator = entries.entries.iterator()

            iterator.next()
            iterator.remove()
        }
    }
}

internal fun IconSvgSource.previewRenderKey(logicalSize: Int): IconPreviewRenderKey? =
    when (this) {
        is IconSvgSource.Local ->
            runCatching {
                val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)

                IconPreviewRenderKey(
                    uri = uri,
                    logicalSize = logicalSize,
                    localFingerprint =
                        LocalIconPreviewFingerprint(
                            fileKey = attributes.fileKey()?.toString(),
                            lastModifiedTime = attributes.lastModifiedTime(),
                            size = attributes.size(),
                        ),
                )
            }.getOrNull()

        is IconSvgSource.Remote ->
            IconPreviewRenderKey(
                uri = uri,
                logicalSize = logicalSize,
                localFingerprint = null,
            )
    }

internal data class IconPreviewRenderKey(
    val uri: URI,
    val logicalSize: Int,
    val localFingerprint: LocalIconPreviewFingerprint?,
)

internal data class LocalIconPreviewFingerprint(
    val fileKey: String?,
    val lastModifiedTime: FileTime,
    val size: Long,
)
