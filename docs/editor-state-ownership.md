# Editor State Ownership

Which component owns each fact the editor knows. Companion to
[editor-state-and-view.md](./editor-state-and-view.md), which covers what the components are.

**Written with AI assistance, and should be checked against the code.**

---

## Where state lives

| Fact | Owner |
|---|---|
| Which page a view shows | `OpenPage`, held by `PageView` |
| Scroll and zoom | `ViewportState` |
| The window bitmap | `PageRenderer` |
| Signals for one view | `ViewEventBus`, held by `PageView` |
| Signals for the device or the app | `CanvasEventBus` |
| A page's number and its notebook | Derived from the page record, not stored |

`PageDataManager` still tracks a *foreground* page and persists scroll, but it is no longer the live
source for either — it is the cache and persistence layer. The distinction matters because asking it
"which page am I showing?" gives an app-wide answer, which is only right by coincidence.

## Signals

`CanvasEventBus` keeps five signals that are genuinely device- or app-wide: the raw-draw mutex (the
Onyx firmware has one raw-drawing state for the whole panel), drawing state, window focus, the menu
state, and the immediate surface repaint.

Everything else moved to `ViewEventBus`, one instance per `PageView`. Code holding a page emits to
`page.events`. Code with no view in hand — the navigator, quick-nav, the settings dialogs — means
"whichever view is on screen" and goes through `CanvasEventBus.active`.

Two scopes currently coincide on `ViewEventBus`, because a view shows exactly one page:

- **View** — `forceUpdate`, `refreshUi`, `reinitSignal`, `rectangleToSelectByGesture`,
  `addImageByUri`, `saveCurrent`, `isScrubbing`, `previewPage`, `restoreCanvas`, `changePage`.
  These concern the surface: `forceUpdate` carries a rect in surface coordinates, and a selection
  gesture is screen-space.
- **Document** — `reloadFromDb` and `clearPageSignal` concern a page wherever it happens to be shown.
- **Undecided** — the two history-commit signals. Whether undo belongs to a view, a page or a
  notebook only needs answering once those stop being the same thing.

If a view ever spans more than one page, the document-scoped signals need routing to every view
showing that page rather than to one. The bus is named for the view so the half that stays put is
already named correctly.

## Page geometry is still device-shaped

`SCREEN_WIDTH` and `SCREEN_HEIGHT` are process-wide `var`s set from `displayMetrics`, read across
nine files. They determine page size (`PageContentRenderer.kt:135` returns them directly), background
render width, export bounds, and zoom snap targets. **Zoom 1.0 means "the page fits the device
screen width"**, so a page is defined by the display rather than by anything of its own.

Zoom snapping no longer reads them — `ViewportState` snaps to its own aspect ratio, so a viewport
that is not the whole screen gets a target describing itself. The other eight files still read the
globals directly.

## Failure modes in this area

These share a signature: no exception, no failed test, and nothing wrong at any individual call
site. The symptom is usually content that is simply absent or stale, so they are found by tracing on
hardware rather than by reading.

**Silent signal loss.** A zero-buffer `MutableSharedFlow` discards an emit when nothing is
subscribed. Anything that emits during construction, layout or teardown can arrive before its
collector exists, and the result is a repaint that never happens rather than an error.

**Keys that the keyed thing can change.** Indexing live objects by one of their mutable attributes
looks correct until the attribute moves. The index then points at the wrong object, and operations
meant for one instance land on another — or cancel it.

**Two owners for one fact.** Where two components can both answer the same question, they diverge
and the later writer wins. This is invisible while only one of them is ever written, which makes it
easy to introduce and hard to attribute later.

**Stored derivations.** Fields computed from something else, but updatable independently of it, go
stale in ways the type system cannot catch. The risk is highest when several correlated fields are
written by one code path and only some are cleared by another.

**Bidirectional channels.** State written outward and read back inward becomes a second store, so a
value set anywhere else is overwritten a moment later by whatever the write triggered. The fix is
usually direction, not care: seed from it, or report to it, but not both.
