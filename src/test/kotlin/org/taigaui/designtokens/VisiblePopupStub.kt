package org.taigaui.designtokens

import com.intellij.openapi.ui.popup.JBPopup
import java.lang.reflect.Proxy

/**
 * Tests popup guards without depending on whether the CI window manager marks a real popup visible.
 */
internal fun visiblePopupStub(): JBPopup =
    Proxy.newProxyInstance(
        JBPopup::class.java.classLoader,
        arrayOf(JBPopup::class.java),
    ) { proxy, method, arguments ->
        when (method.name) {
            "isVisible" -> true
            "isDisposed" -> false
            "toString" -> "VisibleTestPopup"
            "hashCode" -> System.identityHashCode(proxy)
            "equals" -> proxy === arguments?.firstOrNull()
            else -> null
        }
    } as JBPopup
