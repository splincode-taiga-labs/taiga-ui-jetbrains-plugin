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
        val mappedBinding = mapped.bindings.single()
        val mappedContext = mappedBinding.context.single()
        val mappedIcon = mapped.icons.single()
        val mappedElement = requireNotNull(mapped.element)
        val mappedAttribute = mappedElement.attributes.single()

        assertEquals(101, mapped.startOffset)
        assertEquals(145, mapped.endOffset)
        assertEquals("<button>", mapped.usage)
        assertEquals(110, mappedBinding.startOffset)
        assertEquals(125, mappedBinding.endOffset)
        assertEquals(102, mappedContext.startOffset)
        assertEquals(108, mappedContext.endOffset)
        assertEquals("button", mappedContext.text)
        assertEquals(130, mappedIcon.startOffset)
        assertEquals(138, mappedIcon.endOffset)
        assertEquals(100, mappedElement.startOffset)
        assertEquals(145, mappedElement.endOffset)
        assertEquals(110, mappedAttribute.startOffset)
        assertEquals(114, mappedAttribute.endOffset)
        assertEquals("size", mappedAttribute.text)

        assertEquals(10, binding.startOffset)
        assertEquals(30, icon.startOffset)
        assertEquals(0, element.startOffset)
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
        val mappedBinding = requireNotNull(mapped.binding)
        val mappedContext = mappedBinding.context.single()
        val mappedCollectionBinding = mapped.bindings.single()
        val mappedCollectionContext = mappedCollectionBinding.context.single()
        val mappedIcon = mapped.icons.single()
        val mappedElement = requireNotNull(mapped.element)
        val mappedAttribute = mappedElement.attributes.single()

        assertEquals(28, mapped.startOffset)
        assertEquals(36, mapped.endOffset)
        assertEquals(8, mappedBinding.startOffset)
        assertEquals(23, mappedBinding.endOffset)
        assertEquals(0, mappedContext.startOffset)
        assertEquals(6, mappedCollectionContext.endOffset)
        assertEquals(28, mappedIcon.startOffset)
        assertEquals(-2, mappedElement.startOffset)
        assertEquals(8, mappedAttribute.startOffset)
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
