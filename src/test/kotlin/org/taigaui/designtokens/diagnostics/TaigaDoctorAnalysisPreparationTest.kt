package org.taigaui.designtokens.diagnostics

import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.testFramework.HeavyPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.PsiTestUtil
import com.intellij.util.concurrency.AppExecutorUtil
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable

class TaigaDoctorAnalysisPreparationTest : HeavyPlatformTestCase() {
    override fun isCreateDirectoryBasedProject(): Boolean = true

    fun testPreparesAnalysisForConfiguredAngularApplication() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val app = Files.createDirectories(path.resolve("apps/demo"))
        val ignored = Files.createDirectories(path.resolve("apps/ignored"))
        Files.writeString(
            path.resolve("angular.json"),
            """
            {
                "projects": {
                    "demo": {"root":"apps/demo"},
                    "invalid": {"root":42},
                    "missing": {},
                    "external": "../another-project"
                }
            }
            """.trimIndent(),
        )
        root.refresh(false, true)

        val previous = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            assertNotNull(prepare(app, TaigaDoctorMode.APP))
            assertNull(prepare(ignored, TaigaDoctorMode.APP))
            assertNull(prepare(app, TaigaDoctorMode.MIGRATION))
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previous
        }
    }

    fun testPreparesMigrationAnalysisOnlyForVersionFour() {
        val root = getOrCreateProjectBaseDir()
        PsiTestUtil.addContentRoot(module, root)
        val path = root.toNioPath()
        val cdk = Files.createDirectories(path.resolve("node_modules/@taiga-ui/cdk"))
        Files.writeString(cdk.resolve("package.json"), """{"name":"@taiga-ui/cdk","version":"4.110.0"}""")
        Files.writeString(path.resolve("package.json"), "{}")
        Files.writeString(path.resolve("angular.json"), """{"projects":{"demo":{"root":""}}}""")
        root.refresh(false, true)

        val previous = InspectionProfileImpl.INIT_INSPECTIONS
        InspectionProfileImpl.INIT_INSPECTIONS = true
        try {
            assertNotNull(prepare(path, TaigaDoctorMode.MIGRATION))
        } finally {
            InspectionProfileImpl.INIT_INSPECTIONS = previous
        }
    }

    private fun prepare(
        source: Path,
        mode: TaigaDoctorMode,
    ): TaigaDoctorAnalysis? =
        PlatformTestUtil.waitForFuture(
            AppExecutorUtil.getAppExecutorService().submit(
                Callable { runBlocking { prepareTaigaDoctorAnalysis(project, source, mode) } },
            ),
        )
}
