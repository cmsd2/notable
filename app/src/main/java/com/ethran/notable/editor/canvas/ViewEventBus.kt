package com.ethran.notable.editor.canvas

import android.graphics.Rect
import android.net.Uri
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Signals scoped to a single editor view.
 *
 * Split out of [CanvasEventBus], which was an `object` holding every signal in the editor with no
 * addressing at all. That is fine with one editor surface and wrong with two: instantiate a second
 * observer registry and *both* views clear when you clear one, both reload when one reloads, and
 * both redraw on every refresh.
 *
 * Everything here is per-view — a redraw, a reload, a history commit and a page change all concern
 * one view and should not reach another. Genuinely device-wide signals stay on [CanvasEventBus]:
 * the drawing mutex, focus, and the shared menu state.
 *
 * Code holding a page addresses its own bus directly (`page.events`). Code that has no view in
 * hand — the navigator, quick-nav, the settings dialogs — means "whichever view is on screen", and
 * addresses [CanvasEventBus.active].
 *
 * ### Two scopes currently coincide here
 *
 * A view shows exactly one page today, so "this view" and "this page" pick out the same thing and
 * the signals below can share a bus. They are not the same question, and most of these are the
 * *view* one:
 *
 * - **View-scoped** — [forceUpdate], [refreshUi], [reinitSignal], [rectangleToSelectByGesture],
 *   [addImageByUri], [saveCurrent], [isScrubbing], [previewPage], [restoreCanvas], [changePage].
 *   These concern the surface and what is displayed on it. [forceUpdate] carries a rect in surface
 *   coordinates; a selection gesture is screen-space.
 * - **Document-scoped** — [reloadFromDb] and [clearPageSignal] concern a *page*, wherever it is
 *   being shown, and would need to reach every view showing it — possibly none, possibly several.
 * - **Undecided** — [commitHistorySignal] and [commitHistorySignalImmediately]. Whether undo
 *   belongs to a view, a page or a notebook is a design question that only has to be answered once
 *   the three stop coinciding.
 *
 * If a view ever spans more than one page — a viewport scrolling across page boundaries, say — the
 * document-scoped signals need their own bus and a page-to-views lookup to route them. That is a
 * split to make when the need is real; splitting now would be guessing at a design that does not
 * exist yet. The name says *view* rather than *page* so that the day it happens, the half that
 * stays put is already named correctly.
 */
class ViewEventBus {

    /** Redraw a region of this view. Null means the whole thing. */
    val forceUpdate = MutableSharedFlow<Rect?>()

    val refreshUi = MutableSharedFlow<Unit>()

    val reinitSignal = MutableSharedFlow<Unit>()

    val reloadFromDb = MutableSharedFlow<Unit>()

    // before undo we need to commit changes
    val commitHistorySignal = MutableSharedFlow<Unit>()
    val commitHistorySignalImmediately = MutableSharedFlow<Unit>()

    // used for checking if commit was completed
    var commitCompletion = CompletableDeferred<Unit>()

    // It might be bad idea, but plan is to insert graphic in this, and then take it from it
    // There is probably better way
    val addImageByUri = MutableStateFlow<Uri?>(null)

    // Event, not state: each emission is one gesture-selection request.
    val rectangleToSelectByGesture = MutableSharedFlow<Rect>()

    // For cleaning whole page, activated from toolbar menu
    val clearPageSignal = MutableSharedFlow<Unit>()

    // For QuickNav scrolling with previews
    val saveCurrent = MutableSharedFlow<Unit>()
    val isScrubbing = MutableStateFlow(false)
    val previewPage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val restoreCanvas = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    val changePage = MutableSharedFlow<String>(extraBufferCapacity = 1)
}
