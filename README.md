# Taiga xUI Community Plugin

[![codecov](https://codecov.io/gh/splincode-taiga-labs/taiga-ui-jetbrains-plugin/branch/main/graph/badge.svg)](https://codecov.io/gh/splincode-taiga-labs/taiga-ui-jetbrains-plugin)

<!-- marketplace-description:start -->
<p>Taiga UI design tokens, icon previews, and Angular tooling for WebStorm.</p>

<p>
    Taiga xUI Community provides project-aware completion, previews, and inspections for Taiga UI projects.
    It discovers the installed Taiga UI packages and local styles, so suggestions match your project version.
</p>

<h3>Features</h3>
<ul>
    <li>Design token completion and color previews in CSS, Less, and SCSS.</li>
    <li>Resolved token values, source navigation, and project override detection.</li>
    <li>Searchable Token Explorer with theme and platform previews, reference chains, and copy actions.</li>
    <li>Inspections and safe quick fixes for unknown or deprecated tokens.</li>
    <li><code>@tui.*</code> icon completion and SVG previews in Angular, HTML, TypeScript, and JavaScript.</li>
    <li>Visual icon browser with search, SVG previews, and copy/insert actions (Tools → Taiga UI: Browse Icons).</li>
    <li><code>rem</code>-to-<code>px</code> editor hints for styles and Angular bindings.</li>
    <li>Angular event-plugin completion, validation, and hover documentation.</li>
</ul>

<h3>Getting started</h3>
<p>
    Install the plugin in WebStorm and open a Taiga UI project. Type <code>var(--tui-</code> in a stylesheet
    or <code>@tui.</code> in a supported code context to explore available tokens and icons.
    No separate token catalog is needed.
</p>

<p>
    An independent community project, not affiliated with or endorsed by Taiga UI, Pineopticone, or T-Bank.
    <a href="https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin">Source code and issues</a>.
</p>
<!-- marketplace-description:end -->

## About the project

**Taiga xUI Community** is developed in the open by **splincode-taiga-labs**. It helps developers work with Taiga UI projects in JetBrains IDEs while remaining independently maintained.

Use the plugin to look up installed APIs in Quick Documentation or Search Everywhere, browse icons and design tokens,
fix package imports, and diagnose or migrate an Angular or Nx workspace from WebStorm.

This is an **independent community project**, not affiliated with or endorsed by Taiga UI, Pineopticone, or T-Bank. The name Taiga UI is used only to describe compatibility. Contributions, bug reports, and ideas are welcome through GitHub issues and pull requests.

**[Live Angular demo](https://splincode-taiga-labs.github.io/taiga-ui-jetbrains-plugin/)**

The plugin reads the Taiga UI packages installed in the current project, so completion and previews match the version the project actually uses.

## Contextual documentation

Hover a Taiga UI component, directive, pipe, or input/output to see its purpose and relevant API directly in the editor. Input cards show every directive that receives the binding, including inherited declarations and exposed `hostDirectives` aliases, using Angular's selector matching and component import scope. Installed declarations provide cards even when online documentation is unavailable or omits the input. Component and directive cards show parameters immediately; clicking a parameter opens its focused description. Inputs and outputs use vertically stacked rows with compact types, descriptions and known defaults. Empty API tabs are hidden. Pipe cards show their arguments, result, and recalculation behavior when that information is available.

Use **Pin** to keep a card open while editing; **Unpin**, **Close**, or Escape dismiss it when appropriate. Binding cards distinguish the current value from a library default and show required inputs, expanded type aliases, and accepted transform types when available. Literal choices can update an existing static input when its finite string type is confirmed by every receiver of that binding. The edit supports Undo and refuses to overwrite a binding that changed after opening the card. Larger editable sets have value search. Dynamic expressions and documentation-only choices remain informational.

Hovering `iconEnd`, `iconStart`, or another icon input with a static `@tui.*` value shows its SVG preview and a **Choose icon** action directly in the input card. The chooser also fills an existing empty string input, searches the project's existing icon catalog, and replaces the selected literal with undo support. Element cards also offer icon previews. Dynamic expressions remain untouched.

Cards can add a missing required input with an empty expression ready to fill in, or rename a deprecated binding when the installed declaration explicitly identifies a compatible replacement. Both actions support Undo and check that the element has not changed. Adding a required input keeps keyboard focus in the editor so its expression can be entered immediately. Cards link to locally resolved source definitions and official documentation; examples are opened on demand.

Use the Inputs, Outputs and Examples tabs to inspect the installed API. A parameter opens its focused card; Back returns to the previous view. The header links to the official documentation. Code examples use the editor color scheme and flattened single-element examples receive readable indentation. Cards have no Details disclosure or clipboard buttons. Angular quick fixes opens the IDE's context actions at the tracked source target, including available import fixes. Use WebStorm's **Quick Documentation** action or open documentation from the completion list for the native view.

Use **Show Taiga UI Card** from Find Action or the editor context menu, or press **Alt+Shift+Q**, to open the same interactive card at the caret without mouse hover. Use **Tab/Shift+Tab** to reach its actions, **Enter/Space** to activate buttons and links, **Escape** to close it. The explicit action works even when mouse-hover documentation is disabled in IDE settings. Loading and indexing show progress; an unavailable caret target offers Angular quick fixes.

Code changes invalidate the card's source-changing actions, including changes to component imports and installed declarations. A stale card keeps navigation available and offers **Refresh** / **F5** to resolve the current API. Refresh preserves the browsed member, Back navigation, scroll position, and a pinned card's position and size. Successful value and template edits refresh the card automatically; Undo/Redo require refreshing before another edit. If its source target was deleted or renamed outside the card, reopen the card at the caret.

## Browse and search the Taiga UI API

Open **Taiga UI** on the right-hand tool window bar to browse components, directives, pipes and other APIs by category. The search field filters the tree by name, public symbol or selector. Select an entry to see its package and summary, then press **Enter** in the tree or click **Open Documentation**.

Open the **Taiga UI** tab in Search Everywhere (press **Shift** twice) for the same results without leaving the editor. Both views use the cached documentation for the Taiga UI version installed for the active file, or the project root when no file is selected.

## Project Doctor

Open **Taiga UI → Doctor** to see installed packages and their versions for the active file. The tab flags differences
within the Taiga UI release family and shows independently versioned packages in the same list. In a monorepo, open a
file from the application you want to check. Select a package and click **Open package.json** to inspect its installed
manifest. Click **Reload** after installing or updating dependencies.

The Doctor tab also shows which installed packages provide design tokens, the configured icon source, and the
documentation cache status for the active Taiga UI version. Open the corresponding documentation directly from the tab.

Click **Analyze App** to run the Taiga UI inspections for the active application. WebStorm groups wrong-package imports,
unknown or deprecated design tokens, and invalid event modifiers in its inspection results. Select a finding to jump to
the source and use the existing quick fix when one is available.

To upgrade from Taiga UI 4, right-click a workspace folder in the Project view and select **Taiga UI → Run v4 to v5 Migration**.
Confirm the workspace directory to run the official Angular CLI or Nx migration. Follow the output in the **Run** window.
The workspace must have Taiga UI CDK 4 and its local CLI installed.

If Nx has already generated a plan, use **Taiga UI → Run Existing Nx Migration Plan** to review and run
`migrations.json` for the workspace. Nx runs the entire file. Steps that already ran may run again.

With WebStorm's MCP Server enabled, a connected agent can inspect the saved plan and run it after you confirm in the
IDE. The [MCP tools](#mcp-tools-for-coding-agents) also expose project diagnostics, API lookup, tokens, and icons.

With Taiga UI 5 installed, click **Analyze v4 to v5 Migration** to review the remaining findings. The results
include migration TODOs, remaining `@taiga-ui/legacy` imports, deprecated design tokens, and wrong-package imports.
Select a finding to open the source. The guide is available in the Doctor tab and in the migration inspection descriptions.

Click **Copy Report** in the Doctor tab to copy a Markdown summary of installed packages, version mismatches, and source
status. It contains no local file paths, so it can be shared in an issue or team chat.

## Taiga UI release notifications

WebStorm's **Notifications** tool window shows when a newer stable release is available for the Taiga UI major version
used by your active workspace. Select **View release on GitHub** to see the release notes. Manage these entries under
**Settings → Appearance & Behavior → Notifications → Taiga UI Releases**.

## MCP tools for coding agents

Connect an agent to WebStorm's **MCP Server** to inspect a project and run Taiga UI actions. Pass a file path
relative to the project root to select the right Angular or Nx app when a monorepo contains multiple versions.

| Area                                  | MCP tools                                                                  |
| ------------------------------------- | -------------------------------------------------------------------------- |
| Project Doctor and releases           | `taiga_ui_inspect_project`, `taiga_ui_check_release`                       |
| API docs and installed bindings       | `taiga_ui_search_api`, `taiga_ui_read_api`, `taiga_ui_api_at`              |
| Design tokens and exact color matches | `taiga_ui_search_tokens`, `taiga_ui_resolve_token`, `taiga_ui_match_color` |
| Folder color replacement              | `taiga_ui_preview_folder_colors`, `taiga_ui_apply_folder_colors`           |
| Icon catalog and CSS units            | `taiga_ui_search_icons`, `taiga_ui_convert_rem`                            |
| New Angular or Nx migration           | `taiga_ui_inspect_migration`, `taiga_ui_run_migration`                     |
| Saved Nx migrations                   | `taiga_ui_inspect_nx_migration_plan`, `taiga_ui_run_nx_migration_plan`     |

To make a change, preview it and pass the returned `revision` to the Run or Apply tool with the same directory.
WebStorm shows a confirmation before either action changes the workspace.

WebStorm's built-in `get_file_problems` tool also reports enabled Taiga UI inspections, including incorrect imports,
unknown or deprecated design tokens, and invalid event modifiers. See the [MCP sandbox guide](docs/local-debugging.md#mcp-tools)
for setup and sample calls.

## TypeScript imports

When a named import uses the wrong Taiga UI package, press **Alt+Enter** on the highlighted symbol to move it to the package that exports it. Suggestions use the packages installed for the current file and are available when exactly one package exports the symbol.

## Design tokens

### Token Explorer

Open **Taiga UI → Tokens** and search by token name. Select a result to see its resolved values, color previews,
project overrides, and reference chains. Use the source icon beside a value to open its declaration, or **Copy var()**
to copy a CSS reference. The list follows the active file's Taiga UI scope and marks names that are only known to the
catalog. Use **Reload** after editing styles or installing packages while staying in the same file.

### Replace a color with a token

Put the caret on a standalone hex, `rgb()`, or `rgba()` value in CSS, Less, or SCSS and press **Alt+Enter**. Choose
**Replace with var(…)** or select a token from the list when several share the color. The action uses the active
project's token values and offers matches that resolve to the same color across Taiga UI themes and platforms.
Internal `--tui-const-*` tokens are excluded from suggestions.

Right-click a folder in the **Project** tree and choose **Taiga UI → Replace Colors with Taiga UI Tokens** to process
CSS, Less, and SCSS files in that folder and its subfolders. Select a file in the preview to inspect its diff and click
**Apply Changes** to update the stylesheets. A single exact match replaces the color. When several tokens match, the
preview shows a CSS comment with the options beside the original color. **Cancel** discards the preview. Undo reverts
all edits from the run together.

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

The plugin supports **WebStorm 2025.3 through 2026.3.x** (IntelliJ Platform builds `253` through `263.*`).

CI runs JetBrains Plugin Verifier against:

- **WebStorm 2025.3** — the minimum supported IDE;
- **WebStorm 2026.1.5** — the earlier supported major version;
- **WebStorm 2026.2.3** — the previous major version;
- **WebStorm 2026.3** — the latest supported major version, including build `WS-263.7307.202`.

The plugin is built against WebStorm 2025.3.6 and targets Java 21 bytecode. Compatibility with new IDE major versions is checked by the JetBrains Plugin Verifier before publication.

## Developer documentation

- [Architecture](docs/ARCHITECTURE.md)
- [Implementation roadmap](docs/roadmap.md)
- [Local debugging](docs/local-debugging.md)
- [Releasing](docs/releasing.md)
