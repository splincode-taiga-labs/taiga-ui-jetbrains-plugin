package org.taigaui.designtokens.project

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenProjectStylesheetGraphTest : BasePlatformTestCase() {
    fun testFollowsAngularGlobalStylesAndSassImportsWithoutScanningUnrelatedFiles() =
        withWorkspace { workspaceRoot ->
            val sourceFile = createFile(workspaceRoot.resolve("src/app/component.scss"), "color: var(--tui-test);")
            val stylesFile = createFile(workspaceRoot.resolve("src/styles.scss"), "@use './theme';")
            val themeFile =
                createFile(
                    workspaceRoot.resolve("src/_theme.scss"),
                    ":root { --tui-test: #123; }",
                )

            createFile(
                workspaceRoot.resolve("src/unrelated.scss"),
                ":root { --tui-test: hotpink; }",
            )
            createFile(
                workspaceRoot.resolve("angular.json"),
                """
                {
                  "projects": {
                    "demo": {
                      "architect": {
                        "build": {
                          "options": {
                            "styles": ["src/styles.scss"]
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

            val graph = DesignTokenProjectStylesheetGraph(project)
            val scope =
                graph.buildScope(
                    graph.createRequest(
                        sourceFile = sourceFile,
                        workspaceRootHint = workspaceRoot,
                    ),
                )

            assertEquals(
                setOf(sourceFile, stylesFile, themeFile).map { path -> path.normalized() }.toSet(),
                scope.sourceFiles.toSet(),
            )
            assertFalse(
                scope.sourceFiles.contains(workspaceRoot.resolve("src/unrelated.scss").normalized()),
            )
        }

    fun testSupportsNxProjectStylesPlusLessAndCssImports() =
        withWorkspace { workspaceRoot ->
            val projectRoot = workspaceRoot.resolve("apps/demo")
            val sourceFile = createFile(projectRoot.resolve("src/component.css"), "color: var(--tui-test);")
            val stylesFile =
                createFile(
                    projectRoot.resolve("src/styles.less"),
                    "@import (reference) './theme.less';",
                )
            val themeFile =
                createFile(
                    projectRoot.resolve("src/theme.less"),
                    "@import './palette.css';",
                )
            val paletteFile =
                createFile(
                    projectRoot.resolve("src/palette.css"),
                    ":root { --tui-test: #456; }",
                )

            createFile(
                projectRoot.resolve("project.json"),
                """
                {
                  "targets": {
                    "build": {
                      "options": {
                        "styles": ["apps/demo/src/styles.less"]
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

            val graph = DesignTokenProjectStylesheetGraph(project)
            val scope =
                graph.buildScope(
                    graph.createRequest(
                        sourceFile = sourceFile,
                        workspaceRootHint = workspaceRoot,
                    ),
                )

            assertEquals(projectRoot.normalized(), scope.projectRoot)
            assertEquals(
                setOf(sourceFile, stylesFile, themeFile, paletteFile).map { path -> path.normalized() }.toSet(),
                scope.sourceFiles.toSet(),
            )
        }

    fun testSupportsObjectStyleEntriesFromNxProjectConfiguration() =
        withWorkspace { workspaceRoot ->
            val projectRoot = workspaceRoot.resolve("apps/demo")
            val sourceFile = createFile(projectRoot.resolve("src/component.ts"), "export const demo = true;")
            val stylesFile =
                createFile(
                    projectRoot.resolve("src/styles.scss"),
                    ":root { --tui-test: #789; }",
                )

            createFile(workspaceRoot.resolve("nx.json"), "{}")
            createFile(
                projectRoot.resolve("project.json"),
                """
                {
                  "targets": {
                    "build": {
                      "options": {
                        "styles": [
                          {
                            "input": "apps/demo/src/styles.scss",
                            "inject": true
                          }
                        ]
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

            val graph = DesignTokenProjectStylesheetGraph(project)
            val scope =
                graph.buildScope(
                    graph.createRequest(
                        sourceFile = sourceFile,
                        workspaceRootHint = workspaceRoot,
                    ),
                )

            assertEquals(listOf(stylesFile.normalized()), scope.sourceFiles)
        }

    fun testReusesParsedImportsForUnchangedFilesAcrossGraphRebuilds() =
        withWorkspace { workspaceRoot ->
            val sourceFile =
                createFile(
                    workspaceRoot.resolve("src/app/component.scss"),
                    "color: var(--tui-test);",
                )
            val stylesFile =
                createFile(
                    workspaceRoot.resolve("src/styles.scss"),
                    "@use './theme';",
                )
            val themeFile =
                createFile(
                    workspaceRoot.resolve("src/_theme.scss"),
                    ":root { --tui-test: #123; }",
                )
            createFile(
                workspaceRoot.resolve("angular.json"),
                """
                {
                  "projects": {
                    "demo": {
                      "architect": {
                        "build": {
                          "options": {
                            "styles": ["src/styles.scss"]
                          }
                        }
                      }
                    }
                  }
                }
                """.trimIndent(),
            )

            val stamps =
                mutableMapOf(
                    sourceFile.normalized() to 1L,
                    stylesFile.normalized() to 1L,
                    themeFile.normalized() to 1L,
                )
            val reads = mutableMapOf<Path, Int>()
            val importCache =
                ProjectStylesheetImportCache(
                    readText = { path ->
                        val normalized = path.normalized()

                        reads[normalized] = reads.getOrDefault(normalized, 0) + 1
                        Files.readString(normalized)
                    },
                    modificationStampProvider =
                        ProjectStylesheetModificationStampProvider { path ->
                            stamps[path.normalized()]
                        },
                )
            val graph =
                DesignTokenProjectStylesheetGraph(
                    project = project,
                    imports = importCache::imports,
                )
            val request =
                graph.createRequest(
                    sourceFile = sourceFile,
                    workspaceRootHint = workspaceRoot,
                )

            graph.buildScope(request)
            graph.buildScope(request)

            assertEquals(1, reads[stylesFile.normalized()])
            assertEquals(1, reads[themeFile.normalized()])
            assertEquals(1, reads[sourceFile.normalized()])

            Files.writeString(themeFile, ":root { --tui-test: #456; }")
            stamps[themeFile.normalized()] = 2L
            importCache.invalidate(listOf(themeFile))

            graph.buildScope(request)

            assertEquals(1, reads[stylesFile.normalized()])
            assertEquals(2, reads[themeFile.normalized()])
            assertEquals(1, reads[sourceFile.normalized()])
        }

    fun testFollowsImportsFromTheCurrentStylesheet() =
        withWorkspace { workspaceRoot ->
            val sourceFile =
                createFile(
                    workspaceRoot.resolve("src/component.scss"),
                    "@forward './tokens';\ncolor: var(--tui-local);",
                )
            val importedFile =
                createFile(
                    workspaceRoot.resolve("src/_tokens.scss"),
                    ":root { --tui-local: tomato; }",
                )

            val graph = DesignTokenProjectStylesheetGraph(project)
            val scope =
                graph.buildScope(
                    graph.createRequest(
                        sourceFile = sourceFile,
                        workspaceRootHint = workspaceRoot,
                    ),
                )

            assertTrue(scope.sourceFiles.contains(importedFile.normalized()))
        }

    fun testDefaultWorkspaceHintAndScopeSkipInvalidAndDuplicateEntries() =
        withWorkspace { workspaceRoot ->
            val sourceFile =
                createFile(
                    workspaceRoot.resolve("src/component.css"),
                    ":root { --tui-test: red; }",
                )
            val nodeModulesFile =
                createFile(
                    workspaceRoot.resolve("node_modules/pkg/theme.css"),
                    ":root { --tui-test: blue; }",
                )
            val textFile = createFile(workspaceRoot.resolve("README.md"), "docs")
            val graph = DesignTokenProjectStylesheetGraph(project)

            val request = graph.createRequest(sourceFile, workspaceRoot)
            val scope =
                graph.buildScope(
                    request.copy(
                        entryFiles =
                            listOf(
                                sourceFile,
                                sourceFile,
                                nodeModulesFile,
                                textFile,
                            ),
                    ),
                )

            assertEquals(listOf(sourceFile.normalized()), scope.sourceFiles)
        }

    private fun withWorkspace(block: (Path) -> Unit) {
        val workspaceRoot = Files.createTempDirectory("project-stylesheet-graph")

        try {
            block(workspaceRoot)
        } finally {
            workspaceRoot.toFile().deleteRecursively()
        }
    }

    private fun createFile(
        path: Path,
        content: String,
    ): Path {
        Files.createDirectories(path.parent)
        Files.writeString(path, content)

        return path
    }

    private fun Path.normalized(): Path = toAbsolutePath().normalize()
}
