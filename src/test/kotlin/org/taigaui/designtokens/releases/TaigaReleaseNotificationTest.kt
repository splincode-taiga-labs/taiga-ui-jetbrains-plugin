package org.taigaui.designtokens.releases

import com.intellij.notification.NotificationDisplayType
import com.intellij.notification.NotificationGroupManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaReleaseNotificationTest : BasePlatformTestCase() {
    fun testReleaseGroupLogsToTheNotificationsToolWindowWithoutABalloon() {
        val group = NotificationGroupManager.getInstance().getNotificationGroup("Taiga UI Releases")

        assertEquals(NotificationDisplayType.NONE, group.displayType)
        assertTrue(group.isLogByDefault)
    }
}
