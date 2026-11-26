package org.taigaui.designtokens.documentation

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.taigaui.designtokens.mcp.resolvedApi

class TaigaQuickDocumentationMcpResponseTest : TaigaQuickDocumentationControllerTestCase() {
    fun testEntityResponseUsesInstalledAngularBindings() {
        open("<button tuiButton size=\"m\">Save</button>", "tuiButton")
        val data = resolvedApi(view().resolved)
        val inputs = requireNotNull(data["inputs"]).jsonArray.map { it.jsonObject }
        val names = inputs.map { it["name"]?.jsonPrimitive?.content }

        assertEquals("TuiButton", data["name"]?.jsonPrimitive?.content)
        assertEquals("@taiga-ui/core", data["package"]?.jsonPrimitive?.content)
        assertEquals("DIRECTIVE", data["kind"]?.jsonPrimitive?.content)
        assertEquals("true", data["installedBindings"]?.jsonPrimitive?.content)
        assertTrue(names.containsAll(listOf("size", "enabled", "baseInput", "iconEnd")))
        assertFalse(names.contains("internalSize"))
        assertTrue(requireNotNull(data["outputs"]).jsonArray.isEmpty())
    }

    fun testMemberResponsePreservesInstalledTypeAndRequiredFlag() {
        open("<button tuiButton size=\"m\">Save</button>", "size")
        val data = resolvedApi(view().resolved)
        val receiver = requireNotNull(data["receivers"]).jsonArray.single().jsonObject

        assertEquals("size", data["name"]?.jsonPrimitive?.content)
        assertEquals("INPUT", data["kind"]?.jsonPrimitive?.content)
        assertEquals("TuiButton", receiver["owner"]?.jsonPrimitive?.content)
        assertEquals("@taiga-ui/core", receiver["package"]?.jsonPrimitive?.content)
        assertEquals("true", receiver["required"]?.jsonPrimitive?.content)
        assertFalse(receiver["installedType"]?.jsonPrimitive?.content.isNullOrBlank())
    }

    fun testMemberResponseListsEveryAngularReceiver() {
        open("<button tuiButton tuiAux size=\"m\">Save</button>", "size")
        val data = resolvedApi(view().resolved)
        val receivers = requireNotNull(data["receivers"]).jsonArray.map { it.jsonObject }
        val owners = receivers.map { it["owner"]?.jsonPrimitive?.content }.toSet()

        assertEquals(setOf("TuiButton", "TuiAux"), owners)
        assertTrue(receivers.all { it["package"]?.jsonPrimitive?.content == "@taiga-ui/core" })
    }
}
