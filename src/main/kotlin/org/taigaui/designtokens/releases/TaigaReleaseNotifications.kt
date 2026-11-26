package org.taigaui.designtokens.releases

import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.taigaui.designtokens.diagnostics.TaigaProjectDoctor
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal class TaigaReleaseStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        if (!ApplicationManager.getApplication().isHeadlessEnvironment) {
            project.service<TaigaReleaseNotifications>().start()
        }
    }
}

internal class TaigaReleaseEditorListener : FileEditorManagerListener {
    override fun selectionChanged(event: FileEditorManagerEvent) {
        event.manager.project
            .service<TaigaReleaseNotifications>()
            .sourceChanged()
    }
}

@Service(Service.Level.PROJECT)
internal class TaigaReleaseNotifications(
    private val project: Project,
    private val scope: CoroutineScope,
) {
    private val changes = Channel<Unit>(Channel.CONFLATED)
    private val doctor = TaigaProjectDoctor()

    fun start() {
        scope.launch(Dispatchers.IO) {
            delay(5.seconds)

            while (isActive) {
                checkRelease()
                withTimeoutOrNull(RELEASE_CHECK_INTERVAL) { changes.receive() }
                delay(500.milliseconds)
                changes.tryReceive()
            }
        }
    }

    fun sourceChanged() {
        changes.trySend(Unit)
    }

    private suspend fun checkRelease() {
        val source = withContext(Dispatchers.EDT) { selectedSource() }
        val installed =
            source
                ?.let(doctor::inspect)
                ?.reference
                ?.version
                ?.let(TaigaReleaseVersion::parse) ?: return
        val available = service<TaigaReleaseFeed>().latestFor(installed.major) ?: return

        withContext(Dispatchers.EDT) {
            if (!project.isDisposed && source == selectedSource()) {
                showRelease(installed, available)
            }
        }
    }

    private fun selectedSource(): Path? {
        val file = FileEditorManager.getInstance(project).selectedFiles.firstOrNull()

        return file?.takeIf { it.isInLocalFileSystem }?.path?.let(Path::of) ?: project.basePath?.let(Path::of)
    }

    internal fun showRelease(
        installed: TaigaReleaseVersion,
        available: TaigaReleaseVersion,
        notify: (Notification) -> Unit = { it.notify(project) },
    ) {
        val properties = PropertiesComponent.getInstance(project)
        val key = "org.taigaui.designtokens.lastNotifiedRelease.${available.major}"
        val lastNotified = properties.getValue(key)?.let(TaigaReleaseVersion::parse)

        if (shouldShowRelease(installed, available, lastNotified)) {
            val notification =
                Notification(
                    "Taiga UI Releases",
                    "Taiga UI ${available.tag} is available",
                    "This workspace uses Taiga UI ${installed.tag}${if (installed.stable) "" else " (pre-release)"}.",
                    NotificationType.INFORMATION,
                )

            notification.addAction(
                NotificationAction.createSimple("View release on GitHub") {
                    BrowserUtil.browse(available.url)
                },
            )
            notify(notification)
            properties.setValue(key, available.tag)
        }
    }
}
