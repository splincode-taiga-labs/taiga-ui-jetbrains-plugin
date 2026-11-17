package org.taigaui.designtokens.imports

import com.intellij.lang.javascript.psi.JSFile
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.testFramework.builders.EmptyModuleFixtureBuilder
import com.intellij.testFramework.fixtures.CodeInsightFixtureTestCase
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import java.nio.file.Files
import java.nio.file.Path

@RunWith(JUnit4::class)
class TaigaImportInspectionTest : CodeInsightFixtureTestCase<EmptyModuleFixtureBuilder<*>>() {
    override fun setUp() {
        super.setUp()
        myFixture.enableInspections(TaigaImportInspection())
        myFixture.addFileToProject("package.json", """{"name":"taiga-workspace","private":true}""")
        myFixture.addFileToProject("pnpm-workspace.yaml", "packages: ['apps/*']")
        myFixture.addFileToProject(
            "apps/v4/package.json",
            """
            {"name":"taiga-four","dependencies":{"@taiga-ui/core":"4.60.0","@taiga-ui/kit":"4.60.0"}}
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "apps/v5/package.json",
            """
            {
              "name":"taiga-five",
              "dependencies":{"@taiga-ui/core":"5.18.0","@taiga-ui/kit":"5.18.0","@taiga-ui/addon-mobile":"5.18.0"}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject(
            "tsconfig.json",
            """{"compilerOptions":{"module":"ESNext","moduleResolution":"bundler","verbatimModuleSyntax":true}}""",
        )
    }

    @Test
    fun testMovedExportUsesEachAppsInstalledPackagesInMixedPnpmWorkspace() {
        installCheckbox("apps/v4", "4.60.0", "kit")
        installCheckbox("apps/v5", "5.18.0", "core")
        val v4 = configure("apps/v4/src/example.ts", "import {<caret>TuiCheckbox} from '@taiga-ui/core';")
        val v5 = configure("apps/v5/src/example.ts", "import {<caret>TuiCheckbox} from '@taiga-ui/core';")

        assertEmpty(problems())
        myFixture.configureFromExistingVirtualFile(v4.virtualFile)
        myFixture.editor.caretModel.moveToOffset(v4.text.indexOf("TuiCheckbox"))
        assertEquals(listOf("'TuiCheckbox' is exported by '@taiga-ui/kit' instead of '@taiga-ui/core'"), problems())
        applyFix("kit")
        assertEquals("import {TuiCheckbox} from '@taiga-ui/kit';", myFixture.file.text)
        assertEmpty(problems())

        myFixture.configureFromExistingVirtualFile(v5.virtualFile)
        assertEmpty(problems())
        configure("apps/v5/src/from-kit.ts", "import {<caret>TuiCheckbox} from '@taiga-ui/kit';")
        assertEquals(listOf("'TuiCheckbox' is exported by '@taiga-ui/core' instead of '@taiga-ui/kit'"), problems())
        applyFix("core")
        assertEquals("import {TuiCheckbox} from '@taiga-ui/core';", myFixture.file.text)
        assertEmpty(problems())
    }

    @Test
    fun testMergesAliasedAndTypeOnlyImportsAndKeepsOtherBindings() {
        installCheckbox("apps/v5", "5.18.0", "core")
        configure(
            "apps/v5/src/example.ts",
            """
            import {TuiButton} from '@taiga-ui/core';
            import {<caret>type TuiCheckboxOptions as Options, TuiBadge} from '@taiga-ui/kit';
            """.trimIndent(),
        )

        applyFix("core")

        val imports = (myFixture.file as JSFile).taigaImports().associateBy { it.taigaPackageName }
        val core = requireNotNull(imports["@taiga-ui/core"])
        val kit = requireNotNull(imports["@taiga-ui/kit"])
        assertEquals(setOf("TuiButton", "Options"), core.importSpecifiers.map { it.declaredName }.toSet())
        val options = core.importSpecifiers.single { it.declaredName == "Options" }
        assertEquals("TuiCheckboxOptions", options.referenceName)
        assertEquals("type TuiCheckboxOptions as Options", options.text)
        assertEquals(listOf("TuiBadge"), kit.importSpecifiers.map { it.declaredName })
        assertEmpty(problems())
    }

    @Test
    fun testKeepsStandaloneTypeImportAndDoesNotDuplicateAnExistingBinding() {
        installCheckbox("apps/v5", "5.18.0", "core")
        configure(
            "apps/v5/src/example.ts",
            "import type {<caret>TuiCheckboxOptions as Options} from '@taiga-ui/kit';",
        )

        applyFix("core")
        assertEquals("import type {TuiCheckboxOptions as Options} from '@taiga-ui/core';", myFixture.file.text)

        configure(
            "apps/v5/src/duplicate.ts",
            """
            import type {TuiCheckboxOptions as Options} from '@taiga-ui/core';
            import type {<caret>TuiCheckboxOptions as Options} from '@taiga-ui/kit';
            """.trimIndent(),
        )

        applyFix("core")
        val imports = (myFixture.file as JSFile).taigaImports()
        assertEquals(1, imports.size)
        assertEquals(
            "Options",
            imports
                .single()
                .importSpecifiers
                .single()
                .declaredName,
        )
        assertEmpty(problems())
    }

    @Test
    fun testLeavesUnknownAmbiguousAndPrivateExportsAlone() {
        val app = "apps/v5"
        installCheckbox(app, "5.18.0", "core")
        val addon = install(app, "addon-mobile", "5.18.0", "export {TuiCheckbox} from './checkbox';")
        myFixture.addFileToProject("$addon/checkbox.d.ts", "export declare class TuiCheckbox {}")
        myFixture.addFileToProject("$addon/private.d.ts", "export declare class TuiHidden {}")
        configure(
            "$app/src/example.ts",
            """
            import {TuiCheckbox, TuiUnknown, TuiHidden} from '@taiga-ui/kit';
            import {TuiButton} from '@taiga-ui/core';
            """.trimIndent(),
        )

        assertEmpty(problems())
    }

    @Test
    fun testUsesHoistedSiblingWhenTheNestedScopeHasOnlyOnePackage() {
        install("", "core", "4.60.0", "export declare class TuiButton {}")
        install("apps/v5", "kit", "5.18.0", "export declare class TuiBadge {}")
        configure("apps/v5/src/example.ts", "import {<caret>TuiButton, TuiBadge} from '@taiga-ui/kit';")

        applyFix("core")

        val imports = (myFixture.file as JSFile).taigaImports().associateBy { it.taigaPackageName }
        assertEquals("TuiButton", imports["@taiga-ui/core"]?.importSpecifiers?.single()?.referenceName)
        assertEquals("TuiBadge", imports["@taiga-ui/kit"]?.importSpecifiers?.single()?.referenceName)
        assertEmpty(problems())
    }

    private fun installCheckbox(
        app: String,
        version: String,
        owner: String,
    ) {
        val packages = mapOf("core" to "TuiButton", "kit" to "TuiBadge")
        packages.forEach { (name, other) ->
            val declarations = "export declare class $other {}"
            if (name == owner) {
                val library =
                    install(
                        app,
                        name,
                        version,
                        "$declarations\nexport * from '@taiga-ui/$name/components/checkbox';",
                        ""","./components/checkbox":{"types":"./components/checkbox/index.d.ts"}""",
                    )
                myFixture.addFileToProject("$library/components/checkbox/index.d.ts", "export * from './checkbox';")
                myFixture.addFileToProject(
                    "$library/components/checkbox/checkbox.d.ts",
                    "export declare class TuiCheckbox {}\nexport interface TuiCheckboxOptions {}",
                )
            } else {
                install(app, name, version, declarations)
            }
        }
    }

    private fun install(
        app: String,
        name: String,
        version: String,
        declarations: String,
        exports: String = "",
    ): String {
        val store = "node_modules/.pnpm/@taiga-ui+$name@$version/node_modules/@taiga-ui/$name"
        myFixture.addFileToProject(
            "$store/package.json",
            """
            {
              "name":"@taiga-ui/$name",
              "version":"$version",
              "types":"./index.d.ts",
              "type":"module",
              "module":"./index.mjs",
              "exports":{".":{"types":"./index.d.ts","default":"./index.mjs"}$exports}
            }
            """.trimIndent(),
        )
        myFixture.addFileToProject("$store/index.d.ts", declarations)
        myFixture.addFileToProject("$store/index.mjs", declarations.replace("declare ", ""))
        val scope =
            myFixture.tempDirFixture.findOrCreateDir(
                listOf(app, "node_modules/@taiga-ui").filter(String::isNotEmpty).joinToString("/"),
            )
        val root = Path.of(myFixture.tempDirPath)
        val link = Path.of(scope.path).resolve(name)
        Files.createSymbolicLink(link, link.parent.relativize(root.resolve(store)))
        VfsUtil.markDirtyAndRefresh(false, true, true, scope)
        return store
    }

    private fun configure(
        path: String,
        text: String,
    ): JSFile {
        val file = myFixture.addFileToProject(path, text.replace("<caret>", ""))
        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        text.indexOf("<caret>").takeIf { it >= 0 }?.let(myFixture.editor.caretModel::moveToOffset)
        return file as JSFile
    }

    private fun problems(): List<String> =
        myFixture
            .doHighlighting()
            .mapNotNull { highlight -> highlight.description }
            .filter { description -> "is exported by '@taiga-ui/" in description }

    private fun applyFix(target: String) {
        val action = myFixture.findSingleIntention("Move import to '@taiga-ui/$target'")
        myFixture.launchAction(action)
    }
}
