package com.poco.camera.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.poco.camera.updates.AvailableUpdate

@Composable
fun UpdateDialog(
    update: AvailableUpdate,
    downloading: Boolean,
    progress: Int,
    error: String?,
    onInstall: () -> Unit,
    onDismiss: () -> Unit,
    onOpenGitHub: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text("NeoCam " + update.version + " доступна") },
        text = {
            Column {
                Text("Есть новая версия камеры. Скачать APK и открыть системный установщик?")
                Spacer(Modifier.height(12.dp))
                if (downloading) {
                    Text("Загрузка: $progress%")
                    LinearProgressIndicator(
                        progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (error != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInstall, enabled = !downloading) {
                Text("Скачать и установить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !downloading) {
                Text("Позже")
            }
            TextButton(onClick = onOpenGitHub, enabled = !downloading) {
                Text("GitHub")
            }
        },
    )
}
