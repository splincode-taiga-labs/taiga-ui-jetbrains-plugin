package org.taigaui.designtokens.documentation

import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.util.ActionCallback
import com.intellij.openapi.util.ExpirableRunnable
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import java.awt.Component
import java.awt.Container
import java.awt.Dimension
import java.awt.Point
import java.awt.Window
import java.lang.reflect.Proxy
import javax.swing.JComponent

/** Records the platform focus contract; OS focus transfer still needs a real IDE smoke test. */
internal class TaigaDocumentationRecordingFocusManager : IdeFocusManager() {
    val requests = mutableListOf<Component>()

    override fun requestFocus(
        component: Component,
        forced: Boolean,
    ): ActionCallback {
        requests += component
        return ActionCallback.DONE
    }

    override fun getFocusTargetFor(component: JComponent): JComponent = component

    override fun doWhenFocusSettlesDown(runnable: Runnable) = runnable.run()

    override fun doWhenFocusSettlesDown(
        runnable: Runnable,
        modality: ModalityState,
    ) = runnable.run()

    override fun doWhenFocusSettlesDown(runnable: ExpirableRunnable) {
        if (!runnable.isExpired) runnable.run()
    }

    override fun getFocusedDescendantFor(component: Component): Component? = null

    override fun isFocusTransferEnabled(): Boolean = true

    override fun getFocusOwner(): Component? = requests.lastOrNull()

    override fun runOnOwnContext(
        context: DataContext,
        runnable: Runnable,
    ) = runnable.run()

    override fun getLastFocusedFor(frame: Window?): Component? = null

    override fun getLastFocusedFrame(): IdeFrame? = null

    override fun getLastFocusedIdeWindow(): Window? = null

    override fun toFront(component: JComponent) = Unit
}

/** Platform tests do not show windows; provide only their externally controlled geometry. */
@Suppress("SpreadOperator")
internal fun documentationPopupWithGeometry(
    delegate: JBPopup,
    size: Dimension,
    location: Point,
): JBPopup =
    Proxy.newProxyInstance(
        JBPopup::class.java.classLoader,
        arrayOf(JBPopup::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "isVisible" -> true
            "getSize" -> Dimension(size)
            "getLocationOnScreen" -> Point(location)
            "equals" -> proxy === arguments?.firstOrNull()
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "DocumentationTestWindow($size, $location)"
            else -> method.invoke(delegate, *(arguments ?: emptyArray()))
        }
    } as JBPopup

@Suppress("UNCHECKED_CAST")
internal fun <T> documentationField(
    target: Any,
    name: String,
): T {
    val field =
        generateSequence<Class<*>>(target.javaClass) { it.superclass }
            .flatMap { it.declaredFields.asSequence() }
            .first { it.name == name }
            .apply { isAccessible = true }
    return field.get(target) as T
}

internal fun documentationComponents(component: Component): Sequence<Component> =
    sequence {
        yield(component)
        if (component is Container) component.components.forEach { yieldAll(documentationComponents(it)) }
    }

internal fun layoutDocumentation(component: Component) {
    if (component is Container) {
        component.doLayout()
        component.components.forEach(::layoutDocumentation)
    }
}
