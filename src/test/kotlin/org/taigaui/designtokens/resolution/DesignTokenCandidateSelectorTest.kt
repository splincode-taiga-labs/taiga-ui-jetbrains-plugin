package org.taigaui.designtokens.resolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.PROJECT_STYLES_PACKAGE
import java.nio.file.Path

class DesignTokenCandidateSelectorTest {
    private val root =
        Path
            .of("build", "fixtures", "candidate-selector")
            .toAbsolutePath()
            .normalize()

    @Test
    fun `returns missing when token has no variants`() {
        val selection =
            selector().select(
                "--tui-missing",
                DesignTokenContext.DEFAULT,
            )

        assertTrue(selection is DesignTokenCandidateSelection.Missing)
    }

    @Test
    fun `prefers project styles over installed package variants`() {
        val selection =
            selector(
                declaration("light/core.css", "@taiga-ui/core", "#111"),
                declaration(
                    "project.css",
                    PROJECT_STYLES_PACKAGE,
                    "#222",
                    selectorChain = listOf(":root"),
                    localOverride = true,
                ),
            ).select(
                TOKEN,
                DesignTokenContext(
                    DesignTokenPlatform.DESKTOP,
                    DesignTokenTheme.LIGHT,
                ),
            )

        assertEquals(
            PROJECT_STYLES_PACKAGE,
            (selection as DesignTokenCandidateSelection.Selected)
                .variant
                .origins
                .single()
                .packageName,
        )
    }

    @Test
    fun `prefers highest known package layer within the same context`() {
        val selection =
            selector(
                declaration("light/design.css", "@taiga-ui/design-tokens", "#111"),
                declaration("light/core.css", "@taiga-ui/core", "#111"),
                declaration("light/proprietary.css", "@taiga-ui/proprietary", "#111"),
            ).select(
                TOKEN,
                DesignTokenContext(
                    DesignTokenPlatform.DESKTOP,
                    DesignTokenTheme.LIGHT,
                ),
            )

        assertEquals(
            "@taiga-ui/proprietary",
            (selection as DesignTokenCandidateSelection.Selected)
                .variant
                .origins
                .single()
                .packageName,
        )
    }

    @Test
    fun `keeps candidates ambiguous when an unknown package participates`() {
        val selection =
            selector(
                declaration("light/core.css", "@taiga-ui/core", "#111"),
                declaration("light/custom.css", "@custom/theme", "#111"),
            ).select(
                TOKEN,
                DesignTokenContext(
                    DesignTokenPlatform.DESKTOP,
                    DesignTokenTheme.LIGHT,
                ),
            )

        assertEquals(
            2,
            (selection as DesignTokenCandidateSelection.Ambiguous).candidates.size,
        )
    }

    @Test
    fun `falls back through desktop mobile ios and android precedence`() {
        val selector =
            selector(
                declaration("base.css", "@taiga-ui/core", "desktop"),
                declaration("dark/base.css", "@taiga-ui/core", "desktop-dark"),
                declaration("mobile/base.css", "@taiga-ui/core", "mobile"),
                declaration("mobile/light/base.css", "@taiga-ui/core", "mobile-light"),
                declaration(
                    "platform.css",
                    "@taiga-ui/core",
                    "ios",
                    selectorChain = listOf("[tuiPlatform='ios']"),
                ),
                declaration(
                    "platform-dark.css",
                    "@taiga-ui/core",
                    "android-dark",
                    selectorChain = listOf("[data-platform=android]", "[tuiTheme=dark]"),
                ),
            )

        assertValue(
            "desktop-dark",
            selector.select(
                TOKEN,
                DesignTokenContext(DesignTokenPlatform.DESKTOP, DesignTokenTheme.DARK),
            ),
        )
        assertValue(
            "mobile-light",
            selector.select(
                TOKEN,
                DesignTokenContext(DesignTokenPlatform.MOBILE, DesignTokenTheme.LIGHT),
            ),
        )
        assertValue(
            "ios",
            selector.select(
                TOKEN,
                DesignTokenContext(DesignTokenPlatform.IOS, DesignTokenTheme.UNSPECIFIED),
            ),
        )
        assertValue(
            "android-dark",
            selector.select(
                TOKEN,
                DesignTokenContext(DesignTokenPlatform.ANDROID, DesignTokenTheme.DARK),
            ),
        )
    }

    @Test
    fun `falls back from platform specific theme to mobile and desktop defaults`() {
        val onlyDesktop =
            selector(
                declaration("base.css", "@taiga-ui/core", "desktop"),
            )

        listOf(
            DesignTokenContext(DesignTokenPlatform.MOBILE, DesignTokenTheme.UNSPECIFIED),
            DesignTokenContext(DesignTokenPlatform.IOS, DesignTokenTheme.LIGHT),
            DesignTokenContext(DesignTokenPlatform.ANDROID, DesignTokenTheme.UNSPECIFIED),
        ).forEach { context ->
            assertValue(
                "desktop",
                onlyDesktop.select(TOKEN, context),
            )
        }
    }

    private fun selector(vararg declarations: DesignTokenDeclaration): DesignTokenCandidateSelector =
        DesignTokenCandidateSelector(
            DesignTokenIndex.build(
                packageRoot = root,
                declarations = declarations.toList(),
            ),
        )

    private fun declaration(
        relativePath: String,
        packageName: String,
        value: String,
        selectorChain: List<String> = emptyList(),
        localOverride: Boolean = false,
    ): DesignTokenDeclaration =
        DesignTokenDeclaration(
            name = TOKEN,
            value = value,
            sourceFile = root.resolve(relativePath),
            line = 1,
            selectorChain = selectorChain,
            packageName = packageName,
            packageVersion = "1",
            localOverride = localOverride,
        )

    private fun assertValue(
        expected: String,
        selection: DesignTokenCandidateSelection,
    ) {
        assertEquals(
            expected,
            (selection as DesignTokenCandidateSelection.Selected).variant.rawValue,
        )
    }

    private companion object {
        const val TOKEN = "--tui-test"
    }
}
