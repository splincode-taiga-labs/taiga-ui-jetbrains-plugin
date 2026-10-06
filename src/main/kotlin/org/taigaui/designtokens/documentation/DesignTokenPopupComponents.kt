package org.taigaui.designtokens.documentation

import com.intellij.icons.AllIcons
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Insets
import java.awt.RenderingHints
import java.awt.datatransfer.StringSelection
import java.awt.geom.AffineTransform
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.JTextPane
import javax.swing.SwingConstants
import javax.swing.Timer
import javax.swing.text.SimpleAttributeSet
import javax.swing.text.StyleConstants

internal class WrappedTextPane(
    text: String,
    width: Int,
    textFont: Font,
    textColor: Color,
    alignment: Int,
) : JTextPane() {
    init {
        font = textFont
        foreground = textColor
        isEditable = false
        isOpaque = false
        isFocusable = false
        border = JBUI.Borders.empty()
        margin = Insets(0, 0, 0, 0)
        highlighter = null

        styledDocument.insertString(0, text, null)

        val paragraphAttributes = SimpleAttributeSet()

        StyleConstants.setAlignment(paragraphAttributes, alignment)
        styledDocument.setParagraphAttributes(0, styledDocument.length, paragraphAttributes, false)

        val scaledWidth = JBUI.scale(width)

        setSize(Dimension(scaledWidth, Short.MAX_VALUE.toInt()))
        val calculatedHeight = super.getPreferredSize().height

        preferredSize = Dimension(scaledWidth, calculatedHeight)
        minimumSize = preferredSize
        maximumSize = Dimension(scaledWidth, calculatedHeight)
        alignmentX = JComponent.LEFT_ALIGNMENT
        alignmentY = JComponent.CENTER_ALIGNMENT
    }
}

internal class CopyValueButton(
    value: String,
    private val copyTooltip: String = "Copy value",
) : JButton(AllIcons.Actions.Copy) {
    private val resetTimer =
        Timer(COPY_FEEDBACK_DURATION_MS) {
            icon = AllIcons.Actions.Copy
            toolTipText = copyTooltip
        }.apply {
            isRepeats = false
        }

    init {
        configurePopupIconButton(copyTooltip)

        addActionListener {
            CopyPasteManager.getInstance().setContents(StringSelection(value))
            icon = AllIcons.Actions.Checked
            toolTipText = "Copied"
            resetTimer.restart()
        }
    }
}

internal class NavigateToDefinitionButton(
    onNavigate: () -> Unit,
) : JButton(AllIcons.Actions.EditSource) {
    init {
        configurePopupIconButton("Go to definition")
        addActionListener { onNavigate() }
    }
}

private fun JButton.configurePopupIconButton(tooltip: String) {
    toolTipText = tooltip
    isOpaque = false
    isContentAreaFilled = false
    isBorderPainted = false
    isFocusable = true
    horizontalAlignment = SwingConstants.CENTER
    verticalAlignment = SwingConstants.CENTER
    iconTextGap = 0
    margin = Insets(0, 0, 0, 0)
    border = JBUI.Borders.empty()
    preferredSize = JBUI.size(POPUP_ICON_BUTTON_SIZE, POPUP_ICON_BUTTON_SIZE)
    minimumSize = preferredSize
    maximumSize = preferredSize
    alignmentY = JComponent.CENTER_ALIGNMENT
}

internal class TokenBadge : JComponent() {
    init {
        preferredSize = JBUI.size(TOKEN_BADGE_SIZE, TOKEN_BADGE_SIZE)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D

        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2D.color = TOKEN_BADGE_BACKGROUND
        val arc = minOf(width, height) * TOKEN_BADGE_CORNER_RATIO

        graphics2D.fillRoundRect(0, 0, width, height, arc.toInt(), arc.toInt())
        graphics2D.color = Color.WHITE

        val logoWidth = width * TOKEN_LOGO_WIDTH_RATIO
        val logoHeight = logoWidth * TOKEN_LOGO_VIEWBOX_HEIGHT / TOKEN_LOGO_VIEWBOX_WIDTH
        val transform =
            AffineTransform().apply {
                translate(
                    (width - logoWidth) / 2.0,
                    (height - logoHeight) / 2.0,
                )
                scale(
                    logoWidth / TOKEN_LOGO_VIEWBOX_WIDTH,
                    logoHeight / TOKEN_LOGO_VIEWBOX_HEIGHT,
                )
            }

        graphics2D.fill(transform.createTransformedShape(TAIGA_LOGO_PATH))
        graphics2D.dispose()
    }
}

internal class ReferenceDot : JComponent() {
    init {
        preferredSize = JBUI.size(10, 16)
        minimumSize = preferredSize
        maximumSize = preferredSize
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        val size = JBUI.scale(8)
        val x = (width - size) / 2
        val y = (height - size) / 2

        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2D.color = DESIGN_TOKEN_POPUP_LINK_COLOR
        graphics2D.fillOval(x, y, size, size)
        graphics2D.dispose()
    }
}

internal class ColorSwatch(
    private val swatchColor: Color,
    size: Int,
) : JComponent() {
    init {
        preferredSize = JBUI.size(size, size)
        minimumSize = preferredSize
        maximumSize = preferredSize
        alignmentY = CENTER_ALIGNMENT

        val alpha = formatAlpha(swatchColor.alpha)

        toolTipText = "rgba(${swatchColor.red}, ${swatchColor.green}, ${swatchColor.blue}, $alpha)"
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D
        val inset = JBUI.scale(1)
        val diameter = minOf(width, height) - inset * 2
        val circle = Ellipse2D.Float(inset.toFloat(), inset.toFloat(), diameter.toFloat(), diameter.toFloat())

        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2D.clip = circle
        paintCheckerboard(graphics2D, diameter, inset)
        graphics2D.color = swatchColor
        graphics2D.fill(circle)
        graphics2D.clip = null
        graphics2D.color = SWATCH_BORDER
        graphics2D.stroke = BasicStroke(JBUI.scale(1).toFloat())
        graphics2D.draw(circle)
        graphics2D.dispose()
    }

    private fun paintCheckerboard(
        graphics: Graphics2D,
        diameter: Int,
        inset: Int,
    ) {
        val tile = maxOf(JBUI.scale(4), 1)
        var row = 0
        var y = inset

        while (y < inset + diameter) {
            var column = 0
            var x = inset

            while (x < inset + diameter) {
                graphics.color = if ((row + column) % 2 == 0) CHECKER_LIGHT else CHECKER_DARK
                graphics.fillRect(x, y, tile, tile)
                column++
                x += tile
            }

            row++
            y += tile
        }
    }
}

internal class RoundedRowPanel : JPanel() {
    init {
        isOpaque = false
    }

    override fun paintComponent(graphics: Graphics) {
        val graphics2D = graphics.create() as Graphics2D

        graphics2D.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        graphics2D.color = ROW_BACKGROUND
        graphics2D.fill(
            RoundRectangle2D.Float(
                0f,
                0f,
                (width - 1).toFloat(),
                (height - 1).toFloat(),
                JBUI.scale(12).toFloat(),
                JBUI.scale(12).toFloat(),
            ),
        )
        graphics2D.color = ROW_BORDER
        graphics2D.stroke = BasicStroke(JBUI.scale(1).toFloat())
        graphics2D.draw(
            RoundRectangle2D.Float(
                0.5f,
                0.5f,
                (width - 2).toFloat(),
                (height - 2).toFloat(),
                JBUI.scale(12).toFloat(),
                JBUI.scale(12).toFloat(),
            ),
        )
        graphics2D.dispose()
        super.paintComponent(graphics)
    }
}

internal fun calculateNaturalTextWidth(value: String): Int {
    val label =
        JBLabel(value).apply {
            font = DESIGN_TOKEN_POPUP_CODE_FONT
        }

    return JBUI.unscale(label.preferredSize.width + JBUI.scale(TEXT_WIDTH_PADDING))
}

private fun formatAlpha(alpha: Int): String =
    if (alpha == OPAQUE_ALPHA) {
        "1"
    } else {
        "%.2f".format(alpha.toDouble() / OPAQUE_ALPHA).trimEnd('0').trimEnd('.')
    }

private val TAIGA_LOGO_PATH =
    Path2D.Double(Path2D.WIND_EVEN_ODD).apply {
        moveTo(34.0, 29.4667)
        lineTo(17.0, 0.0)
        lineTo(0.0, 29.4667)
        lineTo(10.3208, 29.4667)
        lineTo(14.6218, 22.8197)
        lineTo(11.4867, 22.8197)
        lineTo(17.0002, 14.09)
        lineTo(22.5137, 22.8197)
        lineTo(19.3785, 22.8197)
        lineTo(23.6795, 29.4667)
        closePath()
    }

internal val DESIGN_TOKEN_POPUP_BACKGROUND = JBColor(Color(247, 248, 250), Color(35, 37, 42))
internal val DESIGN_TOKEN_POPUP_LINK_COLOR = JBColor(Color(45, 108, 223), Color(88, 157, 246))
internal val DESIGN_TOKEN_POPUP_CODE_FONT = Font(Font.MONOSPACED, Font.PLAIN, JBUI.scale(13))
internal const val DESIGN_TOKEN_POPUP_SWATCH_SIZE = 26
internal const val DESIGN_TOKEN_POPUP_SMALL_SWATCH_SIZE = 20

private val ROW_BACKGROUND = JBColor(Color(255, 255, 255), Color(43, 46, 52))
private val ROW_BORDER = JBColor(Color(220, 223, 229), Color(65, 69, 77))
private val TOKEN_BADGE_BACKGROUND = Color(255, 112, 67)
private val CHECKER_LIGHT = Color(235, 235, 235)
private val CHECKER_DARK = Color(185, 185, 185)
private val SWATCH_BORDER = JBColor(Color(110, 110, 110), Color(170, 170, 170))

private const val TOKEN_BADGE_SIZE = 16
private const val TOKEN_BADGE_CORNER_RATIO = 0.25
private const val TOKEN_LOGO_WIDTH_RATIO = 0.72
private const val TOKEN_LOGO_VIEWBOX_WIDTH = 34.0
private const val TOKEN_LOGO_VIEWBOX_HEIGHT = 30.0
private const val POPUP_ICON_BUTTON_SIZE = 20
private const val COPY_FEEDBACK_DURATION_MS = 2_000
private const val TEXT_WIDTH_PADDING = 2
private const val OPAQUE_ALPHA = 255
