package io.github.ilevs.p2pmusic.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import io.github.ilevs.p2pmusic.AppGraph
import io.github.ilevs.p2pmusic.R

private enum class Tab(
    @param:StringRes val title: Int,
    val icon: ImageVector,
) {
    Search(R.string.tab_search, Icons.Filled.Search),
    Player(R.string.tab_player, Icons.Filled.PlayArrow),
    Settings(R.string.tab_settings, Icons.Filled.Settings),
}

@Composable
fun MainScreen(
    graph: AppGraph,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Search) }
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.title)) },
                    )
                }
            }
        },
    ) { padding ->
        val content = Modifier.padding(padding)
        when (tab) {
            Tab.Search -> {
                SearchScreen(
                    controller = graph.search,
                    onOpen = { result ->
                        graph.stream.openTorrent { graph.search.torrentFile(result) }
                        tab = Tab.Player
                    },
                    modifier = content,
                )
            }

            Tab.Player -> {
                StreamScreen(graph.stream, content)
            }

            Tab.Settings -> {
                SettingsScreen(graph.settings, graph.search, content)
            }
        }
    }
}
