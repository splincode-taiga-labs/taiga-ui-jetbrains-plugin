package org.taigaui.designtokens.documentation

import com.intellij.util.ui.JBUI
import java.awt.AlphaComposite
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.image.BufferedImage
import javax.swing.JPanel
import javax.swing.Timer

/** Stable Swing container: switching hover targets must not recreate the containing JBPopup. */
internal class TaigaDocumentationPopupHost(
    initial: TaigaQuickDocumentationPopupPanel,
    val stabilizesHoverSize: Boolean = false,
) : JPanel(BorderLayout()) {
    var card: TaigaQuickDocumentationPopupPanel = initial
        private set

    // Keep compact hovers stable, but let full directive cards use their natural size.
    private var lockedSize: Dimension? =
        initial.takeIf { stabilizesHoverSize && it.compactHover }?.let(::compactHoverSize)

    override fun getPreferredSize(): Dimension =
        if (stabilizesHoverSize && card.compactHover) {
            lockedSize?.let(::Dimension) ?: super.getPreferredSize()
        } else {
            super.getPreferredSize()
        }

    private fun compactHoverSize(panel: TaigaQuickDocumentationPopupPanel): Dimension =
        Dimension(
            panel.preferredSize.width.coerceAtLeast(JBUI.scale(380)),
            panel.preferredSize.height.coerceIn(JBUI.scale(220), JBUI.scale(300)),
        )

    private var previousFrame: BufferedImage? = null
    private var transitionStart: Long = 0
    private val transitionTimer =
        Timer(16) {
            if (System.nanoTime() - transitionStart >= TRANSITION_NANOS) {
                stopTransition()
            } else {
                repaint()
            }
        }.apply { isRepeats = true }

    init {
        isOpaque = false
        add(initial, BorderLayout.CENTER)
    }

    fun showCard(next: TaigaQuickDocumentationPopupPanel) {
        if (card === next) return

        stopTransition()
        if (isShowing && width > 0 && height > 0) {
            val frame = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
            val graphics = frame.createGraphics()
            try {
                card.paint(graphics)
            } finally {
                graphics.dispose()
            }
            previousFrame = frame
            transitionStart = System.nanoTime()
        }

        remove(card)
        card = next
        if (stabilizesHoverSize && next.compactHover && lockedSize == null) {
            lockedSize = compactHoverSize(next)
        }
        add(next, BorderLayout.CENTER)
        revalidate()
        repaint()
        if (previousFrame != null) transitionTimer.start()
    }

    override fun paintChildren(graphics: Graphics) {
        super.paintChildren(graphics)
        val previous = previousFrame ?: return
        val progress = ((System.nanoTime() - transitionStart).toDouble() / TRANSITION_NANOS).coerceIn(0.0, 1.0)
        val copy = graphics.create() as Graphics2D
        try {
            copy.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, (1.0 - progress).toFloat())
            copy.drawImage(previous, 0, 0, null)
        } finally {
            copy.dispose()
        }
    }

    fun stopTransition() {
        transitionTimer.stop()
        previousFrame = null
        repaint()
    }

    override fun removeNotify() {
        stopTransition()
        super.removeNotify()
    }
}

private const val TRANSITION_NANOS = 120_000_000L
