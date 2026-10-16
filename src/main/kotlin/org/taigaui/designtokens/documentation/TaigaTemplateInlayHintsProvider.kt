package org.taigaui.designtokens.documentation

import com.intellij.codeInsight.hints.declarative.HintColorKind
import com.intellij.codeInsight.hints.declarative.HintFontSize
import com.intellij.codeInsight.hints.declarative.HintFormat
import com.intellij.codeInsight.hints.declarative.InlayHintsCollector
import com.intellij.codeInsight.hints.declarative.InlayHintsProvider
import com.intellij.codeInsight.hints.declarative.InlayTreeSink
import com.intellij.codeInsight.hints.declarative.InlineInlayPosition
import com.intellij.codeInsight.hints.declarative.SharedBypassCollector
import com.intellij.lang.injection.InjectedLanguageManager
import com.intellij.openapi.editor.Editor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiLanguageInjectionHost
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.psi.xml.XmlAttribute

/** Only installed, Angular-resolved Taiga UI bindings receive inline hints. */
internal class TaigaTemplateInlayHintsProvider : InlayHintsProvider {
    override fun createCollector(
        file: PsiFile,
        editor: Editor,
    ): InlayHintsCollector =
        if (file.language.id in HTML_LANGUAGES) {
            object : SharedBypassCollector {
                override fun collectFromElement(
                    element: PsiElement,
                    sink: InlayTreeSink,
                ) {
                    (element as? XmlAttribute)?.addTaigaHint(sink) { it }
                }
            }
        } else {
            object : SharedBypassCollector {
                private val manager = InjectedLanguageManager.getInstance(file.project)

                override fun collectFromElement(
                    element: PsiElement,
                    sink: InlayTreeSink,
                ) {
                    val host = element as? PsiLanguageInjectionHost ?: return
                    manager.enumerate(host) { injectedFile, _ ->
                        if (injectedFile.language.id !in HTML_LANGUAGES) return@enumerate
                        PsiTreeUtil.findChildrenOfType(injectedFile, XmlAttribute::class.java).forEach { attribute ->
                            attribute.addTaigaHint(sink) { offset -> manager.injectedToHost(injectedFile, offset) }
                        }
                    }
                }
            }
        }
}

private fun XmlAttribute.addTaigaHint(
    sink: InlayTreeSink,
    mapOffset: (Int) -> Int,
) {
    val attributeName = name
    if (attributeName != "tuiButton" && attributeName != "size") return
    val nameRange = nameElement?.textRange ?: return
    val request = TaigaDocumentationResolver.findRequest(containingFile, nameRange.startOffset) ?: return
    val documentation = resolveDocumentation(request) ?: return
    if (documentation.packageName?.startsWith("@taiga-ui/") != true) return

    val hint =
        when {
            attributeName == "tuiButton" &&
                documentation is TaigaResolvedDocumentation.Entity &&
                documentation.badge == "Directive" ->
                "Taiga UI directive" to nameRange.endOffset

            attributeName == "size" &&
                documentation is TaigaResolvedDocumentation.Member &&
                documentation.kind == TaigaApiMemberKind.INPUT -> {
                val literal = value ?: return
                val label = taigaSizeDescription(literal) ?: return
                if (literal !in documentation.localValues()) return
                label to textRange.endOffset
            }

            else -> return
        }
    sink.addPresentation(
        position = InlineInlayPosition(mapOffset(hint.second), true),
        hintFormat = TAIGA_INLINE_FORMAT,
        tooltip = "Taiga UI · installed API",
    ) {
        text(" ${hint.first}")
    }
}

internal fun taigaSizeDescription(value: String): String? =
    when (value) {
        "xs" -> "extra small"
        "s" -> "small"
        "m" -> "medium"
        "l" -> "large"
        "xl" -> "extra large"
        else -> null
    }

private val HTML_LANGUAGES = setOf("HTML", "Angular2HTML")
private val TAIGA_INLINE_FORMAT =
    HintFormat.default
        .withColorKind(HintColorKind.TextWithoutBackground)
        .withFontSize(HintFontSize.ABitSmallerThanInEditor)
