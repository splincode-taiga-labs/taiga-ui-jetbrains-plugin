package org.taigaui.designtokens.migration

internal data class NxMigrationResult(
    val status: String,
    val message: String?,
    val exitCode: Int? = null,
    val output: String = "",
    val outputTruncated: Boolean = false,
)

internal class MigrationOutput {
    private val tail = StringBuilder()
    private var truncated = false

    @Synchronized
    fun append(text: String) {
        if (text.length >= OUTPUT_LIMIT) {
            truncated = truncated || text.length > OUTPUT_LIMIT || tail.isNotEmpty()
            tail.clear()
            tail.append(text, text.length - OUTPUT_LIMIT, text.length)
        } else {
            val overflow = tail.length + text.length - OUTPUT_LIMIT

            if (overflow > 0) {
                truncated = true
                tail.delete(0, overflow)
            }

            tail.append(text)
        }
    }

    @Synchronized
    fun result(
        status: String,
        message: String?,
        exitCode: Int? = null,
    ): NxMigrationResult = NxMigrationResult(status, message, exitCode, tail.toString(), truncated)
}

private const val OUTPUT_LIMIT = 16_000
