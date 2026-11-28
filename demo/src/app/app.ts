import { ChangeDetectionStrategy, Component, ElementRef, computed, signal, viewChild } from '@angular/core';

type Category = 'All features' | 'Design tokens' | 'Icons' | 'CSS hints' | 'Events' | 'Angular API' | 'Settings';

interface Feature {
  readonly category: Category;
  readonly label: string;
  readonly title: string;
  readonly description: string;
  readonly image: string;
  readonly alt: string;
}

@Component({
  selector: 'app-root',
  templateUrl: './app.html',
  styleUrl: './app.css',
  changeDetection: ChangeDetectionStrategy.OnPush,
})
export class App {
  protected readonly repositoryUrl = 'https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin';
  protected readonly marketplaceUrl = 'https://plugins.jetbrains.com/plugin/34582';
  protected readonly categories: readonly Category[] = [
    'All features',
    'Design tokens',
    'Icons',
    'CSS hints',
    'Events',
    'Angular API',
    'Settings',
  ];
  protected readonly category = signal<Category>('All features');
  protected readonly selectedFeature = signal<Feature | null>(null);
  private readonly previewDialog = viewChild.required<ElementRef<HTMLDialogElement>>('previewDialog');

  protected readonly features: readonly Feature[] = [
    {
      category: 'Design tokens',
      label: 'Design token preview',
      title: 'One token. Every theme and platform.',
      description: 'See resolved values, color swatches, and reference chains together in a single hover.',
      image: 'screens/design-token-preview.webp',
      alt: 'WebStorm hover showing desktop, mobile, light, and dark values of --tui-background-base',
    },
    {
      category: 'Design tokens',
      label: 'Folder color replacement',
      title: 'Replace colors across a folder.',
      description:
        'Open Taiga UI in the Project tree context menu to replace exact color matches. When several public tokens match, the plugin leaves a review comment.',
      image: 'screens/folder-color-replacement.webp',
      alt: 'WebStorm’s Project tree context menu with Taiga UI and Replace Colors with Taiga UI Tokens selected',
    },
    {
      category: 'Angular API',
      label: 'Angular API card',
      title: 'Explore Taiga UI APIs in your editor.',
      description: 'Read Angular inputs, imports, and examples in the interactive card without leaving your template.',
      image: 'screens/angular-api-card.webp',
      alt: 'The Taiga UI card for TuiButton showing its Angular import, Inputs tab, and an example template',
    },
    {
      category: 'Icons',
      label: 'Icon hover preview',
      title: 'Know exactly which icon you are using.',
      description: 'Hover an @tui reference to see the icon right beside your code.',
      image: 'screens/icon-hover-preview.webp',
      alt: 'An eye icon preview beside a complete @tui icon reference in WebStorm',
    },
    {
      category: 'Icons',
      label: 'Icon autocomplete',
      title: 'Find the right icon as you type.',
      description: 'Browse icons available in your project, with a preview of the selected completion.',
      image: 'screens/icon-autocomplete.webp',
      alt: 'WebStorm icon completion in an Angular template with an emoji bank icon preview',
    },
    {
      category: 'Icons',
      label: 'Fancy icon autocomplete',
      title: 'Explore the whole icon family.',
      description: 'Nested icon names and fancy icons get the same native completion and visual previews.',
      image: 'screens/fancy-icon-autocomplete.webp',
      alt: 'WebStorm completing @tui.fancy icon names with a ruble currency icon preview',
    },
    {
      category: 'CSS hints',
      label: 'CSS unit hints',
      title: 'Less mental math. More flow.',
      description: 'See rem values in pixels, inline in stylesheets and Angular style bindings. Based on 1rem = 16px.',
      image: 'screens/css-unit-hint.webp',
      alt: 'An Angular style binding showing the inline conversion of 0.25rem to 4px',
    },
    {
      category: 'Events',
      label: 'Event plugin completions',
      title: 'The right modifier, without the lookup.',
      description: 'Discover ng-event-plugins modifiers and their documentation while writing a template.',
      image: 'screens/event-plugin-completions.webp',
      alt: 'WebStorm completing capture, once, prevent, and other ng-event-plugins modifiers with documentation',
    },
    {
      category: 'Events',
      label: 'Host binding completions',
      title: 'Helpful in your component, too.',
      description: 'Get contextual Taiga UI event suggestions, including longtap, inside TypeScript host bindings.',
      image: 'screens/longtap-completion.webp',
      alt: 'WebStorm suggesting the longtap event with its description inside Angular component host metadata',
    },
    {
      category: 'Settings',
      label: 'Plugin settings',
      title: 'Configure Taiga UI in WebStorm.',
      description: 'Open Settings → Tools → Taiga UI to manage editor previews, network access, and MCP permissions.',
      image: 'screens/plugin-settings.webp',
      alt: 'Taiga UI settings showing design token previews, API cards, network access, and MCP controls',
    },
  ];

  protected readonly visibleFeatures = computed(() =>
    this.features.filter((feature) => this.category() === 'All features' || feature.category === this.category()),
  );
  protected readonly selectedIndex = computed(() => {
    const selected = this.selectedFeature();
    return selected ? this.features.indexOf(selected) : -1;
  });

  protected openPreview(feature: Feature): void {
    this.selectedFeature.set(feature);
    this.previewDialog().nativeElement.showModal();
  }

  protected closePreview(): void {
    this.previewDialog().nativeElement.close();
  }

  protected movePreview(direction: number, event?: Event): void {
    event?.preventDefault();
    const index = (this.selectedIndex() + direction + this.features.length) % this.features.length;
    this.selectedFeature.set(this.features[index] ?? null);
  }

  protected closeOnBackdrop(event: MouseEvent): void {
    const dialog = this.previewDialog().nativeElement;
    const bounds = dialog.getBoundingClientRect();

    if (
      event.target === dialog &&
      (event.clientX < bounds.left ||
        event.clientX > bounds.right ||
        event.clientY < bounds.top ||
        event.clientY > bounds.bottom)
    ) {
      this.closePreview();
    }
  }
}
