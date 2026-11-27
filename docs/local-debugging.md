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

Pin an input card, then add another receiving directive, change its component imports, or edit an installed input declaration. Verify that source-changing actions stop working until **Refresh** / **F5**, while navigation remains available. Repeat after Undo/Redo. Check that value/template actions refresh their current value and API, that a renamed binding remains refreshable, and that deleting the original tag cannot redirect an old action to another element.

For card UX regression checks:

Use the pinned real-package fixture and record results in [documentation-acceptance.md](documentation-acceptance.md). It includes inline/external templates, shared inputs, host directives and a missing-import component for native Angular quick fixes.

- Add a missing required input and immediately type an expression without clicking the editor. Verify the caret and focus stay inside the new binding through automatic Refresh, and one Undo removes the insertion.
- Resize and move a pinned card, select a parameter from the vertical Inputs list, then change a related declaration and press F5. Verify the member, Back history, scroll position and popup geometry survive with fresh types.
- Invoke Show Taiga UI Card while indexing and on an unimported selector. Verify progress and the unavailable-target message, then use Angular quick fixes and check the native import intentions offered by WebStorm.
- Open an input with more than 12 finite values, use value search, then invalidate the card. Apply must remain disabled after filtering.
- Verify that the card has no Details disclosure, Browse API footer, View API link or clipboard buttons. Check the code indentation in the Examples tab.
- Hover a binding name: verify its background highlight and the popup pointer, then close the popup and verify the highlight is removed. Repeat at the screen edges and after pinning.
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

## MCP tools

Open the project in sandbox WebStorm. Enable the bundled **MCP Server** plugin, then turn on the server in
**Settings | AI | MCP Server**. Auto-configure a detected client such as Codex or JetBrains Air when your IDE
build offers it. For any compatible MCP client, copy the HTTP, SSE, or stdio configuration shown by the IDE
and select a transport the client supports. Restart the client after changing its configuration. See the
[JetBrains MCP Server guide](https://www.jetbrains.com/help/webstorm/mcp-server.html).

Call `tools/list` from the client. On builds with **Exposed Tools**, confirm that the Taiga UI tools are enabled.
If a tool is marked **Router-only** on a build that supports routing, use the JetBrains router and repeat a tool call
through it. Direct discovery requires enabling the tool for direct exposure. Pass a source path relative to the
opened project, picking a file from the intended app in a mixed monorepo.

| Tool                                 | Example arguments                                            |
| ------------------------------------ | ------------------------------------------------------------ |
| `taiga_ui_inspect_project`           | `{"source":"."}`                                             |
| `taiga_ui_inspect_app`               | `{"source":"src/app.html","mode":"migration"}`               |
| `taiga_ui_search_api`                | `{"source":"src/app.html","query":"tuiButton"}`              |
| `taiga_ui_read_api`                  | `{"source":"src/app.html","sectionId":"components/button"}`  |
| `taiga_ui_api_at`                    | `{"source":"src/app.html","line":1,"column":9}`              |
| `taiga_ui_search_tokens`             | `{"source":"src/styles.css","query":"background"}`           |
| `taiga_ui_resolve_token`             | `{"source":"src/styles.css","name":"--tui-background-base"}` |
| `taiga_ui_match_color`               | `{"source":"src/styles.css","color":"#fff"}`                 |
| `taiga_ui_preview_folder_colors`     | `{"directory":"src"}`                                        |
| `taiga_ui_apply_folder_colors`       | `{"directory":"src","revision":"<preview revision>"}`        |
| `taiga_ui_search_icons`              | `{"source":"src/app.html","query":"search"}`                 |
| `taiga_ui_convert_rem`               | `{"rem":"1.25"}`                                             |
| `taiga_ui_check_release`             | `{"source":"."}`                                             |
| `taiga_ui_inspect_migration`         | `{"directory":"."}`                                          |
| `taiga_ui_run_migration`             | `{"directory":".","revision":"<inspection revision>"}`       |
| `taiga_ui_inspect_nx_migration_plan` | `{"directory":"."}`                                          |
| `taiga_ui_run_nx_migration_plan`     | `{"directory":".","revision":"<plan revision>"}`             |

Use paths and token names from a disposable test workspace. Compare the version and source information with Project
Doctor, token values with Token Explorer, and a binding with Quick Documentation. WebStorm's built-in
`get_file_problems` reports IDE inspection findings for individual files. The Doctor's **Analyze App** and
**Analyze v4 to v5 Migration** actions run the existing batch inspections over the selected app in WebStorm.
`taiga_ui_inspect_app` uses the same inspection sets and returns project-relative findings. A bounded result reports
`partial: true` if it skips files larger than 1 MiB or reaches the file or finding limit.

For folder cleanup, pass the preview's revision to the apply tool, review the IDE diff, and confirm. Check cancellation,
stale revision rejection, and IDE Undo in the disposable workspace. For migration, inspect the workspace before
requesting execution and decline the first confirmation. Use a separate disposable workspace with pre-provisioned
Angular/Nx dependencies for a successful run. The CLI uses its own registry and network configuration.

### Offline smoke check

1. Start a fresh IDE sandbox with local Taiga UI packages for v4 and v5, and select **Offline only** in
   **Settings | Tools | Taiga UI**. Block external network access while keeping the local MCP connection available.
2. With an empty IDE disk cache, start the IDE and connect a generic MCP client. List the tools and call the examples
   above against the corresponding fixtures. Confirm API search and read report `BUNDLED` with the app's own major
   and an upstream revision. Use `taiga_ui_api_at` to check installed declarations for a real binding.
3. Repeat with a valid and then stale same-major disk cache. Check that the response reports `DISK_CACHE`, exposes
   freshness, and uses the disk cache over the bundled version. Check an invalid cache falls back to the bundle.
4. Reload the API tab and a contextual card, open the icon browser, call the release tool, and inspect outbound
   connections. Confirm no access attempts to the Taiga UI documentation host, the T-Bank CDNs, or GitHub Releases.
5. In a sandbox with network available, start a refresh in **Online**, switch to **Offline only**, then repeat
   the lookups. Check that cached/local results remain available and the plugin stops initiating requests.
6. Record the IDE build, client and version, transport, router support, and the result for each case. Repeat on
   the oldest and newest supported IDE builds. Disable the optional **MCP Server** plugin and confirm the Taiga UI
   editor, API browser and Doctor still work.

**Cache first** is the default. It uses local data for automatic operations, including MCP calls. The API tab's
**Reload** and an explicit card refresh can update the documentation cache. **Online** also enables automatic
documentation refresh, uncached CDN icons and previews, and GitHub release polling. **Offline only** uses cached or
bundled data even when the user clicks Reload. Cached release and remote icon data are available within the running IDE
session when previously fetched. Missing remote-only data appears as unavailable. The network policy governs the plugin's
data access. An AI client may still require an online model connection, and an external documentation link opens in the browser.

### Update bundled documentation

The v4 and v5 snapshots are generated documentation published by the Apache-2.0-licensed
[Taiga UI project](https://github.com/taiga-family/taiga-ui). Keep the license and attribution alongside the archives.
During release preparation, verify that the upstream GitHub Pages revision has the expected majors at
`v4/llms-full.txt` and `llms-full.txt`, then run:

```bash
python3 scripts/update-docs-snapshots.py <gh-pages-commit-sha> --retrieved <yyyy-mm-dd>
./gradlew test --tests '*TaigaDocsRepositoryTest'
```

Review the manifest and commit the archives with it. Plugin builds use the checked-in bytes. A snapshot identifies a
documentation major and deployment revision. Use installed declarations to verify bindings for a specific minor version.

## Verify the Nx migration MCP tools

Use a disposable Nx workspace with its local CLI installed and a saved `migrations.json` containing
`@taiga-ui/cdk`'s `updateToV5` migration. The Run tool applies the full saved plan, including migrations from
other packages. A previously applied step may run again.

1. Open the workspace in the sandbox WebStorm. In **Settings | AI | MCP Server**, enable the server and use the
   Codex auto-configuration option or the configuration snippet for your MCP client.
2. In the connected client, list tools and call `taiga_ui_inspect_nx_migration_plan` with `{"directory":"."}`.
   Check the workspace and plan paths, step order, and returned `revision`. A preview should leave the workspace clean.
3. Call `taiga_ui_run_nx_migration_plan` with the same directory and revision. Decline the prompt in WebStorm and
   check that the tool returns `cancelled` and the workspace stays clean.
4. In the disposable workspace, make the same call and approve the plan. Check the structured result, CLI output,
   and installed CDK version. Repeat the inspect/run sequence with a change to `migrations.json` between calls and
   verify that the stale revision is rejected before Nx starts.
5. Disable the **MCP Server** plugin and restart the sandbox. Verify the Taiga UI folder menu and Doctor still load.
   Repeat the smoke check on the oldest and newest supported WebStorm builds.

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
