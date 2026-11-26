package io.github.ilevs.p2pmusic.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.catalog.CatalogController

private const val EXPORT_NAME = "p2p-music-library.json"

/** Moves "Мои альбомы" between devices through a file, e.g. phone to head unit over a USB stick. */
@Composable
fun LibrarySync(
    catalog: CatalogController,
    modifier: Modifier = Modifier,
) {
    val resolver = LocalContext.current.contentResolver
    val state by catalog.state.collectAsState()
    val export =
        rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let(resolver::openOutputStream)?.let(catalog::exportLibrary)
        }
    val import =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(resolver::openInputStream)?.let(catalog::importLibrary)
        }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.library), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { export.launch(EXPORT_NAME) }) { Text(stringResource(R.string.export_library)) }
            OutlinedButton(
                onClick = { import.launch(arrayOf("*/*")) },
            ) { Text(stringResource(R.string.import_library)) }
        }
        state.message?.let { Text(it) }
    }
}
