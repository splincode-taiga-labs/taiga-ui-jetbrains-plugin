package org.taigaui.designtokens.packageinfo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class YarnPnpManifestTest {
    @Test
    fun `locator display name handles top level and package locators`() {
        assertEquals("<top-level>@<top-level>", YarnPnpLocator(null, null).displayName)
        assertEquals("@taiga-ui/core@npm:5.0.0", YarnPnpLocator("@taiga-ui/core", "npm:5.0.0").displayName)
    }

    @Test
    fun `manifest finds deepest issuer and falls back to top level`() {
        val root = Files.createTempDirectory("pnp-manifest-issuer")
        val top = YarnPnpLocator(null, null)
        val app = YarnPnpLocator("app", "workspace:.")
        val nested = YarnPnpLocator("nested", "workspace:nested")
        val discarded = YarnPnpLocator("discarded", "workspace:discarded")
        val manifest =
            manifest(
                root,
                enableTopLevelFallback = false,
                packages =
                    mapOf(
                        top to info(top, "./"),
                        app to info(app, "./packages/app/"),
                        nested to info(nested, "./packages/app/nested/"),
                        discarded to info(discarded, "./packages/app/nested/discarded/", discard = true),
                    ),
            )

        assertEquals(
            nested,
            manifest.findIssuer(root.resolve("packages/app/nested/src/file.ts")),
        )
        assertEquals(top, manifest.findIssuer(root.resolve("outside/file.ts")))
    }

    @Test
    fun `manifest traverses taiga dependencies cycles aliases and top level fallback`() {
        val root = Files.createTempDirectory("pnp-manifest-deps")
        val top = YarnPnpLocator(null, null)
        val issuer = YarnPnpLocator("app", "workspace:.")
        val core = YarnPnpLocator("@taiga-ui/core", "npm:1")
        val icons = YarnPnpLocator("@taiga-ui/icons", "npm:2")
        val unrelated = YarnPnpLocator("rxjs", "npm:7")
        val packages =
            mapOf(
                top to
                    info(
                        top,
                        "./",
                        deps =
                            mapOf(
                                "icons-alias" to YarnPnpDependency("@taiga-ui/icons", "npm:2"),
                            ),
                    ),
                issuer to
                    info(
                        issuer,
                        "./app",
                        deps =
                            mapOf(
                                "@taiga-ui/core" to YarnPnpDependency("@taiga-ui/core", "npm:1"),
                                "rxjs" to YarnPnpDependency("rxjs", "npm:7"),
                                "@taiga-ui/missing" to YarnPnpDependency("@taiga-ui/missing", "npm:0"),
                            ),
                    ),
                core to
                    info(
                        core,
                        "./core",
                        deps =
                            mapOf(
                                "@taiga-ui/core" to YarnPnpDependency("@taiga-ui/core", "npm:1"),
                            ),
                    ),
                icons to info(icons, "./icons"),
                unrelated to info(unrelated, "./rxjs"),
            )
        val manifest = manifest(root, enableTopLevelFallback = true, packages = packages)

        assertEquals(
            listOf(core, icons),
            manifest.reachableTaigaUiPackages(issuer).map(YarnPnpPackageInfo::locator),
        )
    }

    @Test
    fun `package path rejects zip segments and resolves yarn virtual paths`() {
        val root = Files.createTempDirectory("pnp-manifest-path")
        val physical = YarnPnpLocator("physical", "npm:1")
        val zip = YarnPnpLocator("zip", "npm:1")
        val virtual = YarnPnpLocator("virtual", "npm:1")
        val manifest =
            manifest(
                root,
                packages =
                    mapOf(
                        physical to info(physical, "./packages/physical"),
                        zip to info(zip, "./.yarn/cache/pkg.zip/node_modules/pkg"),
                        virtual to info(virtual, "./packages/__virtual__/pkg/0/cache/pkg"),
                    ),
            )

        assertEquals(root.resolve("packages/physical").toAbsolutePath().normalize(), manifest.packagePath(physical))
        assertNull(manifest.packagePath(zip))
        assertEquals(
            root.resolve("packages/cache/pkg").toAbsolutePath().normalize(),
            manifest.packagePath(virtual),
        )
    }

    @Test
    fun `reader prefers data file and parses primitive alias null and invalid dependencies`() {
        val root = Files.createTempDirectory("pnp-reader-data")
        val data = root.resolve(".pnp.data.json")
        Files.writeString(
            root.resolve(".pnp.cjs"),
            "const RAW_RUNTIME_STATE = 'not-json';",
        )
        Files.writeString(
            data,
            """
            {
              "enableTopLevelFallback": true,
              "packageRegistryData": [
                [
                  null,
                  [
                    [
                      null,
                      {
                        "packageLocation": "./",
                        "discardFromLookup": null,
                        "packageDependencies": [
                          ["@taiga-ui/core", "npm:1"],
                          ["icons-alias", ["@taiga-ui/icons", "npm:2"]],
                          ["ignored-null", null],
                          [null, "npm:3"],
                          ["bad-alias", ["@taiga-ui/only-name"]]
                        ]
                      }
                    ]
                  ]
                ],
                [
                  "@taiga-ui/core",
                  [["npm:1", {"packageLocation":"./core","packageDependencies":[]}]]
                ],
                [
                  "@taiga-ui/icons",
                  [["npm:2", {"packageLocation":"./icons","discardFromLookup":true}]]
                ],
                [
                  "missing-location",
                  [["npm:1", {"packageDependencies":[]}]]
                ]
              ]
            }
            """.trimIndent(),
        )

        val manifest = requireNotNull(YarnPnpManifestReader().read(root))
        val top = requireNotNull(manifest.packages[YarnPnpLocator(null, null)])

        assertEquals(data.toAbsolutePath().normalize(), manifest.primarySource)
        assertEquals(2, manifest.sourceFiles.size)
        assertTrue(manifest.enableTopLevelFallback)
        assertEquals(YarnPnpDependency("@taiga-ui/core", "npm:1"), top.packageDependencies["@taiga-ui/core"])
        assertEquals(YarnPnpDependency("@taiga-ui/icons", "npm:2"), top.packageDependencies["icons-alias"])
        assertFalse("ignored-null" in top.packageDependencies)
        assertFalse("bad-alias" in top.packageDependencies)
        assertTrue(requireNotNull(manifest.packages[YarnPnpLocator("@taiga-ui/icons", "npm:2")]).discardFromLookup)
        assertFalse(manifest.packages.keys.any { locator -> locator.name == "missing-location" })
    }

    @Test
    fun `reader fails closed for missing malformed and loader without runtime state`() {
        val root = Files.createTempDirectory("pnp-reader-invalid")
        val reader = YarnPnpManifestReader()

        assertNull(reader.read(root))

        Files.writeString(root.resolve(".pnp.data.json"), "{not json")
        assertNull(reader.read(root))

        Files.delete(root.resolve(".pnp.data.json"))
        Files.writeString(root.resolve(".pnp.cjs"), "module.exports = {};")
        assertNull(reader.read(root))
    }

    @Test
    fun `reader parses inline runtime state when data file is absent`() {
        val root = Files.createTempDirectory("pnp-reader-loader")
        val loader = root.resolve(".pnp.cjs")
        val json =
            """{"packageRegistryData":[[null,[[null,{"packageLocation":"./"}]]]],"enableTopLevelFallback":false}"""

        Files.writeString(loader, "const RAW_RUNTIME_STATE = '$json';")

        val manifest = requireNotNull(YarnPnpManifestReader().read(root))

        assertEquals(loader.toAbsolutePath().normalize(), manifest.primarySource)
        assertEquals(setOf(loader.toAbsolutePath().normalize()), manifest.sourceFiles)
        assertTrue(YarnPnpLocator(null, null) in manifest.packages)
    }

    @Test
    fun `inline runtime state decoder handles javascript string escapes and invalid input`() {
        val reader = YarnPnpManifestReader()
        val method =
            YarnPnpManifestReader::class.java
                .getDeclaredMethod("extractInlineRuntimeState", String::class.java)
                .apply { isAccessible = true }

        fun decode(source: String): String? = method.invoke(reader, source) as String?

        assertNull(decode("const value = 'x';"))
        assertNull(decode("const RAW_RUNTIME_STATE;"))
        assertNull(decode("const RAW_RUNTIME_STATE = 42;"))
        assertNull(decode("const RAW_RUNTIME_STATE = 'unterminated"))
        assertNull(decode("const RAW_RUNTIME_STATE = 'dangling\\"))
        assertNull(decode("const RAW_RUNTIME_STATE = '\\xZ1';"))
        assertNull(decode("const RAW_RUNTIME_STATE = '\\u12';"))

        val decoded =
            requireNotNull(
                decode(
                    "const RAW_RUNTIME_STATE = \"a\\\\b\\'c\\\"d\\n\\r\\t\\b\\f\\v\\0\\x41\\u0042\\q\";",
                ),
            )

        assertTrue(decoded.startsWith("a\\b'c\"d"))
        assertTrue(decoded.contains('A'))
        assertTrue(decoded.contains('B'))
        assertTrue(decoded.endsWith("q"))

        assertEquals(
            "ab",
            decode("const RAW_RUNTIME_STATE = 'a\\\nb';"),
        )
        assertEquals(
            "ab",
            decode("const RAW_RUNTIME_STATE = 'a\\\r\nb';"),
        )
    }

    @Test
    fun `manifest without top level package returns null issuer and tolerates missing traversal start`() {
        val root = Files.createTempDirectory("pnp-no-top")
        val only = YarnPnpLocator("app", "workspace:.")

        val manifest =
            manifest(
                root,
                packages = mapOf(only to info(only, "./app")),
            )

        assertNull(manifest.findIssuer(root.resolve("outside/file.ts")))
        assertTrue(manifest.reachableTaigaUiPackages(YarnPnpLocator("missing", "npm:1")).isEmpty())
    }

    @Test
    fun `reader defaults optional manifest fields and handles absent registries and dependencies`() {
        val root = Files.createTempDirectory("pnp-reader-defaults")
        val data = root.resolve(".pnp.data.json")

        Files.writeString(
            data,
            """
            {
              "enableTopLevelFallback": null,
              "packageRegistryData": [
                [
                  null,
                  [
                    [
                      null,
                      {
                        "packageLocation": "./"
                      }
                    ]
                  ]
                ]
              ]
            }
            """.trimIndent(),
        )

        val manifest = requireNotNull(YarnPnpManifestReader().read(root))

        assertFalse(manifest.enableTopLevelFallback)
        assertTrue(
            requireNotNull(manifest.packages[YarnPnpLocator(null, null)])
                .packageDependencies
                .isEmpty(),
        )

        Files.writeString(
            data,
            """{"enableTopLevelFallback":false,"packageRegistryData":{}}""",
        )

        assertTrue(requireNotNull(YarnPnpManifestReader().read(root)).packages.isEmpty())

        Files.writeString(
            data,
            """{"enableTopLevelFallback":false}""",
        )

        assertTrue(requireNotNull(YarnPnpManifestReader().read(root)).packages.isEmpty())
    }

    @Test
    fun `reader ignores non primitive dependency names references and incomplete aliases`() {
        val root = Files.createTempDirectory("pnp-reader-dependency-shapes")
        val data = root.resolve(".pnp.data.json")
        Files.writeString(
            data,
            """
            {
              "packageRegistryData": [
                [
                  null,
                  [
                    [
                      null,
                      {
                        "packageLocation": "./",
                        "packageDependencies": [
                          [{}, "npm:1"],
                          ["primitive", "npm:2"],
                          ["object-target", {}],
                          ["empty-alias", []],
                          ["one-alias", ["@taiga-ui/core"]],
                          ["null-alias", [null, "npm:3"]]
                        ]
                      }
                    ]
                  ]
                ]
              ]
            }
            """.trimIndent(),
        )

        val top =
            requireNotNull(
                requireNotNull(YarnPnpManifestReader().read(root))
                    .packages[YarnPnpLocator(null, null)],
            )

        assertEquals(
            mapOf("primitive" to YarnPnpDependency("primitive", "npm:2")),
            top.packageDependencies,
        )
    }

    @Test
    fun `private file version fails closed for missing path`() {
        val method =
            YarnPnpManifestReader::class.java
                .getDeclaredMethod("fileVersion", Path::class.java)
                .apply { isAccessible = true }

        assertEquals(
            "unknown",
            method.invoke(YarnPnpManifestReader(), Path.of("definitely-missing-pnp-source")),
        )
    }

    @Test
    fun `portable and virtual path helpers handle invalid metadata safely`() {
        val root = Files.createTempDirectory("pnp-path-helpers")
        val portable = resolvePortablePackageLocation(root, "packages\\core")

        assertEquals(root.resolve("packages/core").toAbsolutePath().normalize(), portable)
        assertTrue(containsZipSegment(root.resolve(".yarn/cache/PKG.ZIP/node_modules/pkg")))
        assertFalse(containsZipSegment(root.resolve("packages/pkg")))

        val incomplete = root.resolve("__virtual__/pkg")
        val invalidDepth = root.resolve("__virtual__/pkg/not-a-number/cache/pkg")
        val excessiveDepth = root.resolve("__virtual__/pkg/99/cache/pkg")
        val valid = root.resolve("base/__virtual__/pkg/0/cache/pkg")

        assertEquals(incomplete.toAbsolutePath().normalize(), resolveYarnVirtualPath(incomplete))
        assertEquals(invalidDepth.toAbsolutePath().normalize(), resolveYarnVirtualPath(invalidDepth))
        assertEquals(excessiveDepth.toAbsolutePath().normalize(), resolveYarnVirtualPath(excessiveDepth))
        assertEquals(root.resolve("base/cache/pkg").toAbsolutePath().normalize(), resolveYarnVirtualPath(valid))
    }

    private fun manifest(
        root: Path,
        enableTopLevelFallback: Boolean = false,
        packages: Map<YarnPnpLocator, YarnPnpPackageInfo>,
    ): YarnPnpManifest =
        YarnPnpManifest(
            root = root,
            primarySource = root.resolve(".pnp.cjs"),
            sourceFiles = emptySet(),
            packages = packages,
            enableTopLevelFallback = enableTopLevelFallback,
            contentVersion = "test",
        )

    private fun info(
        locator: YarnPnpLocator,
        location: String,
        deps: Map<String, YarnPnpDependency> = emptyMap(),
        discard: Boolean = false,
    ): YarnPnpPackageInfo =
        YarnPnpPackageInfo(
            locator = locator,
            packageLocation = location,
            packageDependencies = deps,
            discardFromLookup = discard,
        )
}
