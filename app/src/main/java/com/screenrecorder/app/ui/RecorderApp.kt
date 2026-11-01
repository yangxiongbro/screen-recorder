package com.screenrecorder.app.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.screenrecorder.app.apps.ForegroundAppMonitor
import com.screenrecorder.app.core.CaptureMode
import com.screenrecorder.app.core.RecorderController
import com.screenrecorder.app.core.RecorderSettings
import com.screenrecorder.app.core.RecorderState
import com.screenrecorder.app.core.RecordingCommands
import com.screenrecorder.app.core.SaveLocation
import com.screenrecorder.app.core.SettingsRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private enum class AppTab(val label: String) {
    HOME("首页"),
    FILES("录制文件"),
    SETTINGS("设置"),
}

private fun AppTab.icon(): ImageVector = when (this) {
    AppTab.HOME -> Icons.Default.Home
    AppTab.FILES -> Icons.Default.VideoLibrary
    AppTab.SETTINGS -> Icons.Default.Settings
}

@Composable
fun RecorderApp() {
    val context = LocalContext.current
    val repository = remember { SettingsRepository.get(context) }
    val settings by repository.settings.collectAsStateWithLifecycle()
    val recorderState by RecorderController.state.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val foregroundMonitor = remember(context) { ForegroundAppMonitor(context) }

    var tab by rememberSaveable { mutableStateOf(AppTab.HOME) }
    var showAppPicker by remember { mutableStateOf(false) }
    var countdown by remember { mutableIntStateOf(0) }
    var startSignal by remember { mutableIntStateOf(0) }
    var hasUsageAccess by remember { mutableStateOf(foregroundMonitor.hasPermission()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        hasUsageAccess = foregroundMonitor.hasPermission()
    }

    val projectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            RecordingCommands.start(context, result.resultCode, data)
        } else {
            RecorderController.publish(RecorderState.Failed("已取消屏幕捕获授权"))
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            launchProjection(context, settings, projectionLauncher)
        } else {
            scope.launch {
                snackbarHostState.showSnackbar("未授予麦克风权限，本次将不录制声音")
            }
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* The foreground service notification is shown regardless of this result. */ }

    val folderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
            repository.update {
                it.copy(saveLocation = SaveLocation.CUSTOM_FOLDER, customFolderUri = uri.toString())
            }
        }
    }

    val beginRecording: () -> Unit = {
        val audioGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (settings.hasAudio && !audioGranted) {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        } else {
            launchProjection(context, settings, projectionLauncher)
        }
    }

    // Asked once on first launch so it never competes with the screen capture dialog.
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(startSignal) {
        if (startSignal == 0) return@LaunchedEffect
        for (value in settings.countdownSeconds downTo 1) {
            countdown = value
            delay(1_000L)
        }
        countdown = 0
        beginRecording()
    }

    LaunchedEffect(recorderState) {
        when (val state = recorderState) {
            is RecorderState.Finished -> {
                tab = AppTab.FILES
                scope.launch { snackbarHostState.showSnackbar("已保存：${state.fileName}") }
                RecorderController.reset()
            }

            is RecorderState.Failed -> {
                scope.launch { snackbarHostState.showSnackbar(state.message) }
                RecorderController.reset()
            }

            else -> Unit
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                AppTab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon(), contentDescription = item.label) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (tab) {
                AppTab.HOME -> HomeScreen(
                    settings = settings,
                    state = recorderState,
                    onStart = {
                        if (recorderState !is RecorderState.Recording) startSignal++
                    },
                    onStop = { RecordingCommands.stop(context) },
                    onPause = { RecordingCommands.pause(context) },
                    onResume = { RecordingCommands.resume(context) },
                    onOpenSettings = { tab = AppTab.SETTINGS },
                )

                AppTab.FILES -> Column(modifier = Modifier.fillMaxSize()) {
                    ScreenTitle("录制文件")
                    RecordingsScreen(settings = settings, modifier = Modifier.weight(1f))
                }

                AppTab.SETTINGS -> Column(modifier = Modifier.fillMaxSize()) {
                    ScreenTitle("设置")
                    SettingsScreen(
                        settings = settings,
                        hasUsageAccess = hasUsageAccess,
                        onUpdate = repository::update,
                        onPickFolder = { folderLauncher.launch(null) },
                        onPickApp = { showAppPicker = true },
                        onOpenUsageAccess = {
                            runCatching {
                                context.startActivity(ForegroundAppMonitor.usageAccessSettingsIntent())
                            }.onFailure {
                                Toast.makeText(context, "无法打开使用情况访问设置", Toast.LENGTH_SHORT)
                                    .show()
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            onDismiss = { showAppPicker = false },
            onSelect = { app ->
                repository.update { it.copy(targetPackage = app.packageName, targetLabel = app.label) }
            },
        )
    }

    if (countdown > 0) {
        Dialog(
            onDismissRequest = { },
            properties = DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 4.dp,
            ) {
                Box(
                    modifier = Modifier.width(160.dp).padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "$countdown",
                        fontSize = 64.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ScreenTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.headlineSmall,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp),
    )
}

private fun launchProjection(
    context: Context,
    settings: RecorderSettings,
    launcher: ActivityResultLauncher<Intent>,
) {
    val manager = context.getSystemService(MediaProjectionManager::class.java) ?: return
    val intent = when {
        // Android 14+: ask explicitly for the whole display, otherwise the system picker may
        // default to a single app capture.
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE &&
            settings.captureMode == CaptureMode.SINGLE_APP ->
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForUserChoice())

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())

        else -> manager.createScreenCaptureIntent()
    }
    launcher.launch(intent)
}
