package com.soundmirror.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Process-lifetime owner of the audio stream. Playback runs in [scope] (a
 * SupervisorJob detached from any Composable/Activity), so audio keeps going
 * when the UI is backgrounded or destroyed. A [PlaybackService] is started in
 * the foreground while connected so the OS does not kill the process, and a
 * Wi-Fi/CPU lock keeps the radio responsive during screen-off.
 */
object PlaybackController {
    // Swallow any uncaught coroutine exception (e.g. a transient socket bind/IO
    // failure) so a streaming hiccup never crashes the whole app.
    private val errorHandler = CoroutineExceptionHandler { _, e ->
        Log.w("SoundMirror", "stream coroutine error", e)
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default + errorHandler)
    private lateinit var appContext: Context
    private var client: AudioStreamClient? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var initialized = false
    private var serviceRunning = false
    private var stopJob: Job? = null
    // Mirrors StreamSettings.wifiSaverActive. The low-latency Wi-Fi lock keeps the radio
    // hot (lowest latency, more battery). When the Wi-Fi-saver feature is on we release
    // it so the radio can power-save. Applied immediately from the toggle — not screen.
    @Volatile private var wifiSaverActive = false

    private val idleStats = MutableStateFlow(StreamStats())
    private val idleAudioLevel = MutableStateFlow(0f)

    /** Stable stats flow for the UI. Safe to read before [ensureInit]. */
    val stats: StateFlow<StreamStats>
        get() = client?.stats ?: idleStats.asStateFlow()

    /** Fast, lightweight state used only by the audio-reactive visualizer. */
    val audioLevel: StateFlow<Float>
        get() = client?.audioLevel ?: idleAudioLevel.asStateFlow()

    /** Call once (e.g. from Activity.onCreate) before observing [stats]. */
    fun ensureInit(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        val created = AudioStreamClient(appContext, scope)
        client = created

        // Tear down the service when the stream ends — but debounced, so the brief
        // connected→false→true flicker during a reconnect doesn't churn the
        // foreground service (which would trigger ForegroundServiceDidNotStartInTime).
        scope.launch {
            var wasConnected = false
            created.stats.collect { s ->
                if (s.connected) {
                    stopJob?.cancel()
                    stopJob = null
                } else if (wasConnected) {
                    stopJob?.cancel()
                    stopJob = scope.launch {
                        delay(2500)
                        if (!created.stats.value.connected) stopServiceAndLocks()
                    }
                }
                wasConnected = s.connected
            }
        }
    }

    fun connect(device: DiscoveredDevice, settings: StreamSettings) {
        val c = client ?: return
        stopJob?.cancel()
        stopJob = null
        wifiSaverActive = settings.wifiSaverActive
        startServiceAndLocks(device.name)
        c.connect(device, settings)
    }

    fun disconnect() {
        stopJob?.cancel()
        stopJob = null
        client?.disconnect()
        stopServiceAndLocks()
    }

    fun updateSettings(settings: StreamSettings) {
        client?.updateSettings(settings)
        if (wifiSaverActive != settings.wifiSaverActive) {
            wifiSaverActive = settings.wifiSaverActive
            // Apply the Wi-Fi-saver toggle immediately: release the lock when turned on,
            // re-take it when turned off.
            adjustWifiLock()
        }
    }

    // Called by the service when it is destroyed (incl. system kill) so our
    // serviceRunning flag stays in sync and a later connect can restart it.
    fun notifyServiceStopped() {
        serviceRunning = false
    }

    private fun startServiceAndLocks(deviceName: String) {
        if (!serviceRunning) {
            serviceRunning = true
            val intent = Intent(appContext, PlaybackService::class.java).apply {
                action = PlaybackService.ACTION_START
                putExtra(PlaybackService.EXTRA_NAME, deviceName)
            }
            appContext.startForegroundService(intent)
        }
        acquireLocks()
    }

    private fun stopServiceAndLocks() {
        if (serviceRunning) {
            serviceRunning = false
            appContext.stopService(Intent(appContext, PlaybackService::class.java))
        }
        releaseLocks()
    }

    private fun acquireLocks() {
        if (wifiLock == null) {
            val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            // FULL_LOW_LATENCY (API 29+) actually disables Wi-Fi power save / reduces
            // latency; the older HIGH_PERF mode is a no-op on modern Android.
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                @Suppress("DEPRECATION")
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifi.createWifiLock(mode, "soundmirror:wifi")
        }
        // Hold the low-latency Wi-Fi lock unless the Wi-Fi-saver feature is on.
        if (!wifiSaverActive) wifiLock?.takeIf { !it.isHeld }?.acquire()
        if (wakeLock == null) {
            val pm = appContext.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "soundmirror:cpu")
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire()
    }

    // Hold the Wi-Fi low-latency lock unless the Wi-Fi-saver feature is on.
    // No-op unless we're actively streaming.
    private fun adjustWifiLock() {
        if (!serviceRunning) return
        if (!wifiSaverActive) {
            wifiLock?.takeIf { !it.isHeld }?.acquire()
        } else {
            wifiLock?.takeIf { it.isHeld }?.release()
        }
    }

    private fun releaseLocks() {
        wifiLock?.takeIf { it.isHeld }?.release()
        wakeLock?.takeIf { it.isHeld }?.release()
    }
}

/**
 * Thin foreground service. It owns no streaming logic — its sole job is to keep
 * the process alive (and show the ongoing notification) while [PlaybackController]
 * streams audio.
 */
class PlaybackService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Android REQUIRES startForeground() promptly after startForegroundService(),
        // even on the STOP path — otherwise it throws ForegroundServiceDidNotStartInTime
        // and kills the app. So always call it first, before handling the action.
        val name = intent?.getStringExtra(EXTRA_NAME) ?: "PC"
        try {
            val notification = buildNotification(name)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            Log.w("SoundMirror", "startForeground failed", e)
            stopSelf()
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_STOP) {
            PlaybackController.disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        // PlaybackController keeps the actual stream only in this process. If Android
        // kills it there is no session state to restore from a null restart intent, so
        // START_STICKY would create a foreground "playing" service with no audio or
        // locks. A fresh user connection starts the service again normally.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        PlaybackController.notifyServiceStopped()
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun buildNotification(deviceName: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "오디오 재생",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "PC 오디오 미러링 재생 상태"
                setShowBadge(false)
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, PlaybackService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("SoundMirror 재생 중")
            .setContentText("$deviceName 의 오디오를 재생하고 있습니다")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .addAction(Notification.Action.Builder(null, "중지", stopIntent).build())
            .build()
    }

    companion object {
        const val ACTION_START = "com.soundmirror.app.action.START"
        const val ACTION_STOP = "com.soundmirror.app.action.STOP"
        const val EXTRA_NAME = "device_name"
        private const val CHANNEL_ID = "playback"
        private const val NOTIF_ID = 1
    }
}
