package org.taigaui.designtokens.diagnostics

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaDoctorInspectionProfileTest : BasePlatformTestCase() {
    fun testIncludesOnlyTheRegisteredTaigaInspections() {
        val previous = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true

        try {
            val manager = InspectionProjectProfileManager.getInstance(project)
            val current = manager.currentProfile
            val profile = taigaDoctorInspectionProfile(project)
            val enabled = profile.getAllEnabledInspectionTools(project).map { it.tool.shortName }.toSet()

            assertEquals(
                setOf(
                    "UnknownTaigaUIDesignToken",
                    "DeprecatedTaigaUIDesignToken",
                    "UnknownTaigaUIEventModifier",
                    "DuplicateTaigaUIEventModifier",
                    "InvalidTaigaUIEventModifierInHostBinding",
                    "WrongTaigaUIImport",
                ),
                enabled,
            )
            assertSame(current, manager.currentProfile)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previous
        }
    }
}
