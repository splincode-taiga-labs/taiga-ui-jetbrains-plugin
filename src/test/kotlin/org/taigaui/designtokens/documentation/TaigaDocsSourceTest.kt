package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaigaDocsSourceTest {
    @Test
    fun resolvesVersionedV4DocumentationSource() {
        val source = requireNotNull(TaigaDocsSources.forMajor(4))

        assertEquals(4, source.majorVersion)
        assertEquals("v4", source.cacheKey)
        assertEquals("https://taiga-ui.dev/v4/llms-full.txt", source.contentUri.toString())
        assertEquals(source, source.copy())
        assertEquals(source.hashCode(), source.copy().hashCode())
        assertEquals(source.majorVersion, source.component1())
        assertEquals(
            "https://taiga-ui.dev/v4/components/button",
            source.documentationUri("/components/button").toString(),
        )
    }

    @Test
    fun resolvesCurrentV5DocumentationSource() {
        val source = requireNotNull(TaigaDocsSources.forMajor(5))

        assertEquals("https://taiga-ui.dev/llms-full.txt", source.contentUri.toString())
        assertEquals(
            "https://taiga-ui.dev/components/button",
            source.documentationUri("components/button").toString(),
        )
    }

    @Test
    fun doesNotGuessDocumentationSourceForUnsupportedMajor() {
        assertNull(TaigaDocsSources.forMajor(3))
        assertNull(TaigaDocsSources.forMajor(6))
    }
}
