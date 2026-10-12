# NAV-02 retained pager draft, paused 2026-10-12 11:01 UTC

This is a source draft, not an accepted build or a delivered APK. Work was paused to prioritize the user's independent local-app visibility/name hotfix. Do not include this draft in that hotfix. The frozen `build/candidate19-v9/` and `build/delivery/` were not modified.

## Evidence and intentional boundary

Read README, NAV-02 in feature-status.json, and the neighboring reference analysis interface_inventory.md, runtime_verification_matrix.md and runtime/verified_screen_index.json. The original evidence establishes seven modules, title/tab/drawer correspondence, tab folding, and the drawer remaining open after selection. A static ViewPager field does not establish background terminal execution, exact offscreen retention, swipe timing or animation parity. No original APK, assets, VM or actual device were run by this task.

The draft retains visited page Views within one Activity and exact AdbSession object. New pages have a UI-only preview; queries and terminal creation begin only on committed navigation. Horizontal dragging moves two pages continuously and settles or returns; tabs/drawer select through the same pager. The terminal still closes its PTY connection on leaving the page, preserving text and an unsubmitted draft and opening a fresh shell on return. This must not be described as original terminal-background parity. Historical commands are not replayed.

## Changed production sources

- MainActivity.java: separate selectModule from explicit showDevice refresh; retained module slots; centralized toolbar; generation/activation gates; lightweight exact-session rotation restoration; initial-read/cancel bookkeeping; retained tools click binding; owned drawer lifecycle
- CollapsingRoot.java: continuous gesture drag/release/cancel with activation revision checks
- RetainedModulePager.java: new API19-compatible animation/view host, lazy preview and cancellation
- ModuleScrollState.java: new scalar-only rotation scroll snapshots; ListView restore (not API21 AbsListView restore)
- ModuleSwipePolicy.java: exposes horizontal displacement; the long-press timeout only applies before axis ownership so slow established drags remain continuous
- RetainedModuleState.java: new pure session identity/page token/per-activation model
- TerminalTranscript.java and TerminalRenderer.java: immutable styled transcript snapshot access only

No name/manifest/permission change was made by this task. MainActivity edits stopped at 10:57 UTC per parent request. The subsequent compatibility fix touched only the new ModuleScrollState class.

## Checks completed

- Full core + Android main-source javac compilation passed against official API35 Android stubs with Java8 source/target, output `build/nav-compile/` (10:57 UTC). This was compilation only, not dex/signing/APK delivery or Android runtime verification.
- ModuleScrollState then recompiled after replacing the API21 AbsListView.setSelectionFromTop call with API1 ListView.setSelectionFromTop.
- API reference inventory: new pager/scroll classes introduce no unguarded post-19 member references. Existing MainActivity guarded/backported references remain. This inventory is not a control-flow proof.
- `ModuleNavigationTests`: 41 passed
- `RetainedModuleStateTests`: 1114 passed; session object identity, revisit/refresh/new target/close, immutable snapshots and repeated activations
- `RetainedPagerGestureTests`: 60 passed; continuous/slow/reversed/cancelled/bounded synthetic gestures and styled transcript snapshots
- `bash tests/navigation/run.sh`: 23 passed; production RetainedModulePager with deterministic host View/Animator stand-ins, intermediate offsets, settle/rebound, lazy previews, retained identity, superseded/lifecycle/resize cancellation, disposal

Host stand-ins do not exercise Android's real input dispatch, framework focus, windows, rendering or animation timing. No full test-all run, new APK build, API19/API26/API30 runtime test or second security review was performed. No path-substitution, link, private-file or real-target tests were run.

## Issues confirmed in the paused draft (fixed in source follow-up below)

1. Old initial-read completion can change a newer attempt's ready/loading fields. workScoped writes its captured `initial` object unconditionally on success/error. Cancelling a first read, revisiting the same ModulePage, succeeding a later read, then receiving a late first callback sets ready=false again. Use a per-initialization attempt token, or replace the page record/token on each failed/cancelled rebuild. Do not weaken generation/isCurrent checks.
2. A cached inactive file page misses show_hidden changes from Settings. onResume consumes hiddenChanged but only refreshes when page==3. Invalidate the file slot on any change or record its filter generation and compare on activation.
3. disposeModules cancels Futures/scopes without synchronously removing them. A queued Future cancelled before its runnable starts never reaches workScoped's finally-equivalent UI cleanup; stale nonempty scopes can disable navigation. Detach/clear cancelled scopes and their progress windows synchronously, while preserving operation result reporting.

## Runtime acceptance still required after fixes

- Two-page drag intermediate frame, short drag rebound, direction reversal, first/last edge, slow drag, vertical folding and tab scrolling
- Drawer remains open; title/current tab/module and toolbar actions agree after adjacent and nonadjacent navigation
- Input/selectable text/checkbox/horizontal-scroll exclusion; multitouch, focus loss, resize, rapid successive selection and Back while animating
- Same View identity and scroll/list selection/input draft retention on revisit; tool toggles, file/app row actions still bind to the current activation
- Lazy preview does not query a device or create a PTY; terminal leave/reopen has fresh session and preserved display/draft without history replay
- Cancel-before-start and late callbacks; revisit after failed load; hidden-setting changes while file page is inactive
- Rotation retains only exact-session lightweight state and closes old windows/PTY/tasks; actual v9 owned-dialog lifecycle regression checks
- Leave device vs disconnect, new session with same target string, stale confirmations/results, current target changes

Keep NAV-02 partial/reference_parity not-confirmed until exact candidate runtime evidence exists. Any future feature-status update must distinguish this source draft from the previously delivered v9.

## Limited source follow-up, 2026-10-12 11:43 UTC

The three confirmed lifecycle defects above have now been corrected in `MainActivity.java`. This does not turn the source draft into an accepted/delivered candidate, or expand the original ViewPager evidence into exact animation/retention parity.

- Each initial read owns a unique attempt token on its retained `ModulePage`. Cancellation and success/failure completion can settle only that token. Applying a result still requires the same session object, generation, page token and activation; an old attempt cannot regain authority on revisit or change a newer attempt's ready/loading fields.
- A `show_hidden` preference change invalidates the inactive file slot, its page token and stale file data/selection on resume. It does not activate or query the file page, or expire an unrelated current page's activation. An active file page retains its explicit refresh path.
- Progress windows are associated with their operation scopes. Lifecycle cancellation synchronously detaches scopes, Futures and progress windows, then cancels scopes/Futures and dismisses the windows. A Future cancelled before starting no longer relies on a callback that cannot run. Completed/error callbacks detach only their own operation, and cancellation's partial-result reporting remains intact.

Production changes in this follow-up are limited to MainActivity's operation-window field, ModulePage initialization helpers, file-cache invalidation, disposeModules, workScoped/cleanup helpers, onResume and onDestroy. No pager, gesture policy, brand, version, signature, preset or server-interface implementation was changed by this follow-up.

Checks against the follow-up source:

- Full core + Android main-source Java8 javac compilation against official Android API35 stubs: passed, only existing deprecated-API notes. Resources compiled/linked solely to generate R for compilation. Output is `build/nav02-retained-fixes/`; no dex, signed/installable APK or delivery was produced by this follow-up.
- `ModuleNavigationTests`: 41 passed.
- `RetainedModuleStateTests`: 1114 passed.
- `RetainedPagerGestureTests`: 60 passed.
- `bash tests/navigation/run.sh`: 23 passed.
- New `bash tests/navigation/run-lifecycle.sh`: 76 passed. It extracts the unchanged production MainActivity lifecycle method bodies and ModulePage class into a pure in-memory harness with deterministic executor/UI/dialog/preference stand-ins, alongside real OperationScope and RetainedModuleState. It also checks the dispose/destroy cancellation call sites. Coverage includes late success and error before/after a newer initial completion, same-activation supersession, cancel-before-start, repeated cancel, current result-handler failure, exact lifetime gates, stale-resource closure, surviving new scopes, retained cancellation reports and inactive/active hidden-filter changes.

This is fragment/host regression coverage, not an instantiated Android Activity or a proof of actual Android window/input/lifecycle behavior. The full test-all aggregate, API19/API26/API30 runtime acceptance, original APK, emulator/VM, real target/account/network, flashing, second security review, symbolic-link/path-replacement/private-file fixtures and delivery rebuild were not run. `build/candidate19-v9/`, `build/delivery/` and the independent local-app hotfix directory were not touched. All runtime acceptance items above remain open, including cancellation/window behavior on actual Android and exact-candidate navigation rendering.
