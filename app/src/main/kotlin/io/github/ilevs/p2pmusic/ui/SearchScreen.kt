package io.github.ilevs.p2pmusic.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.search.SearchController
import io.github.ilevs.p2pmusic.search.SearchResult

private const val BYTES_PER_MB = 1_048_576f

@Composable
fun SearchScreen(
    controller: SearchController,
    onOpen: (SearchResult) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by controller.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    val search = { if (query.isNotBlank()) controller.search(query.trim()) }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text(stringResource(R.string.search_label)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { search() }),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = search,
                enabled = query.isNotBlank() && !state.isLoading,
                modifier = Modifier.padding(start = 8.dp),
            ) { Text(stringResource(R.string.search)) }
        }
        if (state.isLoading) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        state.message?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(modifier = Modifier.weight(1f)) {
            items(state.results, key = { it.id }) { result ->
                ResultRow(result = result, onClick = { onOpen(result) })
                HorizontalDivider()
            }
        }
    }
}

@Composable
private fun ResultRow(
    result: SearchResult,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp)) {
        Text(text = result.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            text =
                stringResource(
                    R.string.result_details,
                    result.category,
                    result.sizeBytes / BYTES_PER_MB,
                    result.seeds,
                ),
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
