# Taiga UI Companion

[![codecov](https://codecov.io/gh/splincode-taiga-labs/taiga-ui-jetbrains-plugin/branch/main/graph/badge.svg)](https://codecov.io/gh/splincode-taiga-labs/taiga-ui-jetbrains-plugin)

Community WebStorm plugin that brings project-aware Taiga UI tooling directly into the editor.

## About the project

Taiga UI Companion is developed in the open by **splincode-taiga-labs**, an independent community initiative focused on improving the developer experience around Taiga UI in JetBrains IDEs.

The project aims to make the information that already exists in a Taiga UI codebase — design tokens, icons, event-plugin modifiers, local overrides, and CSS units — discoverable directly in the editor, without maintaining a separate hard-coded catalog.

This project is **not an official Taiga UI or T-Bank product**. Contributions, bug reports, and ideas are welcome through GitHub issues and pull requests.

**[Live Angular demo](https://splincode-taiga-labs.github.io/taiga-ui-jetbrains-plugin/)**

The plugin reads the Taiga UI packages installed in the current project, so completion and previews match the version the project actually uses.

## Contextual documentation

Hover a Taiga UI component, directive, pipe, or input/output to see its purpose and relevant API directly in the editor. Component and directive cards show parameters immediately; clicking a parameter opens its focused description. Directive cards can explain applicable elements and documented local defaults. Pipe cards show their arguments, result, and recalculation behavior when that information is available.

When an element has a static `@tui.*` icon value, its card can show an SVG preview and a **Choose icon** action. The chooser searches the project's existing icon catalog and replaces the selected literal with undo support. Dynamic expressions remain untouched.

Cards link to full documentation and locally resolved source definitions. Examples are opened on demand. Standard Quick Documentation and completion documentation remain available.

## Design tokens

### Completion

Inside CSS, Less, and SCSS `var(...)` expressions, typing `--tui-` opens WebStorm's native completion with design-token names from installed Taiga UI packages and project styles.

```css
.button {
    color: var(--tui-text-primary);
}
```

The selected completion item shows a side preview with the effective value, platform/theme variants, and a color swatch when applicable. Deprecated tokens remain discoverable but are visually marked as deprecated.

### Hover preview

Hovering a complete Taiga UI token reference shows its resolved values and sources.

The popup can display:

- effective desktop/mobile and light/dark values;
- color previews;
- project overrides and installed-package values;
- reference chains for tokens that use other `var(...)` values;
- deprecation details and an explicit replacement when the installed token source provides one;
- navigation to the source declaration.

### Unknown-token inspection

Unknown `--tui-*` references are highlighted in CSS, Less, and SCSS.

```css
.demo {
    color: var(--tui-text-primry);
}
```

When a safe match exists, the plugin offers a `Replace with ...` quick fix. Ambiguous or distant names remain warnings without an unsafe automatic replacement.

### Deprecated-token inspection

When an installed or project token declaration is explicitly documented with `@deprecated`, usages are marked as deprecated in CSS, Less, and SCSS. If the metadata names exactly one replacement `--tui-*` token, the plugin offers a `Replace with ...` quick fix.

Replacement names are read from the token source metadata for the installed project version; the plugin does not infer migration targets by fuzzy matching. A project-defined override suppresses package deprecation for the same token name unless the local declaration is itself explicitly deprecated.

### Project overrides

Token completion and value previews include reachable project CSS/Less/SCSS declarations in addition to installed Taiga UI packages.

Project overrides are shown separately from package values so it is clear which value is effectively applied.

## CSS units

### `rem` to `px` inlay hints

CSS, Less, and SCSS declarations with literal `rem` values show their pixel equivalents as unobtrusive editor hints using the browser default root size of `16px`. The same hints are shown for CSS injected into Angular component `styles` template literals.

```css
gap: 1rem;             16px
min-width: 21rem;      336px
```

Angular template style bindings with numeric `rem` literals show the same conversion next to the binding value:

```html
<tui-icon [style.font-size.rem]="1" />
<!-- editor hint: 16px -->
<tui-icon [style.border-width.rem]="0.25" />
<!-- editor hint: 4px -->
```

Dynamic expressions such as `[style.width.rem]="size"` are not evaluated. Multiple `rem` values on the same stylesheet declaration are shown in source order. Inlay hints are visual editor decorations and do not modify the source file.

## Icons

### `@tui.*` completion

Inside JavaScript, TypeScript, HTML, and Angular templates, typing `@tui.` opens native completion with icon names available to the current project.

```ts
const arrow = '@tui.a-arrow-down';
const flag = '@tui.flags.ab';
```

```html
<button iconStart="@tui.fancy.medium.info-circle">Save</button>
<button [iconStart]="'@tui.fancy.medium.info-circle'">Save</button>
```

Nested icon directories become dot-separated names. For example:

```text
@taiga-ui/icons/src/flags/ab.svg
→ @tui.flags.ab
```

### Public and proprietary icons

For public projects, icon names come from the installed `@taiga-ui/icons` package.

When `@taiga-ui/proprietary` is installed, the plugin uses proprietary icons instead:

1. installed `@taiga-ui/tds-icons` when available;
2. otherwise the T-Bank design-token icon catalog as a fallback.

### Icon preview

Selecting an `@tui.*` completion item shows its SVG preview beside the completion list.

Hovering a complete icon reference shows the same preview after a short delay. The preview is HiDPI-aware and remains sharp on Retina displays.

## Project-aware discovery

The plugin does not ship a fixed catalog of token or icon names. It discovers data from the current project, including the nearest installed Taiga UI packages and reachable project styles.

This allows different projects or monorepo packages to use different Taiga UI versions without changing plugin configuration.

## Settings

The plugin adds an application-scoped settings page under **Settings → Tools → Taiga UI**.

Two editor features can be controlled independently:

- **Show design token completion preview** — shows the side preview for the currently selected `--tui-*` completion item, including resolved values and color swatches.
- **Show design token hover popup** — shows the custom token popup when hovering a complete `var(--tui-...)` reference.

Both options are enabled by default. Changes affect editor presentation only and do not change token resolution, source precedence, indexing, or cache behavior.

## Compatibility

The plugin currently supports **WebStorm 2025.3 through 2026.2.x** (IntelliJ Platform builds `253` through `262.*`).

CI runs JetBrains Plugin Verifier against:

- **WebStorm 2025.3** — the minimum supported IDE;
- **WebStorm 2026.2.2** — the current supported IDE used as the upper compatibility boundary.

The plugin is built against WebStorm 2025.3.6 and targets Java 21 bytecode. Support for a newer IDE major version should be added only after updating the compatibility range and adding that version to the verifier matrix.

## Developer documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Implementation roadmap](docs/roadmap.md)
- [Local debugging](docs/local-debugging.md)
- [Releasing](docs/releasing.md)
