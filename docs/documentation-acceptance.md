# Contextual documentation acceptance

Implementation is tracked in [PR #116](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/pull/116), covering [#100](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/100) and [#101](https://github.com/splincode-taiga-labs/taiga-ui-jetbrains-plugin/issues/101). Issue completion and the remaining manual checks are separate from automated coverage.

## Automated coverage

The controller suite uses the IntelliJ Platform editor, Angular PSI, installed declaration fixtures, real documents, Undo/Redo, range markers, popup factories and the production resolution coroutine. Platform UI interception prevents desktop windows from appearing. A recording focus service verifies requested focus; a popup proxy supplies external window geometry. These checks do not prove operating-system focus transfer or screen positioning.

`testUnavailableImportHelpInvokesNativeActionAtTheTrackedSourceTarget` additionally records native action dispatch after the caret moves and text is inserted before the original target. Import candidate selection and the resulting Angular component edit still require the real-IDE smoke check below.

| Scenario                                                                                           | Regression evidence                                                                                            |
| -------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------- |
| Add input retains editor focus and caret through automatic Refresh; one Undo removes the insertion | `TaigaQuickDocumentationControllerTest.testRequiredInputReturnsFocusAndCaretAfterAutomaticRefreshAndOneUndo`   |
| Typing immediately during pending Refresh retains the new expression                               | `testImmediateTypingDuringAutomaticRefreshKeepsTheNewExpression`                                               |
| F5 retains browsed API, fresh types, search, selection, Back history, scroll and pinned geometry   | `testF5RebasesBrowsedMemberHistoryQuerySelectionAndPinnedGeometry`                                             |
| Repeated F5 runs one resolution; Escape cancels it                                                 | `testRepeatedF5WhileIndexingKeepsOneRefreshAndEscapeCancelsIt`                                                 |
| Close or editor release during loading cannot reopen the card                                      | `testCloseDuringIndexingCannotPublishALateCard`, `testEditorReleaseDuringIndexingCannotPublishALateCard`       |
| Unavailable target remains explainable and dismissible                                             | `testUnavailableTargetKeepsStatusUntilEscape`                                                                  |
| Changed imports disable source edits, including previously captured actions                        | `testChangedImportsDisableButtonsAndRejectPreviouslyCapturedAction`                                            |
| Undo/Redo cannot reuse a captured editable snapshot                                                | `testUndoRedoCannotReuseTheRefreshedBindingAction`                                                             |
| Deleted source element cannot redirect an old action to a neighboring element                      | `testDeletedTargetCannotRedirectAnOldActionToTheNextElement`                                                   |
| Pin/Unpin/Close restore native hover and dispose editing range markers                             | `testPinUnpinAndCloseRestoreNativeHoverAndDisposeEditorAdapters`                                               |
| Inline templates, shared inputs and exposed host-directive aliases use the host editor             | `testInlineSharedBindingAndHostDirectiveUseTheHostEditor`; external templates are used throughout the suite    |
| Native completion provides installed documentation without opening the interactive card            | `testNativeCompletionProvidesInstalledDocumentationWithoutOpeningAnInteractiveCard`                            |
| Long shared types fit bounded cards in both themes at 100%, 125% and 200% user scale               | `TaigaQuickDocumentationUxTest.testLongSharedBindingCardsFitBothThemesAtEverySupportedScale`; six PNG previews |

Run these checks with:

```bash
npm ci
npm run install:fixtures --prefix test-fixtures
./gradlew test \
  --tests 'org.taigaui.designtokens.documentation.TaigaQuickDocumentation*' \
  --tests 'org.taigaui.designtokens.documentation.TaigaLocalDocumentationTest'
./gradlew ktlintCheck detekt check buildPlugin verifyPluginStructure
```

CI publishes the installable plugin, rendered card previews, documentation test XML and the JaCoCo XML report in the `taiga-ui-contextual-documentation` artifact. Inspect controller, editor-adapter and status-popup coverage from that report rather than inferring it from panel tests.

## Issue criteria

| Issue | Acceptance criterion                                           | Evidence and remaining check                                                                                    |
| ----- | -------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------- |
| #100  | Component/directive Quick Documentation in Angular templates   | `TaigaQuickDocumentationIntegrationTest` renders `tuiButton` and `tui-calendar`; real-project smoke remains     |
| #100  | Standard documentation UI and keyboard invocation              | Native `DocumentationTargetProvider` and rendered targets are exercised; macOS keymap smoke remains             |
| #100  | Documentation from completion                                  | Controller suite uses native Angular completion and `LookupElementDocumentationTargetProvider`                  |
| #100  | Unknown and non-Taiga elements unaffected                      | Integration and DX suites reject unknown/unimported selectors, native events and ordinary TypeScript strings    |
| #100  | No EDT network or filesystem scanning during lookup            | Cold-cache provider fails open and starts background warm-up; expensive work remains in background read actions |
| #100  | Offline cached documentation usable                            | Cache suites and offline installed-API DX tests; online data cannot override installed members/types            |
| #100  | Tests include a directive and an element component             | `tuiButton` and `tui-calendar` integration cases                                                                |
| #101  | Known input/output docs, owner and emitted type                | Integration cases render `iconEnd`, `valueChange`, `TuiButton`, `TuiIcon` and `MouseEvent`                      |
| #101  | Types match installed version                                  | DX suite checks installed aliases, signal write types, inheritance and multiple receivers                       |
| #101  | Unsupported/removed bindings are not accepted from remote docs | DX remote-enrichment case and view tests reject missing/removed members                                         |
| #101  | Angular inputs/outputs are tested                              | Integration, DX and controller suites; inline/external manual smoke remains                                     |

## Real-package IDE fixture

`test-fixtures/taiga-ui-v5` contains pinned Angular **19.2.21** and Taiga UI **5.18.0** packages. Its Angular workspace and sources provide external and inline templates, a shared `size` input, an exposed `hostSize` host-directive alias, `TuiCalendar` inputs/outputs, a native attribute and a component deliberately missing its `TuiCalendar` import.

After the fixture install above, open it in the sandbox:

```bash
./gradlew runIde \
  -PdebugProjectPath="$PWD/test-fixtures/taiga-ui-v5"
```

The workspace is an IDE inspection fixture; it has no serving/build target. The missing-import component is intentional. In its inline template, invoke Angular quick fixes and verify that only `MissingImportDocumentationComponent.imports` receives `TuiCalendar`, even though other components in the same file already import it.

## Manual WebStorm/macOS checklist

Record IDE build, macOS version, keymap, theme and scale for each pass. Follow the sandbox setup in [local-debugging.md](local-debugging.md).

- [ ] Inspect external and inline `tuiButton`, `tui-calendar`, `showAdjacent` and `dayClick`; verify installed types and owners.
- [ ] Inspect `size` on the element with `localSized`; verify both receivers and safe common literal choices.
- [ ] Inspect exposed `hostSize`; verify the installed `TuiButton.size` declaration remains the authority.
- [ ] Invoke native Quick Documentation and completion documentation; verify one documentation surface and no duplicate interactive hover.
- [ ] Use Angular quick fixes in the deliberately missing-import component; verify the correct component is changed.
- [ ] Add a required input and type immediately through automatic Refresh; verify OS focus, caret and one Undo.
- [ ] Resize/move a pinned window, browse/search, press F5, then Back; verify history, selection, scroll and geometry.
- [ ] Close the card or source editor during indexing/loading; verify no late popup appears.
- [ ] Change imports, Undo/Redo or delete the source element; verify old actions cannot write.
- [ ] Traverse all actions with Tab/Shift+Tab; use Enter/Space, Escape, search, Back, F5 and the configured Show Taiga UI Card shortcut.
- [ ] Repeat in light/dark themes at 100%, 125% and 200%; inspect long types/descriptions/examples and each screen edge.

Manual execution is pending. Headless CI previews and recorded focus/geometry assertions cannot substitute for a real macOS/WebStorm session.
