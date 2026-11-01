package com.screenrecorder.app.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.core.RecorderState
import com.screenrecorder.app.util.DisplayUtils
import com.screenrecorder.app.util.Formatters
import kotlinx.coroutines.delay

@Composable
fun HomeScreen(
    settings: RecorderSettings,
    state: RecorderState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val displaySize = remember { DisplayUtils.currentDisplaySize(context) }
    val captureSize = remember(settings, displaySize) {
        settings.captureSize(displaySize.width, displaySize.height)
    }
    val bitrateKbps = remember(settings, captureSize) {
        settings.videoBitrateKbps(captureSize.width, captureSize.height)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "录屏助手",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "录制屏幕或单个应用",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Default.Settings, contentDescription = "设置")
            }
        }

        Spacer(Modifier.height(24.dp))

        when (state) {
            is RecorderState.Recording -> RecordingPanel(
                state = state,
                onPause = onPause,
                onResume = onResume,
                onStop = onStop,
            )

            else -> IdlePanel(
                enabled = state !is RecorderState.Preparing,
                onStart = onStart,
            )
        }

        Spacer(Modifier.height(28.dp))

        SummaryCard(
            settings = settings,
            captureSize = captureSize,
            bitrateKbps = bitrateKbps,
            onClick = onOpenSettings,
        )

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun IdlePanel(enabled: Boolean, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size(148.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, CircleShape),
            )
            Box(
                modifier = Modifier
                    .size(118.dp)
                    .border(2.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f), CircleShape),
            )
            Surface(
                shape = CircleShape,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
                modifier = Modifier
                    .size(96.dp)
                    .clickable(enabled = enabled, onClick = onStart),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = "开始录制",
                        tint = Color.White,
                        modifier = Modifier.size(38.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text(
            text = "点击开始录制",
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "录制开始前需要授权屏幕捕获",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun RecordingPanel(
    state: RecorderState.Recording,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
) {
    var elapsed by remember(state.startedAtMillis) {
        mutableLongStateOf(System.currentTimeMillis() - state.startedAtMillis)
    }
    LaunchedEffect(state.startedAtMillis) {
        while (true) {
            elapsed = System.currentTimeMillis() - state.startedAtMillis
            delay(500L)
        }
    }

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulsingDot(active = !state.paused)
                Spacer(Modifier.width(10.dp))
                Text(
                    text = if (state.paused) "已暂停" else "正在录制",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (state.paused) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = Formatters.duration(elapsed),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Medium,
                )
            }

            Spacer(Modifier.height(12.dp))

            Text(
                text = state.fileName.ifBlank { "正在准备文件…" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = state.resolution,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(18.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = if (state.paused) onResume else onPause,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (state.paused) Icons.Default.PlayArrow else Icons.Default.Pause,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.paused) "继续" else "暂停")
                }
                Button(
                    onClick = onStop,
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Stop,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("停止")
                }
            }
        }
    }
}

@Composable
private fun PulsingDot(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "pulse")
    val alpha by transition.animateFloat(
        initialValue = 1f,
        targetValue = 0.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulseAlpha",
    )
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(
                color = MaterialTheme.colorScheme.primary.copy(alpha = if (active) alpha else 0.4f),
                shape = CircleShape,
            ),
    )
}

@Composable
private fun SummaryCard(
    settings: RecorderSettings,
    captureSize: android.util.Size,
    bitrateKbps: Int,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.clickable(onClick = onClick).padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "本次录制配置",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    text = "编辑",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.height(12.dp))
            SummaryRow("录制范围", settings.captureMode.label)
            SummaryRow("分辨率", "${settings.resolution.label} · ${captureSize.width}×${captureSize.height}")
            SummaryRow("帧率", settings.frameRate.label)
            SummaryRow("画质", settings.quality.label)
            SummaryRow("视频码率", Formatters.bitrate(bitrateKbps))
            SummaryRow("编码格式", settings.codec.label)
            SummaryRow("声音", settings.audioSource.label)
            SummaryRow("保存位置", settings.saveLocation.label)
            if (settings.maxDurationMinutes > 0) {
                SummaryRow("最长时长", "${settings.maxDurationMinutes} 分钟")
            }
            if (settings.countdownSeconds > 0) {
                SummaryRow("开始倒计时", "${settings.countdownSeconds} 秒")
            }
        }
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontSize = 14.sp,
        )
    }
}
