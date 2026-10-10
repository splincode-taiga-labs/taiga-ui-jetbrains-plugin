package org.taigaui.designtokens

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginSmokeTest {
    @Test
    fun `plugin descriptor is available`() {
        val descriptorUrl = javaClass.classLoader.getResource("META-INF/plugin.xml")

        assertNotNull("META-INF/plugin.xml must be available on the test classpath", descriptorUrl)

        val descriptor = descriptorUrl!!.readText()

        assertTrue(descriptor.contains("<id>org.taigaui.designtokens</id>"))
        assertTrue(descriptor.contains("<name>Taiga UI Companion</name>"))
        assertTrue(
            descriptor.contains(
                "<platform.backend.documentation.targetProvider",
            ),
        )
        assertTrue(
            descriptor.contains(
                "<platform.backend.documentation.lookupElementTargetProvider",
            ),
        )
        assertTrue(
            descriptor.contains(
                "org.taigaui.designtokens.documentation.TaigaQuickDocumentationTargetProvider",
            ),
        )
        assertTrue(
            descriptor.contains(
                "org.taigaui.designtokens.documentation.TaigaQuickDocumentationHoverPopupListener",
            ),
        )
    }
}
