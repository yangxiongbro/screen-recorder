package com.screenrecorder.app.ui

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.storage.RecordingItem
import com.screenrecorder.app.storage.RecordingsRepository
import com.screenrecorder.app.util.Formatters
import kotlinx.coroutines.launch

@Composable
fun RecordingsScreen(settings: RecorderSettings, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val repository = remember { RecordingsRepository(context) }
    val scope = rememberCoroutineScope()

    var items by remember { mutableStateOf<List<RecordingItem>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var deleteTarget by remember { mutableStateOf<RecordingItem?>(null) }

    LaunchedEffect(reloadKey, settings.saveLocation, settings.customFolderUri) {
        loading = true
        items = repository.load(settings)
        loading = false
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        reloadKey++
    }

    Box(modifier = modifier.fillMaxSize()) {
        when {
            loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))

            items.isEmpty() -> EmptyState(
                title = "还没有录制文件",
                description = "回到首页点击录制按钮即可开始第一次录制",
                modifier = Modifier.align(Alignment.Center),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.uri.toString() }) { item ->
                    RecordingRow(
                        item = item,
                        onPlay = {
                            runCatching { context.startActivity(repository.playIntent(item)) }
                                .onFailure {
                                    Toast.makeText(context, "没有可用的播放器", Toast.LENGTH_SHORT)
                                        .show()
                                }
                        },
                        onShare = {
                            runCatching { context.startActivity(repository.shareIntent(item)) }
                                .onFailure {
                                    Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show()
                                }
                        },
                        onDelete = { deleteTarget = item },
                    )
                }
            }
        }
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除录制文件") },
            text = { Text("确定删除「${target.name}」吗？该操作不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteTarget = null
                        scope.launch {
                            val deleted = repository.delete(target)
                            if (!deleted) {
                                Toast.makeText(context, "删除失败", Toast.LENGTH_SHORT).show()
                            }
                            reloadKey++
                        }
                    },
                ) {
                    Text("删除")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun RecordingRow(
    item: RecordingItem,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.name,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = buildString {
                        append(Formatters.fileSize(item.sizeBytes))
                        if (item.durationMillis > 0) {
                            append(" · ")
                            append(Formatters.duration(item.durationMillis))
                        }
                        if (item.dateMillis > 0) {
                            append(" · ")
                            append(Formatters.date(item.dateMillis))
                        }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlay) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    modifier = Modifier.size(22.dp),
                )
            }
            IconButton(onClick = onShare) {
                Icon(
                    imageVector = Icons.Default.Share,
                    contentDescription = "分享",
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "删除",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}
