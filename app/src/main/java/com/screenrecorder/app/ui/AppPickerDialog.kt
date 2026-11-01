package com.screenrecorder.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.screenrecorder.app.apps.InstalledApp
import com.screenrecorder.app.apps.InstalledApps
import androidx.compose.ui.platform.LocalContext

@Composable
fun AppPickerDialog(
    onDismiss: () -> Unit,
    onSelect: (InstalledApp) -> Unit,
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        apps = InstalledApps.load(context)
        loading = false
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.82f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("选择目标应用", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "仅用于界面提示与自动停止，实际录制哪个应用请在系统弹窗中选择",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("搜索应用") },
                )
                Box(modifier = Modifier.weight(1f)) {
                    when {
                        loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                        else -> {
                            val filtered = apps.filter {
                                query.isBlank() ||
                                    it.label.contains(query, ignoreCase = true) ||
                                    it.packageName.contains(query, ignoreCase = true)
                            }
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                items(filtered, key = { it.packageName }) { app ->
                                    Surface(
                                        color = MaterialTheme.colorScheme.surface,
                                        onClick = {
                                            onSelect(app)
                                            onDismiss()
                                        },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) {
                                        Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                            Text(
                                                text = app.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                            Text(
                                                text = app.packageName,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("取消")
                }
            }
        }
    }
}
