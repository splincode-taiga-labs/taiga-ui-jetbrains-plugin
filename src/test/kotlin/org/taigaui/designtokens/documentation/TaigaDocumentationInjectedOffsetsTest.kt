package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TaigaDocumentationInjectedOffsetsTest {
    private val binding =
        TaigaDocumentationBinding(
            name = "size",
            startOffset = 10,
            endOffset = 25,
            text = "size=\"m\"",
            valueStart = 6,
            valueEnd = 7,
            literal = "m",
            expression = false,
            context = listOf(TaigaBindingContext(2, 8, "button")),
        )
    private val icon = TaigaDocumentationIcon("iconStart", "@tui.eye", 30, 38)
    private val element =
        TaigaDocumentationElement(
            startOffset = 0,
            endOffset = 45,
            text = "<button size=\"m\">",
            attributes = listOf(TaigaDocumentationAttributeName("size", 10, 14)),
        )

    @Test
    fun `entity maps all host offsets but preserves installed declaration facts`() {
        val request =
            TaigaDocumentationRequest.Entity(
                subjects = emptyList(),
                startOffset = 1,
                endOffset = 45,
                usage = "<button>",
                bindings = listOf(binding),
                icons = listOf(icon),
                element = element,
            )

        val mapped = request.mapTemplateOffsets { it + 100 } as TaigaDocumentationRequest.Entity

        assertEquals(101, mapped.startOffset)
        assertEquals(145, mapped.endOffset)
        assertEquals("<button>", mapped.usage)
        assertEquals(110, mapped.bindings.single().startOffset)
        assertEquals(125, mapped.bindings.single().endOffset)
        assertEquals(102, mapped.bindings.single().context.single().startOffset)
        assertEquals(108, mapped.bindings.single().context.single().endOffset)
        assertEquals("button", mapped.bindings.single().context.single().text)
        assertEquals(130, mapped.icons.single().startOffset)
        assertEquals(138, mapped.icons.single().endOffset)
        assertEquals(100, mapped.element?.startOffset)
        assertEquals(145, mapped.element?.endOffset)
        assertEquals(110, mapped.element?.attributes?.single()?.startOffset)
        assertEquals(114, mapped.element?.attributes?.single()?.endOffset)
        assertEquals("size", mapped.element?.attributes?.single()?.text)

        assertEquals(10, request.bindings.single().startOffset)
        assertEquals(30, request.icons.single().startOffset)
        assertEquals(0, request.element?.startOffset)
    }

    @Test
    fun `member maps its selected binding and nested collections independently`() {
        val request =
            TaigaDocumentationRequest.Member(
                owners = emptyList(),
                name = "iconStart",
                kind = TaigaApiMemberKind.INPUT,
                startOffset = 30,
                endOffset = 38,
                binding = binding,
                bindings = listOf(binding),
                icons = listOf(icon),
                element = element,
            )

        val mapped = request.mapTemplateOffsets { it - 2 } as TaigaDocumentationRequest.Member

        assertEquals(28, mapped.startOffset)
        assertEquals(36, mapped.endOffset)
        assertEquals(8, mapped.binding?.startOffset)
        assertEquals(23, mapped.binding?.endOffset)
        assertEquals(0, mapped.binding?.context?.single()?.startOffset)
        assertEquals(6, mapped.bindings.single().context.single().endOffset)
        assertEquals(28, mapped.icons.single().startOffset)
        assertEquals(-2, mapped.element?.startOffset)
        assertEquals(8, mapped.element?.attributes?.single()?.startOffset)
    }

    @Test
    fun `optional member offsets remain absent when template context is missing`() {
        val request =
            TaigaDocumentationRequest.Member(
                owners = emptyList(),
                name = "size",
                kind = TaigaApiMemberKind.INPUT,
                startOffset = 1,
                endOffset = 2,
            )

        val mapped = request.mapTemplateOffsets { it * 10 } as TaigaDocumentationRequest.Member

        assertEquals(10, mapped.startOffset)
        assertEquals(20, mapped.endOffset)
        assertNull(mapped.binding)
        assertNull(mapped.element)
        assertEquals(emptyList<TaigaDocumentationBinding>(), mapped.bindings)
        assertEquals(emptyList<TaigaDocumentationIcon>(), mapped.icons)
    }
}
