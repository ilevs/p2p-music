package io.github.ilevs.p2pmusic.search

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SearchState(
    val results: List<SearchResult> = emptyList(),
    val isLoading: Boolean = false,
    val message: String? = null,
)

/** Search on the built-in tracker and the "check login" button of the settings screen. */
class SearchController(
    private val tracker: RuTracker,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
) {
    private val mutableState = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = mutableState.asStateFlow()

    fun search(query: String) {
        run { tracker.search(query).sortedByDescending { it.seeds } to null }
    }

    /** Blocking. */
    fun torrentFile(result: SearchResult): ByteArray = tracker.torrentFile(result)

    fun checkLogin() {
        run {
            tracker.login()
            state.value.results to "Вход в ${tracker.name} выполнен"
        }
    }

    private fun run(block: () -> Pair<List<SearchResult>, String?>) {
        mutableState.update { it.copy(isLoading = true, message = null) }
        scope.launch {
            val result = runCatching { withContext(io) { block() } }
            mutableState.update { state ->
                result.fold(
                    onSuccess = { (results, message) -> SearchState(results, isLoading = false, message = message) },
                    onFailure = { state.copy(isLoading = false, message = it.message ?: it.toString()) },
                )
            }
        }
    }
}
