package org.taigaui.designtokens.documentation

import com.intellij.ide.actions.searcheverywhere.FoundItemDescriptor
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.Presentation
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.EmptyProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.SimpleColoredComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.taigaui.designtokens.documentation.discovery.TaigaApiCatalog
import org.taigaui.designtokens.documentation.discovery.TaigaApiEntry
import org.taigaui.designtokens.documentation.discovery.TaigaSearchEverywhereContributorFactory
import org.taigaui.designtokens.documentation.discovery.taigaApiSource
import java.lang.reflect.Proxy
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JList

class TaigaDocsServiceCoverageTest : BasePlatformTestCase() {
    fun testDiscoverySwitchesBetweenV4AndV5UsingTheActiveEditor() =
        runBlocking {
            val workspace = Files.createTempDirectory("taiga-docs-mixed")
            val cache = TaigaDocsCache()
            val v4Source = requireNotNull(TaigaDocsSources.forMajor(4))
            val v5Source = requireNotNull(TaigaDocsSources.forMajor(5))
            val cachedV4 = cache.read(v4Source)
            val cachedV5 = cache.read(v5Source)
            val service = project.service<TaigaDocsService>()

            try {
                assertTrue(cache.write(v4Source, discoveryDocs("Legacy Input", "LEGACY")))
                assertTrue(cache.write(v5Source, discoveryDocs("Input Chip", "KIT")))
                service.clearMemory()
                val v4 = createApp(workspace.resolve("apps/legacy"), "4.60.0", "legacy")
                val v5 = createApp(workspace.resolve("apps/current"), "5.18.0", "kit")

                assertDiscovery(v4, v5, "4.60.0", "https://taiga-ui.dev/v4/", "legacy-input")
                assertDiscovery(v5, v4, "5.18.0", "https://taiga-ui.dev/", "input-chip")
                assertDiscovery(v4, v5, "4.60.0", "https://taiga-ui.dev/v4/", "legacy-input")
            } finally {
                val editors = FileEditorManager.getInstance(project)
                editors.openFiles.forEach { editors.closeFile(it) }
                service.clearMemory()
                restoreDocs(cache, v4Source, cachedV4)
                restoreDocs(cache, v5Source, cachedV5)
                workspace.toFile().deleteRecursively()
            }
        }

    fun testSearchEverywhereShowsDedicatedTabAndHandlesMissingDocumentation() =
        runBlocking {
            val factory = TaigaSearchEverywhereContributorFactory()
            val context = SimpleDataContext.builder().add(CommonDataKeys.PROJECT, project).build()
            val event = AnActionEvent.createEvent(context, Presentation(), "SearchEverywhere", ActionUiKind.NONE, null)
            val contributor = factory.createContributor(event)

            try {
                assertFalse(factory.isAvailable(null))
                assertTrue(factory.isAvailable(project))
                assertEquals("TaigaApiSearch", contributor.searchProviderId)
                assertEquals("Taiga UI", contributor.groupName)
                assertEquals(1_000, contributor.sortWeight)
                assertTrue(contributor.isDumbAware)
                assertTrue(contributor.isShownInSeparateTab)
                assertFalse(contributor.showInFindResults())

                withContext(Dispatchers.Default) {
                    assertEmpty(contributor.search(" ", EmptyProgressIndicator()))
                    assertEmpty(contributor.search("Input", EmptyProgressIndicator()))
                }
            } finally {
                contributor.dispose()
            }
        }

    fun testDiscoveryUsesProjectRootWithoutAnActiveEditor() {
        assertEmpty(FileEditorManager.getInstance(project).selectedFiles)
        assertEquals(Path.of(requireNotNull(project.basePath)), taigaApiSource(project))
    }

    fun testStandaloneSourceHasNoDocumentationSnapshotOrRefresh() =
        runBlocking {
            val root = Files.createTempDirectory("taiga-docs-service")

            try {
                val source = root.resolve("src/component.ts")

                Files.createDirectories(source.parent)
                Files.writeString(source, "export class Example {}")

                val service = project.service<TaigaDocsService>()

                assertNull(service.snapshotFor(source))
                assertNull(service.refresh(source))
                assertNull(service.cachedIndexFor(3))
                assertNull(service.cachedIndexFor(6))

                service.invalidate(3)
                service.invalidate(5, removeDiskCache = true)
                service.invalidate(6, removeDiskCache = true)
                service.clearMemory()
                service.warmUp(source)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    fun testWarmUpReturnsImmediatelyForDisposedProject() {
        val disposedProject =
            Proxy.newProxyInstance(
                Project::class.java.classLoader,
                arrayOf(Project::class.java),
            ) { proxy, method, arguments ->
                when (method.name) {
                    "isDisposed" -> true
                    "toString" -> "DisposedProject"
                    "hashCode" -> System.identityHashCode(proxy)
                    "equals" -> proxy === arguments?.firstOrNull()
                    else -> null
                }
            } as Project
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        try {
            TaigaDocsService(disposedProject, scope)
                .warmUp(Files.createTempDirectory("disposed-docs").resolve("component.ts"))
        } finally {
            scope.cancel()
        }
    }

    private fun createApp(
        root: Path,
        version: String,
        extraPackage: String,
    ): VirtualFile {
        listOf("core", extraPackage).forEach { name ->
            val packageRoot = Files.createDirectories(root.resolve("node_modules/@taiga-ui/$name"))
            val manifest = "{\"name\":\"@taiga-ui/$name\",\"version\":\"$version\"}"

            Files.writeString(packageRoot.resolve("package.json"), manifest)
        }
        val src = Files.createDirectories(root.resolve("src"))
        val file = Files.writeString(src.resolve("app.ts"), "export class App {}")

        return requireNotNull(LocalFileSystem.getInstance().refreshAndFindFileByNioFile(file))
    }

    private suspend fun assertDiscovery(
        active: VirtualFile,
        projectTreeSelection: VirtualFile,
        version: String,
        baseUrl: String,
        extraSlug: String,
    ) {
        myFixture.openFileInEditor(active)
        val context =
            SimpleDataContext
                .builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.VIRTUAL_FILE, projectTreeSelection)
                .build()
        val event = AnActionEvent.createEvent(context, Presentation(), "SearchEverywhere", ActionUiKind.NONE, null)
        val contributor = TaigaSearchEverywhereContributorFactory().createContributor(event)

        try {
            val source = requireNotNull(taigaApiSource(project))
            assertEquals(Path.of(active.path), source)

            val catalog = requireNotNull(TaigaApiCatalog.load(project, source))
            assertEquals(version, catalog.version)

            val sidebarResults = catalog.search("Input").map { it.entry.entity.documentationUri }
            val searchResults =
                withContext(Dispatchers.Default) {
                    val results = contributor.search("Input", EmptyProgressIndicator())

                    results.map { it.entity.documentationUri }
                }
            val expected =
                listOf(
                    URI.create("${baseUrl}components/input"),
                    URI.create("${baseUrl}components/$extraSlug"),
                )

            assertEquals(expected, sidebarResults)
            assertEquals(expected, searchResults)

            val first =
                withContext(Dispatchers.Default) {
                    val results = mutableListOf<FoundItemDescriptor<TaigaApiEntry>>()

                    contributor.fetchWeightedElements("Input", EmptyProgressIndicator()) { result ->
                        results.add(result)
                        false
                    }
                    results.single().item
                }
            val entryWithSummary = first.copy(entity = first.entity.copy(description = "Text entry"))
            val renderer =
                contributor.elementsRenderer
                    .getListCellRendererComponent(JList<TaigaApiEntry>(), entryWithSummary, 0, true, false)
                    as SimpleColoredComponent

            assertEquals(expected.first(), first.entity.documentationUri)
            assertEquals("Input  Component · @taiga-ui/core  Text entry", renderer.getCharSequence(false).toString())
        } finally {
            contributor.dispose()
        }
    }

    private fun discoveryDocs(
        extraTitle: String,
        extraPackage: String,
    ): String =
        """
        # components/Input
        - **Package**: CORE

        # components/$extraTitle
        - **Package**: $extraPackage
        """.trimIndent()

    private fun restoreDocs(
        cache: TaigaDocsCache,
        source: TaigaDocsSource,
        original: String?,
    ) {
        if (original == null) {
            cache.invalidate(source)
        } else {
            cache.write(source, original)
        }
    }
}
