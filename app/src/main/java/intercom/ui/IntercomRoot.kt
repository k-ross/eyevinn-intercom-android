package intercom.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.withResumed
import intercom.IntercomApp
import intercom.call.CallService
import intercom.call.LineCall
import intercom.data.AppSettings
import intercom.data.CallUrl
import intercom.data.CallUrlResult

@Composable
fun IntercomRoot() {
    val context = LocalContext.current
    val app = context.applicationContext as IntercomApp
    val settings by app.settings.settings.collectAsStateWithLifecycle()
    val calls by app.callManager.calls.collectAsStateWithLifecycle()
    val notice by app.callManager.notice.collectAsStateWithLifecycle()

    var showSettings by rememberSaveable { mutableStateOf(false) }
    var microphoneDenied by rememberSaveable { mutableStateOf(false) }
    var notificationsRequested by rememberSaveable { mutableStateOf(false) }

    fun startCalls() {
        if (!app.callManager.join()) return
        try {
            CallService.start(context)
        } catch (e: IllegalStateException) {
            // The system refused a foreground service (app not in front); the
            // call still runs for as long as the app stays visible.
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        microphoneDenied = !context.hasPermission(Manifest.permission.RECORD_AUDIO)
        if (!microphoneDenied) startCalls()
    }

    fun join() {
        val missing = buildList {
            if (!context.hasPermission(Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            // The call notification is optional, so this is only asked once.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !notificationsRequested &&
                !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
            ) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (missing.isEmpty()) {
            microphoneDenied = false
            startCalls()
        } else {
            notificationsRequested = true
            permissionLauncher.launch(missing.toTypedArray())
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(Unit) {
        lifecycleOwner.withResumed {
            if (!app.autoJoinHandled) {
                app.autoJoinHandled = true
                val current = app.settings.settings.value
                if (current.autoJoin && current.isConfigured && app.callManager.calls.value.isEmpty()) join()
            }
        }
    }

    // Like the web client's wake lock: the screen stays on while in a call.
    val view = LocalView.current
    val inCall = calls.isNotEmpty()
    DisposableEffect(view, inCall) {
        view.keepScreenOn = inCall
        onDispose { view.keepScreenOn = false }
    }

    BackHandler(enabled = showSettings) { showSettings = false }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(IntercomColors.Background)
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        if (showSettings) {
            SettingsScreen(
                initial = settings,
                inCall = inCall,
                onSave = { updated ->
                    val reconnect = updated.connectionDiffersFrom(settings)
                    app.settings.save(updated)
                    if (reconnect) app.callManager.leaveAll()
                    showSettings = false
                },
                onCancel = { showSettings = false },
            )
        } else {
            MainScreen(
                settings = settings,
                calls = calls,
                notice = notice,
                microphoneDenied = microphoneDenied,
                onDismissNotice = app.callManager::dismissNotice,
                onOpenSettings = { showSettings = true },
                onJoin = ::join,
                onVolumeChange = app.callManager::setVolume,
                onLeave = app.callManager::leave,
                onOpenAppPermissions = {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        )
                    )
                },
            )
        }
    }
}

@Composable
private fun MainScreen(
    settings: AppSettings,
    calls: List<LineCall>,
    notice: String?,
    microphoneDenied: Boolean,
    onDismissNotice: () -> Unit,
    onOpenSettings: () -> Unit,
    onJoin: () -> Unit,
    onVolumeChange: (LineCall, Float) -> Unit,
    onLeave: (LineCall) -> Unit,
    onOpenAppPermissions: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        AppHeader(username = settings.username.ifBlank { "Guest" }, onOpenSettings = onOpenSettings)
        if (calls.isNotEmpty()) PageTitle("Calls")
        Column(
            modifier = Modifier.padding(start = 17.dp, end = 17.dp, top = if (calls.isEmpty()) 16.dp else 0.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (notice != null) NoticeBanner(notice, onDismissNotice)
            when {
                calls.isNotEmpty() -> calls.forEach { call ->
                    key(call.key) {
                        CallCard(
                            call = call,
                            onVolumeChange = { onVolumeChange(call, it) },
                            onLeave = { onLeave(call) },
                        )
                    }
                }
                !settings.isConfigured -> InfoCard("Set up Open Intercom") {
                    BodyText("Enter your name and the call link you were sent to join a call.")
                    ActionButton("Open settings", onClick = onOpenSettings, modifier = Modifier.fillMaxWidth())
                }
                microphoneDenied -> InfoCard("Permissions have been denied", titleColor = IntercomColors.Warning) {
                    BodyText("To use this application it has to be granted access to the microphone.")
                    ActionButton("Try again", onClick = onJoin, modifier = Modifier.fillMaxWidth())
                    ActionButton("Open app permissions", onClick = onOpenAppPermissions, modifier = Modifier.fillMaxWidth())
                }
                else -> InfoCard("Not in a call") {
                    BodyText(settings.callSummary())
                    ActionButton("Join Call", onClick = onJoin, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }
}

@Composable
private fun NoticeBanner(text: String, onDismiss: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(IntercomColors.Warning)
            .clickable(onClickLabel = "Dismiss", role = Role.Button, onClick = onDismiss)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, color = IntercomColors.OnAction, fontSize = 16.sp, modifier = Modifier.weight(1f))
        Text("Dismiss", color = IntercomColors.OnAction, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

private fun AppSettings.callSummary(): String {
    val target = (CallUrl.parse(callUrl) as? CallUrlResult.Ok)?.target ?: return ""
    val lines = if (target.calls.size == 1) "1 line" else "${target.calls.size} lines"
    return "Ready to join $lines on ${target.origin.host} as $username."
}

private fun AppSettings.connectionDiffersFrom(other: AppSettings): Boolean =
    username != other.username || callUrl != other.callUrl || authUsername != other.authUsername ||
        authPassword != other.authPassword || apiUrl != other.apiUrl

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
