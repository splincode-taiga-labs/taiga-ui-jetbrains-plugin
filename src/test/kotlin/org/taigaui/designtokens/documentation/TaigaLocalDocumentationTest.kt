package org.taigaui.designtokens.documentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TaigaLocalDocumentationTest {
    @Test
    fun `reads pipe semantics from source without executing mapper`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                """
                @Pipe({name: 'tuiMapper'})
                export class TuiMapperPipe {
                    /** @param mapper a mapping function */
                    transform<T extends unknown[], U, G>(value: U, mapper: TuiMapper<[U, ...T], G>, ...args: T): G {
                        return mapper(value, ...args);
                    }
                }
                """.trimIndent(),
            )
        val pipe = requireNotNull(local.pipe)

        assertEquals("tuiMapper", pipe.name)
        assertEquals(listOf("value", "mapper", "args"), pipe.parameters.map(TaigaPipeParameter::name))
        assertTrue(pipe.parameters.last().variadic)
        assertEquals("...args", pipe.parameters.last().presentationName)
        assertEquals("TuiMapper<[U, ...T], G>", pipe.parameters[1].type)
        assertEquals("a mapping function", pipe.parameters[1].description)
        assertEquals("G", pipe.resultType)
        assertEquals("mapper(value, ...args)", pipe.invocation)
        assertEquals(true, pipe.pure)
    }

    @Test
    fun `standalone declaration does not establish pipe purity`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                """
                export declare class TuiFormatNumberPipe {
                    transform(value: number, settings?: Partial<TuiNumberFormatSettings>): string;
                    static ɵpipe: i0.ɵɵPipeDeclaration<TuiFormatNumberPipe, "tuiFormatNumber", true>;
                }
                """.trimIndent(),
            )
        val pipe = requireNotNull(local.pipe)

        assertEquals("tuiFormatNumber", pipe.name)
        assertTrue(pipe.parameters[1].optional)
        assertEquals("string", pipe.resultType)
        assertNull(pipe.pure)
        assertNull(pipe.invocation)
    }

    @Test
    fun `respects explicit impure pipe metadata`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                "@Pipe({name: 'tuiFormatNumber', pure: false}) class TuiFormatNumberPipe {}",
            )

        assertEquals(false, requireNotNull(local.pipe).pure)
    }

    @Test
    fun `does not guess a computed purity flag`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                "@Pipe({name: 'tuiMapper', pure: configuredPurity}) class TuiMapperPipe {}",
            )

        assertNull(requireNotNull(local.pipe).pure)
    }

    @Test
    fun `reads selectors and distinguishes injected defaults from literals`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                """
                @Directive({selector: 'a[tuiButton],button[tuiButton],label[tuiButton]'})
                export class TuiButton {
                    size = input(inject(TUI_BUTTON_OPTIONS).size);
                    background = input('');
                    dynamic = input(computeDefault());
                }
                """.trimIndent(),
            )

        assertEquals("a[tuiButton],button[tuiButton],label[tuiButton]", local.selector)
        assertEquals("TUI_BUTTON_OPTIONS", local.defaults.first { it.name == "size" }.provider)
        assertNull(local.defaults.first { it.name == "size" }.value)
        assertEquals("''", local.defaults.first { it.name == "background" }.value)
        assertFalse(local.defaults.any { it.name == "dynamic" })
    }

    @Test
    fun `reads installed signal input types`() {
        val local =
            TaigaLocalDocumentationParser.parse(
                """
                iconEnd: i0.InputSignal<string | undefined>;
                size: i0.InputSignal<TuiSizeXL | TuiSizeXS>;
                """.trimIndent(),
            )

        assertEquals("string | undefined", local.inputTypes["iconEnd"])
        assertEquals("TuiSizeXL | TuiSizeXS", local.inputTypes["size"])
    }

    @Test
    fun `does not split nested types callback parameters and literal commas`() {
        assertEquals(
            listOf(
                "value: U",
                "mapper: (value: U, index: number) => G",
                "settings: Record<string, number>",
                "suffix: string = ','",
            ),
            splitTypeScriptParameters(
                "value: U, mapper: (value: U, index: number) => G, settings: Record<string, number>, suffix: string = ','",
            ),
        )
    }
}
