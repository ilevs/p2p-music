package io.github.ilevs.p2pmusic.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.ilevs.p2pmusic.R
import io.github.ilevs.p2pmusic.SettingsStore
import io.github.ilevs.p2pmusic.UserSettings
import io.github.ilevs.p2pmusic.catalog.CatalogController
import io.github.ilevs.p2pmusic.search.SearchController

@Composable
fun SettingsScreen(
    store: SettingsStore,
    search: SearchController,
    catalog: CatalogController,
    onSave: (UserSettings) -> Unit,
    modifier: Modifier = Modifier,
) {
    var settings by remember { mutableStateOf(store.load()) }
    val save = {
        store.save(settings)
        onSave(settings)
    }
    val state by search.state.collectAsState()
    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(stringResource(R.string.rutracker_section), style = MaterialTheme.typography.titleMedium)
        Field(
            R.string.login,
            settings.rutrackerLogin,
            onValueChange = { settings = settings.copy(rutrackerLogin = it) },
        )
        Field(R.string.password, settings.rutrackerPassword, isSecret = true, onValueChange = {
            settings =
                settings.copy(rutrackerPassword = it)
        })
        Text(stringResource(R.string.proxy_section), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.proxy_hint), style = MaterialTheme.typography.bodySmall)
        Field(R.string.proxy_host, settings.proxyHost, onValueChange = { settings = settings.copy(proxyHost = it) })
        Field(R.string.proxy_port, settings.proxyPort, keyboardType = KeyboardType.Number, onValueChange = {
            settings =
                settings.copy(proxyPort = it)
        })
        Field(R.string.login, settings.proxyUser, onValueChange = { settings = settings.copy(proxyUser = it) })
        Field(R.string.password, settings.proxyPassword, isSecret = true, onValueChange = {
            settings =
                settings.copy(proxyPassword = it)
        })
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.seeding), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.seeding_hint), style = MaterialTheme.typography.bodySmall)
            }
            Switch(checked = settings.seeding, onCheckedChange = { settings = settings.copy(seeding = it) })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = save) { Text(stringResource(R.string.save)) }
            FilledTonalButton(
                onClick = {
                    save()
                    search.checkLogin()
                },
                enabled = !state.isLoading,
            ) { Text(stringResource(R.string.check_login)) }
        }
        state.message?.let { Text(it) }
        LibrarySync(catalog)
    }
}

@Composable
private fun Field(
    @StringRes label: Int,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    isSecret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = if (isSecret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions =
            KeyboardOptions(keyboardType = if (isSecret) KeyboardType.Password else keyboardType),
        modifier = modifier.fillMaxWidth(),
    )
}
