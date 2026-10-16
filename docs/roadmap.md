# Taiga UI Companion roadmap

Taiga UI Companion is developed in small, reviewable increments. Completed stages are kept here as historical milestones; active implementation details and acceptance criteria live in GitHub issues and epics.

Each production change should leave the project in a buildable state and pass the relevant tests, ktlint, detekt, plugin structure checks, and CI.

## Current roadmap

### Release follow-up

The production publishing pipeline is already in place and production releases have shipped through **v0.1.3**.

[#54 — Prepare the first production-ready plugin release](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/54) remains open only for final release follow-up:

- [x] signing and Marketplace publishing are implemented via [#53](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/53);
- [x] production release notes/changelog follow-up is completed by [#79](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/pull/79);
- [x] production releases are published, latest: [v0.1.3](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/releases/tag/v0.1.3);
- [ ] install the Marketplace-delivered artifact on a supported IDE and smoke-test the core token/icon workflows.

### Stage 8 — Taiga UI Companion

Status: planned / in progress.

The current product roadmap is tracked by [#109 — Taiga UI Developer Companion for WebStorm](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/109).

The main direction is to grow from design-token tooling into a broader project-aware Taiga UI companion while keeping installed project packages as the source of truth.

#### Phase 1 — Documentation foundation

- [ ] [#99 — Version-aware Taiga UI documentation index](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/99)

Build a structured, cached, version-aware documentation index using official Taiga UI documentation as enrichment while keeping installed packages authoritative for actual API availability.

#### Phase 2 — Documentation inside the editor

- [ ] [#100 — Taiga UI component docs in Quick Documentation](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/100)
- [ ] [#101 — Documentation for Taiga UI inputs and outputs](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/101)
- [ ] [#102 — Taiga UI Search Everywhere contributor](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/102)

Prefer native IntelliJ/WebStorm surfaces such as Quick Documentation and Search Everywhere instead of introducing parallel custom UI.

#### Phase 3 — Safer coding assistance

- [ ] [#103 — Inspect incorrect Taiga UI import packages and provide a quick fix](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/103)

Installed `node_modules/@taiga-ui/*` exports remain authoritative for diagnostics and source-changing fixes.

#### Phase 4 — Visual discovery

- [ ] [#104 — Visual Taiga UI icon browser and picker](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/104)
- [ ] [#105 — Taiga UI design token explorer](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/105)
- [ ] [#106 — Suggest Taiga UI design tokens for literal CSS values](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/106)

These features should reuse the existing icon catalog, token index, resolver, source navigation, and project-aware precedence instead of creating duplicate discovery pipelines.

#### Phase 5 — Project-level workflows

- [ ] [#107 — Taiga UI project doctor](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/107)
- [ ] [#108 — Taiga UI migration assistant for remaining v4 → v5 issues](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/108)

Project Doctor should establish reusable project-level diagnostics. Migration Assistant can then build on those diagnostics plus official migration documentation.

#### Recommended implementation order

```text
#99   Documentation index
 ↓
#100  Component Quick Documentation
 ↓
#101  Inputs / Outputs documentation
 ↓
#102  Search Everywhere
 ↓
#103  Import inspection + quick fix
 ↓
#104  Icon Browser
 ↓
#105  Token Explorer
 ↓
#106  CSS value → Taiga token
 ↓
#107  Project Doctor
 ↓
#108  Migration Assistant
```

The detailed dependency map, architecture principles, and Definition of Done are maintained in [Epic #109](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/109) to avoid duplicating implementation-level requirements here.

## Completed milestones

## Stage 1 — Project scaffold

Status: implemented.

- Configure Kotlin and IntelliJ Platform Gradle Plugin 2.x.
- Target WebStorm with bundled JavaScript and CSS plugins.
- Add plugin metadata, a smoke test, and CI.
- Document the intended architecture and local development commands.

## Stage 2 — Package discovery and token index

Status: implemented.

- Locate the nearest `node_modules/@taiga-ui/design-tokens` package for the current project module.
- Read the installed package version from `package.json`.
- Extract CSS, SCSS, and Less custom-property declarations through the bundled stylesheet PSI.
- Preserve each physical declaration's raw value, source file, line number, and outer-to-inner selector chain.
- Classify declarations as mobile when they are published under a `mobile` path or enclosed by a `[tuiPlatform='android'|'ios']` or `[data-platform='android'|'ios']` selector; classify every other declaration as desktop.
- Classify declarations as light or dark when the context is encoded by the source path or a `[tuiTheme='light'|'dark']` selector; retain an unspecified theme when neither theme is encoded.
- Treat a selector list whose branches all express the same platform/theme context as one physical declaration and one logical context, regardless of attribute order or descendant-selector layout.
- Treat CSS, Less, and SCSS as parallel source representations rather than separate semantic variants.
- Group only exact duplicates with the same token name, context, and raw value into one logical variant.
- Retain every physical CSS, Less, and SCSS origin together with its selector chain on the logical variant.
- Keep different raw values separate until recursive resolution, even when they may resolve to the same terminal value.
- Cache immutable indexes by normalized real package root and installed version.
- Share one cache entry between logical npm or pnpm aliases that resolve to the same real package.
- Replace stale entries when the installed version changes or one logical package path resolves to another real target.
- Invalidate only the package affected by CSS, SCSS, Less, `package.json`, package-root, or relevant directory changes.
- Keep unrelated project files, package documentation, and other monorepo packages cached.
- Remove affected entries on VFS events and rebuild lazily on the next request instead of scanning during the write action.
- Serialize concurrent cache misses so one installed package is scanned only once.
- Do not retain a failed build as a permanent cache result.
- Cover npm, pnpm symlinks, monorepo layouts, selector nesting, selector lists, VFS changes, concurrent requests, and the pinned real npm package with tests.

## Stage 3 — Recursive value resolution

Status: implemented.

- Parse nested and compound `var(...)` expressions with a balanced scanner rather than regex replacement.
- Ignore `var(...)` text inside quoted strings and comments.
- Resolve multiple references inside one value while preserving all non-reference text.
- Resolve token-to-token references recursively until a terminal value is reached.
- Preserve the active resolution context when a mobile lookup selects a compatible desktop declaration.
- For a mobile dark reference, prefer candidates in this order: mobile/dark, mobile/unspecified, desktop/dark, desktop/unspecified.
- For a mobile light reference, use the equivalent light order and never fall back to a conflicting theme.
- For a desktop reference, stay on desktop and prefer the exact theme before an unspecified theme.
- For an unspecified theme, use only unspecified-theme candidates and never guess between light and dark.
- Treat multiple distinct candidates at the same precedence as ambiguous instead of silently choosing one.
- Keep raw declaration values unchanged and return resolved values as separate structured results.
- Preserve the complete nested reference tree, selected variants, declaration contexts, active contexts, and fallback usage.
- Support nested and empty `var(...)` fallbacks.
- Use a fallback for missing or invalid referenced values, while keeping static ambiguity explicit.
- Detect cycles with the active context included in the resolution node.
- Do not allow a fallback inside a cyclic token definition to hide its own cycle; allow an outer consumer fallback to recover from an invalid cyclic token.
- Return explicit missing, ambiguous, circular, and invalid-expression reasons.
- Detect terminal hex, named, and standard CSS color-function values for later previews.
- Collapse equivalent resolved terminal values for presentation, including canonical color equivalence, without losing root or reference-tree origins.
- Expose grouped resolution results through the project service.
- Cover pure parser and resolver semantics, project-service cache invalidation, and the pinned real npm package with tests.

## Stage 4 — Token hover and navigation

Status: implemented.

- Detect a `--tui-*` custom property under the pointer only when it is the first argument of `var(...)`.
- Support CSS, SCSS, and Less source files, including Angular inline styles injected into TypeScript.
- Support offsets at the beginning, middle, end, and immediately after a token name.
- Support several `var(...)` references in one value and token references nested inside fallbacks.
- Ignore declarations, comments, strings, unrelated custom properties, unsupported files, and unknown Taiga UI tokens.
- Register one editor mouse-motion listener and debounce hover requests.
- Render one custom Swing popup instead of combining it with IntelliJ Quick Documentation.
- Keep the popup within the current screen and truncate long values with the full text available in a tooltip.
- Present grouped contexts and immediately useful final values in `Platform` and `Value` rows.
- Keep intermediate `var(...)` expressions out of the summary and expose them in the reference chain instead.
- Preserve and display the original CSS color notation while using the canonical value for grouping and swatch painting.
- Collapse a complete set of equivalent desktop/mobile and light/dark contexts into one explicit applicability label.
- Keep incomplete context combinations listed explicitly instead of implying broader applicability.
- Show recursively resolved terminal values and keep distinct chains in one left-aligned vertical list.
- Keep `Reference chain` collapsed by default and expand it on demand as an accordion.
- Extract a token description from an adjacent CSS, Less, or SCSS comment when all discovered descriptions agree.
- Keep source files, line numbers, and selector contexts in the resolution model.
- Show a checkerboard-backed color swatch for terminal colors.
- Explain missing, ambiguous, circular, and invalid values instead of pretending that one runtime value is known.
- Add `Go to definition` and `Report a bug` actions.
- Cover offset detection, popup-model mapping, and comment extraction with pure tests.
- Document sandbox launch and debugger attachment against a real local project.

## Stage 5 — Production editor features

Status: implemented.

Implemented:

### Project-aware tokens

- Discover project-level global stylesheet entrypoints from Angular and Nx configuration.
- Build a project stylesheet graph across local CSS, Less, and Sass `@import`, `@use`, and `@forward` edges.
- Resolve project-level token overrides before installed Taiga UI package declarations while preserving platform/theme scope.
- Preserve project cascade/source order for reachable stylesheets and equal-scope declarations.
- Keep overridden project and package declarations visible as explicit `Not applied` rows.
- Keep cold project/package graph builds off the UI thread and outside cache monitors.

### Token completion and inspection

- Complete installed and project-defined `--tui-*` token names inside the first argument of CSS `var(...)` without hardcoding a token catalog.
- Register completion explicitly for CSS, Less, and SCSS and auto-open WebStorm's native completion lookup while a `--tui-*` token is typed.
- Reuse the existing package/project indexes for completion and warm cold completion data in the background without invalidating an in-progress token typing session.
- Keep WebStorm's native completion lookup as the primary list instead of rendering a competing token list.
- Suppress and close the hover popup while the native completion lookup is open.
- Show a non-focusable side preview for the currently selected `--tui-*` completion item with effective platform/theme values and color swatches.
- Refresh the side preview as the selected completion item changes through keyboard navigation.
- Highlight unknown installed/project `--tui-*` references in the first `var(...)` argument for CSS, Less, and SCSS.
- Reuse the completion token-name index for inspections without building a cold graph on the inspection/UI path.
- Restart highlighting after background token-index warmup instead of reporting false unknown-token warnings from a stale catalog.
- Offer `Replace with ...` only when the closest known design token is sufficiently close and unambiguous.

### Deprecated design tokens

- Discover version-aware deprecation metadata from adjacent comments on installed/project token declarations.
- Keep deprecated tokens discoverable in completion while marking them as deprecated.
- Show deprecation and explicit replacement information in hover and completion previews.
- Highlight deprecated `--tui-*` usages with a dedicated inspection in CSS, Less, and SCSS.
- Offer `Replace with ...` only when metadata names one explicit, unambiguous replacement.
- Let project-defined overrides suppress package-level deprecation for the effective local token.
- Keep deprecation state context-aware for monorepos with different installed Taiga UI versions.
- Implemented and verified by [#47](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/47) / [#55](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/pull/55).

### Icon completion and preview

- Complete `@tui.*` icon names in JavaScript, TypeScript, HTML, and Angular templates, including static and bound string attributes.
- Discover public icon names from the installed `@taiga-ui/icons` package instead of shipping a fixed catalog.
- Use installed `@taiga-ui/tds-icons` for proprietary projects when available.
- Fall back to the T-Bank icon catalog for proprietary projects when local TDS icons are unavailable.
- Preserve public/proprietary source precedence and keep the icon subsystem independent from token resolution.
- Show SVG previews for the selected completion item and for complete icon references on hover.
- Keep SVG loading/rendering off the UI thread and render previews sharply on HiDPI displays.

### CSS unit helpers

- Show `rem` → `px` declarative inlay hints in CSS, Less, and SCSS using the fixed browser-default assumption `1rem = 16px`.
- Support stylesheet PSI injected into JavaScript/TypeScript hosts such as Angular component `styles` template literals.
- Support Angular numeric style-unit bindings such as `[style.font-size.rem]="1"` and `[style.border-width.rem]="0.25"`.
- Ignore dynamic Angular expressions instead of guessing runtime values.
- Render unobtrusive text-without-background hints without modifying source files.

### Event plugin support

- Support Taiga UI event-plugin modifiers in Angular templates through versioned Web Types.
- Support native events, global targets, Taiga modifiers, validation, hover documentation, and TypeScript `host` metadata.
- Keep TypeScript host auto-completion responsive with debounced completion startup.
- Implemented by [#31](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/31) / [#56](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/pull/56).

### Plugin settings

- Add application-scoped settings under **Tools → Taiga UI**.
- Allow the design-token completion side preview to be enabled or disabled.
- Allow the design-token hover popup to be enabled or disabled.
- Keep both editor features enabled by default.
- Apply presentation changes without changing token resolution semantics.
- Tracked by [#49](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/49).

## Stage 6 — Performance and architecture hardening

Status: implemented.

Tracked and completed by [#18](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/18).

Implemented:

- telemetry-free performance diagnostics and a representative Angular/Nx fixture;
- precise dependency-aware project stylesheet invalidation;
- per-file declaration caching for incremental project-index rebuilds;
- immutable token-resolution snapshots and cached merged indexes;
- memoized `var(...)` parsing and safe recursive resolution results;
- semantic context keys shared across source files in the same effective project context;
- explicit icon-catalog invalidation and remote refresh policy;
- extensible ordered icon source strategies;
- generation-aware single-flight infrastructure where it reduces code without hiding domain semantics;
- extensible Nx/Angular/conventional/current-file stylesheet entrypoint providers;
- structured IntelliJ JSON PSI parsing for workspace configuration.

Acceptance goals from #18 are covered by the merged implementation and CI regression tests.

## Stage 7 — Release and production hardening

Status: production release shipped; final manual follow-up remains in #54.

Completed:

- [x] [#50 — Define supported IDE matrix and add Plugin Verifier CI](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/50).
- [x] [#51 — Validate packaged plugin against real Angular and Nx projects](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/51).
- [x] [#52 — Audit editor popups, previews and inlay hints for accessibility](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/52).
- [x] [#53 — Add plugin signing and JetBrains Marketplace publishing](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/53).
- [x] Production releases published through [v0.1.3](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/releases/tag/v0.1.3).
- [x] Release notes/changelog follow-up completed by [#79](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/pull/79).

Remaining:

- [ ] [#54](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/54) — complete the final Marketplace-delivered artifact smoke test on a supported IDE.
