package org.taigaui.designtokens.diagnostics

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.profile.codeInspection.InspectionProjectProfileManager
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class TaigaDoctorInspectionProfileTest : BasePlatformTestCase() {
    fun testIncludesOnlyTheRegisteredTaigaInspections() =
        assertProfile(
            TaigaDoctorMode.APP,
            setOf(
                "UnknownTaigaUIDesignToken",
                "DeprecatedTaigaUIDesignToken",
                "UnknownTaigaUIEventModifier",
                "DuplicateTaigaUIEventModifier",
                "InvalidTaigaUIEventModifierInHostBinding",
                "WrongTaigaUIImport",
            ),
        )

    fun testMigrationProfileEnablesTheDefaultDisabledMigrationInspections() =
        assertProfile(
            TaigaDoctorMode.MIGRATION,
            setOf(
                "TaigaV5MigrationTodo",
                "TaigaV5LegacyImport",
                "DeprecatedTaigaUIDesignToken",
                "WrongTaigaUIImport",
            ),
        )

    private fun assertProfile(
        mode: TaigaDoctorMode,
        expected: Set<String>,
    ) {
        val previous = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true

        try {
            val manager = InspectionProjectProfileManager.getInstance(project)
            val current = manager.currentProfile
            val profile = taigaDoctorInspectionProfile(project, mode)
            val enabled = profile.getAllEnabledInspectionTools(project).map { it.tool.shortName }.toSet()

            assertEquals(expected, enabled)
            assertSame(current, manager.currentProfile)
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previous
        }
    }
}
