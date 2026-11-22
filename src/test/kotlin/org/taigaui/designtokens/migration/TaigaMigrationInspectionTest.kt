package org.taigaui.designtokens.migration

import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaMigrationInspectionTest : BasePlatformTestCase() {
    fun testFindsOnlySchematicCommentsInTypeScript() {
        myFixture.enableInspections(TaigaMigrationTodoInspection())

        val findings =
            inspect(
                "example.ts",
                """
                // TODO: a regular project task
                // TODO: (Taiga UI migration) Review the new dialog API.
                const note = '// TODO: (Taiga UI migration) This is a string';
                /*
                 * TODO: (Taiga UI migration) Check input behavior.
                 */
                """.trimIndent(),
                "Review this Taiga UI migration TODO",
            )

        assertEquals(List(2) { "TODO: (Taiga UI migration)" }, findings)
    }

    fun testFindsMigrationCommentsInTemplatesAndStyles() {
        myFixture.enableInspections(TaigaMigrationTodoInspection())

        assertEquals(
            listOf("TODO: (Taiga UI migration)"),
            inspect(
                "example.html",
                """
                <!-- TODO: (Taiga UI migration) Replace the old control. -->
                <p>TODO: (Taiga UI migration)</p>
                """.trimIndent(),
                "Review this Taiga UI migration TODO",
            ),
        )
        assertEquals(
            listOf("TODO: (Taiga UI migration)", "TODO: (Taiga UI migration)"),
            inspect(
                "example.css",
                """
                // TODO: (Taiga UI migration) Replace the old selector.
                /* TODO: (Taiga UI migration) Review the old token. */
                .example { content: "TODO: (Taiga UI migration)"; }
                .escaped { content: "a quoted value \
                // TODO: (Taiga UI migration)"; }
                """.trimIndent(),
                "Review this Taiga UI migration TODO",
            ),
        )
    }

    fun testFindsOnlyStaticImportsFromTheLegacyPackage() {
        myFixture.enableInspections(TaigaLegacyImportInspection())

        val findings =
            inspect(
                "example.ts",
                """
                import {TuiInputModule} from '@taiga-ui/legacy';
                import type {OldType} from '@taiga-ui/legacy/example';
                import {TuiButton} from '@taiga-ui/core';
                import {Example} from '@taiga-ui/legacy-example';
                const legacy = '@taiga-ui/legacy';
                """.trimIndent(),
                "Review this Taiga UI legacy import",
            )

        assertEquals(listOf("'@taiga-ui/legacy'", "'@taiga-ui/legacy/example'"), findings)
    }

    private fun inspect(
        filename: String,
        content: String,
        message: String,
    ): List<String> {
        myFixture.configureByText(filename, content)

        return myFixture
            .doHighlighting()
            .filter { it.description == message }
            .map { myFixture.file.text.substring(it.startOffset, it.endOffset) }
    }
}
