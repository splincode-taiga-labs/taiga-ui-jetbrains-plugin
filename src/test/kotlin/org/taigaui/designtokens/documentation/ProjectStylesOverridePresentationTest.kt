package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenOrigin
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenSourceFormat
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.DesignTokenVariant
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import org.taigaui.designtokens.resolution.DesignTokenValueResolution
import org.taigaui.designtokens.resolution.DesignTokenVariantResolution
import java.nio.file.Path

class ProjectStylesOverridePresentationTest {
    @Test
    fun `generic project override mutes a more specific package declaration`() {
        val packageResolution = resolution(packageVariant(DARK_DESKTOP, sharedAcrossPlatforms = true), DARK_DESKTOP)
        val projectResolution = resolution(projectVariant(ANY_DESKTOP, sharedAcrossPlatforms = true), DARK_DESKTOP)
        val decorated = listOf(packageResolution, projectResolution).withOverrideState()

        assertEquals(
            "Overridden by $PROJECT_STYLES_PACKAGE",
            decorated.single { item -> item.packageName == DESIGN_TOKENS_PACKAGE }.overrideMessage,
        )
        assertNull(decorated.single { item -> item.packageName == PROJECT_STYLES_PACKAGE }.overrideMessage)
    }

    @Test
    fun `dark project override mutes only desktop dark package context`() {
        val packageVariant = packageVariant(DARK_DESKTOP, sharedAcrossPlatforms = true)
        val projectVariant = projectVariant(DARK_DESKTOP, sharedAcrossPlatforms = false)
        val decorated =
            listOf(
                resolution(packageVariant, DARK_DESKTOP),
                resolution(packageVariant, DARK_IOS),
                resolution(packageVariant, DARK_ANDROID),
                resolution(projectVariant, DARK_DESKTOP),
            ).withOverrideState()
        val packageResolutions = decorated.filter { item -> item.packageName == DESIGN_TOKENS_PACKAGE }

        assertEquals(3, packageResolutions.size)
        assertEquals(
            "Overridden by $PROJECT_STYLES_PACKAGE",
            packageResolutions.single { item -> item.resolution.requestedContext == DARK_DESKTOP }.overrideMessage,
        )
        assertNull(packageResolutions.single { item -> item.resolution.requestedContext == DARK_IOS }.overrideMessage)
        assertNull(
            packageResolutions.single { item -> item.resolution.requestedContext == DARK_ANDROID }.overrideMessage,
        )
    }

    @Test
    fun `ios dark project override mutes only ios dark package context`() {
        val packageVariant = packageVariant(DARK_DESKTOP, sharedAcrossPlatforms = true)
        val projectVariant = projectVariant(DARK_IOS, sharedAcrossPlatforms = false)
        val decorated =
            listOf(
                resolution(packageVariant, DARK_DESKTOP),
                resolution(packageVariant, DARK_IOS),
                resolution(packageVariant, DARK_ANDROID),
                resolution(projectVariant, DARK_IOS),
            ).withOverrideState()
        val packageResolutions = decorated.filter { item -> item.packageName == DESIGN_TOKENS_PACKAGE }

        assertEquals(3, packageResolutions.size)
        assertNull(
            packageResolutions.single { item -> item.resolution.requestedContext == DARK_DESKTOP }.overrideMessage,
        )
        assertEquals(
            "Overridden by $PROJECT_STYLES_PACKAGE",
            packageResolutions.single { item -> item.resolution.requestedContext == DARK_IOS }.overrideMessage,
        )
        assertNull(
            packageResolutions.single { item -> item.resolution.requestedContext == DARK_ANDROID }.overrideMessage,
        )
        assertTrue(decorated.any { item -> item.packageName == PROJECT_STYLES_PACKAGE && item.overrideMessage == null })
    }

    @Test
    fun `known package precedence marks lower layer as overridden`() {
        val context = DARK_DESKTOP
        val designTokens =
            resolution(
                variant(
                    packageName = DESIGN_TOKENS_PACKAGE,
                    context = context,
                    value = "design-tokens",
                    sharedAcrossPlatforms = false,
                ),
                context,
            )
        val core =
            resolution(
                variant(
                    packageName = "@taiga-ui/core",
                    context = context,
                    value = "core",
                    sharedAcrossPlatforms = false,
                ),
                context,
            )

        val decorated = listOf(designTokens, core).withOverrideState()

        assertEquals(
            "Overridden by @taiga-ui/core",
            decorated.single { item -> item.packageName == DESIGN_TOKENS_PACKAGE }.overrideMessage,
        )
        assertNull(decorated.single { item -> item.packageName == "@taiga-ui/core" }.overrideMessage)
    }

    @Test
    fun `unknown package layer stays active instead of guessing precedence`() {
        val context = DARK_DESKTOP
        val first =
            resolution(
                variant(
                    packageName = "@custom/first",
                    context = context,
                    value = "first",
                    sharedAcrossPlatforms = false,
                ),
                context,
            )
        val second =
            resolution(
                variant(
                    packageName = "@custom/second",
                    context = context,
                    value = "second",
                    sharedAcrossPlatforms = false,
                ),
                context,
            )

        assertTrue(
            listOf(first, second)
                .withOverrideState()
                .all { item -> item.overrideMessage == null },
        )
    }

    @Test
    fun `platform and theme specificity explain package overrides`() {
        val shared =
            resolution(
                packageVariant(ANY_DESKTOP, sharedAcrossPlatforms = true),
                DARK_IOS,
            )
        val ios =
            resolution(
                packageVariant(DARK_IOS, sharedAcrossPlatforms = false),
                DARK_IOS,
            )
        val platformDecorated = listOf(shared, ios).withOverrideState()

        assertEquals(
            "Overridden by a platform-specific declaration",
            platformDecorated.single { item -> item.resolution === shared }.overrideMessage,
        )

        val anyTheme =
            resolution(
                packageVariant(ANY_DESKTOP, sharedAcrossPlatforms = false),
                DARK_DESKTOP,
            )
        val dark =
            resolution(
                packageVariant(DARK_DESKTOP, sharedAcrossPlatforms = false),
                DARK_DESKTOP,
            )
        val themeDecorated = listOf(anyTheme, dark).withOverrideState()

        assertEquals(
            "Overridden by a theme-specific declaration",
            themeDecorated.single { item -> item.resolution === anyTheme }.overrideMessage,
        )
    }

    @Test
    fun `later project cascade overrides earlier declaration in same scope`() {
        val early =
            resolution(
                projectVariant(
                    context = DARK_DESKTOP,
                    sharedAcrossPlatforms = false,
                    cascadeOrder = 1,
                ),
                DARK_DESKTOP,
            )
        val late =
            resolution(
                projectVariant(
                    context = DARK_DESKTOP,
                    sharedAcrossPlatforms = false,
                    cascadeOrder = 2,
                ),
                DARK_DESKTOP,
            )

        val decorated = listOf(early, late).withOverrideState()

        assertEquals(
            "Overridden by a later $PROJECT_STYLES_PACKAGE declaration",
            decorated.single { item -> item.resolution === early }.overrideMessage,
        )
        assertNull(decorated.single { item -> item.resolution === late }.overrideMessage)
    }

    private fun packageVariant(
        context: DesignTokenContext,
        sharedAcrossPlatforms: Boolean,
    ): DesignTokenVariant =
        variant(
            packageName = DESIGN_TOKENS_PACKAGE,
            context = context,
            value = "package",
            sharedAcrossPlatforms = sharedAcrossPlatforms,
        )

    private fun projectVariant(
        context: DesignTokenContext,
        sharedAcrossPlatforms: Boolean,
        cascadeOrder: Int? = null,
    ): DesignTokenVariant =
        variant(
            packageName = PROJECT_STYLES_PACKAGE,
            context = context,
            value = "project",
            sharedAcrossPlatforms = sharedAcrossPlatforms,
            cascadeOrder = cascadeOrder,
        )

    private fun variant(
        packageName: String,
        context: DesignTokenContext,
        value: String,
        sharedAcrossPlatforms: Boolean,
        cascadeOrder: Int? = null,
    ): DesignTokenVariant =
        DesignTokenVariant(
            name = TOKEN,
            context = context,
            rawValue = value,
            origins =
                listOf(
                    DesignTokenOrigin(
                        sourceFile = Path.of("styles/$packageName.less"),
                        line = 1,
                        format = DesignTokenSourceFormat.LESS,
                        selectorChain = listOf(":root"),
                        packageName = packageName,
                        packageVersion = "1.0.0",
                        sharedAcrossPlatforms = sharedAcrossPlatforms,
                        cascadeOrder = cascadeOrder,
                    ),
                ),
        )

    private fun resolution(
        variant: DesignTokenVariant,
        requestedContext: DesignTokenContext,
    ): DesignTokenVariantResolution =
        DesignTokenVariantResolution(
            variant = variant,
            result =
                DesignTokenValueResolution.Resolved(
                    rawValue = variant.rawValue,
                    value = variant.rawValue,
                ),
            requestedContext = requestedContext,
        )

    private companion object {
        const val TOKEN = "--tui-text-primary"
        const val DESIGN_TOKENS_PACKAGE = "@taiga-ui/design-tokens"
        val ANY_DESKTOP = DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.UNSPECIFIED)
        val DARK_DESKTOP = DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.DARK)
        val DARK_IOS = DesignTokenContext(DesignTokenPlatform.IOS, DesignTokenTheme.DARK)
        val DARK_ANDROID = DesignTokenContext(DesignTokenPlatform.ANDROID, DesignTokenTheme.DARK)
    }
}
