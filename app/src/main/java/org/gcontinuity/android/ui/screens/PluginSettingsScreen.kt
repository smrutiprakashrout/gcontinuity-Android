package org.gcontinuity.android.ui.screens

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.gcontinuity.android.store.ALL_PLUGINS
import org.gcontinuity.android.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginSettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit,
) {
    val pluginStates by viewModel.pluginStates.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.Rounded.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                title = {
                    Text(
                        text = "Plugin settings",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(ALL_PLUGINS, key = { it.id }) { plugin ->
                ListItem(
                    headlineContent = {
                        Text(
                            text = plugin.title,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    },
                    supportingContent = {
                        Text(
                            text = plugin.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    trailingContent = {
                        Switch(
                            checked = pluginStates[plugin.id] ?: plugin.enabledByDefault,
                            onCheckedChange = { viewModel.setPluginEnabled(plugin.id, it) },
                        )
                    }
                )
            }
        }
    }
}
