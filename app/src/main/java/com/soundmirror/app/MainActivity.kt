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
import androidx.compose.animation.core.animateFloatAsState
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.PriorityQueue
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
    val amplitude: Float = 0f,
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
    // Zero freezes live metrics; connection lifecycle remains active.
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
    @Volatile private var settings = StreamSettings()
    private val _stats = MutableStateFlow(StreamStats())
    val stats: StateFlow<StreamStats> = _stats.asStateFlow()
    private var reusableShorts = ShortArray(65535)
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

    // Reusable decode output buffers. Packet sizes are constant within a stream, so
    // these hit steady-state reuse and remove ~0.5-1 MB/s of per-packet ByteArray
    // garbage — fewer GC pauses (micro-stutters) and less battery.
    private var adpcmBytes = ByteArray(0)
    private var mulawBytes = ByteArray(0)
    private var opusBytes = ByteArray(0)
    private var flacBytes = ByteArray(0)
    private var upsampleBytes = ByteArray(0)

    private fun reusableBytes(current: ByteArray, needed: Int): ByteArray =
        if (current.size == needed) current else ByteArray(needed)

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
        _stats.value = StreamStats(
            connected = true,
            deviceName = device.name,
            deviceIp = device.ip,
            codecLabel = settings.codec.label,
            bitrateLabel = "측정 중",
            latencyMs = settings.codec.estimatedBufferMs(settings.prebufferPackets),
            latencyLabel = "${settings.codec.estimatedBufferMs(settings.prebufferPackets)}ms",
        )
        opusDecoder?.close()
        opusDecoder = null
        releaseFlac()
        // Cancel the previous session BEFORE scheduling the new one: both sessions run
        // on the single playout thread, so if the new body were the one to deliver the
        // cancel, the old loop would never yield the thread and we'd deadlock.
        previous?.cancel()
        job = scope.launch(playoutDispatcher) {
            // Make sure any previous session has fully released the audio socket
            // before we rebind it — otherwise a quick reconnect hits EADDRINUSE.
            try { previous?.join() } catch (_: Exception) {}
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
                    play()
                }
                receiveAudio(socket, track, device)
            } catch (e: Exception) {
                // Never crash the app on a connect/bind failure — just reset state.
                Log.w("SoundMirror", "connect failed", e)
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                runCatching { socket?.close() }
                opusDecoder?.close()
                opusDecoder = null
                releaseFlac()
                _stats.value = StreamStats()
            }
        }
    }

    fun disconnect() {
        job?.cancel()
        job = null
    }

    private fun createAudioTrack(sampleRate: Int, channels: Int, streamSettings: StreamSettings): AudioTrack {
        val mask = if (channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val minBuffer = AudioTrack.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT)
        // Keep the AudioTrack buffer SMALL. The jitter buffer lives in our own queue
        // (clock-driven playout drains it), so WRITE_BLOCKING must pace us tightly to
        // real time — a large track buffer would let us drain the queue faster than
        // real time, empty it, and then inject filler (the cause of the rapid "툭툭툭"
        // chop). A couple of minBuffers is just enough to ride thread-scheduling jitter.
        val multiplier = if (streamSettings.requestLowLatency) 2 else 3
        val bufferBytes = (minBuffer * multiplier).coerceAtLeast(minBuffer)
        return AudioTrack.Builder()
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
    }

    private fun announceConnect(device: DiscoveredDevice, repeatCount: Int = 3) {
        DatagramSocket().use { socket ->
            val payload = """SM_CONNECT {"audioPort":$AUDIO_PORT,"quality":"Custom_${settings.prebufferPackets}","codec":"${settings.codec.wireName}","opusBitrate":${settings.opusBitrateKbps}}"""
                .toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(
                payload,
                payload.size,
                InetAddress.getByName(device.ip),
                device.controlPort,
            )
            repeat(repeatCount) {
                socket.send(packet)
                if (repeatCount > 1) {
                    Thread.sleep(80)
                }
            }
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
        var lastUiUpdateMs = 0L
        var lastTrimMs = 0L
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
                        delay(1500)
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
                    receivedPayloadBytes.addAndGet(parsed.payload.size.toLong())
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
            while (currentCoroutineContext().isActive) {
                try {
                    delay(3000)
                    announceConnect(device, 1)
                } catch (_: Exception) {
                    break
                }
            }
        }

        // Clock-driven playout. The receive job fills [queue]; this loop drains it at
        // real-time pace (paced by WRITE_BLOCKING) regardless of when packets arrive.
        // That's what makes the jitter buffer actually protect against gaps: when the
        // network stalls (e.g. a periodic Wi-Fi background scan leaves the channel for
        // 1-3s every few minutes) we keep playing buffered audio instead of underrunning.
        var prevDeepMode = deepBufferMode()
        // For non-Opus loss concealment: number of shorts in the last packet written
        // (still sitting in reusableShorts) and whether to fade the next packet in.
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
                        if (queue.poll() != null) dropped++
                    }
                    lost += dropped
                    expectedSeq = queue.peek()?.seq
                }

                // 3) (Re)buffering gate. While buffering, block for the next arrival so
                //    we don't busy-spin, and accumulate up to the target before playing.
                if (isBuffering) {
                    if (queue.size >= prebufferTarget) {
                        isBuffering = false
                        try { track.play() } catch (_: Exception) {}
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
                        try { track.pause() } catch (_: Exception) {}
                    } else {
                        break
                    }
                    continue
                }

                val expected = expectedSeq
                if (expected != null && playPacket.seq < expected) continue
                if (expected != null && playPacket.seq > expected) {
                    lost += playPacket.seq - expected
                    if (playPacket.codec == WireCodec.Opus) {
                        // Opus has real packet-loss concealment — let it synthesise the gap.
                        val missing = (playPacket.seq - expected).coerceAtMost(5).toInt()
                        val plc = concealOpus(playPacket.frames, missing)
                        if (plc.isNotEmpty()) {
                            val plcSamples = fillReusableShorts(plc)
                            track.write(reusableShorts, 0, plcSamples, AudioTrack.WRITE_BLOCKING)
                            totalFramesWritten += plcSamples / 2
                        }
                    } else if (prevSamples > 0) {
                        // PCM/ADPCM/MULAW have no PLC. Fade the previous frame (still in
                        // reusableShorts) out to zero so a lost packet decays smoothly
                        // instead of a hard click, and flag the next frame to fade back in.
                        val frames = (prevSamples / 2).coerceAtLeast(1)
                        var i = 0
                        while (i < prevSamples) {
                            val g = 1f - (i / 2) / frames.toFloat()
                            reusableShorts[i] = (reusableShorts[i] * g).toInt().toShort()
                            reusableShorts[i + 1] = (reusableShorts[i + 1] * g).toInt().toShort()
                            i += 2
                        }
                        track.write(reusableShorts, 0, prevSamples, AudioTrack.WRITE_BLOCKING)
                        totalFramesWritten += prevSamples / 2
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
                var decodedRms = -1f

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
                    decodedRms = pcmAmplitude(decoded)
                    if (decodedRms < SILENCE_SHED_RMS) continue
                }

                val samples = fillReusableShorts(decoded)
                if (fadeInNext && samples > 0) {
                    // Ramp the first packet after a concealed gap up from zero so the
                    // resume is click-free too.
                    val frames = (samples / 2).coerceAtLeast(1)
                    var i = 0
                    while (i < samples) {
                        val g = (i / 2) / frames.toFloat()
                        reusableShorts[i] = (reusableShorts[i] * g).toInt().toShort()
                        reusableShorts[i + 1] = (reusableShorts[i + 1] * g).toInt().toShort()
                        i += 2
                    }
                    fadeInNext = false
                }
                track.write(reusableShorts, 0, samples, AudioTrack.WRITE_BLOCKING)
                totalFramesWritten += samples / 2
                prevSamples = samples

                // 5) Throttled stats + latency trim.
                val now = System.currentTimeMillis()
                val uiUpdateIntervalMs = settings.statsRefreshMs
                if (uiUpdateIntervalMs > 0L && now - lastUiUpdateMs >= uiUpdateIntervalMs) {
                    lastUiUpdateMs = now
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

                    // Trim a ballooned playout buffer. This only fires when the AudioTrack
                    // buffer has grown large — which happens on Bluetooth (the OS allocates
                    // a big A2DP buffer and WRITE_BLOCKING fills it, pinning latency at
                    // ~300ms). On a small wired/speaker buffer trackDelay never reaches the
                    // threshold, so this never fires there (no glitch on wired). We let the
                    // buffer fill (so BT keeps streaming), then flush the excess back down —
                    // each trim costs one brief rebuffer "tick" but keeps BT latency low.
                    // Deep-buffer mode deliberately accepts latency for continuity.
                    // Flushing AudioTrack here fought that policy and caused a periodic
                    // audible tick followed by another full rebuffer.
                    if (!deepMode && trackDelayMs > 100.0 && now - lastTrimMs > 2500) {
                        Log.d("SoundMirrorLat", "trim: track=${trackDelayMs.toInt()}ms -> reset")
                        lastTrimMs = now
                        lost += queue.size.toLong()
                        queue.clear()
                        expectedSeq = null
                        try {
                            track.pause()
                            track.flush()
                            totalFramesWritten = track.playbackHeadPosition.toLong() and 0xFFFF_FFFFL
                        } catch (_: Exception) {}
                        prevSamples = 0
                        fadeInNext = false
                        smoothedLatencyMs = 0.0
                        isBuffering = true
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
                        amplitude = if (decodedRms >= 0f) decodedRms else pcmAmplitude(decoded),
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
        } finally {
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
        val header = ByteBuffer.wrap(data, 0, headerSize).order(ByteOrder.BIG_ENDIAN)
        header.position(4) // skip past the 4-byte magic
        val codec = if (isV2) WireCodec.fromId(header.get().toInt()) else WireCodec.Pcm16
        val seq = header.int.toLong() and 0xFFFF_FFFFL
        val sampleRate = header.int
        val channels = header.get().toInt()
        val frames = header.short.toInt() and 0xFFFF
        val payloadSize = header.int
        val sendTimeNs = header.long
        if (payloadSize <= 0 || headerSize + payloadSize > length) return null
        return AudioPacket(seq, codec, sampleRate, channels, frames, sendTimeNs, data.copyOfRange(headerSize, headerSize + payloadSize), recvNs)
    }

    private fun fillReusableShorts(bytes: ByteArray): Int {
        val sampleCount = bytes.size / 2
        if (sampleCount > reusableShorts.size) {
            reusableShorts = ShortArray(sampleCount * 2)
        }
        var s = 0
        var b = 0
        while (b + 1 < bytes.size) {
            val low = bytes[b].toInt() and 0xFF
            val high = bytes[b + 1].toInt() shl 8
            reusableShorts[s++] = (low or high).toShort()
            b += 2
        }
        return sampleCount
    }

    private fun decodePacket(packet: AudioPacket, deviceSampleRate: Int): ByteArray {
        val decoded = when (packet.codec) {
            WireCodec.Pcm16 -> packet.payload
            WireCodec.Adpcm -> decodeAdpcm(packet.payload, packet.frames)
            WireCodec.Mulaw -> decodeMulaw(packet.payload)
            WireCodec.MulawLite -> decodeMulaw(packet.payload)
            WireCodec.AdpcmLite -> decodeAdpcm(packet.payload, packet.frames)
            WireCodec.Opus -> decodeOpus(packet)
            WireCodec.Flac -> decodeFlac(packet.payload)
        }
        if (packet.sampleRate > 0 && packet.sampleRate < deviceSampleRate) {
            val scale = deviceSampleRate / packet.sampleRate
            if (scale == 2) {
                upsampleBytes = reusableBytes(upsampleBytes, decoded.size * 2)
                val upsampled = upsampleBytes
                var src = 0
                var dst = 0
                while (src + 3 < decoded.size) {
                    val b0 = decoded[src]
                    val b1 = decoded[src + 1]
                    val b2 = decoded[src + 2]
                    val b3 = decoded[src + 3]
                    
                    // First copy
                    upsampled[dst++] = b0
                    upsampled[dst++] = b1
                    upsampled[dst++] = b2
                    upsampled[dst++] = b3
                    
                    // Second copy (upsample by 2)
                    upsampled[dst++] = b0
                    upsampled[dst++] = b1
                    upsampled[dst++] = b2
                    upsampled[dst++] = b3
                    
                    src += 4
                }
                // Reused buffer: zero any tail the loop didn't overwrite.
                if (dst < upsampled.size) upsampled.fill(0, dst, upsampled.size)
                return upsampled
            }
        }
        return decoded
    }

    private fun decodeOpus(packet: AudioPacket): ByteArray {
        val rate = packet.sampleRate.coerceAtLeast(8000)
        val decoder = opusDecoder ?: try {
            OpusDecoderWrapper(rate, 2).also { opusDecoder = it }
        } catch (_: Exception) {
            return ByteArray(0)
        }
        // Opus packets can hold up to 120 ms; size the output buffer for that.
        val maxPerChannel = rate / 1000 * 120
        if (opusOut.size < maxPerChannel * 2) {
            opusOut = ShortArray(maxPerChannel * 2)
        }
        val perChannel = try {
            decoder.decode(packet.payload, packet.payload.size, opusOut, maxPerChannel, false)
        } catch (_: Exception) {
            return ByteArray(0)
        }
        val totalShorts = perChannel * 2
        opusBytes = reusableBytes(opusBytes, totalShorts * 2)
        val out = opusBytes
        var o = 0
        for (i in 0 until totalShorts) {
            val s = opusOut[i].toInt()
            out[o++] = (s and 0xFF).toByte()
            out[o++] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    // Opus packet loss concealment: ask the decoder to synthesise [count] missing
    // frames (passing null input triggers Opus PLC). Turns a dropped-packet gap into
    // a smooth interpolation instead of silence. Capped by the caller.
    private fun concealOpus(frames: Int, count: Int): ByteArray {
        val dec = opusDecoder
        if (dec == null || count <= 0 || frames <= 0) return ByteArray(0)
        val perFrameShorts = frames * 2
        if (opusOut.size < perFrameShorts) opusOut = ShortArray(perFrameShorts)
        val out = ByteArray(count * perFrameShorts * 2)
        var off = 0
        repeat(count) {
            val per = try {
                dec.decode(null, 0, opusOut, frames, false)
            } catch (_: Exception) {
                return if (off > 0) out.copyOf(off) else ByteArray(0)
            }
            var i = 0
            val n = per * 2
            while (i < n) {
                val v = opusOut[i].toInt()
                out[off++] = (v and 0xFF).toByte()
                out[off++] = ((v shr 8) and 0xFF).toByte()
                i++
            }
        }
        return if (off == out.size) out else out.copyOf(off)
    }

    // Each FLAC packet is a complete, self-contained FLAC stream (header + one block).
    // The packaged native libFLAC decoder reuses one handle across packets.
    // Output is signed 16-bit LE stereo interleaved — exactly our PCM wire format.
    private fun decodeFlac(payload: ByteArray): ByteArray {
        if (NativeFlac.available) {
            if (flacHandle == 0L && !flacTriedInit) {
                flacTriedInit = true
                flacHandle = try { NativeFlac.nativeCreate() } catch (_: Throwable) { 0L }
            }
            if (flacHandle != 0L) {
                // 8192 shorts = 4096 stereo frames, far above our 480-frame blocks.
                if (flacOut.size < 8192) flacOut = ShortArray(8192)
                val n = try {
                    NativeFlac.nativeDecode(flacHandle, payload, payload.size, flacOut, flacOut.size)
                } catch (_: Throwable) { -1 }
                if (n <= 0) return ByteArray(0)
                flacBytes = reusableBytes(flacBytes, n * 2)
                val bytes = flacBytes
                var o = 0
                var i = 0
                while (i < n) {
                    val s = flacOut[i].toInt()
                    bytes[o++] = (s and 0xFF).toByte()
                    bytes[o++] = ((s shr 8) and 0xFF).toByte()
                    i++
                }
                return bytes
            }
        }
        return ByteArray(0)
    }

    private fun decodeMulaw(payload: ByteArray): ByteArray {
        mulawBytes = reusableBytes(mulawBytes, payload.size * 2)
        val out = mulawBytes
        var o = 0
        for (byte in payload) {
            val sample = mulawToLinear(byte.toInt() and 0xFF)
            out[o++] = (sample.toInt() and 0xFF).toByte()
            out[o++] = ((sample.toInt() shr 8) and 0xFF).toByte()
        }
        return out
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

    private fun decodeAdpcm(payload: ByteArray, frames: Int): ByteArray {
        if (payload.size < 6 || frames <= 0) return ByteArray(0)
        var left = leShort(payload, 0).toInt()
        var leftIndex = payload[2].toInt().coerceIn(0, 88)
        var right = leShort(payload, 3).toInt()
        var rightIndex = payload[5].toInt().coerceIn(0, 88)
        adpcmBytes = reusableBytes(adpcmBytes, frames * 4)
        val out = adpcmBytes
        writeShort(out, 0, left)
        writeShort(out, 2, right)
        var offset = 4
        var i = 6
        while (offset + 3 < out.size && i < payload.size) {
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
            writeShort(out, offset, left)
            writeShort(out, offset + 2, right)
            offset += 4
        }
        // Reused buffer: zero any tail a truncated payload didn't overwrite.
        if (offset < out.size) out.fill(0, offset, out.size)
        return out
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

    private fun writeShort(bytes: ByteArray, offset: Int, value: Int) {
        bytes[offset] = (value and 0xFF).toByte()
        bytes[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun pcmAmplitude(payload: ByteArray): Float {
        if (payload.size < 2) return 0f
        var sum = 0.0
        var samples = 0
        var i = 0
        while (i + 1 < payload.size) {
            val value = ((payload[i + 1].toInt() shl 8) or (payload[i].toInt() and 0xFF)).toShort().toInt()
            sum += value.toDouble() * value.toDouble()
            samples += 1
            i += 2
        }
        return (sqrt(sum / samples) / 32768.0).toFloat().coerceIn(0f, 1f)
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
        fun fromId(id: Int): WireCodec = values().firstOrNull { it.id == id } ?: Pcm16
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

private data class AudioPacket(
    val seq: Long,
    val codec: WireCodec,
    val sampleRate: Int,
    val channels: Int,
    val frames: Int,
    val sendTimeNs: Long,
    val payload: ByteArray,
    // True network arrival time (nanoTime at socket receive). Used for the jitter
    // metric so it reflects real inter-arrival, not when our write-paced playout loop
    // happens to drain the channel (which arrives in bursts and inflates the number).
    val recvNs: Long = 0,
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
            Text("상태 갱신 주기", color = Color(0xFF394150), fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            Text(STATS_REFRESH_LABELS[statsRefreshIndex], color = Color(0xFF007AFF), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
        Text(
            "갱신 간격을 길게 하거나 정적으로 설정하면 화면 처리량이 줄어 배터리 절약에 유리합니다.",
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
            OrbitalVisualizer(
                connected = stats.connected,
                amplitude = stats.amplitude,
            )
        }
        AnimatedVisibility(visible = stats.connected, enter = fadeIn(), exit = fadeOut()) {
            ConnectedStatsPanel(stats = stats, settings = settings)
        }
    }
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
    val transition = rememberInfiniteTransition(label = "visual")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse",
    )
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
    val ringAmp by animateFloatAsState(
        targetValue = if (connected) amplitude else 0.08f + pulse * 0.04f,
        animationSpec = tween(120),
        label = "ring-amp",
    )
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
