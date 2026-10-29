# Spike 24: TuiRunner vs Toolkit

## Decision

Move the shell (`VintelliShell`) to the Toolkit level for 0.1.0: run it with `ToolkitRunner` (via `ToolkitRunner.builder()` for `bindings`, `styleEngine` and `faultTolerant`; `ToolkitApp` is only a convenience wrapper that builds the runner from `TuiConfig` and would need `runner().styleEngine(...)` in `onStart()`) and build the UI from Toolkit elements. `TuiRunner`, `TuiConfig` and `Bindings` remain underneath, since `ToolkitRunner` wraps a `TuiRunner`. In the 0.6.0 artifacts, automatic CSS theming (#26) is wired only through `ToolkitRunner`, and by-id Pilot testing requires `ToolkitTestRunner` from the toolkit test-fixtures artifact.

## Verified facts

The facts below were verified with `javap` against the local `~/.m2/repository/dev/tamboui/*/0.6.0` jars, against Maven Central for the test-fixtures classifier, and against the Tamboui styling (https://tamboui.dev/docs/main/styling.html) and Pilot testing (https://tamboui.dev/docs/main/pilot-testing.html) documentation pages (note: the published docs describe `0.7.0-SNAPSHOT`).

### API surface difference

`dev.tamboui.tui.TuiRunner` (tamboui-tui jar), main members:

- `TuiRunner.create()/create(TuiConfig)`, `run(EventHandler, Renderer)`, `draw(Consumer<Frame>)`, `pollEvent()`, `quit()`, `isRunning()`, `runOnRenderThread()`, `dispatch(Event)`, `builder()`.
- `TuiRunner.Builder`: `bindings`, `eventHandler`, `renderer`, `rawMode`, `alternateScreen`, `hideCursor`, `mouseCapture`, `pollTimeout`, `tickRate`, `noTick`, `resizeGracePeriod`, `shutdownHook`.
- No `styleEngine`, no focus API, no element registry. The immediate-mode contract is what PR #13 uses: `shell.handle(Event, TuiRunner)` plus `shell.render(Frame)`.

`dev.tamboui.toolkit.app.ToolkitRunner` (tamboui-toolkit jar):

- `Builder`: `app(Object)`, `config(TuiConfig)`, `bindings(Bindings)`, `styleEngine(StyleEngine)`, `mouseCapture`, `tickRate`, `noTick`, `bracketedPaste`, `withAutoBindingRegistration`, `faultTolerant(boolean)`, `errorOutput(PrintStream)`, `postRenderProcessor`.
- Instance API: `run(Supplier<Element>)`, `focusManager()`, `eventRouter()`, `elementRegistry()`, `styledAreaRegistry()`, `styleEngine(StyleEngine)` (also settable after construction), `schedule()/scheduleRepeating()/scheduleWithFixedDelay()`, `quit()`, and `tuiRunner()`, i.e. the Toolkit wraps a TuiRunner: it is the same TuiRunner layer from PR #13 underneath, not a competing one.
- `ToolkitApp` (abstract base): `configure()` returns TuiConfig, `render()` returns the root `Element`, `onStart()/onStop()`, `run()`.

Other relevant pieces verified in the toolkit jar:

- `dev.tamboui.toolkit.Toolkit` static DSL: `panel/row/column/columns/dock/stack/grid/table/tabs/list/tree/textInput/form/dialog/scrollable/spacer/lazy/widget(...)`. `DockElement` is a direct fit for an IDE shell (tool window bars documented via `dock-top-height`, `dock-left-width`, ... CSS properties).
- `dev.tamboui.toolkit.element.StyledElement`: `.id(String)`, `.addClass(String...)`, `.attr(...)`, `.focusable()`, `.constraint/length/percent/min/max/fill`, style hooks (`resolveEffectiveStyle(RenderContext, ..., PseudoClassState, ...)`) inheriting from `dev.tamboui.css.Styleable`.
- `dev.tamboui.toolkit.focus.FocusManager` (plain class: `focusedId()`, `setFocus()`, `focusNext()`, `focusPrevious()`, `focusAt(x,y)`, `registerFocusable()`), `dev.tamboui.toolkit.event.EventRouter` (constructed with `FocusManager` + `ElementRegistry`, routes key/mouse/drag events to elements and global handlers), `dev.tamboui.toolkit.element.ElementRegistry` (`query(id)`, `getArea(id)`, `contains(id)`).
- `dev.tamboui.toolkit.elements.GenericWidgetElement` via `Toolkit.widget(T)` and `Toolkit.handleTextInputKey(...)`: core ratatui-like widget states (e.g. `TextInputState`) can be embedded inside a Toolkit element tree when needed.

### What CSS theming requires

`StyleEngine` lives in the separate `tamboui-css` jar (`dev.tamboui.css.engine.StyleEngine`: `loadStylesheet(resourceName)` and `loadStylesheet(name, resourceName)` (classpath resources), `loadStylesheet(Path)` and `loadStylesheet(name, Path)` (file system), `addStylesheet(name, cssSource)` (inline text), `setActiveStylesheet`, `resolve(Styleable, PseudoClassState, ancestors)`, `parseColor`, `addChangeListener`). Two ways to use it:

1. Documented and automatic: `ToolkitRunner.builder().styleEngine(engine)` supplies the engine to the element tree; every `StyledElement` then resolves its effective style through the `RenderContext` during rendering, incl. pseudo-class state. This is the only path where the engine is wired into rendering.
2. Manual, at any level. The `Styleable` interface (`styleType()`, `cssId()`, `cssClasses()`, `cssParent()`, `styleAttributes()`) can be implemented by own widgets, and one resolves styles by hand: `CssStyleResolver r = engine.resolve(widget)` then apply fg/bg/modifiers onto the `Style` before rendering (docs' "Implementing Styleable"). At the TuiRunner level nothing is automated: the 0.6.0 `TuiRunner.Builder` exposes no style hook, and core widget builders carry no resolver parameter, so per-widget style application would have to be built and applied on every frame ourselves. The docs' "CSS works at any API level" remark (0.7.0-SNAPSHOT) is true in principle but describes effort we would have to write ourselves. The 0.7.0-SNAPSHOT page says to implement `Styleable` and pass a `styleResolver` to widgets, but no `styleResolver` parameter exists in `tamboui-widgets` or `tamboui-core` 0.6.0 (checked by grepping all classes).

Consequence for #26: a real runtime theme (dark/light switch via `setActiveStylesheet` + `addChangeListener` redraw) is only practical at the Toolkit level with 0.6.0.

### What focusable panels with ids require

The `.id()`/`.focusable()`/`focusable(boolean)` setters exist on `StyledElement` (toolkit), and focus handling comes from the trio `FocusManager` + `EventRouter` + `ElementRegistry`, which `ToolkitRunner` owns and drives. Using `FocusManager` "manually" inside a TuiRunner EventHandler is technically possible (it is a plain class), but registration of areas every render, hit testing, and key routing would have to be re-implemented, i.e. rebuilding what the toolkit already does. The CSS side needs ids too: pseudo-class selectors like `Panel:focus` in `.tcss` are matched from this same focus state (`dev.tamboui.css.cascade.PseudoClassState`). So id-able, focusable panels are a Toolkit-level feature in practice.

### Mixing the levels

Mixing works in one direction: `ToolkitRunner.tuiRunner()` shows Toolkit is layered on TuiRunner, and `Toolkit.widget(T)` (`GenericWidgetElement<T>`) embeds raw core widget states in the element tree. The other direction (Toolkit elements inside a raw TuiRunner frame) is possible only by hand: `Element.render(Frame, Rect, RenderContext)` is public and a `DefaultRenderContext` can be constructed, but nothing wires events, focus or the element registry for a raw `TuiRunner`, so that would be our own glue code. The app model is therefore: ToolkitRunner + ToolkitApp roots the shell; core widgets enter via `Toolkit.widget(...)` or through toolkit element equivalents.

### Pilot testing

Both runners ship a `TestRunner` for tests:

- `TuiTestRunner` (tamboui-tui test-fixtures classifier, already in use in `VintelliShellTest`): `runTest(EventHandler, Renderer[, Size[, TuiConfig]])`, drives `TuiPilot`. Its `Pilot` supports `press(...)`, `click(x,y)`, `doubleClick`, `tripleClick`, `hover(x,y)`, `resize`, `pause`, `quit`.
- By-id element selection is a ToolkitPilot feature: the `Pilot` interface default methods in the tui test-fixtures were decompiled; `findElement(String)` throws `UnsupportedOperationException("Widget selection by ID is not supported")` and `hasElement(String)` returns `false` unless the implementation overrides them. `ToolkitPilot` (toolkit test-fixtures) overrides `findElement/click("id")/hasElement`.
- `ToolkitTestRunner` (toolkit test-fixtures classifier, verified: exists on Maven Central for 0.6.0): `runTest(Supplier<Element>[, Size[, TuiConfig]])` returns a handle exposing `runner()` (the `ToolkitRunner`, so a test can set `styleEngine(engine)` and `eventRouter().addGlobalHandler(handler)`), `pilot()`, and `backend()` (`dev.tamboui.terminal.TestBackend`, same as TuiTestRunner).

### What tests look like after the move

The state-assertion style (`shell.toolIndex()`, `shell.status()`, clocked with `pilot.pause(...)`) carries over, and `Pilot` keeps `press/click/pause/quit`. What changes: key handling moves from `EventHandler` into element handlers, a global handler or `Bindings`, so the shell needs a `root()` supplier and that wiring; tests gain `click("id")`, `findElement("id")` and `hasElement("id")`; cell and style assertions stay on offline `Buffer` rendering (with a `DefaultRenderContext`), since `TestBackend` exposes no cells in 0.6.0. Existing tests will need rework, not just a runner swap.

## Risks and open questions

- Toolkit elements inside a raw TuiRunner frame have no event or focus wiring in 0.6.0: any part of the shell that stays at the TuiRunner level would need hand-written glue to host toolkit elements, so a partial migration risks rework. Migrating the whole shell in one change avoids this.
- Several core widget states have first-class Toolkit elements (`textInput(TextInputState)`, `textArea(TextAreaState)`, `form(FormState)`, `tabs`, `list`, `tree`); only widgets without an element equivalent need `Toolkit.widget(...)` and manual key handling (`Toolkit.handleTextInputKey`). Whether the Toolkit `ListElement`/`TreeElement`/`TabsElement` cover what the current shell does with `ListState`/`TabsState` still needs to be proven in code.
- The published Tamboui documentation describes `0.7.0-SNAPSHOT` while 0.6.0 is used here; doc-described behavior was only relied upon where it matched the verified 0.6.0 API.
- Manual styling (implementing `Styleable` and applying resolved styles per widget per frame) is the documented fallback for non-Toolkit levels; effort was not estimated.
- Native image: neither the Toolkit nor the CSS engine has been exercised in the native build (epic principle 5, #11). The tamboui 0.6.0 jars ship no `META-INF/native-image` metadata, so the first migration PR must keep the native smoke CI green, including the stylesheet resources.
- ToolWindow SPI (#17): if `ToolWindow` lives in `vintelli-api` and returns a Toolkit `Element`, the api module gains a TamboUI dependency, which conflicts with "`ShellState` is readable without depending on TamboUI types" and with the module layout. Decide whether `ToolWindow` stays in `vintelli-tui`, or the api exposes a TamboUI-free view model.
- Scope not covered by the criteria for #26: its own tasks "fault-tolerant rendering on unknown widget state" (maps to `ToolkitRunner.Builder.faultTolerant(boolean)`) and "real project tree (`TreeElement`)" (Toolkit has `tree()`) are not mapped in this document.

## Criteria for issue 26

Consider #26 (CSS theming + focus) executable against the Toolkit shell when:

1. The shell is at `ToolkitRunner` with `styleEngine(engine)` wired, and dark/light themes are classpath resources under `src/main/resources` loaded via the classpath overload `engine.loadStylesheet("dark"|"light", "<resource name>")` (not a `Path`), and the resources are explicitly included in the native image, and switch with `setActiveStylesheet` at runtime (status bar toggles theme).
2. Panels carry ids and classes (`#tool-run`, `#tool-terminal`, `Panel.dock-top` etc.), and at least one `:focus` pseudo-class rule in `.tcss` visibly changes the focused panel (border/type/color) via `FocusManager`.
3. Tab traversal works: `focusNext()/focusPrevious()` cycles among `.focusable()` panels and the focused id is reflected both in rendering and in `engine` pseudo-class state.
4. Pilot tests demonstrate it: `ToolkitTestRunner.runTest(shell::root)` with `test.runner().styleEngine(engine)`, `pilot.click(...)` on a second focusable panel (not the first one, which is already focused after the first render) moves focus, `pilot.press(KeyCode.TAB)` changes `focusManager().focusedId()`, and a style assertion on the focused panel's border by rendering the root `Element` into a `Buffer` with a `DefaultRenderContext` that has the `StyleEngine` and `FocusManager` set (the same offline-frame technique `VintelliShellTest` already uses).
5. Theme switch does not require restart: flipping `setActiveStylesheet("light")` mid-session reaches the next frame, or a change listener triggers a redraw.

## Next steps

- Open a tracking issue (or fold into #17) for "migrate `VintelliShell` to `ToolkitRunner`", sequenced after #16 and before or inside #17; the shell is rewritten once, not per tool window.
- Add the `tamboui-toolkit` `test-fixtures` dependency (scope test, classifier `test-fixtures`; published on Maven Central for 0.6.0, `tamboui-toolkit` itself is already a compile dependency in `pom.xml`).
- Update `AGENTS.md` once the migration lands: "TamboUI: follow its documented levels (TuiRunner/Toolkit)" and "Tests use TamboUI Pilot (`TuiTestRunner`)" should name the Toolkit level and `ToolkitTestRunner`.
- Port the keymap work (#29) onto `ToolkitRunner.builder().bindings(...)`.
