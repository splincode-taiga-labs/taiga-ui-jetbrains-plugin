import {Component, Directive, Input} from '@angular/core';
import {TuiDay} from '@taiga-ui/cdk';
import {TuiButton, TuiCalendar} from '@taiga-ui/core';

@Directive({selector: 'button[localSized]', standalone: true})
export class SharedSizeDirective {
    @Input() public size: 'm' | 'l' = 'm';
}

@Directive({
    selector: 'button[localHostButton]',
    standalone: true,
    hostDirectives: [{directive: TuiButton, inputs: ['size: hostSize']}],
})
export class ButtonHostDirective {}

@Component({
    selector: 'qa-external',
    standalone: true,
    imports: [TuiButton, TuiCalendar, SharedSizeDirective, ButtonHostDirective],
    templateUrl: './documentation.component.html',
})
export class ExternalDocumentationComponent {
    public selected: TuiDay | null = null;

    public onDayClick(day: TuiDay): void {
        this.selected = day;
    }
}

@Component({
    selector: 'qa-inline',
    standalone: true,
    imports: [TuiButton, TuiCalendar, SharedSizeDirective, ButtonHostDirective],
    template: `
        <button tuiButton localSized size="m">Shared inline binding</button>
        <button localHostButton hostSize="m">Host-directive alias</button>
        <tui-calendar [showAdjacent]="false" (dayClick)="onDayClick($event)" />
    `,
})
export class InlineDocumentationComponent {
    public onDayClick(_day: TuiDay): void {}
}

// Deliberately omitted from this component's imports. Native Angular fixes must target this component.
@Component({
    selector: 'qa-missing-import',
    standalone: true,
    imports: [],
    template: `<tui-calendar />`,
})
export class MissingImportDocumentationComponent {}
