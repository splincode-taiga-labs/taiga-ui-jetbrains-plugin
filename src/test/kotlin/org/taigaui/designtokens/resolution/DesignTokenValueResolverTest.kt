package org.taigaui.designtokens.resolution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.taigaui.designtokens.index.DesignTokenContext
import org.taigaui.designtokens.index.DesignTokenDeclaration
import org.taigaui.designtokens.index.DesignTokenIndex
import org.taigaui.designtokens.index.DesignTokenPlatform
import org.taigaui.designtokens.index.DesignTokenTheme
import org.taigaui.designtokens.index.DesignTokenVariant
import java.nio.file.Path

class DesignTokenValueResolverTest {
    private val packageRoot =
        Path
            .of("build", "fixtures", "resolution")
            .toAbsolutePath()
            .normalize()

    @Test
    fun `returns terminal value and detected color without changing raw value`() {
        val index = index(token("palette/light.css", ROOT, "#FFF"))
        val variant = index.find(ROOT).single()
        val result = resolved(DesignTokenValueResolver(index).resolve(variant))

        assertEquals("#FFF", variant.rawValue)
        assertEquals("#FFF", result.rawValue)
        assertEquals("#FFF", result.value)
        assertEquals("#ffffff", requireNotNull(result.color).canonicalValue)
        assertEquals(DesignTokenColorFormat.HEX, requireNotNull(result.color).format)
    }

    @Test
    fun `resolves complete multi-hop reference tree`() {
        val index =
            index(
                token("palette/light.css", ROOT, "var(--tui-middle)"),
                token("palette/light.css", "--tui-middle", "var(--tui-terminal)"),
                token("palette/light.css", "--tui-terminal", "rgb(1 2 3 / 50%)"),
            )
        val result = resolved(resolve(index, ROOT, LIGHT_DESKTOP))
        val middleReference = result.references.single()
        val middleResult = middleReference.primaryResult as DesignTokenValueResolution.Resolved
        val terminalReference = middleResult.references.single()

        assertEquals("rgb(1 2 3 / 50%)", result.value)
        assertEquals("--tui-middle", middleReference.name)
        assertEquals("--tui-terminal", terminalReference.name)
        assertEquals(
            "rgb(1 2 3 / 50%)",
            (terminalReference.primaryResult as DesignTokenValueResolution.Resolved).value,
        )
    }

    @Test
    fun `substitutes multiple references inside compound value`() {
        val index =
            index(
                token("palette/base.css", ROOT, "calc(var(--tui-size) * 2) solid var(--tui-color)"),
                token("palette/base.css", "--tui-size", "4px"),
                token("palette/base.css", "--tui-color", "#000"),
            )
        val result = resolved(resolve(index, ROOT, DESKTOP))

        assertEquals("calc(4px * 2) solid #000", result.value)
        assertEquals(listOf("--tui-size", "--tui-color"), result.references.map { it.name })
    }

    @Test
    fun `uses nested fallback with commas when reference is missing`() {
        val index =
            index(
                token(
                    "palette/base.css",
                    ROOT,
                    "var(--tui-missing, rgb(1, 2, var(--tui-alpha, 0.5)))",
                ),
                token("palette/base.css", "--tui-alpha", "0.8"),
            )
        val result = resolved(resolve(index, ROOT, DESKTOP))
        val reference = result.references.single()

        assertEquals("rgb(1, 2, 0.8)", result.value)
        assertTrue(reference.fallbackUsed)
        assertEquals("rgb(1, 2, var(--tui-alpha, 0.5))", reference.fallbackRawValue)
        assertTrue(requireNotNull(reference.fallbackResult) is DesignTokenValueResolution.Resolved)
    }

    @Test
    fun `supports empty fallback`() {
        val index = index(token("palette/base.css", ROOT, "prefix var(--tui-missing,) suffix"))
        val result = resolved(resolve(index, ROOT, DESKTOP))

        assertEquals("prefix  suffix", result.value)
        assertTrue(result.references.single().fallbackUsed)
    }

    @Test
    fun `returns missing result when reference and fallback are absent`() {
        val index = index(token("palette/base.css", ROOT, "var(--tui-missing)"))
        val result = unresolved(resolve(index, ROOT, DESKTOP))
        val reason = result.reason as DesignTokenUnresolvedReason.MissingReference

        assertEquals("--tui-missing", reason.name)
        assertEquals(DESKTOP, reason.requestedContext)
        assertFalse(result.references.single().fallbackUsed)
    }

    @Test
    fun `prefers exact mobile dark candidate`() {
        val index =
            precedenceIndex(
                mobileDark = "mobile-dark",
                mobileUnspecified = "mobile-base",
                desktopDark = "desktop-dark",
                desktopUnspecified = "desktop-base",
            )

        assertEquals("mobile-dark", resolved(resolve(index, ROOT, DARK_MOBILE)).value)
    }

    @Test
    fun `falls through compatible mobile dark contexts in order`() {
        val mobileBase =
            precedenceIndex(
                mobileUnspecified = "mobile-base",
                desktopDark = "desktop-dark",
                desktopUnspecified = "desktop-base",
            )
        val desktopDark =
            precedenceIndex(
                desktopDark = "desktop-dark",
                desktopUnspecified = "desktop-base",
            )
        val desktopBase = precedenceIndex(desktopUnspecified = "desktop-base")

        assertEquals("mobile-base", resolved(resolve(mobileBase, ROOT, DARK_MOBILE)).value)
        assertEquals("desktop-dark", resolved(resolve(desktopDark, ROOT, DARK_MOBILE)).value)
        assertEquals("desktop-base", resolved(resolve(desktopBase, ROOT, DARK_MOBILE)).value)
    }

    @Test
    fun `desktop context never selects mobile declaration`() {
        val index =
            precedenceIndex(
                mobileDark = "mobile-dark",
                desktopUnspecified = "desktop-base",
                rootContext = DARK_DESKTOP,
            )

        assertEquals("desktop-base", resolved(resolve(index, ROOT, DARK_DESKTOP)).value)
    }

    @Test
    fun `light context never falls back to conflicting dark theme`() {
        val index =
            precedenceIndex(
                mobileDark = "mobile-dark",
                desktopDark = "desktop-dark",
                rootContext = LIGHT_MOBILE,
            )
        val result = unresolved(resolve(index, ROOT, LIGHT_MOBILE))

        assertTrue(result.reason is DesignTokenUnresolvedReason.MissingReference)
    }

    @Test
    fun `keeps active mobile context through selected desktop fallback declaration`() {
        val index =
            index(
                token("mobile/dark.css", ROOT, "var(--tui-bridge)"),
                token("palette/dark.css", "--tui-bridge", "var(--tui-leaf)"),
                token("mobile/dark.css", "--tui-leaf", "mobile-value"),
                token("palette/dark.css", "--tui-leaf", "desktop-value"),
            )
        val result = resolved(resolve(index, ROOT, DARK_MOBILE))
        val bridge = result.references.single()
        val leaf =
            (bridge.primaryResult as DesignTokenValueResolution.Resolved)
                .references
                .single()

        assertEquals("mobile-value", result.value)
        assertEquals(DARK_DESKTOP, requireNotNull(bridge.selectedVariant).context)
        assertEquals(DARK_MOBILE, bridge.requestedContext)
        assertEquals(DARK_MOBILE, requireNotNull(leaf.selectedVariant).context)
    }

    @Test
    fun `returns ambiguity instead of choosing distinct values at same precedence`() {
        val index =
            index(
                token("palette/dark.css", ROOT, "var(--tui-target)"),
                token("palette/dark.css", "--tui-target", "#111"),
                token("palette/scss/dark.scss", "--tui-target", "#222"),
            )
        val result = unresolved(resolve(index, ROOT, DARK_DESKTOP))
        val reason = result.reason as DesignTokenUnresolvedReason.AmbiguousReference

        assertEquals(2, reason.candidates.size)
        assertEquals(listOf("#111", "#222"), reason.candidates.map(DesignTokenVariant::rawValue))
    }

    @Test
    fun `does not hide ambiguity behind fallback`() {
        val index =
            index(
                token("palette/dark.css", ROOT, "var(--tui-target, #fff)"),
                token("palette/dark.css", "--tui-target", "#111"),
                token("palette/scss/dark.scss", "--tui-target", "#222"),
            )
        val result = unresolved(resolve(index, ROOT, DARK_DESKTOP))
        val reference = result.references.single()

        assertTrue(result.reason is DesignTokenUnresolvedReason.AmbiguousReference)
        assertFalse(reference.fallbackUsed)
        assertNull(reference.fallbackResult)
    }

    @Test
    fun `reports circular reference chain`() {
        val index =
            index(
                token("palette/base.css", ROOT, "var(--tui-b)"),
                token("palette/base.css", "--tui-b", "var($ROOT)"),
            )
        val result = unresolved(resolve(index, ROOT, DESKTOP))
        val reason = result.reason as DesignTokenUnresolvedReason.CircularReference

        assertEquals(listOf(ROOT, "--tui-b", ROOT), reason.chain.map { it.name })
        assertEquals(listOf(DESKTOP, DESKTOP, DESKTOP), reason.chain.map { it.requestedContext })
    }

    @Test
    fun `outer fallback recovers from invalid cyclic referenced token`() {
        val index =
            index(
                token("palette/base.css", ROOT, "var(--tui-a, #fff)"),
                token("palette/base.css", "--tui-a", "var(--tui-b)"),
                token("palette/base.css", "--tui-b", "var(--tui-a)"),
            )
        val result = resolved(resolve(index, ROOT, DESKTOP))
        val reference = result.references.single()

        assertEquals("#fff", result.value)
        assertTrue(reference.primaryResult is DesignTokenValueResolution.Unresolved)
        assertTrue(reference.fallbackUsed)
    }

    @Test
    fun `fallback inside a self-cycle does not break the cycle`() {
        val index = index(token("palette/base.css", ROOT, "var($ROOT, #fff)"))
        val result = unresolved(resolve(index, ROOT, DESKTOP))
        val reference = result.references.single()

        assertTrue(result.reason is DesignTokenUnresolvedReason.CircularReference)
        assertFalse(reference.fallbackUsed)
    }

    @Test
    fun `outer fallback recovers from invalid referenced expression`() {
        val index =
            index(
                token("palette/base.css", ROOT, "var(--tui-invalid, #fff)"),
                token("palette/base.css", "--tui-invalid", "var(--tui-broken"),
            )
        val result = resolved(resolve(index, ROOT, DESKTOP))
        val reference = result.references.single()

        assertEquals("#fff", result.value)
        assertTrue(reference.primaryResult is DesignTokenValueResolution.Unresolved)
        assertTrue(reference.fallbackUsed)
    }

    @Test
    fun `ignores var text inside string during resolution`() {
        val rawValue = "'var(--tui-missing)' #fff"
        val index = index(token("palette/base.css", ROOT, rawValue))
        val result = resolved(resolve(index, ROOT, DESKTOP))

        assertEquals(rawValue, result.value)
        assertTrue(result.references.isEmpty())
        assertNull(result.color)
    }

    @Test
    fun `returns invalid expression for malformed root value`() {
        val index = index(token("palette/base.css", ROOT, "var(--tui-broken"))
        val result = unresolved(resolve(index, ROOT, DESKTOP))

        assertTrue(result.reason is DesignTokenUnresolvedReason.InvalidExpression)
    }

    @Test
    fun `groups color-equivalent resolved values and retains all root origins`() {
        val index =
            index(
                token("palette/light.css", ROOT, "var(--tui-light-const)"),
                token("palette/dark.css", ROOT, "var(--tui-dark-const)"),
                token("palette/light.css", "--tui-light-const", "#fff"),
                token("palette/dark.css", "--tui-dark-const", "#FFFFFF"),
            )
        val groups = DesignTokenValueResolver(index).resolveGrouped(ROOT)
        val group = groups.single()
        val representative = group.representative as DesignTokenValueResolution.Resolved

        assertEquals(2, group.resolutions.size)
        assertEquals(2, group.origins.size)
        assertEquals(
            "#ffffff",
            requireNotNull(representative.color).canonicalValue,
        )
    }

    @Test
    fun `keeps different terminal values in separate presentation groups`() {
        val index =
            index(
                token("palette/light.css", ROOT, "#fff"),
                token("palette/dark.css", ROOT, "#000"),
            )

        assertEquals(2, DesignTokenValueResolver(index).resolveGrouped(ROOT).size)
    }

    @Test
    fun `detects color functions and named colors but not dimensions`() {
        assertEquals(
            DesignTokenColorFormat.FUNCTION,
            requireNotNull(DesignTokenColorDetector.detect("RGB(1, 2, 3)")).format,
        )
        assertEquals(
            "rebeccapurple",
            requireNotNull(DesignTokenColorDetector.detect("RebeccaPurple")).canonicalValue,
        )
        assertNull(DesignTokenColorDetector.detect("1rem"))
    }

    @Test
    fun `resolve by name expands mobile unspecified declaration to ios and android light and dark contexts`() {
        val index =
            DesignTokenIndex.build(
                packageRoot = packageRoot,
                declarations =
                    listOf(
                        DesignTokenDeclaration(
                            name = ROOT,
                            value = "1rem",
                            sourceFile = packageRoot.resolve("mobile/base.css"),
                            line = 1,
                        ),
                    ),
            )

        val contexts =
            DesignTokenValueResolver(index)
                .resolve(ROOT)
                .map(DesignTokenVariantResolution::requestedContext)
                .toSet()

        assertEquals(
            setOf(
                DesignTokenContext(DesignTokenPlatform.IOS, DesignTokenTheme.LIGHT),
                DesignTokenContext(DesignTokenPlatform.IOS, DesignTokenTheme.DARK),
                DesignTokenContext(DesignTokenPlatform.ANDROID, DesignTokenTheme.LIGHT),
                DesignTokenContext(DesignTokenPlatform.ANDROID, DesignTokenTheme.DARK),
            ),
            contexts,
        )
    }

    @Test
    fun `resolve by name expands shared desktop declaration across desktop ios and android`() {
        val index =
            DesignTokenIndex.build(
                packageRoot = packageRoot,
                declarations =
                    listOf(
                        DesignTokenDeclaration(
                            name = ROOT,
                            value = "1rem",
                            sourceFile = packageRoot.resolve("shared/base.css"),
                            line = 1,
                            selectorChain = listOf(":root"),
                            localOverride = true,
                        ),
                    ),
            )

        val contexts =
            DesignTokenValueResolver(index)
                .resolve(ROOT)
                .map(DesignTokenVariantResolution::requestedContext)
                .map(DesignTokenContext::platform)
                .toSet()

        assertEquals(
            setOf(
                DesignTokenPlatform.DESKTOP,
                DesignTokenPlatform.IOS,
                DesignTokenPlatform.ANDROID,
            ),
            contexts,
        )
    }

    @Test
    fun `known package layers prefer the highest precedence candidate`() {
        val declarations =
            listOf(
                packageToken("@taiga-ui/design-tokens", "tokens.css", "--tui-target", "tokens"),
                packageToken("@taiga-ui/styles", "styles.css", "--tui-target", "styles"),
                packageToken("@taiga-ui/core", "core.css", "--tui-target", "core"),
                packageToken("@taiga-ui/proprietary", "proprietary.css", "--tui-target", "proprietary"),
                DesignTokenDeclaration(
                    name = ROOT,
                    value = "var(--tui-target)",
                    sourceFile = packageRoot.resolve("palette/base.css"),
                    line = 10,
                ),
            )
        val index =
            DesignTokenIndex.build(
                packageRoot = packageRoot,
                declarations = declarations,
            )

        assertEquals("proprietary", resolved(resolve(index, ROOT, DESKTOP)).value)
    }

    @Test
    fun `unknown package layer keeps equal-precedence candidates ambiguous`() {
        val declarations =
            listOf(
                packageToken("@custom/one", "one.css", "--tui-target", "one"),
                packageToken("@custom/two", "two.css", "--tui-target", "two"),
                DesignTokenDeclaration(
                    name = ROOT,
                    value = "var(--tui-target)",
                    sourceFile = packageRoot.resolve("palette/base.css"),
                    line = 10,
                ),
            )
        val index =
            DesignTokenIndex.build(
                packageRoot = packageRoot,
                declarations = declarations,
            )

        val result = unresolved(resolve(index, ROOT, DESKTOP))

        assertTrue(result.reason is DesignTokenUnresolvedReason.AmbiguousReference)
    }

    @Test
    fun `resolver exposes parsed and resolution cache sizes`() {
        val index =
            index(
                token("palette/base.css", ROOT, "var(--tui-leaf)"),
                token("palette/base.css", "--tui-leaf", "1rem"),
            )
        val resolver = DesignTokenValueResolver(index)

        assertEquals(0, resolver.parsedValueCacheSize)
        assertEquals(0, resolver.resolutionCacheSize)

        resolver.resolve(index.find(ROOT).single())

        assertEquals(2, resolver.parsedValueCacheSize)
        assertEquals(2, resolver.resolutionCacheSize)

        resolver.resolve(index.find(ROOT).single())

        assertEquals(2, resolver.parsedValueCacheSize)
        assertEquals(2, resolver.resolutionCacheSize)
    }

    private fun packageToken(
        packageName: String,
        relativePath: String,
        name: String,
        value: String,
    ): DesignTokenDeclaration =
        DesignTokenDeclaration(
            name = name,
            value = value,
            sourceFile = packageRoot.resolve(relativePath),
            line = 1,
            packageName = packageName,
        )

    private fun precedenceIndex(
        mobileDark: String? = null,
        mobileUnspecified: String? = null,
        desktopDark: String? = null,
        desktopUnspecified: String? = null,
        rootContext: DesignTokenContext = DARK_MOBILE,
    ): DesignTokenIndex {
        val definitions = mutableListOf(token(path(rootContext), ROOT, "var(--tui-target)"))

        mobileDark?.let { definitions.add(token("mobile/dark.css", "--tui-target", it)) }
        mobileUnspecified?.let { definitions.add(token("mobile/base.css", "--tui-target", it)) }
        desktopDark?.let { definitions.add(token("palette/dark.css", "--tui-target", it)) }
        desktopUnspecified?.let { definitions.add(token("palette/base.css", "--tui-target", it)) }

        return index(*definitions.toTypedArray())
    }

    private fun resolve(
        index: DesignTokenIndex,
        name: String,
        context: DesignTokenContext,
    ): DesignTokenVariantResolution {
        val variant = index.find(name).single { candidate -> candidate.context == context }

        return DesignTokenValueResolver(index).resolve(variant)
    }

    private fun resolved(resolution: DesignTokenVariantResolution): DesignTokenValueResolution.Resolved {
        assertTrue(resolution.result is DesignTokenValueResolution.Resolved)

        return resolution.result as DesignTokenValueResolution.Resolved
    }

    private fun unresolved(resolution: DesignTokenVariantResolution): DesignTokenValueResolution.Unresolved {
        assertTrue(resolution.result is DesignTokenValueResolution.Unresolved)

        return resolution.result as DesignTokenValueResolution.Unresolved
    }

    private fun index(vararg definitions: TokenDefinition): DesignTokenIndex =
        DesignTokenIndex.build(
            packageRoot = packageRoot,
            declarations =
                definitions.mapIndexed { index, definition ->
                    DesignTokenDeclaration(
                        name = definition.name,
                        value = definition.value,
                        sourceFile = packageRoot.resolve(definition.relativePath),
                        line = index + 1,
                    )
                },
        )

    private fun token(
        relativePath: String,
        name: String,
        value: String,
    ): TokenDefinition = TokenDefinition(relativePath, name, value)

    private fun path(context: DesignTokenContext): String =
        when (context) {
            DARK_MOBILE -> "mobile/dark.css"
            LIGHT_MOBILE -> "mobile/light.css"
            DARK_DESKTOP -> "palette/dark.css"
            LIGHT_DESKTOP -> "palette/light.css"
            DESKTOP -> "palette/base.css"
            else -> error("Unsupported test context: $context")
        }

    private data class TokenDefinition(
        val relativePath: String,
        val name: String,
        val value: String,
    )

    private companion object {
        const val ROOT = "--tui-root"

        val DESKTOP =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.UNSPECIFIED,
            )
        val LIGHT_DESKTOP =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.LIGHT,
            )
        val DARK_DESKTOP =
            DesignTokenContext(
                platform = DesignTokenPlatform.DESKTOP,
                theme = DesignTokenTheme.DARK,
            )
        val LIGHT_MOBILE =
            DesignTokenContext(
                platform = DesignTokenPlatform.MOBILE,
                theme = DesignTokenTheme.LIGHT,
            )
        val DARK_MOBILE =
            DesignTokenContext(
                platform = DesignTokenPlatform.MOBILE,
                theme = DesignTokenTheme.DARK,
            )
    }
}
