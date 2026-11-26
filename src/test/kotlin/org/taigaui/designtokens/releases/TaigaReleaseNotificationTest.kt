package org.taigaui.designtokens.releases

import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.openapi.components.service
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaReleaseNotificationTest : BasePlatformTestCase() {
    fun testReleaseGroupLogsToTheNotificationsToolWindowWithoutABalloon() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Taiga UI Releases")

        assertEquals(NotificationDisplayType.NONE, group.displayType)
        assertTrue(group.isLogByDefault)
    }

    fun testReleaseNotificationsAreDeduplicatedByMajorAndDescribePrereleases() {
        val notifications = project.service<TaigaReleaseNotifications>()
        val properties = PropertiesComponent.getInstance(project)
        val keys = listOf(4, 5).map { "org.taigaui.designtokens.lastNotifiedRelease.$it" }
        val previous = keys.associateWith(properties::getValue)
        val delivered = mutableListOf<Notification>()

        try {
            keys.forEach(properties::unsetValue)
            notifications.showRelease(version("5.34.0-rc.1"), version("5.34.0")) { delivered.add(it) }
            notifications.showRelease(version("5.33.0"), version("5.34.0")) { delivered.add(it) }
            notifications.showRelease(version("5.34.0"), version("5.35.0-rc.1")) { delivered.add(it) }
            notifications.showRelease(version("5.34.0"), version("4.110.0")) { delivered.add(it) }
            notifications.showRelease(version("4.109.0"), version("4.110.0")) { delivered.add(it) }
            notifications.showRelease(version("5.34.0"), version("5.35.0")) { delivered.add(it) }

            assertEquals(
                listOf(
                    "Taiga UI v5.34.0 is available",
                    "Taiga UI v4.110.0 is available",
                    "Taiga UI v5.35.0 is available",
                ),
                delivered.map { it.title },
            )
            assertTrue(delivered.first().content.contains("(pre-release)"))
            assertFalse(delivered.last().content.contains("(pre-release)"))
            assertEquals("v4.110.0", properties.getValue(keys[0]))
            assertEquals("v5.35.0", properties.getValue(keys[1]))
        } finally {
            delivered.forEach(Notification::expire)
            for ((key, value) in previous) {
                if (value == null) properties.unsetValue(key) else properties.setValue(key, value)
            }
        }
    }

    private fun version(value: String): TaigaReleaseVersion = requireNotNull(TaigaReleaseVersion.parse(value))
}
