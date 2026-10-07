package org.taigaui.designtokens.completion

import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DesignTokenCustomPropertyPreviewTest : BasePlatformTestCase() {
    fun testBuildsPreviewFromLookupPsiElementAndChoosesNearestDeclaration() {
        val token = "--tui-custom"
        myFixture.configureByText(
            "styles.css",
            """
            :root {
                $token: red;
            }

            .near {
                $token: calc(1rem + 2px);
                color: var($token);
            }
            """.trimIndent(),
        )
        val usageOffset = myFixture.file.text.lastIndexOf(token)
        val element = requireNotNull(myFixture.file.findElementAt(usageOffset))
        val lookup = LookupElementBuilder.create(element, token)

        val model = lookup.toCustomPropertyPreviewModel(token, project)
        val row =
            requireNotNull(model)
                .sections
                .single()
                .rows
                .single()

        assertEquals(token, model.tokenName)
        assertEquals("Project custom property", model.sections.single().packageName)
        assertEquals("calc(1rem + 2px)", row.resolvedValue)
        assertTrue(row.platform.startsWith("styles.css:"))
        val navigationTarget = requireNotNull(row.navigationTarget)

        assertTrue(navigationTarget.line > 1)
    }

    fun testFallsBackToProjectSearchAndSortsDeclarations() {
        val token = "--tui-project-search"
        myFixture.addFileToProject(
            "styles/z.scss",
            """
            :root {
                $token: 2rem;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "styles/a.css",
            """
            :root {
                $token: #fff;
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "styles/ignored.txt",
            "$token: ignored;",
        )
        val lookup = LookupElementBuilder.create(token)

        val model = lookup.toCustomPropertyPreviewModel(token, project)
        val rows = requireNotNull(model).sections.single().rows

        assertEquals(2, rows.size)
        assertEquals(listOf("#fff", "2rem"), rows.map { row -> row.resolvedValue })
        assertTrue(rows.all { row -> row.navigationTarget != null })
    }

    fun testProjectSearchSortsSameFileDeclarationsByLine() {
        val token = "--tui-same-file"
        myFixture.addFileToProject(
            "styles/repeated.css",
            """
            :root {
                $token: red;
            }

            .dark {
                $token: blue;
            }
            """.trimIndent(),
        )
        val model =
            LookupElementBuilder
                .create(token)
                .toCustomPropertyPreviewModel(token, project)
        val rows = requireNotNull(model).sections.single().rows

        assertEquals(listOf("red", "blue"), rows.map { row -> row.resolvedValue })
    }

    fun testReturnsNullWhenLookupAndProjectContainNoDeclaration() {
        val lookup = LookupElementBuilder.create("--tui-missing")

        assertNull(lookup.toCustomPropertyPreviewModel("--tui-missing", project))
    }

    fun testProjectCustomPropertyRowUsesFallbackLabelWithoutNavigation() {
        val declarationType =
            Class.forName(
                "org.taigaui.designtokens.completion.ProjectCustomPropertyDeclaration",
            )
        val declaration =
            declarationType.declaredConstructors
                .single()
                .apply { isAccessible = true }
                .newInstance("1rem", null, null)
        val supportType =
            Class.forName(
                "org.taigaui.designtokens.completion.DesignTokenCustomPropertyPreviewKt",
            )
        val row =
            supportType.declaredMethods
                .single { method ->
                    method.name == "toHoverValueRow" &&
                        method.parameterCount == 1
                }.apply { isAccessible = true }
                .invoke(null, declaration)

        val platform =
            row.javaClass
                .getMethod("getPlatform")
                .invoke(row) as String
        val resolvedValue =
            row.javaClass
                .getMethod("getResolvedValue")
                .invoke(row) as String
        val navigationTarget =
            row.javaClass
                .getMethod("getNavigationTarget")
                .invoke(row)

        assertEquals("Project styles", platform)
        assertEquals("1rem", resolvedValue)
        assertNull(navigationTarget)

        val withoutLine =
            declarationType.declaredConstructors
                .single()
                .apply { isAccessible = true }
                .newInstance("2rem", java.nio.file.Path.of("styles.css"), null)
        val rowWithoutLine =
            supportType.declaredMethods
                .single { method ->
                    method.name == "toHoverValueRow" &&
                        method.parameterCount == 1
                }
                .apply { isAccessible = true }
                .invoke(null, withoutLine)
        val targetWithoutLine =
            rowWithoutLine.javaClass
                .getMethod("getNavigationTarget")
                .invoke(rowWithoutLine)

        assertNull(targetWithoutLine)
    }

    fun testExtractCustomPropertyValueRejectsEmptyAndNestedBlocks() {
        assertNull(extractCustomPropertyValue(":root { --tui-empty: ; }", "--tui-empty"))
        assertNull(extractCustomPropertyValue(":root { color: red; }", "--tui-absent"))
        assertNull(
            extractCustomPropertyValue(
                ":root { color: red; } .next { --tui-empty: ; }",
                "--tui-empty",
            ),
        )
        assertEquals(
            "rgb(1 2 3 / 50%)",
            extractCustomPropertyValue(
                ":root { --tui-color: rgb(1 2 3 / 50%); }",
                "--tui-color",
            ),
        )
    }
}
