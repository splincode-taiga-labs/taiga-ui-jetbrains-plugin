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

@Suppress("ReturnCount")
private fun XmlAttribute.addTaigaHint(
    sink: InlayTreeSink,
    mapOffset: (Int) -> Int,
) {
    if (name != "tuiButton" && name != "size") return
    val range = nameElement?.textRange ?: return
    val request = TaigaDocumentationResolver.findRequest(containingFile, range.startOffset) ?: return
    val documentation = resolveDocumentation(request) ?: return
    if (documentation.packageName?.startsWith("@taiga-ui/") != true) return
    val hint = resolvedTaigaHint(documentation, range.endOffset) ?: return

    sink.addPresentation(
        position = InlineInlayPosition(mapOffset(hint.second), true),
        hintFormat = TAIGA_INLINE_FORMAT,
        tooltip = "Taiga UI · installed API",
    ) {
        text(" ${hint.first}")
    }
}

private fun XmlAttribute.resolvedTaigaHint(
    documentation: TaigaResolvedDocumentation,
    nameEnd: Int,
): Pair<String, Int>? =
    when (documentation) {
        is TaigaResolvedDocumentation.Entity ->
            if (name == "tuiButton" && documentation.badge == "Directive") {
                "Taiga UI directive" to nameEnd
            } else {
                null
            }

        is TaigaResolvedDocumentation.Member ->
            if (name == "size" && documentation.kind == TaigaApiMemberKind.INPUT) {
                value
                    ?.takeIf { it in documentation.localValues() }
                    ?.let(::taigaSizeDescription)
                    ?.let { it to textRange.endOffset }
            } else {
                null
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
