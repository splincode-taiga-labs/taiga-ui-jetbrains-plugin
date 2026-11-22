package org.taigaui.designtokens.resolution

import com.intellij.openapi.progress.ProgressManager
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme

internal fun DesignTokenIndex.matchColors(
    resolver: DesignTokenValueResolver,
    names: List<String>,
    selectorLocalNames: Set<String>,
): Map<CssLiteralColor, List<String>> {
    val selector = DesignTokenCandidateSelector(this)

    return names
        .mapNotNull { name ->
            ProgressManager.checkCanceled()
            resolver.colorInEveryContext(selector, name, selectorLocalNames)?.let { color -> color to name }
        }.groupBy({ it.first }, { it.second })
}

private fun DesignTokenValueResolver.colorInEveryContext(
    selector: DesignTokenCandidateSelector,
    name: String,
    selectorLocalNames: Set<String>,
): CssLiteralColor? {
    if (name in selectorLocalNames) return null

    return CONTEXTS
        .map { context ->
            val selection = selector.select(name, context) as? DesignTokenCandidateSelection.Selected
            val value = selection?.let { resolve(it.variant, context).result } as? DesignTokenValueResolution.Resolved

            value
                ?.takeUnless { it.hasSelectorLocalReference(selectorLocalNames) }
                ?.let { CssLiteralColor.parse(it.value) }
        }.distinct()
        .singleOrNull()
}

private fun DesignTokenValueResolution.hasSelectorLocalReference(names: Set<String>): Boolean =
    references.any { reference ->
        reference.name in names || reference.effectiveResult.hasSelectorLocalReference(names)
    }

private val CONTEXTS =
    listOf(DesignTokenPlatform.DESKTOP, DesignTokenPlatform.IOS, DesignTokenPlatform.ANDROID).flatMap { platform ->
        listOf(DesignTokenTheme.LIGHT, DesignTokenTheme.DARK).map { theme -> DesignTokenContext(platform, theme) }
    }
