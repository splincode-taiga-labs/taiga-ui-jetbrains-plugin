package org.taigaui.designtokens.resolution

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
import java.nio.file.Path

class ProjectStylesCandidateSelectorCoverageTest {
    @Test
    fun `returns empty when project styles do not apply`() {
        val packageVariant = variant(packageName = "@taiga-ui/core")

        assertTrue(
            ProjectStylesCandidateSelector
                .select(listOf(packageVariant), context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT))
                .isEmpty(),
        )
    }

    @Test
    fun `local override wins over ordinary project candidate`() {
        val ordinary = variant(value = "ordinary", localOverride = false)
        val local = variant(value = "local", localOverride = true)

        val selected =
            ProjectStylesCandidateSelector.select(
                listOf(ordinary, local),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT),
            )

        assertEquals(listOf(local), selected)
    }

    @Test
    fun `mobile project declaration applies to ios and android with lower specificity`() {
        val mobile = variant(value = "mobile", platform = DesignTokenPlatform.MOBILE)
        val ios = variant(value = "ios", platform = DesignTokenPlatform.IOS)

        assertEquals(
            listOf(ios),
            ProjectStylesCandidateSelector.select(
                listOf(mobile, ios),
                context(DesignTokenPlatform.IOS, DesignTokenTheme.LIGHT),
            ),
        )
        assertEquals(
            listOf(mobile),
            ProjectStylesCandidateSelector.select(
                listOf(mobile),
                context(DesignTokenPlatform.ANDROID, DesignTokenTheme.LIGHT),
            ),
        )
    }

    @Test
    fun `shared declaration applies everywhere but platform declaration is more specific`() {
        val shared = variant(value = "shared", sharedAcrossPlatforms = true)
        val android = variant(value = "android", platform = DesignTokenPlatform.ANDROID)

        assertEquals(
            listOf(android),
            ProjectStylesCandidateSelector.select(
                listOf(shared, android),
                context(DesignTokenPlatform.ANDROID, DesignTokenTheme.DARK),
            ),
        )
    }

    @Test
    fun `theme specific declaration wins and unspecified request rejects themed candidate`() {
        val anyTheme = variant(value = "any", theme = DesignTokenTheme.UNSPECIFIED)
        val dark = variant(value = "dark", theme = DesignTokenTheme.DARK)

        assertEquals(
            listOf(dark),
            ProjectStylesCandidateSelector.select(
                listOf(anyTheme, dark),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.DARK),
            ),
        )
        assertEquals(
            listOf(anyTheme),
            ProjectStylesCandidateSelector.select(
                listOf(anyTheme, dark),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.UNSPECIFIED),
            ),
        )
    }

    @Test
    fun `latest comparable cascade order wins`() {
        val early = variant(value = "early", cascadeOrder = 1, selectorChain = listOf(":root"))
        val late = variant(value = "late", cascadeOrder = 3, selectorChain = listOf(" :root "))

        assertEquals(
            listOf(late),
            ProjectStylesCandidateSelector.select(
                listOf(early, late),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT),
            ),
        )
    }

    @Test
    fun `incomparable cascade candidates remain ambiguous`() {
        val withoutOrder = variant(value = "no-order", cascadeOrder = null)
        val ordered = variant(value = "ordered", cascadeOrder = 2)

        val selected =
            ProjectStylesCandidateSelector.select(
                listOf(withoutOrder, ordered),
                context(DesignTokenPlatform.DESKTOP, DesignTokenTheme.LIGHT),
            )

        assertEquals(listOf(withoutOrder, ordered), selected)
    }

    @Test
    fun `cascade helpers normalize scope and require every origin to be ordered`() {
        val originA = origin(cascadeOrder = 2, selectorChain = listOf(" :root   .theme "))
        val originB = origin(cascadeOrder = 5, selectorChain = listOf(":root .theme"))
        val ordered =
            variant(
                value = "ordered",
                origins = listOf(originA, originB),
            )

        assertEquals(5, ordered.projectCascadeOrder())
        assertEquals(listOf(":root .theme"), ordered.projectCascadeScope())

        val mixed =
            variant(
                value = "mixed",
                origins = listOf(originA, origin(cascadeOrder = null)),
            )

        assertNull(mixed.projectCascadeOrder())
    }

    private fun context(
        platform: DesignTokenPlatform,
        theme: DesignTokenTheme,
    ): DesignTokenContext = DesignTokenContext(platform, theme)

    private fun variant(
        value: String = "value",
        platform: DesignTokenPlatform = DesignTokenPlatform.DESKTOP,
        theme: DesignTokenTheme = DesignTokenTheme.UNSPECIFIED,
        packageName: String = PROJECT_STYLES_PACKAGE,
        localOverride: Boolean = false,
        sharedAcrossPlatforms: Boolean = false,
        cascadeOrder: Int? = null,
        selectorChain: List<String> = listOf(":root"),
        origins: List<DesignTokenOrigin>? = null,
    ): DesignTokenVariant =
        DesignTokenVariant(
            name = "--tui-test",
            context = DesignTokenContext(platform, theme),
            rawValue = value,
            origins =
                origins
                    ?: listOf(
                        origin(
                            packageName = packageName,
                            localOverride = localOverride,
                            sharedAcrossPlatforms = sharedAcrossPlatforms,
                            cascadeOrder = cascadeOrder,
                            selectorChain = selectorChain,
                        ),
                    ),
        )

    private fun origin(
        packageName: String = PROJECT_STYLES_PACKAGE,
        localOverride: Boolean = false,
        sharedAcrossPlatforms: Boolean = false,
        cascadeOrder: Int? = null,
        selectorChain: List<String> = listOf(":root"),
    ): DesignTokenOrigin =
        DesignTokenOrigin(
            sourceFile = Path.of("styles.css"),
            line = 1,
            format = DesignTokenSourceFormat.CSS,
            selectorChain = selectorChain,
            packageName = packageName,
            sharedAcrossPlatforms = sharedAcrossPlatforms,
            cascadeOrder = cascadeOrder,
            localOverride = localOverride,
        )
}
