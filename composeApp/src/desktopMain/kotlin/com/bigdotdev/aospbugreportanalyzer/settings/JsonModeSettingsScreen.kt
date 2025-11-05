package com.bigdotdev.aospbugreportanalyzer.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp

@Composable
fun JsonModeSettingsScreen(
    initialSettings: AppSettings,
    onSave: (AppSettings) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
    errorMessage: String? = null
) {
    var strictJsonEnabled by remember(initialSettings) { mutableStateOf(initialSettings.strictJsonEnabled) }
    var systemPrompt by remember(initialSettings) { mutableStateOf(initialSettings.systemPromptText) }

    Column(
        modifier = modifier
            .widthIn(min = 360.dp)
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("LLM response format", style = MaterialTheme.typography.titleLarge)

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = strictJsonEnabled, onCheckedChange = { strictJsonEnabled = it })
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text("Strict JSON mode")
                Text(
                    "When enabled, the model receives an enforcing system prompt and responses are validated.",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("System prompt") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 6
        )

        errorMessage?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Button(onClick = { onSave(AppSettings(strictJsonEnabled, systemPrompt)) }) {
                Text("Save")
            }
            TextButton(onClick = onCancel) {
                Text("Cancel")
            }
        }
    }
}
