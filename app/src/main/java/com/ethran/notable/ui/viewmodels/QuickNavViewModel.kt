package com.ethran.notable.ui.viewmodels


import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ethran.notable.data.AppRepository
import com.ethran.notable.data.datastore.GlobalAppSettings
import com.ethran.notable.data.db.Folder
import com.ethran.notable.data.db.Page
import com.ethran.notable.editor.canvas.CanvasEventBus
import com.ethran.notable.io.ThumbnailBackfillQueue
import com.ethran.notable.ui.SnackConf
import com.ethran.notable.ui.SnackDispatcher
import com.ethran.notable.ui.components.getFolderList
import io.shipbook.shipbooksdk.ShipBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch


/**
 * A notebook's pages, positioned for scrubbing. Only ever built for a notebook worth scrubbing —
 * [MIN_SCRUBBABLE_PAGES] or more — so its existence is the answer to "is there a scrubber?".
 */
data class ScrubberState(
    val pageIds: List<String>,
    /** Zero-based position of the page in view. */
    val index: Int,
    /** Positions of favourited pages, for the scrubber's tick marks. */
    val favouriteIndexes: List<Int> = emptyList(),
) {
    val count: Int get() = pageIds.size

    fun pageAt(index: Int): String? = pageIds.getOrNull(index)
}

/** Below this a scrubber has nothing to move between. */
private const val MIN_SCRUBBABLE_PAGES = 2

data class QuickNavUiState(
    val isLoading: Boolean = true,
    val currentPageId: String? = null,
    val folderId: String? = null,
    val breadcrumbFolders: List<Folder> = emptyList(),
    val bookId: String? = null,
    val isCurrentPageFavorite: Boolean = false,
    val favoritePages: List<Page> = emptyList(),

    /**
     * The scrubber over the current notebook, or null when there is nothing to scrub.
     *
     * One nullable value rather than four correlated fields. As separate fields they could only be
     * *overwritten*, never cleared — [loadBookData] writes them only for a notebook of two or more
     * pages, so opening QuickNav on a shorter notebook, or on a quick page with no notebook at all,
     * left the previous notebook's values in place and the scrubber read e.g. "0/3". A page count
     * with no book behind it is now unrepresentable.
     */
    val scrubber: ScrubberState? = null
)


class QuickNavViewModel(
    private val appRepository: AppRepository,
    private val thumbnailBackfillQueue: ThumbnailBackfillQueue,
    private val snackDispatcher: SnackDispatcher,
) : ViewModel() {
    private val pageRepository = appRepository.pageRepository
    private val bookRepository = appRepository.bookRepository
    private val kv = appRepository.kvProxy
    private val log = ShipBook.getLogger("QuickNavViewModel")

    private val _uiState = MutableStateFlow(QuickNavUiState())
    val uiState: StateFlow<QuickNavUiState> = _uiState.asStateFlow()
    private var lastScrubEndTargetPageId: String? = null

    // Initialize data when the ViewModel is created or when a new page is opened
    fun loadPageData(currentPageId: String?) {
        if (currentPageId == null) return

        // Cleared, not left to be overwritten: the next page may have no scrubber at all.
        _uiState.update { it.copy(isLoading = true, currentPageId = currentPageId, scrubber = null) }

        viewModelScope.launch(Dispatchers.IO) {
            val page = runCatching { pageRepository.getById(currentPageId) }.getOrNull()
            val folderList = getFolderList(appRepository, page)

            // Read favorites from your database/preferences
            val currentSettings = GlobalAppSettings.current
            val favorites = currentSettings.quickNavPages
            val isFavorite = favorites.contains(currentPageId)

            val favoritePagesDb = appRepository.pageRepository.getByIds(favorites)

            _uiState.update { state ->
                state.copy(
                    folderId = page?.parentFolderId,
                    breadcrumbFolders = folderList,
                    bookId = page?.notebookId,
                    isCurrentPageFavorite = isFavorite,
                    favoritePages = favoritePagesDb,
                    isLoading = false
                )
            }

            // Load Scrubber data if it belongs to a book
            page?.notebookId?.let { loadBookData(it, currentPageId, favorites) }
        }
    }

    private suspend fun loadBookData(
        bookId: String, currentPageId: String, favorites: List<String>
    ) {
        val book = bookRepository.getById(bookId)
        if (book != null && book.pageIds.size >= MIN_SCRUBBABLE_PAGES) {
            val currentIdx = appRepository.getPageNumber(bookId, currentPageId)
            val favIndexes = book.pageIds.mapIndexedNotNull { idx, id ->
                if (favorites.contains(id)) idx else null
            }

            _uiState.update { state ->
                state.copy(
                    scrubber = ScrubberState(
                        pageIds = book.pageIds,
                        index = currentIdx,
                        favouriteIndexes = favIndexes,
                    )
                )
            }
        }
    }

    fun toggleFavorite() {
        val currentState = _uiState.value
        val pageId = currentState.currentPageId ?: return

        viewModelScope.launch(Dispatchers.IO) {
            val settings = GlobalAppSettings.current
            val currentFavorites = settings.quickNavPages
            val isFav = currentFavorites.contains(pageId)

            val newFavorites = if (isFav) {
                currentFavorites.filterNot { it == pageId }
            } else {
                currentFavorites + pageId
            }

            // Save to DB
            kv.setAppSettings(settings.copy(quickNavPages = newFavorites))

            // Update UI State locally immediately
            _uiState.update { it.copy(isCurrentPageFavorite = !isFav) }

            // Re-fetch the rich page objects for the ShowPagesRow
            val updatedFavoritePages = appRepository.pageRepository.getByIds(newFavorites)
            _uiState.update { it.copy(favoritePages = updatedFavoritePages) }
        }
    }

    // --- Scrubber Actions ---

    fun onScrubStart() {
        viewModelScope.launch {
            lastScrubEndTargetPageId = null
            CanvasEventBus.active.saveCurrent.emit(Unit)
            CanvasEventBus.active.isScrubbing.emit(true)
        }
    }

    fun onScrubPreview(index: Int) {
        val pageId = _uiState.value.scrubber?.pageAt(index) ?: return
        viewModelScope.launch { CanvasEventBus.active.previewPage.tryEmit(pageId) }
    }

    fun onScrubEnd(index: Int) {
        val targetPageId = _uiState.value.scrubber?.pageAt(index) ?: return

        viewModelScope.launch {
            log.v("onScrubEnd: $index")

            // moved, to be only run if we are changing page to the current page
//            CanvasEventBus.active.restoreCanvas.emit(Unit)

            CanvasEventBus.active.isScrubbing.emit(false)

            // Gesture end callbacks can fire more than once; ignore repeated commit for same target.
            if (targetPageId == lastScrubEndTargetPageId)
            {
                log.e("Events can be send multiple times, really")
                return@launch
            }
            lastScrubEndTargetPageId = targetPageId


            CanvasEventBus.active.changePage.emit(targetPageId)
        }
    }

    fun onReturnClick(quickNavSourcePageId: String?) {
        if (quickNavSourcePageId == null) {
            snackDispatcher.showOrUpdateSnack(
                SnackConf(text = "Can't go back, no QuickNav source page", duration = 4000)
            )
        } else {
            CanvasEventBus.active.changePage.tryEmit(quickNavSourcePageId)
        }
    }

    fun generateThumbnailsForCurrentBook() {
        val pageIds = _uiState.value.scrubber?.pageIds ?: return
        thumbnailBackfillQueue.enqueue(pageIds)
    }
}