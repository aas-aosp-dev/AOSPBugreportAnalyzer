package com.bigdotdev.aospbugreportanalyzer.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun StrictJsonSettingsEntry(
    modifier: Modifier = Modifier,
    buttonLabel: String = "Strict JSON settings"
) {
    var isDialogOpen by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var settings by remember { mutableStateOf<AppSettings?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        val result = runCatching { withContext(Dispatchers.IO) { SettingsStore.load() } }
        settings = result.getOrDefault(AppSettings())
        errorMessage = result.exceptionOrNull()?.message
    }

    TextButton(
        onClick = {
            if (settings == null) {
                scope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { SettingsStore.load() } }
                    settings = result.getOrDefault(AppSettings())
                    errorMessage = result.exceptionOrNull()?.message
                    isDialogOpen = true
                }
            } else {
                isDialogOpen = true
            }
        },
        modifier = modifier
    ) {
        Text(buttonLabel)
    }

    if (isDialogOpen) {
        Dialog(onCloseRequest = { isDialogOpen = false }) {
            Surface(shape = MaterialTheme.shapes.medium) {
                val currentSettings = settings ?: AppSettings()
                Box(modifier = Modifier.padding(16.dp)) {
                    if (isLoading) {
                        CircularProgressIndicator()
                    } else {
                        JsonModeSettingsScreen(
                            initialSettings = currentSettings,
                            onSave = { updated ->
                                scope.launch {
                                    isLoading = true
                                    errorMessage = null
                                    val result = runCatching { withContext(Dispatchers.IO) { SettingsStore.save(updated) } }
                                    if (result.isSuccess) {
                                        settings = updated
                                        isDialogOpen = false
                                    } else {
                                        errorMessage = result.exceptionOrNull()?.message
                                            ?: "Failed to save settings"
                                    }
                                    isLoading = false
                                }
                            },
                            onCancel = { isDialogOpen = false },
                            errorMessage = errorMessage
                        )
                    }
                }
            }
        }
    }
}
