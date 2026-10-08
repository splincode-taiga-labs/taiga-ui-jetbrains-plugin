package org.taigaui.designtokens.packageinfo

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.Comparator
import java.util.zip.ZipFile

internal data class MaterializedYarnPnpPackage(
    val root: Path,
    val contentVersion: String,
    val invalidationRoots: Set<Path>,
)

internal class YarnPnpPackageMaterializer(
    private val cacheRoot: Path =
        Path.of(
            System.getProperty("java.io.tmpdir"),
            "taiga-ui-jetbrains-plugin",
            "yarn-pnp-packages",
            ProcessHandle.current().pid().toString(),
        ),
    private val maxExtractedFiles: Int = MAX_EXTRACTED_FILES,
    private val maxExtractedBytes: Long = MAX_EXTRACTED_BYTES,
) {
    fun materialize(
        manifestRoot: Path,
        packageLocation: String,
        identity: String,
    ): MaterializedYarnPnpPackage? {
        val resolvedLocation =
            resolveYarnVirtualPath(
                resolvePortablePackageLocation(manifestRoot, packageLocation),
            )
        val archiveLocation = splitArchiveLocation(resolvedLocation)

        return if (archiveLocation == null) {
            materializePhysical(resolvedLocation)
        } else {
            materializeArchive(
                archivePath = archiveLocation.archivePath,
                entryRoot = archiveLocation.entryRoot,
                identity = identity,
            )
        }
    }

    private fun materializePhysical(packageRoot: Path): MaterializedYarnPnpPackage? {
        val realRoot =
            packageRoot
                .takeIf(Files::isDirectory)
                ?.toRealPathOrSelf()
                ?: return null
        val packageManifest = realRoot.resolve(PACKAGE_JSON)
        val contentVersion =
            runCatching {
                Files.size(packageManifest).toString() +
                    ":" +
                    Files.getLastModifiedTime(packageManifest).toMillis()
            }.getOrElse {
                runCatching { Files.getLastModifiedTime(realRoot).toMillis().toString() }
                    .getOrDefault("unknown")
            }

        return MaterializedYarnPnpPackage(
            root = realRoot,
            contentVersion = contentVersion,
            invalidationRoots = setOf(realRoot),
        )
    }

    // Each failed archive check exits immediately so unsafe cache entries are never published.
    @Suppress("ReturnCount")
    private fun materializeArchive(
        archivePath: Path,
        entryRoot: String,
        identity: String,
    ): MaterializedYarnPnpPackage? {
        val realArchive =
            archivePath
                .takeIf(Files::isRegularFile)
                ?.toRealPathOrSelf()
                ?: return null
        val archiveVersion =
            runCatching {
                Files.size(realArchive).toString() +
                    ":" +
                    Files.getLastModifiedTime(realArchive).toMillis()
            }.getOrNull()
                ?: return null
        val target =
            cacheRoot
                .resolve(stablePnpHash(identity + "|" + archiveVersion))
                .toAbsolutePath()
                .normalize()

        synchronized(MATERIALIZATION_LOCK) {
            if (!Files.isRegularFile(target.resolve(COMPLETE_MARKER))) {
                target.deleteRecursively()
                Files.createDirectories(target)

                if (!extractPackage(realArchive, entryRoot, target)) {
                    target.deleteRecursively()
                    return null
                }

                Files.writeString(target.resolve(COMPLETE_MARKER), archiveVersion)
            }
        }

        return MaterializedYarnPnpPackage(
            root = target,
            contentVersion = archiveVersion,
            invalidationRoots = setOf(realArchive),
        )
    }

    // Zip extraction keeps safety limits and path checks inline so every write is guarded.
    @Suppress("CyclomaticComplexMethod", "LongMethod", "LoopWithTooManyJumpStatements")
    private fun extractPackage(
        archivePath: Path,
        entryRoot: String,
        targetRoot: Path,
    ): Boolean =
        runCatching {
            val normalizedPrefix =
                entryRoot
                    .replace('\\', '/')
                    .trim('/')
                    .let { prefix -> if (prefix.isEmpty()) "" else prefix + "/" }
            var extractedFiles = 0
            var extractedBytes = 0L

            ZipFile(archivePath.toFile()).use { zip ->
                val entries = zip.entries()

                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val entryName = entry.name.replace('\\', '/')

                    if (!entryName.startsWith(normalizedPrefix)) {
                        continue
                    }

                    val relativeName = entryName.removePrefix(normalizedPrefix).trimStart('/')

                    if (relativeName.isEmpty()) {
                        continue
                    }

                    val destination = targetRoot.resolve(relativeName).normalize()

                    if (!destination.startsWith(targetRoot)) {
                        return@runCatching false
                    }

                    if (entry.isDirectory) {
                        Files.createDirectories(destination)
                        continue
                    }

                    extractedFiles++

                    if (extractedFiles > maxExtractedFiles) {
                        return@runCatching false
                    }

                    Files.createDirectories(destination.parent)
                    zip
                        .getInputStream(entry)
                        .use { input ->
                            Files
                                .newOutputStream(
                                    destination,
                                    StandardOpenOption.CREATE,
                                    StandardOpenOption.TRUNCATE_EXISTING,
                                    StandardOpenOption.WRITE,
                                ).use { output ->
                                    val buffer = ByteArray(COPY_BUFFER_SIZE)

                                    while (true) {
                                        val count = input.read(buffer)

                                        if (count < 0) {
                                            break
                                        }

                                        extractedBytes += count

                                        if (extractedBytes > maxExtractedBytes) {
                                            return@runCatching false
                                        }

                                        output.write(buffer, 0, count)
                                    }
                                }
                        }
                }
            }

            Files.isRegularFile(targetRoot.resolve(PACKAGE_JSON))
        }.getOrDefault(false)

    private fun splitArchiveLocation(path: Path): ArchiveLocation? {
        val archiveIndex =
            (0 until path.nameCount)
                .firstOrNull { index ->
                    path.getName(index).toString().endsWith(ZIP_EXTENSION, ignoreCase = true)
                }
                ?: return null
        var archivePath = path.root ?: Path.of("")

        for (index in 0..archiveIndex) {
            archivePath = archivePath.resolve(path.getName(index).toString())
        }

        val entryRoot =
            ((archiveIndex + 1) until path.nameCount)
                .joinToString("/") { index -> path.getName(index).toString() }

        return ArchiveLocation(
            archivePath = archivePath.toAbsolutePath().normalize(),
            entryRoot = entryRoot,
        )
    }

    private data class ArchiveLocation(
        val archivePath: Path,
        val entryRoot: String,
    )

    private companion object {
        val MATERIALIZATION_LOCK = Any()
        const val PACKAGE_JSON = "package.json"
        const val COMPLETE_MARKER = ".complete"
        const val ZIP_EXTENSION = ".zip"
        const val COPY_BUFFER_SIZE = 8192
        const val MAX_EXTRACTED_FILES = 20_000
        const val MAX_EXTRACTED_BYTES = 256L * 1024L * 1024L
    }
}

internal fun Path.toRealPathOrSelf(): Path =
    runCatching { toRealPath() }
        .getOrElse { toAbsolutePath().normalize() }

internal fun stablePnpHash(value: String): String =
    MessageDigest
        .getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

private fun Path.deleteRecursively() {
    if (!Files.exists(this)) {
        return
    }

    Files.walk(this).use { paths ->
        paths
            .sorted(Comparator.reverseOrder())
            .forEach { path -> Files.deleteIfExists(path) }
    }
}
