package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class TaigaDocumentationExamplePrettierCoverageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val sourceFile = Path.of("/project/src/example.component.html")
    private val code = """<button tuiButton [disabled]="busy">Save</button>"""

    @Test
    fun testFindsClosestProjectPrettierAndSupportsBothCliLayouts() {
        val project = temporaryFolder.newFolder("angular").toPath()
        val nestedSource = project.resolve("apps/demo/src/example.component.html")
        Files.createDirectories(nestedSource.parent)
        val modernCli = project.resolve("node_modules/prettier/bin/prettier.cjs")
        val legacyCli = project.resolve("node_modules/prettier/bin-prettier.js")
        Files.createDirectories(modernCli.parent)
        Files.writeString(modernCli, "")

        val commands = mutableListOf<List<String>>()
        val formatter =
            TaigaDocumentationExamplePrettier(
                run = { command, source ->
                    commands.add(command)
                    source
                },
            )
        assertEquals(code, formatter.format(code, nestedSource))
        assertEquals("node", commands.single()[0])
        assertEquals(modernCli.toString(), commands.single()[1])
        assertEquals(listOf("--parser", "angular"), commands.single().subList(2, 4))
        assertEquals(
            nestedSource.resolveSibling("TaigaExample.component.html").toString(),
            commands.single()[5],
        )

        Files.delete(modernCli)
        Files.writeString(legacyCli, "")
        val legacyFormatter =
            TaigaDocumentationExamplePrettier(
                run = { command, source ->
                    commands.add(command)
                    source
                },
            )
        assertEquals(code, legacyFormatter.format(code, nestedSource))
        assertEquals(legacyCli.toString(), commands.last()[1])
    }

    @Test
    fun testMissingProjectPrettierFallsBackWithoutStartingProcess() {
        val project = temporaryFolder.newFolder("without-prettier").toPath()
        val source = project.resolve("src/example.component.html")
        Files.createDirectories(source.parent)

        val formatter =
            TaigaDocumentationExamplePrettier(
                run = { _, _ -> error("A missing CLI must not be executed") },
            )

        assertEquals(formattedDocumentationExample(code), formatter.format(code, source))
    }

    @Test
    fun testRejectsBlankOversizedAndContentChangingOutput() {
        val unsafeOutputs: List<String?> =
            listOf(null, "", "   ", "x".repeat(12_001), code.replace("Save", "Saved"))

        unsafeOutputs.forEach { output ->
            val formatter =
                TaigaDocumentationExamplePrettier(
                    commandFor = { listOf("node", "prettier.cjs") },
                    run = { _, _ -> output },
                )

            assertEquals(formattedDocumentationExample(code), formatter.format(code, sourceFile))
        }
    }

    @Test
    fun testSkipsCommandLookupForInputsOverSafetyLimit() {
        val oversizedCode = "x".repeat(4_001)
        var commands = 0
        val formatter =
            TaigaDocumentationExamplePrettier(
                commandFor = {
                    commands++
                    listOf("node", "prettier.cjs")
                },
            )

        assertEquals(oversizedCode, formatter.format(oversizedCode, sourceFile))
        assertEquals(0, commands)
    }

    @Test
    fun testCacheIsBoundedAndOlderEntriesRecomputedAfterEviction() {
        var calls = 0
        val formatter =
            TaigaDocumentationExamplePrettier(
                commandFor = { listOf("node", "prettier.cjs") },
                run = { _, source ->
                    calls++
                    source
                },
            )

        repeat(129) { index ->
            val example = """<span data-i="$index">Label</span>"""
            assertEquals(example, formatter.format(example, sourceFile))
        }
        assertEquals(129, calls)

        val first = """<span data-i="0">Label</span>"""
        assertEquals(first, formatter.format(first, sourceFile))
        assertEquals(130, calls)
    }

    @Test
    fun testCacheKeysIncludeNormalizedDirectoryAndCliCommand() {
        var calls = 0
        val formatter =
            TaigaDocumentationExamplePrettier(
                commandFor = { path -> listOf("node", path.fileName.toString()) },
                run = { _, source ->
                    calls++
                    source
                },
            )
        val projectA = Path.of("/project/a/example.html")
        val projectB = Path.of("/project/b/example.html")

        assertEquals(code, formatter.format(code, projectA))
        assertEquals(code, formatter.format(code, projectA))
        assertEquals(code, formatter.format(code, projectB))
        assertTrue(calls == 2)
    }
}
