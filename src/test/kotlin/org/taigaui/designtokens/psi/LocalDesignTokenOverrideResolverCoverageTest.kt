package org.taigaui.designtokens.psi

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Path

class LocalDesignTokenOverrideResolverCoverageTest : BasePlatformTestCase() {
    fun testReturnsEmptyOutsideSelectorContext() {
        myFixture.configureByText("styles.css", "/* no selector context */")

        assertEmpty(
            LocalDesignTokenOverrideResolver(project).resolve(
                psiFile = myFixture.file,
                sourceFile = Path.of("styles.css"),
                referenceOffset = 0,
            ),
        )
    }

    fun testNestedDeclarationDoesNotApplyToShallowerUsage() {
        val token = "--tui-local"
        myFixture.configureByText(
            "styles.less",
            """
            .outer {
                .inner {
                    $token: red;
                }

                color: var($token);
            }
            """.trimIndent(),
        )
        val referenceOffset = myFixture.file.text.lastIndexOf(token)

        assertEmpty(
            LocalDesignTokenOverrideResolver(project).resolve(
                psiFile = myFixture.file,
                sourceFile = Path.of("styles.less"),
                referenceOffset = referenceOffset,
            ),
        )
    }
}
