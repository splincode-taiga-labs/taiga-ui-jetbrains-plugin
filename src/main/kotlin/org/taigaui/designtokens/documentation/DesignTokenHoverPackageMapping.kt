package org.taigaui.designtokens.documentation

import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.resolution.DesignTokenResolutionGroup
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution

internal fun List<DesignTokenResolutionGroup>.toHoverPackageSections(
    tokenName: String,
): List<DesignTokenHoverPackageSection> {
    val decorated =
        flatMap { group -> group.resolutions }
            .flatMap(DesignTokenVariantResolution::splitBySourcePackage)
            .withOverrideState()

    return decorated
        .groupBy(DecoratedResolution::packageName)
        .map { (packageName, packageResolutions) ->
            val displayGroups = packageResolutions.toDisplayGroups()

            DesignTokenHoverPackageSection(
                packageName = packageName,
                rows =
                    displayGroups
                        .map(DisplayResolutionGroup::toHoverValueRow)
                        .sortedWith(VALUE_ROW_COMPARATOR),
                chains =
                    displayGroups
                        .map { group -> group.toHoverReferenceChain(tokenName) }
                        .sortedWith(REFERENCE_CHAIN_COMPARATOR),
            )
        }.sortedBy { section -> section.packageName.packageSortKey() }
}

private fun DesignTokenVariantResolution.splitBySourcePackage(): List<DesignTokenVariantResolution> {
    val originsByPackage =
        variant.origins.groupBy { origin -> origin.packageName ?: DEFAULT_SOURCE_PACKAGE }

    return originsByPackage.map { (_, origins) ->
        copy(variant = variant.copy(origins = origins))
    }
}

internal fun DesignTokenVariantResolution.sourcePackageName(): String =
    variant.origins.first().packageName ?: DEFAULT_SOURCE_PACKAGE

private fun String.packageSortKey(): String = "${packageDisplayRank()}:$this"

private fun String.packageDisplayRank(): Int =
    when (this) {
        PROJECT_STYLES_PACKAGE -> 0
        DEFAULT_SOURCE_PACKAGE -> 1
        "@taiga-ui/styles" -> 2
        "@taiga-ui/core" -> 3
        else -> 4
    }

private fun String.platformDisplayRank(): Int =
    when {
        contains("Desktop") -> 0
        contains("Mobile") -> 1
        contains("iOS") -> 2
        contains("Android") -> 3
        startsWith("All platforms") -> 4
        else -> 5
    }

internal val VALUE_ROW_COMPARATOR =
    compareBy<DesignTokenHoverValueRow>(
        { row -> row.overrideMessage != null },
        { row -> row.platform.platformDisplayRank() },
        DesignTokenHoverValueRow::platform,
        DesignTokenHoverValueRow::resolvedValue,
    )
internal val REFERENCE_CHAIN_COMPARATOR =
    compareBy<DesignTokenHoverReferenceChain>(
        { chain -> chain.overrideMessage != null },
        { chain -> chain.platform.platformDisplayRank() },
        DesignTokenHoverReferenceChain::platform,
    )
internal const val DEFAULT_SOURCE_PACKAGE = "@taiga-ui/design-tokens"
