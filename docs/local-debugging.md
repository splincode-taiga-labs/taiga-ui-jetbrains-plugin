# Debugging the plugin on a real project

The Gradle `runIde` task starts an isolated WebStorm sandbox with the current plugin build installed. The sandbox has its own settings, caches, and plugin directory, so it does not modify the normal WebStorm installation.

## Prerequisites

- JDK 21;
- the repository checked out locally;
- the real project already containing `node_modules/@taiga-ui/design-tokens`;
- an absolute path to that project.

The plugin runtime does not need Node.js. Running `npm ci` in this repository is only required when the pinned real-package tests should run before launching the sandbox.

## Open a real project in the downloaded sandbox WebStorm

From the plugin repository:

```bash
./gradlew runIde \
  -PdebugProjectPath="/absolute/path/to/your/project"
```

Gradle uses the WebStorm version pinned by `platformVersion` and opens the supplied project in the sandbox IDE.

In the sandbox project, move the pointer over a Taiga UI token name inside `var(...)` and keep it still for about 500 ms. Design-token hover uses its custom Swing popup. Taiga UI selectors and input/output names use the same delay for their contextual cards.

For a keyboard check, place the caret on `tuiButton`, `tui-calendar`, or an input and invoke **Show Taiga UI Card** through Find Action or **Alt+Shift+Q**. Check Tab/Shift+Tab, Enter/Space, API search, Back and Escape. The explicit action also works when mouse-hover documentation is disabled. **View | Quick Documentation** / `Ctrl+Q` (`F1` on the default macOS keymap) separately tests the informational native documentation provider.

Pin an input card, then add another receiving directive, change its component imports, or edit an installed input declaration. Verify that source-changing actions stop working until **Refresh** / **F5**, while copying remains available. Repeat after Undo/Redo. Check that value/template actions refresh their current value and API, that a renamed binding remains refreshable, and that deleting the original tag cannot redirect an old action to another element.

For card UX regression checks:

Use the pinned real-package fixture and record results in [documentation-acceptance.md](documentation-acceptance.md). It includes inline/external templates, shared inputs, host directives and a missing-import component for native Angular quick fixes.

- Add a missing required input and immediately type an expression without clicking the editor. Verify the caret and focus stay inside the new binding through automatic Refresh, and one Undo removes the insertion.
- Resize and move a pinned card, browse an owner API, search and select another member, then change a related declaration and press F5. Verify the member, Back history, API query/selection, scroll position and popup geometry survive with fresh types.
- Invoke Show Taiga UI Card while indexing and on an unimported selector. Verify progress and the unavailable-target message, then use Angular quick fixes and check the native import intentions offered by WebStorm.
- Open an input with more than 12 finite values, use Show all and search, then invalidate the card. Apply must remain disabled after filtering; Copy must still work.
- Copy a long type, import and an example longer than the preview. Verify the clipboard contains the complete source text.
- Check long descriptions/types and multiple receivers in light and dark themes at 100%, 125% and 200% display scaling. All actions must remain reachable with Tab and the card must fit the screen.

After changing plugin code, stop the running sandbox IDE and start `runIde` again. Hot reload is not used for plugin classes or `plugin.xml` extension registrations.

## Run against the locally installed WebStorm

To use a specific local WebStorm build instead of the downloaded platform, pass `localIdePath` as well:

```bash
./gradlew runIde \
  -PlocalIdePath="/Applications/WebStorm.app/Contents" \
  -PdebugProjectPath="/absolute/path/to/your/project"
```

The local IDE build must remain compatible with the plugin's configured `sinceBuild`.

## Attach a debugger

Start the sandbox JVM in debug mode:

```bash
./gradlew runIde --debug-jvm \
  -PdebugProjectPath="/absolute/path/to/your/project"
```

Gradle waits for a debugger on `localhost:5005` before WebStorm starts.

In the WebStorm instance where the plugin repository is open:

1. Open **Run | Edit Configurations**.
2. Add **Remote JVM Debug**.
3. Use host `localhost` and port `5005`.
4. Start that configuration.

Useful breakpoint locations:

```text
DesignTokenHoverPopupListener.mouseMoved
DesignTokenHoverPopupController.mouseMoved
DesignTokenHoverPopupController.handleRequest
DesignTokenHoverPopupController.showPopup
DesignTokenReferenceAtOffsetFinder.find
DesignTokenIndexService.resolveToken
DesignTokenValueResolver.resolve
DesignTokenHoverPopupModel.create
DesignTokenHoverPopupPanel.<init>
```

## Inspect sandbox logs

The sandbox log is normally written under:

```text
build/idea-sandbox/*/log/idea.log
```

To find the exact current file:

```bash
find build/idea-sandbox -name idea.log -print
```

Watch it while reproducing a problem:

```bash
tail -f build/idea-sandbox/*/log/idea.log
```

`DesignTokenIndexService` writes package-index build failures to this log.

## Collect performance diagnostics

Performance diagnostics are disabled by default and never send data outside the IDE process. Enable them for a sandbox run with a JVM system property:

```bash
JAVA_TOOL_OPTIONS="-Dtaiga.design.tokens.performanceDiagnostics=true" \
./gradlew runIde \
  -PdebugProjectPath="/absolute/path/to/your/project"
```

The plugin writes one `Taiga UI performance` log entry for each measured operation. Current metrics cover:

```text
package-scan
project-graph-build
psi-extraction
index-composition
value-resolution
icon-catalog-load
```

Use the same user action before and after an optimization and compare operation counts and durations in `idea.log`. The representative Angular/Nx test fixture contains 40 imported stylesheets so future invalidation and declaration-cache changes can be compared against a stable workload.

## Reset the sandbox

When cached IDE state or an old plugin installation interferes with reproduction, stop the sandbox and remove its generated state:

```bash
rm -rf build/idea-sandbox
./gradlew runIde \
  -PdebugProjectPath="/absolute/path/to/your/project"
```

The real project itself is not deleted. Only the isolated IDE settings, system caches, logs, and installed sandbox plugins are recreated.

## Verify the installed package that will be indexed

The resolver searches upward from the opened source file for the nearest package at:

```text
node_modules/@taiga-ui/design-tokens/package.json
```

For monorepos, open and test a source file under the workspace whose nearest `node_modules` contains the expected package version. Different physical package roots receive separate cached indexes; npm or pnpm aliases pointing to the same real package reuse one index.
