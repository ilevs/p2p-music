package io.github.ilevs.p2pmusic.ui

import android.content.res.Configuration
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.AppGraph
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.search.SOULSEEK

private val RAIL_WIDTH = 80.dp

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
    // Landscape head units are short: tabs go to a side rail instead of eating the height.
    val isWide = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Scaffold(
        modifier = modifier.fillMaxSize(),
        bottomBar = {
            // Without the tab bar the mini-player is the lowest thing on screen and keeps off the system bar.
            Column(modifier = if (isWide) Modifier.navigationBarsPadding() else Modifier) {
                if (tab != Tab.Player) MiniPlayer(graph.stream, onOpen = { tab = Tab.Player })
                if (!isWide) {
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
                }
            }
        },
    ) { padding ->
        var content = Modifier.padding(padding)
        if (isWide) {
            NavigationRail(modifier = content) {
                Tab.entries.forEach { item ->
                    NavigationRailItem(
                        selected = item == tab,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(stringResource(item.title)) },
                    )
                }
            }
            content = content.padding(start = RAIL_WIDTH)
        }
        TabContent(tab = tab, graph = graph, onTabChange = { tab = it }, modifier = content)
    }
}

@Composable
private fun TabContent(
    tab: Tab,
    graph: AppGraph,
    onTabChange: (Tab) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        Tab.Search -> {
            SearchScreen(
                catalog = graph.catalog,
                controller = graph.search,
                onAlbum = { album ->
                    graph.catalog.play(album)
                    onTabChange(Tab.Player)
                },
                onOpen = { result ->
                    graph.stream.openAlbum(result.source == SOULSEEK, null) { graph.search.open(result) }
                    onTabChange(Tab.Player)
                },
                modifier = modifier,
            )
        }

        Tab.Player -> {
            StreamScreen(graph.stream, modifier)
        }

        Tab.Settings -> {
            SettingsScreen(
                store = graph.settings,
                search = graph.search,
                catalog = graph.catalog,
                onSave = { graph.engine.setSeeding(it.seeding) },
                modifier = modifier,
            )
        }
    }
}
