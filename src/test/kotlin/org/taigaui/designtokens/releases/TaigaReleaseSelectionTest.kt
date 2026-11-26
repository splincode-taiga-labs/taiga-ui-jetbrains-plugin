package org.taigaui.designtokens.releases

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.util.concurrent.Callable

class TaigaReleaseSelectionTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testCheckSkipsWorkspaceWithoutInstalledRelease() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)

        checkRelease(
            latestFor = { error("Unexpected release lookup") },
            notify = { error("Unexpected notification") },
        )
    }

    fun testCheckUsesInstalledMajorAndSkipsUnavailableRelease() {
        installTaiga()

        var requestedMajor: Int? = null
        checkRelease(
            latestFor = {
                requestedMajor = it
                null
            },
            notify = { error("Unexpected notification") },
        )

        assertEquals(5, requestedMajor)
    }

    fun testCheckPublishesOnlyForTheCurrentSource() {
        val root = getOrCreateProjectBaseDir()
        installTaiga()
        Files.writeString(root.toNioPath().resolve("other.ts"), "export {};")
        root.refresh(false, true)
        val other = requireNotNull(root.findChild("other.ts"))
        val properties = PropertiesComponent.getInstance(project)
        val key = "org.taigaui.designtokens.lastNotifiedRelease.5"
        val previous = properties.getValue(key)
        val delivered = mutableListOf<Notification>()
        val available = requireNotNull(TaigaReleaseVersion.parse("5.35.0"))

        try {
            properties.unsetValue(key)
            checkRelease(
                latestFor = {
                    withContext(Dispatchers.EDT) {
                        FileEditorManager.getInstance(project).openFile(other, true)
                    }
                    available
                },
                notify = { delivered.add(it) },
            )
            assertTrue(delivered.isEmpty())
            assertNull(properties.getValue(key))

            checkRelease(
                latestFor = { available },
                notify = { delivered.add(it) },
            )
            assertEquals("Taiga UI v5.35.0 is available", delivered.single().title)
            assertEquals("v5.35.0", properties.getValue(key))
        } finally {
            FileEditorManager.getInstance(project).closeFile(other)
            delivered.forEach(Notification::expire)
            if (previous == null) properties.unsetValue(key) else properties.setValue(key, previous)
        }
    }

    private fun installTaiga() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val core = Files.createDirectories(path.resolve("node_modules/@taiga-ui/core"))
        Files.writeString(path.resolve("package.json"), "{}")
        Files.writeString(core.resolve("package.json"), """{"name":"@taiga-ui/core","version":"5.34.0"}""")
        root.refresh(false, true)
    }

    private fun checkRelease(
        latestFor: suspend (Int) -> TaigaReleaseVersion?,
        notify: (Notification) -> Unit,
    ) {
        val service = project.service<TaigaReleaseNotifications>()
        PlatformTestUtil.waitForFuture(
            AppExecutorUtil.getAppExecutorService().submit(
                Callable { runBlocking { service.checkRelease(latestFor, notify) } },
            ),
        )
    }
}
