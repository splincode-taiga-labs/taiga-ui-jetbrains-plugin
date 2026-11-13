# Changelog

## Unreleased

### Changed

- Expanded WebStorm compatibility to 2026.3 (`263.*`) and added WebStorm 2026.3 to Plugin Verifier checks.

## 0.1.3 - 2026-09-30

### Added

- Custom light and dark plugin icons packaged in `META-INF` for JetBrains IDEs and Marketplace.
- Expanded Marketplace description covering design tokens, icons, CSS units, event-plugin tooling, settings, and project-aware resolution.

### Changed

- Clarified that the plugin is an independent community project maintained by `splincode-taiga-labs`.
- Finalized public plugin metadata and project positioning for the production-ready release.

## 0.1.2 - 2026-09-29

### Fixed

- Fixed Marketplace publishing with Gradle configuration cache enabled by resolving the release version during task configuration.

## 0.1.1 - 2026-09-29

### Added

- Project-aware Taiga UI design-token discovery from installed packages and reachable project styles.
- Design-token completion for CSS, Less, and SCSS with resolved values, color previews, reference chains, and source navigation.
- Project override resolution with context-aware precedence and recursive `var(...)` resolution.
- Unknown-token and deprecated-token inspections with safe quick fixes where an explicit replacement is available.
- `@tui.*` icon completion and previews for JavaScript, TypeScript, HTML, and Angular contexts, including supported proprietary sources.
- Pixel-equivalent inlay hints for `rem` values in CSS, Less, SCSS, Angular component styles, and numeric Angular style bindings.
- Taiga UI event-plugin completion, validation, inspections, and contextual hover documentation for Angular templates and TypeScript host metadata.
- Application-scoped settings for completion side previews and token hover popups.
- Supported IDE verification for 2025.3, 2026.1, and 2026.2 with JetBrains Plugin Verifier in CI.
- Representative Angular and Nx real-project validation fixtures for npm and pnpm layouts.
- Accessibility improvements for completion previews and custom editor UI.
- Reproducible signing and JetBrains Marketplace publishing workflow with signed release artifacts.
- Angular GitHub Pages demo and repository-wide formatting checks.

### Changed

- Added generation-aware caching and targeted invalidation for project token indexes, resolution snapshots, and icon catalogs.
- Split stylesheet entrypoint discovery and icon catalog loading into extensible providers/sources.
- Switched stylesheet configuration parsing to IntelliJ JSON PSI.
- Added deterministic npm lockfiles and cached `npm ci` installs for release validation fixtures.
- Restored binary compatibility across the supported IntelliJ Platform 253, 261, and 262 lines.

### Fixed

- Fixed selector-local and shared design-token override resolution.
- Fixed icon completion preview scaling and rendering.
- Fixed demo screenshot quality and preview layout.
