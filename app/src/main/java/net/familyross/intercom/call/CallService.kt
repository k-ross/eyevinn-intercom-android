package net.familyross.intercom.call

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import net.familyross.intercom.IntercomApp
import net.familyross.intercom.MainActivity
import net.familyross.intercom.R

/**
 * Keeps the process, microphone and network alive while calls are up and the
 * app is in the background or the screen is off. It holds no call state of its
 * own: it starts once calls exist and stops itself when the last one is left.
 */
class CallService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val callManager get() = (application as IntercomApp).callManager
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var observing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.call_channel_name), NotificationManager.IMPORTANCE_LOW)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_LEAVE) {
            callManager.leaveAll()
        }
        // Always promote first: a service started with startForegroundService()
        // must call startForeground() even if it is about to stop.
        startForeground(
            NOTIFICATION_ID,
            buildNotification(getString(R.string.call_notification_connecting)),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
        )
        acquireLocks()
        if (!observing) {
            observing = true
            scope.launch { observeCalls() }
        }
        return START_NOT_STICKY
    }

    // Swiping the app away ends the call rather than leaving a hidden open line.
    override fun onTaskRemoved(rootIntent: Intent?) {
        callManager.leaveAll()
    }

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        super.onDestroy()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun observeCalls() {
        callManager.calls
            .flatMapLatest { calls ->
                if (calls.isEmpty()) flowOf(null)
                else combine(calls.map { it.state }) { states -> describe(states) }
            }
            .distinctUntilChanged()
            .collect { text ->
                if (text == null) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                } else {
                    getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(text))
                }
            }
    }

    private fun describe(states: Array<CallState>): String {
        val connected = states.filter { it.status == ConnectionStatus.CONNECTED }
        if (connected.isEmpty()) return getString(R.string.call_notification_connecting)
        val names = connected.joinToString { state ->
            listOfNotNull(state.productionName, state.lineName).joinToString(" / ")
        }
        return getString(R.string.call_notification_in_call, names)
    }

    private fun buildNotification(text: String): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val leave = PendingIntent.getService(
            this, 1, Intent(this, CallService::class.java).setAction(ACTION_LEAVE), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_headset)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_CALL)
            .addAction(
                Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_headset),
                    getString(R.string.call_notification_leave),
                    leave,
                ).build()
            )
            .build()
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OpenIntercom:call")
                .apply { acquire() }
        }
        if (wifiLock == null) {
            wifiLock = applicationContext.getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "OpenIntercom:call")
                .apply { acquire() }
        }
    }

    companion object {
        private const val CHANNEL_ID = "call"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_LEAVE = "net.familyross.intercom.action.LEAVE"

        /** Call after [CallManager.join], while the app is in the foreground. */
        fun start(context: Context) {
            context.startForegroundService(Intent(context, CallService::class.java))
        }
    }
}
