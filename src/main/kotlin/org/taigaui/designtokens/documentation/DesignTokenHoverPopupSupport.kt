package org.taigaui.designtokens.documentation

import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.resolution.DesignTokenUnresolvedReason
import org.taigaui.designtokens.resolution.DesignTokenValueResolution

internal fun DesignTokenNavigationTarget.navigate(project: Project): Boolean {
    val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(sourceFile) ?: return false

    OpenFileDescriptor(project, file, (line - 1).coerceAtLeast(0), 0).navigate(true)

    return true
}

internal fun DesignTokenValueResolution.hoverValueText(): String =
    when (this) {
        is DesignTokenValueResolution.Resolved -> color?.cssText ?: value
        is DesignTokenValueResolution.Unresolved -> reason.hoverValueText()
    }

internal fun Collection<DesignTokenContext>.hoverPlatformLabel(): String {
    val contexts = distinct()
    val platforms = contexts.map(DesignTokenContext::platform).toSet()
    val themes = contexts.map(DesignTokenContext::theme).toSet()
    val expectedContexts =
        platforms
            .flatMap { platform ->
                themes.map { theme -> DesignTokenContext(platform, theme) }
            }.toSet()
    val platformLabel = platforms.hoverPlatformLabel()
    val themeLabel = themes.hoverThemeLabel()
    val canCompact =
        contexts.toSet() == expectedContexts &&
            platformLabel != null &&
            themeLabel != null

    return if (canCompact) {
        "$platformLabel · $themeLabel"
    } else {
        contexts.joinToString(separator = ", ", transform = DesignTokenContext::hoverPlatformLabel)
    }
}

private fun DesignTokenUnresolvedReason.hoverValueText(): String =
    when (this) {
        is DesignTokenUnresolvedReason.MissingReference -> "Missing reference: $name"
        is DesignTokenUnresolvedReason.AmbiguousReference ->
            "Ambiguous reference: $name (${candidates.size} candidates)"

        is DesignTokenUnresolvedReason.CircularReference ->
            "Circular reference: ${chain.joinToString(" → ") { node -> node.name }}"

        is DesignTokenUnresolvedReason.InvalidExpression ->
            "Invalid expression at offset $offset: $message"
    }

private fun DesignTokenContext.hoverPlatformLabel(): String {
    val platformLabel =
        when (platform) {
            DesignTokenPlatform.DESKTOP -> "🖥️ Desktop"
            DesignTokenPlatform.MOBILE -> "📱 Mobile"
            DesignTokenPlatform.IOS -> "📱 iOS"
            DesignTokenPlatform.ANDROID -> "🤖 Android"
        }
    val themeLabel =
        when (theme) {
            DesignTokenTheme.LIGHT -> "Light ☀️"
            DesignTokenTheme.DARK -> "Dark 🌚"
            DesignTokenTheme.UNSPECIFIED -> "Any theme"
        }

    return "$platformLabel · $themeLabel"
}

private fun Set<DesignTokenPlatform>.hoverPlatformLabel(): String? =
    when (this) {
        setOf(DesignTokenPlatform.DESKTOP) -> "🖥️ Desktop"
        setOf(DesignTokenPlatform.MOBILE),
        setOf(DesignTokenPlatform.IOS, DesignTokenPlatform.ANDROID),
        -> "📱 Mobile"

        setOf(DesignTokenPlatform.IOS) -> "📱 iOS"
        setOf(DesignTokenPlatform.ANDROID) -> "🤖 Android"
        setOf(DesignTokenPlatform.DESKTOP, DesignTokenPlatform.MOBILE),
        setOf(DesignTokenPlatform.DESKTOP, DesignTokenPlatform.IOS, DesignTokenPlatform.ANDROID),
        -> "All platforms"

        else -> null
    }

private fun Set<DesignTokenTheme>.hoverThemeLabel(): String? =
    when (this) {
        setOf(DesignTokenTheme.LIGHT) -> "Light ☀️"
        setOf(DesignTokenTheme.DARK) -> "Dark 🌚"
        setOf(DesignTokenTheme.UNSPECIFIED) -> "Any theme"
        setOf(DesignTokenTheme.LIGHT, DesignTokenTheme.DARK) -> "Light ☀️ and dark 🌚"
        else -> null
    }
