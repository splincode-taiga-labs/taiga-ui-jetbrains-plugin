package org.taigaui.designtokens.documentation

import com.intellij.openapi.components.service
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import org.taigaui.designtokens.project.DesignTokenIndexService
import java.awt.Point
import java.nio.file.Files
import java.nio.file.Path

class DesignTokenHoverPopupResolutionCoverageTest : BasePlatformTestCase() {
    private lateinit var tempRoot: Path
    private lateinit var indexService: DesignTokenIndexService

    override fun setUp() {
        super.setUp()
        tempRoot = Files.createTempDirectory("hover-resolution")
        indexService = project.service()
        indexService.clear()
    }

    override fun tearDown() {
        try {
            indexService.clear()
            tempRoot.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    fun testResolvesPopupTargetAndDataForProjectToken() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
                --tui-text-secondary: var(--tui-text-primary);
            }

            .demo {
                color: var(--tui-text-secondary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val tokenName = "--tui-text-secondary"
        val offset = editor.document.text.lastIndexOf(tokenName)
        val reference =
            DesignTokenReferenceAtOffset(
                name = tokenName,
                startOffset = offset,
                endOffset = offset + tokenName.length,
            )
        val request = createRequest(editor, reference, editor.document.modificationStamp)
        val target = requireNotNull(resolveTarget(request))
        val popupData = resolveData(target)
        val model =
            requireNotNull(
                readField(popupData, "model") as? DesignTokenHoverPopupModel,
            )

        assertEquals(tokenName, model.tokenName)
        assertTrue(model.sections.isNotEmpty())
        assertTrue(
            model.sections
                .flatMap(DesignTokenHoverPackageSection::rows)
                .any { row -> row.resolvedValue.contains("#ff0000") },
        )
    }

    fun testMissingTokenBuildsNotFoundPopupDataWithSuggestions() {
        configureCss(
            """
            :root {
                --tui-text-primary: #ff0000;
            }

            .demo {
                color: var(--tui-text-primary);
            }
            """.trimIndent(),
        )
        val editor = myFixture.editor
        val offset = editor.document.text.lastIndexOf("--tui-text-primary")
        val missing = "--tui-text-primari"
        val reference =
            DesignTokenReferenceAtOffset(
                name = missing,
                startOffset = offset,
                endOffset = offset + missing.length,
            )
        val target =
            requireNotNull(
                resolveTarget(
                    createRequest(
                        editor,
                        reference,
                        editor.document.modificationStamp,
                    ),
                ),
            )
        val popupData = resolveData(target)
        val model =
            requireNotNull(
                readField(popupData, "model") as? DesignTokenHoverPopupModel,
            )

        assertEquals(missing, model.tokenName)
        assertTrue(model.sections.isNotEmpty())
    }

    fun testRejectsNullAndStaleHoverRequests() {
        configureCss(".demo { color: var(--tui-text-primary); }")
        val editor = myFixture.editor
        val tokenName = "--tui-text-primary"
        val offset = editor.document.text.indexOf(tokenName)
        val reference =
            DesignTokenReferenceAtOffset(
                name = tokenName,
                startOffset = offset,
                endOffset = offset + tokenName.length,
            )

        assertNull(
            resolveTarget(
                createRequest(
                    editor,
                    null,
                    editor.document.modificationStamp,
                ),
            ),
        )
        assertNull(
            resolveTarget(
                createRequest(
                    editor,
                    reference,
                    editor.document.modificationStamp - 1,
                ),
            ),
        )
    }

    private fun configureCss(content: String) {
        val path = tempRoot.resolve("component.css")

        Files.writeString(path, content)

        val file =
            requireNotNull(
                LocalFileSystem
                    .getInstance()
                    .refreshAndFindFileByNioFile(path),
            )

        myFixture.configureFromExistingVirtualFile(file)
    }

    private fun createRequest(
        editor: Editor,
        reference: DesignTokenReferenceAtOffset?,
        modificationStamp: Long,
    ): Any {
        val requestType = resolveTargetMethod.parameterTypes[0]
        val constructor =
            requestType.declaredConstructors
                .single()
                .apply { isAccessible = true }

        return constructor.newInstance(
            editor,
            reference,
            Point(0, 0),
            modificationStamp,
        )
    }

    private fun resolveTarget(request: Any): Any? =
        resolveTargetMethod.invoke(
            null,
            request,
            project,
        )

    private fun resolveData(target: Any): Any =
        requireNotNull(
            resolveDataMethod.invoke(
                null,
                target,
                indexService,
            ),
        )

    private fun readField(
        target: Any,
        name: String,
    ): Any? =
        target.javaClass
            .getDeclaredField(name)
            .apply { isAccessible = true }
            .get(target)

    private val supportClass: Class<*> =
        Class.forName(
            "org.taigaui.designtokens.documentation." +
                "DesignTokenHoverPopupControllerKt",
        )

    private val resolveTargetMethod =
        supportClass.declaredMethods
            .single { method ->
                method.name == "resolvePopupTarget" &&
                    method.parameterCount == 2
            }.apply { isAccessible = true }

    private val resolveDataMethod =
        supportClass.declaredMethods
            .single { method ->
                method.name == "resolvePopupData" &&
                    method.parameterCount == 2
            }.apply { isAccessible = true }
}
