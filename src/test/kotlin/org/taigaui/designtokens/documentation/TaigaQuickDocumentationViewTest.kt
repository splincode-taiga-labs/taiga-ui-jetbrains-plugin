package org.taigaui.designtokens.documentation

import junit.framework.TestCase
import java.awt.Point
import java.net.URI
import java.nio.file.Path

class TaigaQuickDocumentationViewTest : TestCase() {
    fun testRefreshKeepsTheBrowsedMemberAndReplacesItsInstalledBindingAndType() {
        val old = owner("'primary' | 'secondary'", "primary")
        val current = owner("'primary' | 'danger'", "danger")
        val view =
            TaigaDocumentationView(
                old.focusedMember(old.entity.inputs.last(), TaigaApiMemberKind.INPUT),
                scrollPosition = Point(0, 100),
            )
        val originalTarget = current.focusedMember(current.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val refreshed = requireNotNull(view.refreshed(originalTarget, null))
        val member = refreshed.resolved as TaigaResolvedDocumentation.Member

        assertEquals("appearance", member.property.name)
        assertEquals("'primary' | 'danger'", member.typeText)
        assertEquals("danger", member.binding?.literal)
        assertEquals(listOf("primary", "danger"), member.localValues())
        assertEquals(Point(0, 100), refreshed.scrollPosition)
        assertNotSame(view.resolved, member)
    }

    fun testRefreshKeepsOwnerSearchAndSelectionWithFreshInstalledApi() {
        val old = owner("'primary' | 'secondary'", "primary")
        val current = owner("'danger'", "danger")
        val view =
            TaigaDocumentationView(
                old,
                showExample = true,
                fullApi = true,
                query = "appearance",
                scrollPosition = Point(0, 80),
                selectedMember = "INPUT:appearance",
            )
        val target = current.focusedMember(current.entity.inputs.first(), TaigaApiMemberKind.INPUT)
        val refreshed = requireNotNull(view.refreshed(target, null))

        assertTrue(refreshed.fullApi)
        assertTrue(refreshed.showExample)
        assertEquals("appearance", refreshed.query)
        assertEquals("INPUT:appearance", refreshed.selectedMember)
        assertEquals(Point(0, 80), refreshed.scrollPosition)
        assertEquals("'danger'", refreshed.resolved.entity.inputs.last().documentedType)
    }

    fun testRemovedMembersAndReceiversCannotReturnAnOldEditableSnapshot() {
        val old = owner("'primary'", "primary")
        val view = TaigaDocumentationView(old.focusedMember(old.entity.inputs.last(), TaigaApiMemberKind.INPUT))
        val current = owner("'danger'", "danger")
        val withoutAppearance =
            current.copy(
                subject =
                    current.subject.copy(
                        localDocumentation = current.subject.localDocumentation.copy(members = emptyList()),
                    ),
                contextSubjects = emptyList(),
            )
        assertNull(view.refreshed(withoutAppearance, null))
        val otherReceiver =
            current.copy(subject = current.subject.copy(publicSymbol = "TuiOther"), contextSubjects = emptyList())
        assertNull(TaigaDocumentationView(old).refreshed(otherReceiver, null))
    }

    private fun owner(
        appearanceType: String,
        appearance: String,
    ): TaigaResolvedDocumentation.Entity {
        val declaration =
            TaigaDocumentationSubject(
                "tuiButton",
                "TuiButton",
                "@taiga-ui/core",
                TaigaLocalDocumentation(
                    source = TaigaDocumentationSource(Path.of("node_modules/@taiga-ui/core/index.d.ts"), 0),
                    angularResolved = true,
                    kind = TaigaDocKind.DIRECTIVE,
                ),
            )
        val types = linkedMapOf("size" to "'s' | 'm'", "appearance" to appearanceType)
        val subject =
            declaration.copy(
                localDocumentation =
                    declaration.localDocumentation.copy(
                        inputTypes = types,
                        inputValues = types.mapValues { finiteStringValues(it.value) },
                        members =
                            types.map { (name, type) ->
                                TaigaLocalApiMember(name, TaigaApiMemberKind.INPUT, type, declaration)
                            },
                    ),
            )
        return TaigaResolvedDocumentation.Entity(
            TaigaEntityDoc(
                "local/TuiButton",
                "Button",
                setOf("@taiga-ui/core"),
                TaigaDocKind.DIRECTIVE,
                null,
                null,
                setOf("TuiButton"),
                setOf("tuiButton"),
                types.map { (name, type) -> TaigaApiProperty(name, "[$name]", type, null) },
                emptyList(),
                null,
                URI.create("https://taiga-ui.dev"),
            ),
            subject,
            0,
            9,
            null,
            null,
            bindings = listOf(TaigaDocumentationBinding("appearance", 0, 8, "appearance", 0, 8, appearance, false)),
            contextSubjects = listOf(subject),
        )
    }
}
