package com.soundmirror.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.PriorityQueue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val DISCOVERY_PORT = 45670
private const val CONTROL_PORT = 45671
private const val AUDIO_PORT = 45672
private const val MAGIC_BEACON = "SM_BEACON "
private const val AUDIO_HEADER_V1_SIZE = 27
private const val AUDIO_HEADER_V2_SIZE = 28
private const val SAMPLE_RATE = 44100
private const val CHANNELS = 2
// Audio socket read timeout (ms) and how many consecutive timeouts to tolerate
// before giving up — keeps auto-reconnecting through brief Wi-Fi blips (~30s).
private const val AUDIO_SOCKET_TIMEOUT_MS = 2000
private const val MAX_RECONNECT_TIMEOUTS = 15
// Audio-reactive visuals are independent from the numeric stats sampling setting.
// 20 Hz is smooth enough for the meter while avoiding a recomposition per packet.
private const val AUDIO_LEVEL_UPDATE_INTERVAL_MS = 50L
// Playback-health checks must never be disabled by the UI stats setting.
private const val PLAYBACK_MAINTENANCE_INTERVAL_MS = 250L
// Jitter-buffer depth (ms) when the "deep buffer" feature is on. Deep on purpose: it
// rides straight through Android's periodic background Wi-Fi scans (radio leaves the
// channel ~1-3s every few minutes) and power-save bursts. The cost is that playout
// runs this far behind real time — see the in-app description.
const val DEEP_BUFFER_MS = 2000.0

// RMS level (0..1) under which a packet counts as silence for latency shedding
// (~ -52 dBFS: gaps between songs and pauses qualify, quiet music does not).
private const val SILENCE_SHED_RMS = 0.0025f

// IMA ADPCM tables. Hoisted to file scope so they are allocated exactly once for
// the whole process — previously they were rebuilt inside the per-sample decode
// call, allocating two IntArrays on every one of the ~88k samples/sec.
private val ADPCM_STEP_TABLE = intArrayOf(
    7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,
    88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,
    544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,
    2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,
    10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767
)
private val ADPCM_INDEX_TABLE = intArrayOf(-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8)

class MainActivity : ComponentActivity() {
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlaybackController.ensureInit(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        requestBatteryOptimizationExemption()
        setContent {
            MaterialTheme {
                SoundMirrorApp()
            }
        }
    }

    // Ask the user (once, until granted) to exempt the app from battery
    // optimization. Without this, aggressive OEM power management — Samsung One UI
    // in particular — ignores our Wi-Fi low-latency lock when the screen is off and
    // stalls the audio stream for several seconds at a time.
    private fun requestBatteryOptimizationExemption() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
        } catch (e: Exception) {
            Log.w("SoundMirror", "battery optimization request failed", e)
        }
    }
}

@Stable
data class DiscoveredDevice(
    val name: String,
    val ip: String,
    val controlPort: Int,
    val audioPort: Int,
    val sampleRate: Int,
    val channels: Int,
    val lastSeenMs: Long,
)

@Stable
data class StreamStats(
    val connected: Boolean = false,
    val deviceName: String = "",
    val deviceIp: String = "",
    val packetLoss: Float = 0f,
    val jitterBuffer: Int = 0,
    val jitterMs: Int = 0,
    val latencyMs: Int = 0,
    val codecLabel: String = "",
    val bitrateLabel: String = "",
    val latencyLabel: String = "",
)

enum class AudioCodec(
    val wireName: String,
    val label: String,
    val bitrate: String,
    val caption: String,
) {
    Opus("OPUS", "Opus", "160 kbps", "고효율 - 음악과 불안정한 Wi-Fi에 강함"),
    Flac("FLAC", "FLAC 무손실", "자동 (가변)", "무손실 - 음원 내용에 따라 전송률이 자동으로 달라집니다"),
    Pcm16("PCM16", "PCM 16-bit", "1536 kbps", "가장 선명함 (대역폭 높음)"),
}

@Stable
data class StreamSettings(
    val prebufferPackets: Int = 4,
    val codec: AudioCodec = AudioCodec.Opus,
    val volume: Float = 1f,
    val requestLowLatency: Boolean = true,
    // Sampling interval for numeric metrics only. Zero freezes those metrics;
    // connection lifecycle, audio visuals, and playback maintenance stay active.
    val statsRefreshMs: Long = 250L,
    // Sub-features (each applied immediately when on):
    //  deepBuffer — large jitter buffer that rides through Wi-Fi scans/bursts; adds
    //               ~2s of playout latency.
    //  wifiSaver  — drop the low-latency Wi-Fi lock so the radio can power-save.
    val deepBuffer: Boolean = false,
    val wifiSaver: Boolean = false,
    val opusBitrateKbps: Int = 160,
) {
    val deepBufferActive: Boolean get() = deepBuffer
    val wifiSaverActive: Boolean get() = wifiSaver
}

private val OPUS_BITRATES_KBPS = listOf(128, 160)
private val STATS_REFRESH_OPTIONS_MS = listOf(250L, 500L, 1000L, 2000L, 5000L, 0L)
private val STATS_REFRESH_LABELS = listOf("0.25초", "0.5초", "1초", "2초", "5초", "정적")

private fun AudioCodec.settingsBitrate(opusBitrateKbps: Int): String = when (this) {
    AudioCodec.Opus -> "$opusBitrateKbps kbps"
    else -> bitrate
}

private fun AudioCodec.estimatedBufferMs(packets: Int): Int {
    val packetMs = when (this) {
        AudioCodec.Opus, AudioCodec.Flac -> 10.0
        AudioCodec.Pcm16 -> 5.0
    }
    return (packets * packetMs).roundToInt()
}

class DiscoveryManager(context: Context, private val scope: CoroutineScope) {
    private val appContext = context.applicationContext
    private val wifi = appContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var lock: WifiManager.MulticastLock? = null
    private var job: Job? = null
    private var probeJob: Job? = null
    private val _devices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val devices: StateFlow<List<DiscoveredDevice>> = _devices.asStateFlow()

    fun start() {
        if (job != null) return
        lock = wifi.createMulticastLock("soundmirror-discovery").apply {
            setReferenceCounted(false)
            acquire()
        }
        job = scope.launch(Dispatchers.IO) {
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(DISCOVERY_PORT))
                socket.soTimeout = 700
                val buffer = ByteArray(2048)
                while (isActive) {
                    try {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        handleBeacon(packet)
                    } catch (_: SocketTimeoutException) {
                        prune()
                    }
                }
            }
        }
        // Active discovery: periodically broadcast a probe. The PC answers with a
        // unicast beacon, which survives Wi-Fi broadcast filtering — so the PC stays
        // visible even when its periodic broadcasts are dropped by the AP.
        probeJob = scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    val msg = "SM_DISCOVER".toByteArray(Charsets.UTF_8)
                    val addr = InetAddress.getByName("255.255.255.255")
                    while (isActive) {
                        try { s.send(DatagramPacket(msg, msg.size, addr, CONTROL_PORT)) } catch (_: Exception) {}
                        delay(1500)
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        probeJob?.cancel()
        probeJob = null
        lock?.takeIf { it.isHeld }?.release()
        lock = null
    }

    private fun handleBeacon(packet: DatagramPacket) {
        val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
        if (!text.startsWith(MAGIC_BEACON)) return
        val json = JSONObject(text.substring(MAGIC_BEACON.length))
        val now = System.currentTimeMillis()
        val device = DiscoveredDevice(
            name = json.optString("name", packet.address.hostAddress ?: "PC"),
            ip = json.optString("ip", packet.address.hostAddress ?: ""),
            controlPort = json.optInt("controlPort", CONTROL_PORT),
            audioPort = json.optInt("audioPort", AUDIO_PORT),
            sampleRate = json.optInt("sampleRate", SAMPLE_RATE),
            channels = json.optInt("channels", CHANNELS),
            lastSeenMs = now,
        )
        _devices.value = (_devices.value.filterNot { it.ip == device.ip } + device)
            .sortedBy { it.name.lowercase() }
    }

    private fun prune() {
        // Keep a discovered PC listed through brief beacon gaps (Wi-Fi drops
        // broadcasts unreliably) so it doesn't flicker in and out of the list.
        val cutoff = System.currentTimeMillis() - 8000
        _devices.value = _devices.value.filter { it.lastSeenMs >= cutoff }
    }
}

class AudioStreamClient(private val context: Context, private val scope: CoroutineScope) {
    private var job: Job? = null
    // Every connect/disconnect invalidates older session finalizers. Without this,
    // a quick double tap can let the cancelled session publish disconnected after
    // the replacement session is already running, which also drops our service locks.
    private val sessionGeneration = AtomicLong(0L)
    @Volatile private var settings = StreamSettings()
    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()
    private val _audioLevel = MutableStateFlow(0f)
    val audioLevel: StateFlow<Float> = _audioLevel.asStateFlow()
    private var reusableShorts = ShortArray(65535)
    private var upsampleShorts = ShortArray(0)
    private var decodedSampleCount = 0
    private var lowLatencyBufferCeilingFrames = 0
    private var lastPartialWriteLogMs = 0L
    @Volatile private var rttMs = 0L
    // Last time the control channel (SM_PING/PONG) confirmed the PC is reachable.
    // Lets us tell "PC is silent" apart from "connection lost".
    @Volatile private var lastPongMs = 0L
    // Deep buffer is driven purely by the user's toggle (or battery mode), applied
    // immediately — no screen-state coupling.
    private fun deepBufferMode(): Boolean = settings.deepBufferActive
    // Opus is a stateful stream codec; keep one decoder per connection.
    private var opusDecoder: OpusDecoderWrapper? = null
    private var opusOut = ShortArray(0)
    // Native libFLAC decoder handle. One handle per connection is reset per packet.
    private var flacHandle: Long = 0L
    private var flacTriedInit = false
    private var flacOut = ShortArray(0)

    // Dedicated playout thread at urgent-audio priority. The playout loop paces
    // real-time audio with blocking writes; on the shared IO pool it competed with
    // ordinary IO work and picked up scheduling jitter (micro-stutters under load
    // and screen-off CPU throttling).
    private val playoutDispatcher = Executors.newSingleThreadExecutor { r ->
        Thread({
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            } catch (_: Throwable) {}
            r.run()
        }, "sm-playout")
    }.asCoroutineDispatcher()

    // Reusable packet pool: avoids per-packet ByteArray and object allocation at 100-200 Hz,
    // drastically reducing ART GC churn and battery usage while preserving zero latency.
    private val packetPool = ConcurrentLinkedQueue<AudioPacket>()

    private fun obtainAudioPacket(
        seq: Long,
        codec: WireCodec,
        sampleRate: Int,
        channels: Int,
        frames: Int,
        sendTimeNs: Long,
        data: ByteArray,
        offset: Int,
        size: Int,
        recvNs: Long,
    ): AudioPacket {
        val pkt = packetPool.poll() ?: AudioPacket()
        pkt.seq = seq
        pkt.codec = codec
        pkt.sampleRate = sampleRate
        pkt.channels = channels
        pkt.frames = frames
        pkt.sendTimeNs = sendTimeNs
        if (pkt.payload.size < size) {
            pkt.payload = ByteArray(maxOf(size, pkt.payload.size * 2))
        }
        System.arraycopy(data, offset, pkt.payload, 0, size)
        pkt.payloadSize = size
        pkt.recvNs = recvNs
        return pkt
    }

    private fun recyclePacket(packet: AudioPacket?) {
        if (packet != null && packetPool.size < 256) {
            packetPool.offer(packet)
        }
    }

    private fun releaseFlac() {
        if (flacHandle != 0L) {
            try { NativeFlac.nativeDestroy(flacHandle) } catch (_: Throwable) {}
            flacHandle = 0L
        }
        flacTriedInit = false
    }

    fun updateSettings(next: StreamSettings) {
        val prevCodec = settings.codec
        val opusBitrateChanged = settings.opusBitrateKbps != next.opusBitrateKbps
        settings = next
        if (_stats.value.connected &&
            (prevCodec != next.codec || (next.codec == AudioCodec.Opus && opusBitrateChanged))
        ) {
            val deviceIp = _stats.value.deviceIp
            val deviceName = _stats.value.deviceName
            if (deviceIp.isNotEmpty()) {
                scope.launch(Dispatchers.IO) {
                    val device = DiscoveredDevice(
                        name = deviceName,
                        ip = deviceIp,
                        controlPort = CONTROL_PORT,
                        audioPort = AUDIO_PORT,
                        sampleRate = SAMPLE_RATE,
                        channels = CHANNELS,
                        lastSeenMs = System.currentTimeMillis()
                    )
                    announceConnect(device)
                }
            }
        }
    }

    fun connect(device: DiscoveredDevice, initialSettings: StreamSettings) {
        settings = initialSettings
        val previous = job
        val session = sessionGeneration.incrementAndGet()
        _stats.value = StreamStats(
            connected = true,
            deviceName = device.name,
            deviceIp = device.ip,
            codecLabel = settings.codec.label,
            bitrateLabel = "측정 중",
            latencyMs = settings.codec.estimatedBufferMs(settings.prebufferPackets),
            latencyLabel = "${settings.codec.estimatedBufferMs(settings.prebufferPackets)}ms",
        )
        _audioLevel.value = 0f
        // Cancel the previous session BEFORE scheduling the new one: both sessions run
        // on the single playout thread, so if the new body were the one to deliver the
        // cancel, the old loop would never yield the thread and we'd deadlock.
        previous?.cancel()
        job = scope.launch(playoutDispatcher) {
            // Make sure any previous session has fully released the audio socket
            // before we rebind it — otherwise a quick reconnect hits EADDRINUSE.
            try { previous?.join() } catch (_: Exception) {}
            if (sessionGeneration.get() != session) return@launch
            var socket: DatagramSocket? = null
            var track: AudioTrack? = null
            try {
                socket = DatagramSocket(null).apply {
                    reuseAddress = true
                    bind(InetSocketAddress(AUDIO_PORT))
                    soTimeout = AUDIO_SOCKET_TIMEOUT_MS
                    receiveBufferSize = 1024 * 1024
                }
                announceConnect(device)
                track = createAudioTrack(device.sampleRate, device.channels, settings).apply {
                    setVolume(settings.volume)
                }
                receiveAudio(socket, track, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never crash the app on a connect/bind failure — just reset state.
                Log.w("SoundMirror", "connect failed", e)
            } finally {
                // Tell the sender to remove this client immediately. A replacement
                // connection waits for this job to finish, so DISCONNECT cannot race
                // behind its new CONNECT announcement.
                runCatching { announceDisconnect(device) }
                runCatching { track?.stop() }
                runCatching { track?.release() }
                runCatching { socket?.close() }
                opusDecoder?.close()
                opusDecoder = null
                releaseFlac()
                if (sessionGeneration.get() == session) {
                    _audioLevel.value = 0f
                    _stats.value = StreamStats()
                }
            }
        }
    }

    fun disconnect() {
        sessionGeneration.incrementAndGet()
        job?.cancel()
        // Retain the cancelled Job reference until the next connect so it can join
        // the finalizer (which sends SM_DISCONNECT) before announcing a new session.
        _audioLevel.value = 0f
        _stats.value = StreamStats()
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int, streamSettings: StreamSettings): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerFrame = channels.coerceAtLeast(1) * 2
        val minBufferResult = AudioTrack.getMinBufferSize(
            sampleRate,
            mask,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBufferResult > 0) { "AudioTrack rejected $sampleRate Hz / $channels ch" }
        val minBuffer = minBufferResult.coerceAtLeast(bytesPerFrame)
        // Keep the AudioTrack buffer SMALL. The jitter buffer lives in our own queue
        // (clock-driven playout drains it), so WRITE_BLOCKING must pace us tightly to
        // real time — a large track buffer would let us drain the queue faster than
        // real time, empty it, and then inject filler (the cause of the rapid "툭툭툭"
        // chop). A couple of minBuffers is just enough to ride thread-scheduling jitter.
        val multiplier = if (streamSettings.requestLowLatency) 2 else 3
        val bufferBytes = (minBuffer * multiplier).coerceAtLeast(minBuffer)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(mask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(
                if (streamSettings.requestLowLatency) {
                    AudioTrack.PERFORMANCE_MODE_LOW_LATENCY
                } else {
                    AudioTrack.PERFORMANCE_MODE_NONE
                }
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
        if (streamSettings.requestLowLatency) {
            // Reserve the old 2x capacity as a safety ceiling, but expose only one
            // minBuffer to AudioFlinger initially. receiveAudio() grows this active
            // size if the device reports a real underrun.
            val requestedCapacityFrames = (bufferBytes / bytesPerFrame).coerceAtLeast(1)
            lowLatencyBufferCeilingFrames = minOf(
                requestedCapacityFrames,
                track.bufferCapacityInFrames.coerceAtLeast(1),
            )
            val initialFrames = (minBuffer / bytesPerFrame)
                .coerceIn(1, lowLatencyBufferCeilingFrames)
            val appliedFrames = runCatching { track.setBufferSizeInFrames(initialFrames) }
                .getOrNull()
                ?.takeIf { it > 0 }
                ?: track.bufferSizeInFrames
            Log.d(
                "SoundMirrorPerf",
                "AudioTrack low-latency buffer=$appliedFrames/$lowLatencyBufferCeilingFrames frames",
            )
        } else {
            lowLatencyBufferCeilingFrames = 0
        }
        return track
    }

    private fun announceConnect(
        device: DiscoveredDevice,
        repeatCount: Int = 3,
        existingSocket: DatagramSocket? = null,
    ) {
        val payload = """SM_CONNECT {"audioPort":$AUDIO_PORT,"quality":"Custom_${settings.prebufferPackets}","codec":"${settings.codec.wireName}","opusBitrate":${settings.opusBitrateKbps}}"""
            .toByteArray(Charsets.UTF_8)
        val packet = DatagramPacket(
            payload,
            payload.size,
            InetAddress.getByName(device.ip),
            device.controlPort,
        )
        if (existingSocket != null) {
            repeat(repeatCount) {
                existingSocket.send(packet)
                if (repeatCount > 1) {
                    Thread.sleep(80)
                }
            }
        } else {
            DatagramSocket().use { socket ->
                repeat(repeatCount) {
                    socket.send(packet)
                    if (repeatCount > 1) {
                        Thread.sleep(80)
                    }
                }
            }
        }
    }

    private fun announceDisconnect(device: DiscoveredDevice) {
        DatagramSocket().use { socket ->
            val payload = "SM_DISCONNECT".toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                payload,
                payload.size,
                InetAddress.getByName(device.ip),
                device.controlPort,
            )
            // UDP has no delivery acknowledgement. Two back-to-back copies make a
            // user-requested disconnect reliable without delaying reconnection.
            repeat(2) { socket.send(packet) }
        }
    }

    private suspend fun receiveAudio(socket: DatagramSocket, track: AudioTrack, device: DiscoveredDevice) {
        lastPongMs = System.currentTimeMillis()
        val queue = PriorityQueue<AudioPacket>(compareBy { it.seq })
        var expectedSeq: Long? = null
        var received = 0L
        var lost = 0L
        var lastArrivalNs: Long? = null
        var jitterEstimateMs = 0.0
        var liveBitrateLabel = "측정 중"
        val receivedPayloadBytes = AtomicLong(0L)
        val bitrateSamples = ArrayDeque<Pair<Long, Long>>()
        var lastBitrateUpdateMs = 0L
        var totalFramesWritten = 0L
        var isBuffering = true
        var smoothedLatencyMs = 0.0
        var lastStatsSampleMs = 0L
        var lastAudioLevelUpdateMs = 0L
        var lastMaintenanceMs = 0L
        var lastTrimMs = 0L
        // Set after the initial jitter prebuffer is ready. AudioTrack can report a
        // startup underrun before the first write; that is not evidence to grow.
        var lastUnderrunCount = -1
        // Prime one decoded packet into AudioTrack before play/resume. Starting an
        // empty track creates an artificial underrun that would make the adaptive
        // low-latency buffer grow even on an otherwise healthy connection.
        var resumeTrackAfterWrite = false
        var activeTrackBufferFrames = runCatching { track.bufferSizeInFrames }.getOrDefault(0)
        // AudioTrack is created already at settings.volume; only re-apply when the
        // user actually changes the volume, instead of on every played packet.
        var lastVolume = settings.volume

        val packetChannel = Channel<AudioPacket>(
            // Keep the socket pump ahead of the playout queue during Wi-Fi bursts.
            // Deep mode needs roughly 200 packets at 10 ms, so the old 24-packet
            // channel could overflow before the actual jitter buffer saw the data.
            capacity = 512,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val pingJob = scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { socket ->
                    socket.soTimeout = 1000
                    val buf = ByteArray(128)
                    val packet = DatagramPacket(buf, buf.size)
                    while (currentCoroutineContext().isActive) {
                        try {
                            val sentTime = System.currentTimeMillis()
                            val msg = "SM_PING $sentTime".toByteArray(Charsets.UTF_8)
                            val sendPacket = DatagramPacket(msg, msg.size, InetAddress.getByName(device.ip), device.controlPort)
                            socket.send(sendPacket)
                            
                            socket.receive(packet)
                            val resp = String(packet.data, 0, packet.length, Charsets.UTF_8)
                            if (resp.startsWith("SM_PONG ")) {
                                lastPongMs = System.currentTimeMillis()
                                val time = resp.substring(8).trim().toLongOrNull()
                                if (time != null) {
                                    rttMs = System.currentTimeMillis() - time
                                }
                            }
                        } catch (_: Exception) {}
                        val pingDelay = if (_stats.subscriptionCount.value > 0) 1500L else 4000L
                        delay(pingDelay)
                    }
                }
            } catch (_: Exception) {}
        }

        val receiveJob = scope.launch(Dispatchers.IO) {
            // Socket→channel pump feeds the audio pipeline; run it at audio priority so
            // a busy IO pool can't delay packet intake. The loop blocks in receive() and
            // never suspends, so it stays on this thread; priority is restored on exit
            // because Dispatchers.IO threads are shared.
            val rxTid = android.os.Process.myTid()
            val rxPrevPrio = try { android.os.Process.getThreadPriority(rxTid) } catch (_: Throwable) { 0 }
            try {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
            } catch (_: Throwable) {}
            try {
            val recvBuffer = ByteArray(65535)
            val packet = DatagramPacket(recvBuffer, recvBuffer.size)
            var timeoutCount = 0
            var reconnectNotified = false
            while (currentCoroutineContext().isActive) {
                try {
                    packet.setData(recvBuffer, 0, recvBuffer.size)
                    socket.receive(packet)
                    if (timeoutCount > 0) {
                        timeoutCount = 0
                        reconnectNotified = false
                    }
                    val parsed = parseAudioPacket(packet.data, packet.length) ?: continue
                    receivedPayloadBytes.addAndGet(parsed.payloadSize.toLong())
                    packetChannel.trySend(parsed)
                } catch (_: SocketTimeoutException) {
                    // No audio for a while. If the control channel (ping/pong) still
                    // works, the PC is simply silent (paused / gap between tracks) —
                    // that's not a problem, so stay quiet and just keep the PC's client
                    // entry alive. Only treat it as instability if pings also stopped.
                    val controlAlive = System.currentTimeMillis() - lastPongMs < 5000
                    if (controlAlive) {
                        timeoutCount = 0
                        reconnectNotified = false
                        announceConnect(device, 1)
                        continue
                    }
                    timeoutCount++
                    // Keep re-announcing so the PC resumes sending the moment a brief
                    // Wi-Fi outage clears — only give up after a long window.
                    if (timeoutCount >= MAX_RECONNECT_TIMEOUTS) {
                        scope.launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "PC와 연결이 끊어졌습니다.", android.widget.Toast.LENGTH_SHORT).show()
                        }
                        disconnect()
                        break
                    }
                    if (!reconnectNotified) {
                        reconnectNotified = true
                        scope.launch(Dispatchers.Main) {
                            android.widget.Toast.makeText(context, "연결이 불안정합니다. 재연결 시도 중…", android.widget.Toast.LENGTH_SHORT).show()
                        }
                    }
                    announceConnect(device, 1)
                } catch (_: Exception) {
                    break
                }
            }
            } finally {
                try { android.os.Process.setThreadPriority(rxTid, rxPrevPrio) } catch (_: Throwable) {}
            }
        }

        val keepAliveJob = scope.launch(Dispatchers.IO) {
            try {
                DatagramSocket().use { keepAliveSocket ->
                    while (currentCoroutineContext().isActive) {
                        try {
                            val keepAliveDelay = if (_stats.subscriptionCount.value > 0) 3000L else 6000L
                            delay(keepAliveDelay)
                            announceConnect(device, 1, keepAliveSocket)
                        } catch (_: Exception) {
                            break
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        // Clock-driven playout. The receive job fills [queue]; this loop drains it at
        // real-time pace (paced by WRITE_BLOCKING) regardless of when packets arrive.
        // That's what makes the jitter buffer actually protect against gaps: when the
        // network stalls (e.g. a periodic Wi-Fi background scan leaves the channel for
        // 1-3s every few minutes) we keep playing buffered audio instead of underrunning.
        var prevDeepMode = deepBufferMode()
        // For non-Opus loss concealment: retain the last decoded buffer until the next
        // packet is decoded, so a missing packet can fade it out without a copy.
        var prevBuffer: ShortArray? = null
        var prevSamples = 0
        var fadeInNext = false
        try {
            while (currentCoroutineContext().isActive) {
                // 1) Drain everything that has arrived (non-blocking) into the queue,
                //    updating arrival-based jitter/bitrate stats as we go.
                while (true) {
                    val next = packetChannel.tryReceive().getOrNull() ?: break
                    // Use the true receive timestamp, not now — the playout loop drains the
                    // channel in write-paced bursts, so now() here would fake huge jitter.
                    val arrivalNs = if (next.recvNs != 0L) next.recvNs else System.nanoTime()
                    val expectedPacketMs = next.frames * 1000.0 / next.sampleRate.coerceAtLeast(1)
                    lastArrivalNs?.let { previousNs ->
                        val actualMs = (arrivalNs - previousNs) / 1_000_000.0
                        // Skip gap-sized inter-arrivals: a stall/outage (e.g. the Wi-Fi
                        // reconfig when the screen toggles) is not jitter, and feeding that
                        // huge value into the estimate makes the displayed number briefly
                        // explode toward ~1s. Only count packets that arrived in a normal
                        // window; a true gap just resets the reference point.
                        if (actualMs < expectedPacketMs * 8 + 80) {
                            val deviationMs = abs(actualMs - expectedPacketMs)
                            jitterEstimateMs += (deviationMs - jitterEstimateMs) / 16.0
                        }
                    }
                    lastArrivalNs = arrivalNs

                    queue.add(next)
                }

                val pktMs = (queue.peek()?.let { it.frames * 1000.0 / it.sampleRate.coerceAtLeast(1) } ?: 5.0)
                    .coerceAtLeast(2.0)
                // Deep-buffer mode follows the user's toggle (or battery mode), applied
                // immediately. On = large jitter buffer (~DEEP_BUFFER_MS) that bridges
                // Wi-Fi scans/bursts at the cost of latency; off = the user's low-latency
                // depth.
                val deepMode = deepBufferMode()
                val prebufferTarget = if (!deepMode) settings.prebufferPackets else {
                    maxOf(settings.prebufferPackets, (DEEP_BUFFER_MS / pktMs).roundToInt())
                }

                // When the toggle flips into deep mode, rebuffer up to the deep target.
                // This is one brief gap right when you enable it, then it stays smooth.
                if (prevDeepMode != deepMode) {
                    // The latency target changes by roughly two seconds here. Do not
                    // blend the previous mode's value into the new measurement.
                    smoothedLatencyMs = 0.0
                    if (deepMode) {
                        isBuffering = true
                        resumeTrackAfterWrite = false
                        try { track.pause() } catch (_: Exception) {}
                    }
                }
                prevDeepMode = deepMode

                // 2) Shed excess latency by dropping the oldest packets — never by
                //    writing ahead (which only shifts latency into the track buffer).
                val maxQueue = (prebufferTarget * 2).coerceAtLeast(prebufferTarget + 8)
                if (queue.size > maxQueue) {
                    var dropped = 0L
                    while (queue.size > prebufferTarget) {
                        val droppedPkt = queue.poll()
                        if (droppedPkt != null) {
                            dropped++
                            recyclePacket(droppedPkt)
                        }
                    }
                    lost += dropped
                    expectedSeq = queue.peek()?.seq
                }

                // 3) (Re)buffering gate. While buffering, block for the next arrival so
                //    we don't busy-spin, and accumulate up to the target before playing.
                if (isBuffering) {
                    if (queue.size >= prebufferTarget) {
                        isBuffering = false
                        resumeTrackAfterWrite = true
                    } else {
                        val p = packetChannel.receiveCatching().getOrNull() ?: break
                        queue.add(p)
                        continue
                    }
                }

                // 4) Play exactly one packet; WRITE_BLOCKING paces this loop to real time.
                val playPacket = queue.poll()
                if (playPacket == null) {
                    // Queue empty while playing. Don't inject filler — just wait for the
                    // next packet; the (small) AudioTrack buffer keeps playing meanwhile,
                    // which is exactly the pacing we want. Only if the stall outlasts the
                    // buffer do we rebuffer cleanly, instead of chopping with silence.
                    val p = withTimeoutOrNull(150L) { packetChannel.receiveCatching().getOrNull() }
                    if (p != null) {
                        queue.add(p)
                    } else if (currentCoroutineContext().isActive) {
                        isBuffering = true
                        resumeTrackAfterWrite = false
                        try { track.pause() } catch (_: Exception) {}
                    } else {
                        break
                    }
                    continue
                }

                val expected = expectedSeq
                if (expected != null && playPacket.seq < expected) {
                    recyclePacket(playPacket)
                    continue
                }
                if (expected != null && playPacket.seq > expected) {
                    lost += playPacket.seq - expected
                    if (playPacket.codec == WireCodec.Opus) {
                        // Opus has real packet-loss concealment — let it synthesise the gap.
                        val missing = (playPacket.seq - expected).coerceAtMost(5).toInt()
                        val plcSamples = concealOpus(playPacket.frames, missing, track)
                        totalFramesWritten += plcSamples / 2
                    } else if (prevSamples > 0 && prevBuffer != null) {
                        // PCM/ADPCM/MULAW have no PLC. Fade the retained previous frame
                        // out to zero so a lost packet decays smoothly
                        // instead of a hard click, and flag the next frame to fade back in.
                        val frames = (prevSamples / 2).coerceAtLeast(1)
                        val previous = prevBuffer
                        var i = 0
                        while (i < prevSamples) {
                            val g = 1f - (i / 2) / frames.toFloat()
                            previous[i] = (previous[i] * g).toInt().toShort()
                            previous[i + 1] = (previous[i + 1] * g).toInt().toShort()
                            i += 2
                        }
                        val written = writeFully(track, previous, prevSamples)
                        totalFramesWritten += written / 2
                        fadeInNext = true
                    }
                }
                expectedSeq = playPacket.seq + 1
                received += 1
                val vol = settings.volume
                if (vol != lastVolume) {
                    track.setVolume(vol)
                    lastVolume = vol
                }
                val decoded = decodePacket(playPacket, device.sampleRate)
                val samples = decodedSampleCount
                var decodedRms = -1f
                val packetTimeMs = System.currentTimeMillis()

                // Silence-aware latency shedding: when we're holding more than the target
                // (queue backlog after a Wi-Fi burst, or a ballooned AudioTrack buffer),
                // skip near-silent packets instead of playing them. Excess latency drains
                // during quiet moments with no audible artifact, so the tick-causing hard
                // trim below rarely needs to fire. Decoding still ran (keeps stateful
                // codecs like Opus consistent) and seq state was already advanced above.
                val aheadMs = try {
                    (totalFramesWritten - (track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL)) *
                        1000.0 / device.sampleRate.coerceAtLeast(1)
                } catch (_: Exception) { 0.0 }
                if (queue.size > prebufferTarget || aheadMs > 90.0) {
                    // This RMS is operational: it must run even with no UI subscriber
                    // because it lets us shed accumulated latency during silence.
                    decodedRms = pcmAmplitude(decoded, samples)
                    if (decodedRms < SILENCE_SHED_RMS) {
                        if (_audioLevel.subscriptionCount.value > 0 &&
                            packetTimeMs - lastAudioLevelUpdateMs >= AUDIO_LEVEL_UPDATE_INTERVAL_MS
                        ) {
                            lastAudioLevelUpdateMs = packetTimeMs
                            _audioLevel.value = decodedRms
                        }
                        // A deliberately discarded silent packet must not become the
                        // source for later non-Opus loss concealment. In particular,
                        // codec/frame-size changes could otherwise expose stale tail data.
                        prevBuffer = null
                        prevSamples = 0
                        // Silence shedding intentionally lets the hardware queue drain.
                        // Do not interpret that planned underrun as evidence that the
                        // 1x low-latency AudioTrack buffer is too small.
                        lastUnderrunCount = runCatching { track.underrunCount }
                            .getOrDefault(lastUnderrunCount.coerceAtLeast(0))
                        recyclePacket(playPacket)
                        continue
                    }
                }

                if (fadeInNext && samples > 0) {
                    // Ramp the first packet after a concealed gap up from zero so the
                    // resume is click-free too.
                    val frames = (samples / 2).coerceAtLeast(1)
                    var i = 0
                    while (i < samples) {
                        val g = (i / 2) / frames.toFloat()
                        decoded[i] = (decoded[i] * g).toInt().toShort()
                        decoded[i + 1] = (decoded[i + 1] * g).toInt().toShort()
                        i += 2
                    }
                    fadeInNext = false
                }
                val written = writeFully(track, decoded, samples)
                if (resumeTrackAfterWrite && written > 0) {
                    // writeFully starts playback as soon as priming makes progress,
                    // including when the first packet only fits partially.
                    resumeTrackAfterWrite = false
                    lastUnderrunCount = runCatching { track.underrunCount }.getOrDefault(0)
                }
                totalFramesWritten += written / 2
                prevBuffer = decoded
                prevSamples = samples

                // 5) Publish the audio-reactive level on its own fixed cadence. This
                // remains smooth even when numeric metrics are slow or frozen.
                val now = System.currentTimeMillis()
                if (_audioLevel.subscriptionCount.value > 0 &&
                    now - lastAudioLevelUpdateMs >= AUDIO_LEVEL_UPDATE_INTERVAL_MS
                ) {
                    lastAudioLevelUpdateMs = now
                    if (decodedRms < 0f) decodedRms = pcmAmplitude(decoded, samples)
                    _audioLevel.value = decodedRms
                }

                // Playback maintenance is operational audio logic, not UI state. Keep
                // checking it even when the user freezes numeric metric sampling.
                if (now - lastMaintenanceMs >= PLAYBACK_MAINTENANCE_INTERVAL_MS) {
                    lastMaintenanceMs = now
                    val underrunCount = runCatching { track.underrunCount }
                        .getOrDefault(lastUnderrunCount.coerceAtLeast(0))
                    val underrunAdvanced = lastUnderrunCount >= 0 &&
                        underrunCount > lastUnderrunCount
                    // Silence can intentionally drain the track while we shed latency.
                    // Grow the hardware buffer only when an underrun coincides with
                    // audible PCM; otherwise a quiet PC would permanently forfeit the
                    // 1x low-latency setting before the next sound starts.
                    val audibleUnderrun = underrunAdvanced &&
                        (if (decodedRms >= 0f) decodedRms else pcmAmplitude(decoded, samples)) >=
                            SILENCE_SHED_RMS
                    if (audibleUnderrun &&
                        lowLatencyBufferCeilingFrames > 0 &&
                        activeTrackBufferFrames < lowLatencyBufferCeilingFrames
                    ) {
                        // Increase in small steps only after evidence that 1x minBuffer
                        // was insufficient. This keeps the common path low latency while
                        // adapting automatically on devices with tighter scheduling.
                        // Grow by one wire packet (normally 10 ms), not a quarter of
                        // AudioTrack capacity. Bluetooth devices report a very large
                        // minBuffer, where a 1/4 step can add ~90 ms from one underrun.
                        val stepFrames = playPacket.frames.coerceAtLeast(1)
                        val requestedFrames = (activeTrackBufferFrames + stepFrames)
                            .coerceAtMost(lowLatencyBufferCeilingFrames)
                        val appliedFrames = runCatching {
                            track.setBufferSizeInFrames(requestedFrames)
                        }.getOrDefault(activeTrackBufferFrames)
                        if (appliedFrames > 0) {
                            activeTrackBufferFrames = appliedFrames
                            Log.d(
                                "SoundMirrorPerf",
                                "underrun: buffer -> $activeTrackBufferFrames/$lowLatencyBufferCeilingFrames frames",
                            )
                        }
                    }
                    lastUnderrunCount = underrunCount
                    val maintenanceTrackDelayMs = try {
                        val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        val written = totalFramesWritten - head
                        if (written > 0) (written * 1000.0 / device.sampleRate.coerceAtLeast(1)) else 0.0
                    } catch (_: Exception) {
                        0.0
                    }
                    if (!deepMode && maintenanceTrackDelayMs > 100.0 && now - lastTrimMs > 2500) {
                        Log.d("SoundMirrorLat", "trim: track=${maintenanceTrackDelayMs.toInt()}ms -> reset")
                        lastTrimMs = now
                        lost += queue.size.toLong()
                        while (queue.isNotEmpty()) {
                            recyclePacket(queue.poll())
                        }
                        expectedSeq = null
                        try {
                            track.pause()
                            track.flush()
                            totalFramesWritten = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        } catch (_: Exception) {}
                        prevSamples = 0
                        prevBuffer = null
                        fadeInNext = false
                        smoothedLatencyMs = 0.0
                        isBuffering = true
                    }
                }

                // 6) Sample and publish numeric metrics only at the selected cadence.
                // When screen is off or app is backgrounded (_stats.subscriptionCount == 0),
                // bypass heavy queue iteration and StateFlow object allocation for battery saving.
                val statsSampleIntervalMs = settings.statsRefreshMs
                if (statsSampleIntervalMs > 0L && now - lastStatsSampleMs >= statsSampleIntervalMs) {
                    lastStatsSampleMs = now
                    if (_stats.subscriptionCount.value > 0) {
                        if (now - lastBitrateUpdateMs >= 1000) {
                            lastBitrateUpdateMs = now
                            bitrateSamples.addLast(now to receivedPayloadBytes.get())
                            while (bitrateSamples.size > 2 && now - bitrateSamples.first().first > 5000) {
                                bitrateSamples.removeFirst()
                            }
                            if (bitrateSamples.size >= 2) {
                                val first = bitrateSamples.first()
                                val last = bitrateSamples.last()
                                val elapsedMs = last.first - first.first
                                if (elapsedMs > 0) {
                                    val measuredKbps = (last.second - first.second) * 8.0 / elapsedMs
                                    liveBitrateLabel = "${measuredKbps.roundToInt()} kbps"
                                }
                            }
                        }
                        val networkDelayMs = rttMs / 2.0
                        // Sum actual packet durations. PCM packets can be split at the MTU
                        // boundary, so queue size multiplied by one packet's duration is not
                        // a reliable latency estimate.
                        val queueDelayMs = queue.sumOf {
                            it.frames * 1000.0 / it.sampleRate.coerceAtLeast(1)
                        }
                        val trackDelayMs = try {
                            val head = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                            val written = totalFramesWritten - head
                            if (written > 0) (written * 1000.0 / device.sampleRate.coerceAtLeast(1)) else 0.0
                        } catch (_: Exception) {
                            0.0
                        }

                        val totalLatencyMs = networkDelayMs + queueDelayMs + trackDelayMs
                        if (smoothedLatencyMs == 0.0) {
                            smoothedLatencyMs = totalLatencyMs
                        } else {
                            smoothedLatencyMs += (totalLatencyMs - smoothedLatencyMs) * 0.1
                        }
                        val lossRate = if (received + lost == 0L) 0f else lost.toFloat() / (received + lost).toFloat()

                        _stats.value = StreamStats(
                            connected = true,
                            deviceName = device.name,
                            deviceIp = device.ip,
                            packetLoss = lossRate,
                            jitterBuffer = queue.size,
                            jitterMs = jitterEstimateMs.roundToInt(),
                            latencyMs = smoothedLatencyMs.roundToInt(),
                            codecLabel = playPacket.codec.displayName,
                            bitrateLabel = liveBitrateLabel,
                            latencyLabel = "${settings.codec.estimatedBufferMs(settings.prebufferPackets)}ms",
                        )
                    }
                }
                recyclePacket(playPacket)
            }
        } finally {
            while (queue.isNotEmpty()) {
                recyclePacket(queue.poll())
            }
            while (true) {
                val leftover = packetChannel.tryReceive().getOrNull() ?: break
                recyclePacket(leftover)
            }
            packetChannel.close()
            receiveJob.cancel()
            keepAliveJob.cancel()
            pingJob.cancel()
        }
    }

    private fun parseAudioPacket(data: ByteArray, length: Int): AudioPacket? {
        val recvNs = System.nanoTime() // captured at receive time for an honest jitter metric
        if (length < AUDIO_HEADER_V1_SIZE) return null
        // Magic is 'S''M''A' followed by '1' (v1/PCM16) or '2' (v2). Compare the
        // bytes directly instead of allocating a String on every received packet.
        if (data[0].toInt() != 0x53 || data[1].toInt() != 0x4D || data[2].toInt() != 0x41) return null
        val isV2 = data[3].toInt() == 0x32
        if (!isV2 && data[3].toInt() != 0x31) return null
        val headerSize = if (isV2) AUDIO_HEADER_V2_SIZE else AUDIO_HEADER_V1_SIZE
        if (length < headerSize) return null
        // Parse the fixed header directly. This runs roughly once per 10 ms packet,
        // so avoiding ByteBuffer wrappers reduces young-generation GC pressure.
        var cursor = 4
        val codec = if (isV2) WireCodec.fromId(data[cursor++].toInt() and 0xFF) else WireCodec.Pcm16
        val seq = beInt(data, cursor).toLong() and 0xFFFF_FFFFL
        cursor += 4
        val sampleRate = beInt(data, cursor)
        cursor += 4
        val channels = data[cursor++].toInt() and 0xFF
        val frames = beUShort(data, cursor)
        cursor += 2
        val payloadSize = beInt(data, cursor)
        cursor += 4
        val sendTimeNs = beLong(data, cursor)
        if (channels != 2 || frames <= 0 || sampleRate !in 8_000..192_000) return null
        if (payloadSize <= 0 || payloadSize > length - headerSize) return null
        return obtainAudioPacket(
            seq, codec, sampleRate, channels, frames, sendTimeNs,
            data, headerSize, payloadSize, recvNs
        )
    }

    private fun beUShort(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 8) or
            (bytes[offset + 1].toInt() and 0xFF)
    }

    private fun beInt(bytes: ByteArray, offset: Int): Int {
        return ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)
    }

    private fun beLong(bytes: ByteArray, offset: Int): Long {
        val high = beInt(bytes, offset).toLong() and 0xFFFF_FFFFL
        val low = beInt(bytes, offset + 4).toLong() and 0xFFFF_FFFFL
        return (high shl 32) or low
    }

    private fun fillReusableShorts(bytes: ByteArray, size: Int): Int {
        val sampleCount = size / 2
        if (sampleCount > reusableShorts.size) {
            reusableShorts = ShortArray(maxOf(sampleCount, reusableShorts.size * 2))
        }
        var s = 0
        var b = 0
        while (b + 1 < size) {
            val low = bytes[b].toInt() and 0xFF
            val high = bytes[b + 1].toInt() shl 8
            reusableShorts[s++] = (low or high).toShort()
            b += 2
        }
        return sampleCount
    }

    private fun decodePacket(packet: AudioPacket, deviceSampleRate: Int): ShortArray {
        var decoded = when (packet.codec) {
            WireCodec.Pcm16 -> {
                decodedSampleCount = fillReusableShorts(packet.payload, packet.payloadSize)
                reusableShorts
            }
            WireCodec.Adpcm, WireCodec.AdpcmLite -> {
                decodedSampleCount = decodeAdpcm(packet.payload, packet.payloadSize, packet.frames)
                reusableShorts
            }
            WireCodec.Mulaw, WireCodec.MulawLite -> {
                decodedSampleCount = decodeMulaw(packet.payload, packet.payloadSize)
                reusableShorts
            }
            WireCodec.Opus -> {
                decodedSampleCount = decodeOpus(packet)
                opusOut
            }
            WireCodec.Flac -> {
                decodedSampleCount = decodeFlac(
                    packet.payload,
                    packet.payloadSize,
                    packet.frames * packet.channels.coerceAtLeast(1),
                )
                flacOut
            }
        }
        if (packet.sampleRate > 0 && packet.sampleRate < deviceSampleRate) {
            val scale = deviceSampleRate / packet.sampleRate
            if (scale == 2) {
                val channels = packet.channels.coerceAtLeast(1)
                val needed = decodedSampleCount * 2
                if (upsampleShorts.size < needed) {
                    upsampleShorts = ShortArray(maxOf(needed, upsampleShorts.size * 2))
                }
                val upsampled = upsampleShorts
                var src = 0
                var dst = 0
                while (src + channels <= decodedSampleCount) {
                    repeat(2) {
                        for (channel in 0 until channels) {
                            upsampled[dst++] = decoded[src + channel]
                        }
                    }
                    src += channels
                }
                decodedSampleCount = dst
                decoded = upsampled
            }
        }
        return decoded
    }

    private fun decodeOpus(packet: AudioPacket): Int {
        val rate = packet.sampleRate.coerceAtLeast(8000)
        val decoder = opusDecoder ?: try {
            OpusDecoderWrapper(rate, 2).also { opusDecoder = it }
        } catch (_: Exception) {
            return 0
        }
        // The sender puts the decoded frame count in the header. Use it instead of
        // reserving Opus' theoretical 120 ms maximum for every 10 ms packet.
        val maxPerChannel = packet.frames.coerceIn(1, rate * 120 / 1000)
        if (opusOut.size < maxPerChannel * 2) {
            opusOut = ShortArray(maxPerChannel * 2)
        }
        val perChannel = try {
            decoder.decode(packet.payload, packet.payloadSize, opusOut, maxPerChannel, false)
        } catch (_: Exception) {
            return 0
        }
        return perChannel * 2
    }

    // Opus packet loss concealment: ask the decoder to synthesise [count] missing
    // frames (passing null input triggers Opus PLC). Turns a dropped-packet gap into
    // a smooth interpolation instead of silence. Capped by the caller.
    private fun concealOpus(frames: Int, count: Int, track: AudioTrack): Int {
        val dec = opusDecoder
        if (dec == null || count <= 0 || frames <= 0) return 0
        val perFrameShorts = frames * 2
        if (opusOut.size < perFrameShorts) opusOut = ShortArray(perFrameShorts)
        var totalWritten = 0
        repeat(count) {
            val per = try {
                dec.decode(null, 0, opusOut, frames, false)
            } catch (_: Exception) {
                return totalWritten
            }
            totalWritten += writeFully(track, opusOut, per * 2)
        }
        return totalWritten
    }

    // Each FLAC packet is a complete, self-contained FLAC stream (header + one block).
    // The packaged native libFLAC decoder reuses one handle across packets.
    // Output is signed 16-bit LE stereo interleaved — exactly our PCM wire format.
    private fun decodeFlac(payload: ByteArray, size: Int, expectedSamples: Int): Int {
        if (NativeFlac.available) {
            if (flacHandle == 0L && !flacTriedInit) {
                flacTriedInit = true
                flacHandle = try { NativeFlac.nativeCreate() } catch (_: Throwable) { 0L }
            }
            if (flacHandle != 0L) {
                val needed = expectedSamples.coerceAtLeast(2)
                if (flacOut.size < needed) {
                    flacOut = ShortArray(maxOf(needed, flacOut.size * 2))
                }
                val n = try {
                    NativeFlac.nativeDecode(flacHandle, payload, size, flacOut, flacOut.size)
                } catch (_: Throwable) { -1 }
                return n.coerceAtLeast(0)
            }
        }
        return 0
    }

    private fun decodeMulaw(payload: ByteArray, size: Int): Int {
        if (reusableShorts.size < size) {
            reusableShorts = ShortArray(maxOf(size, reusableShorts.size * 2))
        }
        for (i in 0 until size) {
            reusableShorts[i] = mulawToLinear(payload[i].toInt() and 0xFF)
        }
        return size
    }

    private fun mulawToLinear(value: Int): Short {
        val u = value.inv() and 0xFF
        val sign = u and 0x80
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        var sample = ((mantissa shl 3) + 0x84) shl exponent
        sample -= 0x84
        return (if (sign != 0) -sample else sample).toShort()
    }

    private fun decodeAdpcm(payload: ByteArray, size: Int, frames: Int): Int {
        if (size < 6 || frames <= 0) return 0
        var left = leShort(payload, 0).toInt()
        var leftIndex = payload[2].toInt().coerceIn(0, 88)
        var right = leShort(payload, 3).toInt()
        var rightIndex = payload[5].toInt().coerceIn(0, 88)
        val samples = frames * 2
        if (reusableShorts.size < samples) {
            reusableShorts = ShortArray(maxOf(samples, reusableShorts.size * 2))
        }
        val out = reusableShorts
        out[0] = left.toShort()
        out[1] = right.toShort()
        var offset = 2
        var i = 6
        while (offset + 1 < samples && i < size) {
            val packed = payload[i++].toInt() and 0xFF
            // Decode left then right in place. imaStep packs the new predictor and
            // step index into a single Long so the per-sample hot path allocates
            // nothing (no Pair, no array) — this runs ~88k times/sec for ADPCM.
            val l = imaStep(packed and 0x0F, left, leftIndex)
            left = l.toInt()
            leftIndex = (l shr 32).toInt()
            val r = imaStep((packed shr 4) and 0x0F, right, rightIndex)
            right = r.toInt()
            rightIndex = (r shr 32).toInt()
            out[offset] = left.toShort()
            out[offset + 1] = right.toShort()
            offset += 2
        }
        // Reused buffer: zero any tail a truncated payload didn't overwrite.
        if (offset < samples) out.fill(0, offset, samples)
        return samples
    }

    // Returns the next predictor (low 32 bits) and step index (high 32 bits) packed
    // into a Long, so callers can update both channels without allocating.
    private fun imaStep(code: Int, predictor: Int, index: Int): Long {
        val step = ADPCM_STEP_TABLE[index]
        var diff = step shr 3
        if (code and 4 != 0) diff += step
        if (code and 2 != 0) diff += step shr 1
        if (code and 1 != 0) diff += step shr 2
        val next = (predictor + if (code and 8 != 0) -diff else diff).coerceIn(-32768, 32767)
        val nextIndex = (index + ADPCM_INDEX_TABLE[code]).coerceIn(0, 88)
        return (next.toLong() and 0xFFFFFFFFL) or (nextIndex.toLong() shl 32)
    }

    private fun leShort(bytes: ByteArray, offset: Int): Short {
        return ((bytes[offset].toInt() and 0xFF) or (bytes[offset + 1].toInt() shl 8)).toShort()
    }

    private fun writeFully(track: AudioTrack, pcm: ShortArray, sampleCount: Int): Int {
        var offset = 0
        var zeroWrites = 0
        while (offset < sampleCount) {
            val remaining = sampleCount - offset
            val result = track.write(
                pcm,
                offset,
                remaining,
                AudioTrack.WRITE_BLOCKING,
            )
            if (result < 0) {
                Log.e("SoundMirrorPerf", "AudioTrack.write failed: $result")
                throw IllegalStateException("AudioTrack.write failed: $result")
            }
            if (result == 0) {
                // A paused track can retain a full hardware buffer after rebuffering.
                // Let it drain before retrying; waiting for a complete packet before
                // play() would leave every subsequent write returning zero.
                if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                    track.play()
                    zeroWrites = 0
                    continue
                }
                // A blocking write should not return zero, but bound the retry so a
                // broken track cannot busy-spin forever on the urgent-audio thread.
                zeroWrites += 1
                if (zeroWrites >= 3) {
                    Log.e("SoundMirrorPerf", "AudioTrack.write repeatedly returned 0")
                    throw IllegalStateException("AudioTrack.write repeatedly returned 0")
                }
                Thread.yield()
                continue
            }
            if (result < remaining) {
                val now = System.currentTimeMillis()
                if (now - lastPartialWriteLogMs >= 5000) {
                    lastPartialWriteLogMs = now
                    Log.d("SoundMirrorPerf", "AudioTrack partial write: $result/$remaining samples")
                }
            }
            offset += result
            zeroWrites = 0
            // Prime with the data that actually fitted, then start the consumer.
            // This also covers PLC writes made before the first normal packet.
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                track.play()
            }
        }
        return offset
    }

    private fun pcmAmplitude(payload: ShortArray, sampleCount: Int): Float {
        if (sampleCount <= 0) return 0f
        var sum = 0L
        var i = 0
        while (i < sampleCount) {
            val value = payload[i].toLong()
            sum += value * value
            i += 1
        }
        return (sqrt(sum.toDouble() / sampleCount) / 32768.0).toFloat().coerceIn(0f, 1f)
    }
}

private enum class WireCodec(val id: Int, val displayName: String, val displayBitrate: String) {
    Pcm16(0, "PCM 16-bit", "1536 kbps"),
    Adpcm(1, "ADPCM", "384 kbps"),
    Mulaw(2, "G.711 μ-law", "768 kbps"),
    MulawLite(3, "G.711 μ-law Lite", "384 kbps"),
    AdpcmLite(4, "ADPCM Lite", "192 kbps"),
    Opus(5, "Opus", "160 kbps"),
    Flac(6, "FLAC 무손실", "자동 (가변)");

    companion object {
        fun fromId(id: Int): WireCodec = when (id) {
            1 -> Adpcm
            2 -> Mulaw
            3 -> MulawLite
            4 -> AdpcmLite
            5 -> Opus
            6 -> Flac
            else -> Pcm16
        }
    }
}

// Unified Opus decoder: prefers native libopus (faster, lower battery) and
// transparently falls back to the pure-Java Concentus decoder if the .so is
// missing. Both accept null input for packet-loss concealment.
private class OpusDecoderWrapper(sampleRate: Int, channels: Int) {
    private val nativeHandle: Long =
        if (NativeOpus.available) NativeOpus.nativeCreate(sampleRate, channels) else 0L
    private val javaDecoder: io.github.jaredmdobson.concentus.OpusDecoder? =
        if (nativeHandle == 0L) io.github.jaredmdobson.concentus.OpusDecoder(sampleRate, channels) else null

    val isNative: Boolean get() = nativeHandle != 0L

    fun decode(data: ByteArray?, len: Int, out: ShortArray, frameSize: Int, fec: Boolean): Int {
        return if (nativeHandle != 0L) {
            NativeOpus.nativeDecode(nativeHandle, data, len, out, frameSize, fec)
        } else {
            javaDecoder!!.decode(data, 0, len, out, 0, frameSize, fec)
        }
    }

    fun close() {
        if (nativeHandle != 0L) NativeOpus.nativeDestroy(nativeHandle)
    }
}

private class AudioPacket(
    var seq: Long = 0L,
    var codec: WireCodec = WireCodec.Pcm16,
    var sampleRate: Int = 0,
    var channels: Int = 0,
    var frames: Int = 0,
    var sendTimeNs: Long = 0L,
    var payload: ByteArray = ByteArray(4096),
    var payloadSize: Int = 0,
    // True network arrival time (nanoTime at socket receive). Used for the jitter
    // metric so it reflects real inter-arrival, not when our write-paced playout loop
    // happens to drain the channel (which arrives in bursts and inflates the number).
    var recvNs: Long = 0L,
)

@Composable
fun SoundMirrorApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val discovery = remember { DiscoveryManager(context, scope) }
    LaunchedEffect(Unit) { PlaybackController.ensureInit(context) }
    val devices by discovery.devices.collectAsStateWithLifecycle()
    val stats by PlaybackController.stats.collectAsStateWithLifecycle()
    var manualIp by remember { mutableStateOf("") }

    // SharedPreferences에서 저장된 설정 복원
    val prefs = remember { context.getSharedPreferences("soundmirror_settings", Context.MODE_PRIVATE) }
    var selectedPrebuffer by remember {
        mutableStateOf(prefs.getInt("prebuffer_packets", 4).coerceIn(2, 15))
    }
    var selectedCodec by remember {
        val savedCodecName = prefs.getString("codec", AudioCodec.Opus.name) ?: AudioCodec.Opus.name
        mutableStateOf(
            try { AudioCodec.valueOf(savedCodecName) } catch (_: Exception) { AudioCodec.Opus }
        )
    }
    var lowLatency by remember {
        mutableStateOf(prefs.getBoolean("low_latency", true))
    }
    var statsRefreshIndex by remember {
        val migratedDefault = if (prefs.getBoolean("battery_mode", false)) 2 else 0
        mutableStateOf(prefs.getInt("stats_refresh_index", migratedDefault).coerceIn(STATS_REFRESH_OPTIONS_MS.indices))
    }
    var deepBuffer by remember {
        mutableStateOf(prefs.getBoolean("deep_buffer", false))
    }
    var wifiSaver by remember {
        mutableStateOf(prefs.getBoolean("wifi_saver", false))
    }
    var opusBitrateKbps by remember {
        mutableStateOf(prefs.getInt("opus_bitrate_kbps", 160).let {
            OPUS_BITRATES_KBPS.minByOrNull { option -> kotlin.math.abs(option - it) } ?: 160
        })
    }
    val settings = StreamSettings(
        prebufferPackets = selectedPrebuffer,
        codec = selectedCodec,
        volume = 1f,
        requestLowLatency = lowLatency,
        statsRefreshMs = STATS_REFRESH_OPTIONS_MS[statsRefreshIndex],
        deepBuffer = deepBuffer,
        wifiSaver = wifiSaver,
        opusBitrateKbps = opusBitrateKbps,
    )

    // 설정 변경 시 SharedPreferences에 저장 및 스트림 업데이트
    LaunchedEffect(settings) {
        prefs.edit()
            .putInt("prebuffer_packets", selectedPrebuffer)
            .putString("codec", selectedCodec.name)
            .putBoolean("low_latency", lowLatency)
            .putInt("stats_refresh_index", statsRefreshIndex)
            .remove("battery_mode")
            .putBoolean("deep_buffer", deepBuffer)
            .putBoolean("wifi_saver", wifiSaver)
            .putInt("opus_bitrate_kbps", opusBitrateKbps)
            .apply()
        PlaybackController.updateSettings(settings)
    }

    LifecycleStartEffect(stats.connected) {
        if (stats.connected) discovery.stop() else discovery.start()
        onStopOrDispose { discovery.stop() }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF4F7FB)) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ConnectionHero(
                stats = stats,
                devices = devices,
                settings = settings,
                onDeviceClick = { PlaybackController.connect(it, settings) },
                onDisconnect = PlaybackController::disconnect,
            )
            LowLatencyPanel(
                lowLatency = lowLatency,
                onLowLatency = { lowLatency = it },
            )
            PlaybackStabilityPanel(
                statsRefreshIndex = statsRefreshIndex,
                onStatsRefreshIndex = { statsRefreshIndex = it },
                deepBuffer = deepBuffer,
                onDeepBuffer = { deepBuffer = it },
                wifiSaver = wifiSaver,
                onWifiSaver = { wifiSaver = it },
            )
            ServerList(
                devices = devices,
                connectedIp = stats.deviceIp,
                onDeviceClick = { PlaybackController.connect(it, settings) },
            )
            DirectConnect(
                value = manualIp,
                onValueChange = { manualIp = it },
                onConnect = {
                    val ip = manualIp.trim()
                    if (ip.isNotEmpty()) {
                        PlaybackController.connect(
                            DiscoveredDevice("직접 연결", ip, CONTROL_PORT, AUDIO_PORT, SAMPLE_RATE, CHANNELS, System.currentTimeMillis()),
                            settings,
                        )
                    }
                },
            )
            QualityPanel(
                prebufferPackets = selectedPrebuffer,
                onPrebufferChange = { selectedPrebuffer = it },
                codec = selectedCodec,
                onCodec = { selectedCodec = it },
                opusBitrateKbps = opusBitrateKbps,
                onOpusBitrateChange = { opusBitrateKbps = it },
            )
        }
    }
}

@Composable
private fun Header(stats: StreamStats) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text("SoundMirror", color = Color(0xFF111827), fontSize = 30.sp, fontWeight = FontWeight.Bold)
            Text("PC 오디오 플레이어", color = Color(0xFF6B7280), fontSize = 14.sp)
        }
        StatusPill(connected = stats.connected)
    }
}

@Composable
private fun StatusPill(connected: Boolean) {
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (connected) Color(0xFFE7F8EF) else Color(0xFFE9EEF6))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF18A058) else Color(0xFF94A3B8))
        )
        Spacer(Modifier.width(7.dp))
        Text(if (connected) "연결됨" else "대기 중", color = if (connected) Color(0xFF12743F) else Color(0xFF475569), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LowLatencyPanel(
    lowLatency: Boolean,
    onLowLatency: (Boolean) -> Unit,
) {
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("저지연 출력", color = Color(0xFF394150), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text("기기에서 가능한 가장 빠른 재생 경로", color = Color(0xFF8A94A6), fontSize = 12.sp)
            }
            Switch(checked = lowLatency, onCheckedChange = onLowLatency)
        }
    }
}

@Composable
private fun PlaybackStabilityPanel(
    statsRefreshIndex: Int,
    onStatsRefreshIndex: (Int) -> Unit,
    deepBuffer: Boolean,
    onDeepBuffer: (Boolean) -> Unit,
    wifiSaver: Boolean,
    onWifiSaver: (Boolean) -> Unit,
) {
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("상태 측정 주기", color = Color(0xFF394150), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(STATS_REFRESH_LABELS[statsRefreshIndex], color = Color(0xFF007AFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "지연·지터·유실·수신률 수치의 측정 간격입니다. 오디오 시각화와 재생 처리는 영향을 받지 않습니다.",
            color = Color(0xFF8A94A6),
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(8.dp))
        Slider(
            value = statsRefreshIndex.toFloat(),
            onValueChange = { onStatsRefreshIndex(it.roundToInt().coerceIn(STATS_REFRESH_OPTIONS_MS.indices)) },
            valueRange = 0f..STATS_REFRESH_OPTIONS_MS.lastIndex.toFloat(),
            steps = STATS_REFRESH_OPTIONS_MS.size - 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        SettingRow(
            title = "깊은 버퍼 (끊김 방지)",
            description = "켜는 즉시 적용. 재생 지연이 약 2초 늘어납니다. 대신 Wi-Fi 주기 스캔·지터로 인한 끊김을 막아줍니다.",
            checked = deepBuffer,
            onCheckedChange = onDeepBuffer,
            enabled = true,
        )
        Spacer(Modifier.height(10.dp))
        SettingRow(
            title = "Wi-Fi 절전",
            description = "켜는 즉시 적용. 무선 칩이 절전하도록 허용해 배터리를 아낍니다. 지연·지터가 늘 수 있어 '깊은 버퍼'와 함께 쓰길 권장합니다.",
            checked = wifiSaver,
            onCheckedChange = onWifiSaver,
            enabled = true,
        )
    }
}

@Composable
private fun SettingRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean,
    titleColor: Color = Color(0xFF394150),
) {
    // Grey the whole row out when disabled so it reads as "locked".
    val dim = if (enabled) 1f else 0.45f
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f).alpha(dim)) {
            Text(title, color = titleColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(description, color = Color(0xFF8A94A6), fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}

@Composable
private fun ConnectionHero(
    stats: StreamStats,
    devices: List<DiscoveredDevice>,
    settings: StreamSettings,
    onDeviceClick: (DiscoveredDevice) -> Unit,
    onDisconnect: () -> Unit,
) {
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(if (stats.connected) "연결됨" else "서버 검색 중", color = Color(0xFF111827), fontSize = 21.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(5.dp))
                Text(
                    if (stats.connected) "${stats.deviceName}  ${stats.deviceIp}" else "같은 Wi-Fi의 PC 송신기를 찾고 있습니다",
                    color = Color(0xFF667085),
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            AnimatedVisibility(visible = stats.connected, enter = fadeIn(), exit = fadeOut()) {
                TextButton(onClick = onDisconnect) {
                    Text("중지", color = Color(0xFFE5484D), fontWeight = FontWeight.Bold)
                }
            }
        }
        Box(modifier = Modifier.fillMaxWidth().height(150.dp), contentAlignment = Alignment.Center) {
            RealtimeAudioVisualizer(connected = stats.connected)
        }
        AnimatedVisibility(visible = stats.connected, enter = fadeIn(), exit = fadeOut()) {
            ConnectedStatsPanel(stats = stats, settings = settings)
        }
    }
}

@Composable
private fun RealtimeAudioVisualizer(connected: Boolean) {
    // Keep the frequently changing audio level read in this narrow composition scope,
    // so the connection card and numeric stats do not recompose at meter speed.
    val amplitude by PlaybackController.audioLevel.collectAsStateWithLifecycle()
    OrbitalVisualizer(connected = connected, amplitude = amplitude)
}

@Composable
private fun ConnectedStatsPanel(stats: StreamStats, settings: StreamSettings) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7F9FC))
            .padding(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat("지연", "${(stats.latencyMs.takeIf { it > 0 } ?: settings.codec.estimatedBufferMs(settings.prebufferPackets))} ms", Modifier.weight(1f))
            Stat("지터", "${stats.jitterMs} ms", Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat("유실", "${(stats.packetLoss * 100f).roundToInt()}%", Modifier.weight(1f))
            Stat("오디오 수신률", stats.bitrateLabel.ifEmpty { "측정 중" }, Modifier.weight(1f))
        }
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Stat("코덱", stats.codecLabel.ifEmpty { settings.codec.label }, Modifier.weight(1f))
            Stat(
                if (settings.deepBufferActive) "깊은 버퍼" else "버퍼 대기",
                if (settings.deepBufferActive) {
                    "약 ${DEEP_BUFFER_MS.roundToInt()} ms"
                } else {
                    "${settings.prebufferPackets}개 · ${settings.codec.estimatedBufferMs(settings.prebufferPackets)}ms"
                },
                Modifier.weight(1f),
            )
            Stat("출력", if (settings.requestLowLatency) "저지연" else "일반", Modifier.weight(1f))
        }
    }
}

@Composable
private fun ServerList(
    devices: List<DiscoveredDevice>,
    connectedIp: String,
    onDeviceClick: (DiscoveredDevice) -> Unit,
) {
    CardBox {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("서버", color = Color(0xFF111827), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${devices.size}", color = Color(0xFF8A94A6), fontSize = 13.sp)
        }
        Spacer(Modifier.height(10.dp))
        if (devices.isEmpty()) {
            EmptyRow("검색된 PC가 없습니다")
        } else {
            devices.forEach { device ->
                ServerRow(
                    device = device,
                    connected = connectedIp == device.ip,
                    onClick = { onDeviceClick(device) },
                )
            }
        }
    }
}

@Composable
private fun ServerRow(device: DiscoveredDevice, connected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 11.dp, horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (connected) Color(0xFF007AFF) else Color(0xFFEAF0F8)),
            contentAlignment = Alignment.Center,
        ) {
            Text("PC", color = if (connected) Color.White else Color(0xFF52606F), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(device.name, color = Color(0xFF111827), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text("${device.ip}  ${device.sampleRate}Hz", color = Color(0xFF7A8494), fontSize = 12.sp, maxLines = 1)
        }
        Text(if (connected) "연결됨" else "연결", color = if (connected) Color(0xFF18A058) else Color(0xFF007AFF), fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun EmptyRow(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFF7F9FC))
            .padding(18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = Color(0xFF8A94A6), fontSize = 13.sp)
    }
}

@Composable
private fun DirectConnect(value: String, onValueChange: (String) -> Unit, onConnect: () -> Unit) {
    CardBox {
        Text("주소로 연결", color = Color(0xFF111827), fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("IP 주소") },
            )
            Spacer(Modifier.width(10.dp))
            Button(
                onClick = onConnect,
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF007AFF)),
            ) {
                Text("연결")
            }
        }
    }
}

@Composable
private fun QualityPanel(
    prebufferPackets: Int,
    onPrebufferChange: (Int) -> Unit,
    codec: AudioCodec,
    onCodec: (AudioCodec) -> Unit,
    opusBitrateKbps: Int,
    onOpusBitrateChange: (Int) -> Unit,
) {
    CardBox {
        Text("패킷 버퍼 대기량 (지연 시간 조절)", color = Color(0xFF111827), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text("슬라이더를 낮추면 지연이 줄어들고(영상/게임 추천), 높이면 버퍼를 많이 쌓아 끊김에 강해집니다.", color = Color(0xFF7A8494), fontSize = 12.sp)
        Spacer(Modifier.height(16.dp))
        Slider(
            value = prebufferPackets.toFloat(),
            onValueChange = { onPrebufferChange(it.roundToInt().coerceIn(2, 15)) },
            valueRange = 2f..15f,
            steps = 12,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("현재 설정: ${prebufferPackets} 패킷 (약 ${codec.estimatedBufferMs(prebufferPackets)}ms 지연)", color = Color(0xFF007AFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                text = when (prebufferPackets) {
                    in 2..3 -> "저지연 (네트워크 양호 필수)"
                    in 4..7 -> "균형 잡힌 모드"
                    else -> "안정적 모드 (끊김 방지)"
                },
                color = Color(0xFF475569),
                fontSize = 12.sp
            )
        }
        Spacer(Modifier.height(18.dp))
        Text("음질 / 대역폭 선택", color = Color(0xFF394150), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        AudioCodec.values().forEach { option ->
            CodecRow(
                codec = option,
                selected = codec == option,
                onClick = { onCodec(option) },
                bitrateLabel = option.settingsBitrate(opusBitrateKbps),
                selectedBitrateKbps = opusBitrateKbps,
                onBitrateChange = onOpusBitrateChange,
            )
        }
    }
}

@Composable
private fun CodecRow(
    codec: AudioCodec,
    selected: Boolean,
    onClick: () -> Unit,
    bitrateLabel: String,
    selectedBitrateKbps: Int,
    onBitrateChange: (Int) -> Unit,
) {
    var bitrateMenuExpanded by remember(codec) { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) Color(0xFFEAF3FF) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(20.dp)
                .clip(CircleShape)
                .background(if (selected) Color(0xFF007AFF) else Color(0xFFE1E7F0)),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(Color.White))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(codec.label, color = Color(0xFF111827), fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Text(codec.caption, color = Color(0xFF7A8494), fontSize = 12.sp)
        }
        Box {
            Text(
                text = bitrateLabel,
                color = Color(0xFF007AFF),
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.clickable(
                    enabled = codec == AudioCodec.Opus || codec == AudioCodec.Flac,
                ) {
                    onClick()
                    bitrateMenuExpanded = true
                },
            )
            DropdownMenu(
                expanded = bitrateMenuExpanded,
                onDismissRequest = { bitrateMenuExpanded = false },
            ) {
                if (codec == AudioCodec.Opus) {
                    OPUS_BITRATES_KBPS.forEach { kbps ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (kbps == selectedBitrateKbps) "$kbps kbps · 선택됨" else "$kbps kbps"
                                )
                            },
                            onClick = {
                                onBitrateChange(kbps)
                                bitrateMenuExpanded = false
                            },
                        )
                    }
                } else if (codec == AudioCodec.Flac) {
                    DropdownMenuItem(
                        text = { Text("자동 · 무손실 (음원에 따라 전송률 변동)") },
                        onClick = { bitrateMenuExpanded = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun OrbitalVisualizer(
    connected: Boolean,
    amplitude: Float,
) {
    // Connected audio level already updates at 20 Hz. A second perpetual animation
    // would keep rendering near 60 fps, so retain it only for the idle screen.
    val pulse = if (connected) {
        0f
    } else {
        val transition = rememberInfiniteTransition(label = "visual")
        val idlePulse by transition.animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
            label = "pulse",
        )
        idlePulse
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(140.dp),
        contentAlignment = Alignment.Center
    ) {
        CentralTarget(connected = connected, amplitude = amplitude, pulse = pulse)
    }
}

@Composable
private fun CentralTarget(connected: Boolean, amplitude: Float, pulse: Float) {
    val ringAmp = if (connected) amplitude else 0.08f + pulse * 0.04f
    Box(modifier = Modifier.size(130.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val base = size.minDimension / 2.3f
            drawCircle(
                color = if (connected) Color(0x1A007AFF) else Color(0x0F007AFF),
                radius = base + ringAmp * 28f,
                center = center
            )
            drawCircle(
                color = if (connected) Color(0x33007AFF) else Color(0x1F007AFF),
                radius = base * 0.82f + ringAmp * 16f,
                center = center,
                style = Stroke(2.dp.toPx())
            )
            drawCircle(Color.White, radius = base * 0.65f, center = center)
        }
        
        AnimatedVisibility(visible = connected, enter = fadeIn(), exit = fadeOut()) {
            EqualizerBars(amplitude = amplitude)
        }
        AnimatedVisibility(visible = !connected, enter = fadeIn(), exit = fadeOut()) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("대기 중", color = Color(0xFF111827), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Text("서버 검색", color = Color(0xFF8A94A6), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun EqualizerBars(amplitude: Float) {
    val bars = 13
    Canvas(modifier = Modifier.size(104.dp, 76.dp)) {
        val width = size.width / (bars * 1.6f)
        val gap = width * 0.6f
        for (i in 0 until bars) {
            val phase = abs(i - bars / 2f) / (bars / 2f)
            val h = (18f + amplitude * 70f * (1f - phase * 0.55f)).coerceAtLeast(10f)
            val x = i * (width + gap)
            drawRoundRect(
                color = Color(0xFF007AFF),
                topLeft = Offset(x, size.height / 2f - h / 2f),
                size = Size(width, h),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(width / 2f, width / 2f),
            )
        }
    }
}

// DeviceBubble is removed

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Color(0xFF8A94A6), fontSize = 11.sp)
        Text(value, color = Color(0xFF111827), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun CardBox(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.White)
            .padding(16.dp),
        content = content,
    )
}
