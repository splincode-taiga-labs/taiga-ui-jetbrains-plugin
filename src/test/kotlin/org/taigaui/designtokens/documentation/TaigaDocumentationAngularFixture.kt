package org.taigaui.designtokens.documentation

import com.intellij.testFramework.fixtures.CodeInsightTestFixture

/** Angular metadata and installed declarations shared by resolver and controller regressions. */
internal object TaigaDocumentationAngularFixture {
    fun install(fixture: CodeInsightTestFixture) {
        create(
            fixture,
            "angular.json",
            """{"projects":{"test":{"projectType":"application","root":"","sourceRoot":"src"}}}""",
        )
        create(
            fixture,
            "package.json",
            """{"dependencies":{"@angular/core":"17.3.0","@taiga-ui/core":"5.18.0"}}""",
        )
        create(
            fixture,
            "node_modules/@angular/core/package.json",
            """{"name":"@angular/core","version":"17.3.0","types":"index.d.ts"}""",
        )
        create(fixture, "node_modules/@angular/core/index.d.ts", CORE_DECLARATIONS)
        create(
            fixture,
            "node_modules/@taiga-ui/core/package.json",
            """{"name":"@taiga-ui/core","version":"5.18.0","types":"index.d.ts"}""",
        )
        create(fixture, "node_modules/@taiga-ui/core/index.d.ts", TAIGA_DECLARATIONS)
        create(fixture, "src/component.ts", CONSUMER)
    }

    private fun create(
        fixture: CodeInsightTestFixture,
        path: String,
        text: String,
    ) {
        fixture.tempDirFixture.createFile(path, text.trimIndent())
    }

    const val CORE_DECLARATIONS = """
        export interface ComponentMetadata { selector?: string; templateUrl?: string; template?: string; standalone?: boolean; imports?: unknown[]; }
        export declare function Component(metadata: ComponentMetadata): ClassDecorator;
        export declare function Directive(metadata: ComponentMetadata): ClassDecorator;
        export declare function Input(alias?: string): PropertyDecorator;
        export type ɵɵDirectiveDeclaration<T, Selector, ExportAs, Inputs, Outputs, Queries, Content = never, Standalone = false, HostDirectives = never> = unknown;
        export declare const ɵINPUT_SIGNAL_BRAND_WRITE_TYPE: unique symbol;
        export interface InputSignalWithTransform<T, W> { [ɵINPUT_SIGNAL_BRAND_WRITE_TYPE]: W; }
        export interface InputSignal<T> extends InputSignalWithTransform<T, T> {}
    """
    const val TAIGA_DECLARATIONS = """
        import * as i0 from '@angular/core';
        export type TuiSize = 's' | 'm' | 'l';
        export declare class TuiBase {
            baseInput: string;
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiBase, "[tuiBase]", never, {"baseInput": "baseInput"}, {}, never, never, true>;
        }
        export declare class TuiWithIcons {
            icon: string;
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiWithIcons, "[tuiWithIcons]", never, {"icon": "icon"}, {}, never, never, true>;
        }
        export declare class TuiButton extends TuiBase {
            internalSize: TuiSize;
            enabled: i0.InputSignalWithTransform<boolean, boolean | string>;
            iconStart: string;
            /** @deprecated Use newSize instead. */
            oldSize: string;
            newSize: string;
            /** @deprecated Use newAppearance instead. */
            legacyAppearance: string;
            newAppearance: string;
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiButton, "button[tuiButton]", never,
                {"internalSize": {"alias": "size"; "required": true}; "enabled": {"alias": "enabled"; "required": false; "isSignal": true}; "iconStart": "iconStart"; "oldSize": "oldSize"; "newSize": "newSize"; "legacyAppearance": "legacyAppearance"; "newAppearance": "appearance"}, {}, never, never, true,
                [{directive: typeof TuiWithIcons; inputs: {"icon": "iconEnd"}; outputs: {}}]>;
        }
        export declare class TuiAux {
            size: 'm' | 'l';
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiAux, "button[tuiAux]", never, {"size": "size"}, {}, never, never, true>;
        }
        export declare class TuiUnimported {
            size: 'l';
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiUnimported, "button[tuiUnimported]", never, {"size": "size"}, {}, never, never, true>;
        }
        export declare class TuiHint {
            hint: string;
            static ɵdir: i0.ɵɵDirectiveDeclaration<TuiHint, "[tuiHint]", never, {"hint": "tuiHint"}, {}, never, never, true>;
        }
    """
    const val CONSUMER = """
        import {Component, Directive, Input} from '@angular/core';
        import {TuiButton, TuiAux, TuiHint} from '@taiga-ui/core';
        type LocalSize = 's' | 'm';
        @Directive({selector: 'button[localSized]', standalone: true})
        export class LocalSized { @Input() size: LocalSize = 'm'; }
        @Component({selector: 'example', templateUrl: './component.html', standalone: true, imports: [TuiButton, TuiAux, TuiHint, LocalSized]})
        export class ExampleComponent { flag = true; hint = 'text'; }
    """
}
