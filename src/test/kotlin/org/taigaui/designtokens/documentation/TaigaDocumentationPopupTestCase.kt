package org.taigaui.designtokens.documentation

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.labels.LinkLabel
import com.intellij.util.ui.JBUI
import org.taigaui.designtokens.icons.IconSvgPreviewRenderer
import org.taigaui.designtokens.icons.IconSvgSource
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.event.ActionEvent
import java.awt.event.KeyEvent
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import javax.imageio.ImageIO
import javax.swing.JComponent
import javax.swing.KeyStroke

abstract class TaigaDocumentationPopupTestCase : BasePlatformTestCase() {
    internal fun activate(
        component: JComponent,
        key: String,
    ) {
        val name = component.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(key))
        if (component is LinkLabel<*> && key == "SPACE") {
            name?.let { component.actionMap.get(it).actionPerformed(ActionEvent(component, 0, it.toString())) }
            val event = KeyEvent(component, KeyEvent.KEY_RELEASED, 0, 0, KeyEvent.VK_SPACE, ' ')
            component.keyListeners.forEach { it.keyReleased(event) }
            return
        }
        assertNotNull("Missing keyboard action: $key", name)
        component.actionMap
            .get(
                name,
            ).actionPerformed(ActionEvent(component, ActionEvent.ACTION_PERFORMED, name.toString()))
    }

    internal fun renderAndSave(
        panel: TaigaQuickDocumentationPopupPanel,
        name: String,
    ) {
        val size = panel.preferredSize
        assertTrue("Card must stay compact", size.height <= JBUI.scale(700))
        panel.size = Dimension(size)
        layoutRecursively(panel)
        val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        panel.printAll(graphics)
        graphics.dispose()
        val path = Path.of("build/reports/quick-documentation/$name.png")
        Files.createDirectories(path.parent)
        ImageIO.write(image, "png", path.toFile())
    }

    internal fun iconPreview(reference: TaigaDocumentationIcon): TaigaDocumentationIconPreview {
        val path = Files.createTempFile("quick-documentation-eye", ".svg")
        try {
            Files.writeString(
                path,
                """<svg xmlns="http://www.w3.org/2000/svg" width="24" height="24" viewBox="0 0 24 24"><path d="M2 12 Q12 -2 22 12 Q12 26 2 12Z" fill="none" stroke="#111" stroke-width="2"/><circle cx="12" cy="12" r="3" fill="none" stroke="#111" stroke-width="2"/></svg>""",
            )
            val icon =
                CompletableFuture
                    .supplyAsync {
                        IconSvgPreviewRenderer().render(
                            IconSvgSource.Local(path),
                            64,
                        )
                    }.join()
            return TaigaDocumentationIconPreview(reference, requireNotNull(icon))
        } finally {
            Files.deleteIfExists(path)
        }
    }

    internal fun directive(): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiButton",
            selector = "tuiButton",
            kind = TaigaDocKind.DIRECTIVE,
            description = "Styles native buttons. Size and appearance can be configured.",
            inputs =
                listOf(
                    TaigaApiProperty("size", "[size]", "TuiSize", "Button size"),
                    TaigaApiProperty("appearance", "[appearance]", "string", "Visual appearance"),
                    TaigaApiProperty("iconStart", "[iconStart]", "string", "Icon at the start"),
                    TaigaApiProperty("iconEnd", "[iconEnd]", "string", "Icon at the end"),
                ),
            local =
                TaigaLocalDocumentation(
                    selector = "a[tuiButton],button[tuiButton],label[tuiButton]",
                    defaults = listOf(TaigaInputDefault("size", provider = "TUI_BUTTON_OPTIONS")),
                ),
        )

    internal fun component(): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiIcon",
            selector = "tui-icon",
            kind = TaigaDocKind.COMPONENT,
            description = "Displays an icon with an optional badge and background.",
            inputs =
                listOf(
                    TaigaApiProperty("icon", "[icon]", "string", "Main icon"),
                    TaigaApiProperty("badge", "[badge]", "string", "Additional icon"),
                    TaigaApiProperty("background", "[background]", "string", "Background icon"),
                ),
        )

    internal fun pipe(
        local: TaigaLocalDocumentation =
            TaigaLocalDocumentationParser.parse(
                """
                @Pipe({name: 'tuiMapper'})
                class TuiMapperPipe {
                    transform(value: U, mapper: TuiMapper<U, G>, ...args: unknown[]): G {
                        return mapper(value, ...args);
                    }
                }
                """.trimIndent(),
            ),
    ): TaigaResolvedDocumentation.Entity =
        entity(
            name = "TuiMapperPipe",
            selector = "tuiMapper",
            kind = TaigaDocKind.PIPE,
            description = "Passes a value to a mapper function. Extra arguments are forwarded to that function.",
            local = local,
        )

    internal fun entity(
        name: String,
        selector: String,
        kind: TaigaDocKind,
        description: String,
        inputs: List<TaigaApiProperty> = emptyList(),
        local: TaigaLocalDocumentation = TaigaLocalDocumentation(),
    ): TaigaResolvedDocumentation.Entity {
        val packageName = if (kind == TaigaDocKind.PIPE) "@taiga-ui/cdk" else "@taiga-ui/core"
        return TaigaResolvedDocumentation.Entity(
            entity =
                TaigaEntityDoc(
                    "${kind.name.lowercase()}/$selector",
                    name,
                    setOf(packageName),
                    kind,
                    "5.0.0",
                    description,
                    setOf(name),
                    setOf(selector),
                    inputs,
                    emptyList(),
                    null,
                    java.net.URI.create("https://taiga-ui.dev/"),
                ),
            subject = TaigaDocumentationSubject(selector, name, packageName, local),
            startOffset = 0,
            endOffset = 9,
            usage = "<button tuiButton automation-id=\"already-visible\">Current</button>",
            typeDefinition = null,
        )
    }

    internal fun descendants(component: Component): Sequence<Component> =
        sequence {
            yield(component)
            if (component is Container) {
                component.components.forEach { child -> yieldAll(descendants(child)) }
            }
        }

    internal fun labelText(panel: Component): String =
        descendants(panel).filterIsInstance<JBLabel>().joinToString(" ") { it.text.orEmpty() }

    internal fun layoutRecursively(component: Component) {
        if (component is Container) {
            component.doLayout()
            component.components.forEach(::layoutRecursively)
        }
    }
}
