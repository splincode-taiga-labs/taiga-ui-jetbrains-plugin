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

In the sandbox project, move the pointer over a Taiga UI token name inside `var(...)` and keep it still for about 350 ms. Design-token hover still uses its custom Swing popup. For Taiga UI components and directives in Angular templates, place the caret on selectors such as `tuiButton` or `tui-calendar` and invoke **View | Quick Documentation** / `Ctrl+Q` (`F1` on the default macOS keymap) to test the native documentation provider.

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
