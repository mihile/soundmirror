#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::collections::HashMap;
use std::collections::VecDeque;
use std::net::{SocketAddrV4, UdpSocket};

use audiopus::{Application, Bitrate, Channels, SampleRate, coder::Encoder as OpusEncoder};
use std::sync::{Mutex, LazyLock, OnceLock, RwLock};
use std::sync::atomic::{AtomicBool, AtomicU32, Ordering};
use std::thread;
use std::time::{Duration, Instant, SystemTime};

use windows::Win32::System::Com::{CoInitializeEx, COINIT_MULTITHREADED, CoCreateInstance, CLSCTX_ALL, STGM_READ};
use windows::Win32::System::Com::StructuredStorage::{PROPVARIANT, PropVariantClear};
use windows::Win32::System::Threading::{
    CreateEventW, CreateMutexW, GetCurrentThread, SetThreadPriority, WaitForSingleObject, THREAD_PRIORITY_HIGHEST,
};
use windows::Win32::UI::Shell::PropertiesSystem::{PROPERTYKEY, IPropertyStore};
use windows::Win32::UI::Shell::ShellExecuteW;
use windows::Win32::Media::Audio::{
    IMMDevice, IMMDeviceEnumerator, MMDeviceEnumerator, eRender, eConsole, IAudioClient,
    IAudioCaptureClient, AUDCLNT_SHAREMODE_SHARED, AUDCLNT_STREAMFLAGS_EVENTCALLBACK, AUDCLNT_STREAMFLAGS_LOOPBACK,
    AUDCLNT_BUFFERFLAGS_SILENT, WAVEFORMATEX, WAVEFORMATEXTENSIBLE,
};
use windows::Win32::UI::WindowsAndMessaging::{
    FindWindowW, IsIconic, ShowWindow, SetForegroundWindow, SW_HIDE, SW_MINIMIZE,
    GWL_EXSTYLE, WS_EX_APPWINDOW, WS_EX_TOOLWINDOW, SW_RESTORE,
    GetWindowLongW, SetWindowLongW,
};
use windows::Win32::Foundation::{CloseHandle, GetLastError, ERROR_ALREADY_EXISTS, WAIT_OBJECT_0, WIN32_ERROR};
use windows::core::PCWSTR;

// Tray and Registry libs
use winreg::enums::{HKEY_CURRENT_USER, KEY_READ, KEY_WRITE};
use winreg::RegKey;
use tray_icon::{
    menu::{Menu, MenuItem, MenuEvent},
    TrayIconBuilder, TrayIconEvent,
};
use eframe::egui;


// Global ports and config
const DISCOVERY_PORT: u16 = 45670;
const CONTROL_PORT: u16 = 45671;
const CLIENT_AUDIO_PORT: u16 = 45672;
const BLOCK_MS: u64 = 5;

// WAVEFORMAT Constants
const WAVE_FORMAT_PCM: u16 = 1;
const WAVE_FORMAT_IEEE_FLOAT: u16 = 3;
const WAVE_FORMAT_EXTENSIBLE: u16 = 0xFFFE;

// Constants for GUID
const KSDATAFORMAT_SUBTYPE_IEEE_FLOAT: windows::core::GUID = windows::core::GUID {
    data1: 0x00000003,
    data2: 0x0000,
    data3: 0x0010,
    data4: [0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71],
};
const KSDATAFORMAT_SUBTYPE_PCM: windows::core::GUID = windows::core::GUID {
    data1: 0x00000001,
    data2: 0x0000,
    data3: 0x0010,
    data4: [0x80, 0x00, 0x00, 0xaa, 0x00, 0x38, 0x9b, 0x71],
};

const PKEY_DEVICE_FRIENDLY_NAME: PROPERTYKEY = PROPERTYKEY {
    fmtid: windows::core::GUID::from_u128(0xa45c254e_df1c_4efd_8020_67d146a850e0),
    pid: 14,
};

struct Client {
    addr: SocketAddrV4,
    seen: Instant,
    codec: u8,
    opus_bitrate_bps: i32,
}

#[derive(Clone)]
struct ClientEndpoint {
    addr: SocketAddrV4,
    codec: u8,
    opus_bitrate_bps: i32,
}

static CLIENTS: LazyLock<Mutex<Vec<Client>>> = LazyLock::new(|| Mutex::new(Vec::new()));
// Incremented only when the endpoint/codec snapshot used by the audio thread changes.
// Keepalive-only updates still refresh `seen`, but no longer force the real-time thread
// to take CLIENTS' mutex for every capture packet.
static CLIENTS_GENERATION: AtomicU32 = AtomicU32::new(1);
static RUNNING: AtomicBool = AtomicBool::new(true);
static SHOW_WINDOW_FLAG: AtomicBool = AtomicBool::new(false);
// eframe 0.26 on Windows busy-polls RedrawWindow when a repaint deadline expires
// while the native window is invisible. Store the context so tray/menu events can
// wake it only after making the window visible; hidden mode schedules no repaints.
static GUI_CONTEXT: OnceLock<egui::Context> = OnceLock::new();
// RMS is display-only work. The capture thread skips it completely while the GUI is
// in the tray and limits it to the visualizer's useful cadence while it is visible.
static GUI_VISIBLE: AtomicBool = AtomicBool::new(false);
// Serialize native hide/show transitions. The minimize watcher and tray handlers run
// on different threads and must not restore and re-hide the same HWND out of order.
static WINDOW_STATE_LOCK: LazyLock<Mutex<()>> = LazyLock::new(|| Mutex::new(()));

// Shareable real-time audio statistics for GUI
static CURRENT_AMPLITUDE: AtomicU32 = AtomicU32::new(0);
static ACTIVE_DEVICE_NAME: LazyLock<RwLock<String>> = LazyLock::new(|| RwLock::new("Default Output".to_string()));
static ACTIVE_SAMPLE_RATE: LazyLock<Mutex<u32>> = LazyLock::new(|| Mutex::new(0));
// Cached primary IPv4 so the GUI thread never opens a socket per repaint frame.
static LOCAL_IP: LazyLock<RwLock<String>> = LazyLock::new(|| RwLock::new("127.0.0.1".to_string()));
// Signals the audio loop to restart capture when the default render device changes.
static DEVICE_CHANGED: AtomicBool = AtomicBool::new(false);

fn set_current_amplitude(amp: f32) {
    CURRENT_AMPLITUDE.store(amp.to_bits(), Ordering::Relaxed);
}

fn get_current_amplitude() -> f32 {
    f32::from_bits(CURRENT_AMPLITUDE.load(Ordering::Relaxed))
}

fn set_active_device_info(name: String, rate: u32) {
    if let Ok(mut lock) = ACTIVE_DEVICE_NAME.write() {
        *lock = name;
    }
    if let Ok(mut lock) = ACTIVE_SAMPLE_RATE.lock() {
        *lock = rate;
    }
}

fn get_active_device_name() -> String {
    ACTIVE_DEVICE_NAME.read().map(|l| l.clone()).unwrap_or_else(|_| "Default Output".to_string())
}

fn get_active_sample_rate() -> u32 {
    *ACTIVE_SAMPLE_RATE.lock().unwrap()
}

fn set_local_ip(ip: String) {
    if let Ok(mut lock) = LOCAL_IP.write() {
        *lock = ip;
    }
}

fn get_local_ip() -> String {
    LOCAL_IP.read().map(|l| l.clone()).unwrap_or_else(|_| "127.0.0.1".to_string())
}

// Registry Helper to check/set Startup Registry Run Key
fn is_autostart_enabled() -> bool {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    if let Ok(key) = hkcu.open_subkey_with_flags(
        r"Software\Microsoft\Windows\CurrentVersion\Run",
        KEY_READ,
    ) {
        let val: String = key.get_value("SoundMirrorSender").unwrap_or_default();
        if let Ok(exe_path) = std::env::current_exe() {
            let path_str = exe_path.to_string_lossy().to_string();
            if val == path_str || val == format!("\"{}\" --minimized", path_str) {
                return true;
            }
        }
    }
    false
}

// Add an inbound UDP firewall allow-rule for this exe so the phone's SM_CONNECT /
// SM_PING reach us. Attempted once per user (recorded in the registry) to avoid
// nagging with a UAC prompt on every launch.
fn ensure_firewall_rule() {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let key_path = r"Software\SoundMirror";
    if let Ok(key) = hkcu.open_subkey(key_path) {
        if key.get_value::<u32, _>("FirewallRuleAttempted").unwrap_or(0) == 1 {
            return;
        }
    }

    if let Ok(exe) = std::env::current_exe() {
        let params = format!(
            "advfirewall firewall add rule name=\"SoundMirror Sender\" dir=in action=allow program=\"{}\" protocol=udp enable=yes profile=any",
            exe.to_string_lossy()
        );
        let verb: Vec<u16> = "runas\0".encode_utf16().collect();
        let file: Vec<u16> = "netsh\0".encode_utf16().collect();
        let params_w: Vec<u16> = format!("{}\0", params).encode_utf16().collect();
        unsafe {
            ShellExecuteW(
                None,
                PCWSTR(verb.as_ptr()),
                PCWSTR(file.as_ptr()),
                PCWSTR(params_w.as_ptr()),
                PCWSTR::null(),
                SW_HIDE,
            );
        }
    }

    // Record the attempt regardless of whether the user accepted the UAC prompt.
    if let Ok((key, _)) = hkcu.create_subkey(key_path) {
        let _ = key.set_value("FirewallRuleAttempted", &1u32);
    }
}

fn set_autostart(enabled: bool) -> Result<(), std::io::Error> {
    let hkcu = RegKey::predef(HKEY_CURRENT_USER);
    let key = hkcu.open_subkey_with_flags(
        r"Software\Microsoft\Windows\CurrentVersion\Run",
        KEY_WRITE,
    )?;
    
    if enabled {
        if let Ok(exe_path) = std::env::current_exe() {
            let cmd = format!("\"{}\" --minimized", exe_path.to_string_lossy());
            key.set_value("SoundMirrorSender", &cmd)?;
        }
    } else {
        let _ = key.delete_value("SoundMirrorSender");
    }
    Ok(())
}

fn hostname() -> String {
    std::env::var("COMPUTERNAME").unwrap_or_else(|_| "SoundMirror-PC".to_string())
}

fn primary_ipv4() -> String {
    if let Ok(socket) = UdpSocket::bind("0.0.0.0:0") {
        if socket.connect("8.8.8.8:80").is_ok() {
            if let Ok(local_addr) = socket.local_addr() {
                return local_addr.ip().to_string();
            }
        }
    }
    "127.0.0.1".to_string()
}

fn default_render_name(device: &IMMDevice) -> String {
    unsafe {
        let Ok(props): Result<IPropertyStore, _> = device.OpenPropertyStore(STGM_READ) else {
            return "Default output".to_string();
        };
        if let Ok(mut value) = props.GetValue(&PKEY_DEVICE_FRIENDLY_NAME) {
            let value_ptr = &value as *const PROPVARIANT as *const u8;
            let vt = *(value_ptr as *const u16);
            if vt == 31 { // VT_LPWSTR
                let pwsz_ptr = *(value_ptr.add(8) as *const *mut u16);
                if !pwsz_ptr.is_null() {
                    let mut len = 0;
                    while *pwsz_ptr.add(len) != 0 {
                        len += 1;
                    }
                    let slice = std::slice::from_raw_parts(pwsz_ptr, len);
                    if let Ok(name) = String::from_utf16(slice) {
                        let _ = PropVariantClear(&mut value);
                        return name;
                    }
                }
            }
            let _ = PropVariantClear(&mut value);
        }
    }
    "Default output".to_string()
}

fn add_client(addr: SocketAddrV4, codec: u8, opus_bitrate_bps: i32) {
    let now = Instant::now();
    let codec_name = match codec {
        1 => "ADPCM",
        2 => "MULAW",
        3 => "MULAW_LITE",
        4 => "ADPCM_LITE",
        5 => "OPUS",
        6 => "FLAC",
        _ => "PCM16",
    };

    let mut clients = CLIENTS.lock().unwrap();
    let mut is_new = true;
    let mut codec_changed = false;
    let mut endpoint_changed = false;

    for client in clients.iter_mut() {
        if client.addr.ip() == addr.ip() {
            client.seen = now;
            endpoint_changed |= client.addr != addr;
            client.addr = addr;
            if client.codec != codec {
                codec_changed = true;
            }
            endpoint_changed |= client.codec != codec || client.opus_bitrate_bps != opus_bitrate_bps;
            client.codec = codec;
            client.opus_bitrate_bps = opus_bitrate_bps;
            is_new = false;
            break;
        }
    }

    if is_new {
        clients.push(Client { addr, seen: now, codec, opus_bitrate_bps });
        endpoint_changed = true;
        println!("Client [{}:{}] connected with codec: {}", addr.ip(), addr.port(), codec_name);
    } else if codec_changed {
        println!("Client [{}:{}] changed codec to: {}", addr.ip(), addr.port(), codec_name);
    }
    if endpoint_changed {
        CLIENTS_GENERATION.fetch_add(1, Ordering::Release);
    }
}

fn refresh_clients_snapshot(snapshot: &mut Vec<ClientEndpoint>) -> u32 {
    let now = Instant::now();
    let mut clients = CLIENTS.lock().unwrap();
    // Keep a client registered for 30s of silence. When the phone's screen is off,
    // Wi-Fi power save can batch/stall its keepalives for several seconds; an 8s
    // prune would then drop the client mid-stream and force a slow re-announce
    // round-trip (~10s audible gap). 30s rides through those power-save windows so
    // playback resumes the instant the radio wakes, while still dropping a client
    // that has genuinely gone away within a reasonable time.
    let old_len = clients.len();
    clients.retain(|client| now.duration_since(client.seen).as_secs() <= 30);
    if clients.len() != old_len {
        CLIENTS_GENERATION.fetch_add(1, Ordering::Release);
    }
    snapshot.clear();
    snapshot.extend(clients.iter().map(|client| ClientEndpoint {
            addr: client.addr,
            codec: client.codec,
            opus_bitrate_bps: client.opus_bitrate_bps,
        }));
    // Capture the generation while CLIENTS is still locked. If add_client() changes
    // the list immediately after this function returns, its generation increment can
    // no longer be mistaken for the snapshot we just built; the audio loop will see
    // the mismatch on its next packet instead of waiting for 1 s housekeeping.
    CLIENTS_GENERATION.load(Ordering::Acquire)
}

fn clients_snapshot() -> Vec<ClientEndpoint> {
    let mut snapshot = Vec::new();
    refresh_clients_snapshot(&mut snapshot);
    snapshot
}

fn clamp_i16(value: f32) -> i16 {
    if value > 32767.0 {
        32767
    } else if value < -32768.0 {
        -32768
    } else {
        // Round to nearest rather than truncating toward zero. Truncation adds a
        // small DC-biased quantization error on every float sample; rounding halves
        // the quantization noise floor — free fidelity for the PCM16 path.
        value.round() as i16
    }
}

fn calculate_amplitude(pcm: &[u8]) -> f32 {
    if pcm.is_empty() {
        return 0.0;
    }
    let mut sum = 0.0;
    let mut count = 0;
    for chunk in pcm.chunks_exact(2) {
        let val = i16::from_le_bytes([chunk[0], chunk[1]]) as f64;
        sum += val * val;
        count += 1;
    }
    if count == 0 {
        return 0.0;
    }
    let rms = (sum / count as f64).sqrt();
    (rms / 32768.0) as f32
}

fn convert_to_stereo_i16(data: &[u8], frames: u32, fmt: &WAVEFORMATEX, out: &mut Vec<u8>) {
    out.clear();
    out.reserve(frames as usize * 4);
    let channels = fmt.nChannels;
    let mut bits = fmt.wBitsPerSample;
    let mut tag = fmt.wFormatTag;
    
    if tag == WAVE_FORMAT_EXTENSIBLE {
        let ext = fmt as *const WAVEFORMATEX as *const WAVEFORMATEXTENSIBLE;
        unsafe {
            let sub_format = (*ext).SubFormat;
            if sub_format == KSDATAFORMAT_SUBTYPE_IEEE_FLOAT {
                tag = WAVE_FORMAT_IEEE_FLOAT;
            } else if sub_format == KSDATAFORMAT_SUBTYPE_PCM {
                tag = WAVE_FORMAT_PCM;
            }
            if (*ext).Samples.wValidBitsPerSample > 0 {
                bits = (*ext).Samples.wValidBitsPerSample;
            }
        }
    }
    
    let block_align = fmt.nBlockAlign as usize;
    let bits_per_sample = fmt.wBitsPerSample as usize;
    let bytes_per_sample = bits_per_sample / 8;
    
    for frame in 0..frames as usize {
        let mut left = 0i16;
        let mut right = 0i16;
        
        for ch in 0..2 {
            let src_ch = if channels == 1 { 0 } else { ch };
            let offset = frame * block_align + src_ch * bytes_per_sample;
            if offset + bytes_per_sample > data.len() {
                break;
            }
            let p = &data[offset..offset + bytes_per_sample];
            
            let sample = if tag == WAVE_FORMAT_IEEE_FLOAT && bits == 32 {
                let f = f32::from_le_bytes(p.try_into().unwrap_or([0; 4]));
                clamp_i16(f * 32767.0)
            } else if tag == WAVE_FORMAT_PCM && bits == 16 {
                i16::from_le_bytes(p.try_into().unwrap_or([0; 2]))
            } else if tag == WAVE_FORMAT_PCM && bits == 24 {
                let mut v = (p[0] as i32) | ((p[1] as i32) << 8) | ((p[2] as i32) << 16);
                if (v & 0x800000) != 0 {
                    v |= !0xFFFFFF;
                }
                (v >> 8) as i16
            } else if tag == WAVE_FORMAT_PCM && bits == 32 {
                let v = i32::from_le_bytes(p.try_into().unwrap_or([0; 4]));
                (v >> 16) as i16
            } else {
                0
            };
            
            if ch == 0 {
                left = sample;
            } else {
                right = sample;
            }
        }
        
        out.extend_from_slice(&left.to_le_bytes());
        out.extend_from_slice(&right.to_le_bytes());
    }
}

fn pcm_at(pcm: &[u8], frame: usize, channel: usize) -> i16 {
    let offset = frame * 4 + channel * 2;
    if offset + 1 < pcm.len() {
        i16::from_le_bytes([pcm[offset], pcm[offset + 1]])
    } else {
        0
    }
}

fn linear_to_mulaw(sample: i16) -> u8 {
    let bias = 0x84;
    let sign = (sample >> 8) & 0x80;
    let mut value = if sign != 0 { -sample } else { sample } as i32;
    if value > 32635 {
        value = 32635;
    }
    value += bias;
    let mut exponent = 7;
    let mut mask = 0x4000;
    while (value & mask) == 0 && exponent > 0 {
        exponent -= 1;
        mask >>= 1;
    }
    let mantissa = (value >> (exponent + 3)) & 0x0f;
    !((sign as u8) | ((exponent as u8) << 4) | (mantissa as u8))
}

fn encode_mulaw(pcm: &[u8]) -> Vec<u8> {
    let mut out = Vec::with_capacity(pcm.len() / 2);
    for i in (0..pcm.len()).step_by(2) {
        if i + 1 < pcm.len() {
            let sample = i16::from_le_bytes([pcm[i], pcm[i + 1]]);
            out.push(linear_to_mulaw(sample));
        }
    }
    out
}

fn encode_ima_sample(sample: i16, predictor: &mut i32, index: &mut i32) -> u8 {
    static STEP_TABLE: [i32; 89] = [
        7,8,9,10,11,12,13,14,16,17,19,21,23,25,28,31,34,37,41,45,50,55,60,66,73,80,
        88,97,107,118,130,143,157,173,190,209,230,253,279,307,337,371,408,449,494,
        544,598,658,724,796,876,963,1060,1166,1282,1411,1552,1707,1878,2066,2272,
        2499,2749,3024,3327,3660,4026,4428,4871,5358,5894,6484,7132,7845,8630,9493,
        10442,11487,12635,13899,15289,16818,18500,20350,22385,24623,27086,29794,32767
    ];
    static INDEX_TABLE: [i32; 16] = [-1,-1,-1,-1,2,4,6,8,-1,-1,-1,-1,2,4,6,8];
    
    let step = STEP_TABLE[*index as usize];
    let mut diff = sample as i32 - *predictor;
    let mut code = 0u8;
    if diff < 0 {
        code = 8;
        diff = -diff;
    }
    let mut delta = step >> 3;
    if diff >= step {
        code |= 4;
        diff -= step;
        delta += step;
    }
    if diff >= (step >> 1) {
        code |= 2;
        diff -= step >> 1;
        delta += step >> 1;
    }
    if diff >= (step >> 2) {
        code |= 1;
        delta += step >> 2;
    }
    
    *predictor += if (code & 8) != 0 { -delta } else { delta };
    if *predictor > 32767 {
        *predictor = 32767;
    }
    if *predictor < -32768 {
        *predictor = -32768;
    }
    
    *index += INDEX_TABLE[(code & 0x0f) as usize];
    if *index < 0 {
        *index = 0;
    }
    if *index > 88 {
        *index = 88;
    }
    
    code & 0x0f
}

struct AdpcmState {
    left_index: i32,
    right_index: i32,
}

// Per-client Opus encoder. Opus needs fixed frame sizes, so we accumulate the
// variable-length WASAPI buffers into 10 ms frames before encoding. Each client
// keeps its own encoder and sequence counter (the stream is stateful).
const DEFAULT_OPUS_BITRATE: i32 = 160_000;
struct OpusClientState {
    encoder: Option<OpusEncoder>,
    bitrate_bps: i32,
    accum: VecDeque<i16>,
    frame: Vec<i16>,
    frame_total_samples: usize, // samples-per-channel * 2 channels per Opus frame
    frame_per_channel: u16,     // samples per channel (also the packet's `frames`)
}

impl OpusClientState {
    fn new(sample_rate: u32, bitrate_bps: i32) -> Self {
        let encoder = SampleRate::try_from(sample_rate as i32).ok().and_then(|sr| {
            let mut enc = OpusEncoder::new(sr, Channels::Stereo, Application::Audio).ok()?;
            let _ = enc.set_bitrate(Bitrate::BitsPerSecond(bitrate_bps));
            // Complexity 7 preserves the same bitrate/FEC/10 ms framing while leaving
            // substantially more deadline headroom on the real-time capture thread.
            let _ = enc.set_complexity(7);
            let _ = enc.set_inband_fec(true);
            let _ = enc.set_packet_loss_perc(8);
            Some(enc)
        });
        let frame_per_channel = (sample_rate / 100) as u16; // 10 ms frame
        OpusClientState {
            encoder,
            bitrate_bps,
            accum: VecDeque::new(),
            frame: Vec::with_capacity(frame_per_channel as usize * 2),
            frame_total_samples: frame_per_channel as usize * 2,
            frame_per_channel,
        }
    }

    fn update_bitrate(&mut self, bitrate_bps: i32) {
        if self.bitrate_bps == bitrate_bps {
            return;
        }
        if let Some(encoder) = self.encoder.as_mut() {
            let _ = encoder.set_bitrate(Bitrate::BitsPerSecond(bitrate_bps));
        }
        self.bitrate_bps = bitrate_bps;
    }
}

fn pcm_bytes_to_i16(pcm: &[u8], out: &mut Vec<i16>) {
    out.clear();
    out.reserve(pcm.len() / 2);
    out.extend(
        pcm.chunks_exact(2)
            .map(|c| i16::from_le_bytes([c[0], c[1]])),
    );
}

// Per-client FLAC state. FLAC is lossless: identical quality to PCM16 at roughly half
// the bitrate. Like Opus we accumulate into fixed 10 ms blocks, but each block is
// encoded as a *complete, self-contained FLAC stream* (header + one frame) so a lost
// UDP packet never corrupts the others — the receiver decodes each packet on its own.
struct FlacClientState {
    accum: VecDeque<i16>,
    frame: Vec<i16>,
    frame_total_samples: usize, // samples-per-channel * 2 channels
    frame_per_channel: u16,     // samples per channel (also the packet's `frames`)
}

impl FlacClientState {
    fn new(sample_rate: u32) -> Self {
        let frame_per_channel = (sample_rate / 100) as u16; // 10 ms block
        FlacClientState {
            accum: VecDeque::new(),
            frame: Vec::with_capacity(frame_per_channel as usize * 2),
            frame_total_samples: frame_per_channel as usize * 2,
            frame_per_channel,
        }
    }
}

// FLAC encoder config, built once. The crate's default is pathological for our tiny
// 10 ms blocks: it spawns a thread pool per block (multithread=true) and uses an LPC
// order whose search path is ~50× slower, costing ~3 ms/block on the real-time capture
// thread — enough to overrun the 5 ms WASAPI buffer and crackle. Single-threaded with
// LPC order 8 encodes the same block in ~60 µs at identical compression.
static FLAC_CONFIG: LazyLock<flacenc::error::Verified<flacenc::config::Encoder>> = LazyLock::new(|| {
    use flacenc::error::Verify;
    let mut c = flacenc::config::Encoder::default();
    c.multithread = false;
    c.subframe_coding.qlpc.lpc_order = 8;
    c.into_verified().expect("flac config")
});

// Encode one interleaved stereo i16 block as a standalone FLAC stream.
fn encode_flac(frame_i16: &[i16], frames_per_channel: usize, sample_rate: u32) -> Option<Vec<u8>> {
    use flacenc::component::BitRepr;
    let s32: Vec<i32> = frame_i16.iter().map(|&x| x as i32).collect();
    let source = flacenc::source::MemSource::from_samples(&s32, 2, 16, sample_rate as usize);
    let stream = flacenc::encode_with_fixed_block_size(&FLAC_CONFIG, source, frames_per_channel).ok()?;
    let mut sink = flacenc::bitsink::ByteSink::new();
    stream.write(&mut sink).ok()?;
    Some(sink.as_slice().to_vec())
}

// Halve the sample rate for the "lite" codecs. Instead of dropping every other
// frame (pure decimation, which folds high frequencies back as audible aliasing),
// average each adjacent stereo frame pair as a cheap 2-tap low-pass before
// decimating. This noticeably reduces the metallic/harsh artefacts on lite modes.
fn downsample_half(pcm: &[u8], out: &mut Vec<u8>) {
    out.clear();
    out.reserve(pcm.len() / 2);
    for chunk in pcm.chunks_exact(8) {
        let l0 = i16::from_le_bytes([chunk[0], chunk[1]]) as i32;
        let r0 = i16::from_le_bytes([chunk[2], chunk[3]]) as i32;
        let l1 = i16::from_le_bytes([chunk[4], chunk[5]]) as i32;
        let r1 = i16::from_le_bytes([chunk[6], chunk[7]]) as i32;
        let l = ((l0 + l1) / 2) as i16;
        let r = ((r0 + r1) / 2) as i16;
        out.extend_from_slice(&l.to_le_bytes());
        out.extend_from_slice(&r.to_le_bytes());
    }
}

fn encode_adpcm(pcm: &[u8], frames: u32, state: &mut AdpcmState) -> Vec<u8> {
    let mut out = Vec::with_capacity(frames as usize + 8);
    if frames == 0 || pcm.len() < 4 {
        return out;
    }
    let mut left = pcm_at(pcm, 0, 0) as i32;
    let mut right = pcm_at(pcm, 0, 1) as i32;
    
    out.extend_from_slice(&(left as i16).to_le_bytes());
    out.push(state.left_index as u8);
    out.extend_from_slice(&(right as i16).to_le_bytes());
    out.push(state.right_index as u8);
    
    for frame in 1..frames as usize {
        let l = encode_ima_sample(pcm_at(pcm, frame, 0), &mut left, &mut state.left_index);
        let r = encode_ima_sample(pcm_at(pcm, frame, 1), &mut right, &mut state.right_index);
        out.push(l | (r << 4));
    }
    out
}

fn build_packet(seq: u32, sample_rate: u32, frames: u16, codec: u8, payload: &[u8], packet: &mut Vec<u8>) {
    packet.clear();
    packet.reserve(28 + payload.len());
    packet.extend_from_slice(b"SMA2");
    packet.push(codec);
    packet.extend_from_slice(&seq.to_be_bytes());
    packet.extend_from_slice(&sample_rate.to_be_bytes());
    packet.push(2);
    packet.extend_from_slice(&frames.to_be_bytes());
    packet.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    
    let now_ns = SystemTime::now()
        .duration_since(SystemTime::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos() as u64;
    packet.extend_from_slice(&now_ns.to_be_bytes());
    
    packet.extend_from_slice(payload);
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
struct BeaconPayload {
    name: String,
    ip: String,
    control_port: u16,
    audio_port: u16,
    sample_rate: u32,
    channels: u16,
    protocol: u32,
}

// Build the "SM_BEACON {json}" advertisement, or None until capture has a rate.
fn build_beacon_message() -> Option<String> {
    let sample_rate = get_active_sample_rate();
    if sample_rate == 0 {
        return None;
    }
    let payload = BeaconPayload {
        name: hostname(),
        ip: get_local_ip(),
        control_port: CONTROL_PORT,
        audio_port: CLIENT_AUDIO_PORT,
        sample_rate,
        channels: 2,
        protocol: 1,
    };
    serde_json::to_string(&payload)
        .ok()
        .map(|json_str| format!("SM_BEACON {}", json_str))
}

fn beacon_loop() {
    let socket = match UdpSocket::bind("0.0.0.0:0") {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Beacon socket bind failed: {}", e);
            return;
        }
    };
    if let Err(e) = socket.set_broadcast(true) {
        eprintln!("Beacon set_broadcast failed: {}", e);
        return;
    }

    let limited_broadcast = format!("255.255.255.255:{}", DISCOVERY_PORT);

    while RUNNING.load(Ordering::Relaxed) {
        // Read ip/sample rate live so the beacon stays correct across device or
        // network changes. Skip until the capture loop has reported a real rate.
        if let Some(msg) = build_beacon_message() {
            // Limited broadcast plus the /24 subnet-directed broadcast: some
            // Wi-Fi APs drop 255.255.255.255 but forward the directed form,
            // so sending both maximises the chance the phone sees us.
            let _ = socket.send_to(msg.as_bytes(), &limited_broadcast);
            if let Some(directed) = directed_broadcast_v24(&get_local_ip()) {
                let _ = socket.send_to(msg.as_bytes(), format!("{}:{}", directed, DISCOVERY_PORT));
            }
        }

        thread::sleep(Duration::from_secs(1));
    }
}

// Derive the /24 subnet-directed broadcast address from an IPv4 string,
// e.g. "192.168.31.184" -> "192.168.31.255".
fn directed_broadcast_v24(ip: &str) -> Option<String> {
    let octets: Vec<&str> = ip.split('.').collect();
    if octets.len() != 4 || octets[0] == "127" {
        return None;
    }
    if octets.iter().any(|o| o.parse::<u8>().is_err()) {
        return None;
    }
    Some(format!("{}.{}.{}.255", octets[0], octets[1], octets[2]))
}

// Polls the default render endpoint id and flags the capture loop to restart
// when the user switches their default output device (headphones, HDMI, etc).
fn device_monitor_loop() {
    unsafe {
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }
    let enumerator: IMMDeviceEnumerator = match unsafe {
        CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)
    } {
        Ok(e) => e,
        Err(e) => {
            eprintln!("Device monitor: enumerator failed: {:?}", e);
            return;
        }
    };

    let mut last_id: Option<String> = None;
    while RUNNING.load(Ordering::Relaxed) {
        if let Ok(device) = unsafe { enumerator.GetDefaultAudioEndpoint(eRender, eConsole) } {
            if let Ok(pwstr) = unsafe { device.GetId() } {
                let id = unsafe { pwstr.to_string() }.unwrap_or_default();
                unsafe {
                    windows::Win32::System::Com::CoTaskMemFree(Some(pwstr.0 as *const std::ffi::c_void));
                }
                match &last_id {
                    Some(prev) if prev != &id => {
                        DEVICE_CHANGED.store(true, Ordering::Relaxed);
                        last_id = Some(id);
                    }
                    None => last_id = Some(id),
                    _ => {}
                }
            }
        }
        thread::sleep(Duration::from_secs(1));
    }
}

fn parse_codec(text: &str) -> u8 {
    if text.contains("FLAC") {
        6
    } else if text.contains("OPUS") {
        5
    } else if text.contains("ADPCM_LITE") {
        4
    } else if text.contains("MULAW_LITE") {
        3
    } else if text.contains("ADPCM") {
        1
    } else if text.contains("MULAW") {
        2
    } else {
        0
    }
}

#[derive(serde::Deserialize)]
struct ConnectPayload {
    #[serde(rename = "opusBitrate")]
    opus_bitrate_kbps: Option<i32>,
}

fn parse_opus_bitrate(text: &str) -> i32 {
    let payload = text.strip_prefix("SM_CONNECT ")
        .and_then(|json| serde_json::from_str::<ConnectPayload>(json).ok());
    payload.and_then(|p| p.opus_bitrate_kbps)
        .unwrap_or(DEFAULT_OPUS_BITRATE / 1000).clamp(128, 160) * 1000
}

fn control_loop() {
    let socket = match UdpSocket::bind(format!("0.0.0.0:{}", CONTROL_PORT)) {
        Ok(s) => s,
        Err(e) => {
            eprintln!("Control socket bind failed: {}", e);
            return;
        }
    };
    
    if let Err(e) = socket.set_read_timeout(Some(Duration::from_millis(500))) {
        eprintln!("Control socket set_read_timeout failed: {}", e);
        return;
    }
    
    let mut buffer = [0u8; 512];
    
    while RUNNING.load(Ordering::Relaxed) {
        match socket.recv_from(&mut buffer) {
            Ok((n, from)) => {
                if let Ok(text) = std::str::from_utf8(&buffer[..n]) {
                    if text == "SM_SHOW_GUI" && from.ip().is_loopback() {
                        request_gui_show();
                    } else if text.starts_with("SM_CONNECT") {
                        let ip = from.ip();
                        let client_addr = SocketAddrV4::new(
                            match ip {
                                std::net::IpAddr::V4(ipv4) => ipv4,
                                _ => continue,
                            },
                            CLIENT_AUDIO_PORT,
                        );
                        let codec = parse_codec(text);
                        let opus_bitrate_bps = parse_opus_bitrate(text);
                        add_client(client_addr, codec, opus_bitrate_bps);
                    } else if text.starts_with("SM_PING") {
                        let pong = text.replace("SM_PING", "SM_PONG");
                        let _ = socket.send_to(pong.as_bytes(), from);
                    } else if text.starts_with("SM_DISCOVER") {
                        // Active discovery: reply with a unicast beacon to the phone's
                        // discovery port. Unicast survives Wi-Fi broadcast filtering,
                        // so the PC stays visible even when periodic beacons are dropped.
                        if let Some(msg) = build_beacon_message() {
                            let reply_addr = std::net::SocketAddr::new(from.ip(), DISCOVERY_PORT);
                            let _ = socket.send_to(msg.as_bytes(), reply_addr);
                        }
                    }
                }
            }
            Err(ref e) if e.kind() == std::io::ErrorKind::WouldBlock || e.kind() == std::io::ErrorKind::TimedOut => {
                // Timeout is normal
            }
            Err(e) => {
                eprintln!("Control recv_from error: {}", e);
            }
        }
    }
}

fn run_audio() -> Result<(), Box<dyn std::error::Error>> {
    unsafe {
        // May return S_FALSE if COM is already initialised on this thread (restart case).
        let _ = CoInitializeEx(None, COINIT_MULTITHREADED);
    }

    let enumerator: IMMDeviceEnumerator = unsafe {
        CoCreateInstance(&MMDeviceEnumerator, None, CLSCTX_ALL)?
    };
    
    let device = unsafe {
        enumerator.GetDefaultAudioEndpoint(eRender, eConsole)?
    };
    
    let device_name = default_render_name(&device);
    
    let audio_client: IAudioClient = unsafe {
        device.Activate(CLSCTX_ALL, None)?
    };
    
    let mix_format_ptr = unsafe { audio_client.GetMixFormat()? };
    let mix_format = unsafe { &*mix_format_ptr };
    
    let sample_rate = mix_format.nSamplesPerSec;
    set_active_device_info(device_name.clone(), sample_rate);
    
    let buffer_duration = BLOCK_MS as i64 * 10000;
    let capture_event = unsafe { CreateEventW(None, false, false, PCWSTR::null())? };
    
    unsafe {
        audio_client.Initialize(
            AUDCLNT_SHAREMODE_SHARED,
            AUDCLNT_STREAMFLAGS_LOOPBACK | AUDCLNT_STREAMFLAGS_EVENTCALLBACK,
            buffer_duration,
            0,
            mix_format_ptr,
            None,
        )?;
        audio_client.SetEventHandle(capture_event)?;
    }
    
    let capture_client: IAudioCaptureClient = unsafe {
        audio_client.GetService()?
    };
    
    let ip = primary_ipv4();
    set_local_ip(ip.clone());

    let out_socket = UdpSocket::bind("0.0.0.0:0")?;
    
    println!("SoundMirror Rust sender");
    println!("Primary IPv4: {}", ip);
    println!("Capture: WASAPI loopback, {}, {} Hz -> PCM16 stereo", device_name, sample_rate);
    
    unsafe {
        audio_client.Start()?;
    }
    
    // Elevate current streaming thread priority to Time Critical
    unsafe {
        let thread = GetCurrentThread();
        let _ = SetThreadPriority(thread, THREAD_PRIORITY_HIGHEST);
    }
    
    // IMA ADPCM is a stateful, sequential encoder. Each client must keep its own
    // predictor/index so two phones streaming ADPCM at once don't corrupt each
    // other's state (which previously produced loud noise).
    let mut adpcm_states: HashMap<SocketAddrV4, AdpcmState> = HashMap::new();
    let mut opus_states: HashMap<SocketAddrV4, OpusClientState> = HashMap::new();
    let mut flac_states: HashMap<SocketAddrV4, FlacClientState> = HashMap::new();
    // Per-client sequence numbers. Shared across codecs for a given client so that
    // switching codec mid-stream keeps seq monotonic (no backwards jump that the
    // receiver's jitter buffer would mistake for stale packets).
    let mut client_seqs: HashMap<SocketAddrV4, u32> = HashMap::new();
    let mut opus_out = [0u8; 4000];
    let mut pcm_data = Vec::new();
    let mut pcm_i16 = Vec::new();
    let mut lite_payload = Vec::new();
    let mut packet_scratch = Vec::with_capacity(4096);
    let mut flac_fallback_pcm = Vec::new();
    let mut active_clients = Vec::with_capacity(4);
    let mut clients_generation = refresh_clients_snapshot(&mut active_clients);
    let mut last_clients_refresh = Instant::now();
    let mut last_amplitude_update = Instant::now()
        .checked_sub(Duration::from_millis(40))
        .unwrap_or_else(Instant::now);
    // Counts consecutive capture-client errors. If the default endpoint's format
    // changes or the device is invalidated (e.g. an app starts audio at a different
    // rate, spatial sound toggles, a USB/BT device connects), the loopback client
    // dies and stops firing events — Windows keeps playing but we'd silently stall.
    // We detect this by polling GetNextPacketSize even on event timeouts, and
    // restart capture so the phone recovers in ~1s instead of cutting out.
    let mut capture_errors = 0u32;

    while RUNNING.load(Ordering::Relaxed) {
        if DEVICE_CHANGED.swap(false, Ordering::Relaxed) {
            println!("Default audio device changed — restarting capture");
            break;
        }
        let wait_result = unsafe { WaitForSingleObject(capture_event, 200) };

        unsafe {
            // Poll regardless of whether the event fired — Ok(0) means the device is
            // alive but silent; an Err means the client is dead and must be rebuilt.
            let mut packet_size = match capture_client.GetNextPacketSize() {
                Ok(size) => {
                    capture_errors = 0;
                    size
                }
                Err(e) => {
                    capture_errors += 1;
                    if capture_errors >= 5 {
                        eprintln!("Capture client invalidated ({:?}); restarting capture", e);
                        break;
                    }
                    continue;
                }
            };
            // No event and nothing pending: genuinely idle/silent, just wait again.
            if wait_result != WAIT_OBJECT_0 && packet_size == 0 {
                continue;
            }

            while packet_size > 0 {
                let mut data_ptr = std::ptr::null_mut();
                let mut frames = 0u32;
                let mut flags = 0u32;
                
                if capture_client.GetBuffer(&mut data_ptr, &mut frames, &mut flags, None, None).is_ok() {
                    // Refresh immediately on endpoint/codec changes and otherwise once
                    // per second for exact timeout pruning. In the steady state this is
                    // just an atomic load rather than a mutex acquisition per packet.
                    let current_generation = CLIENTS_GENERATION.load(Ordering::Acquire);
                    let snapshot_refreshed = current_generation != clients_generation
                        || last_clients_refresh.elapsed() >= Duration::from_secs(1);
                    if snapshot_refreshed {
                        clients_generation = refresh_clients_snapshot(&mut active_clients);
                        last_clients_refresh = Instant::now();

                        // Encoder cleanup follows client snapshot housekeeping instead
                        // of scanning every map on every 5 ms capture packet.
                        adpcm_states.retain(|addr, _| {
                            active_clients
                                .iter()
                                .any(|c| &c.addr == addr && (c.codec == 1 || c.codec == 4))
                        });
                        opus_states.retain(|addr, _| {
                            active_clients.iter().any(|c| &c.addr == addr && c.codec == 5)
                        });
                        flac_states.retain(|addr, _| {
                            active_clients.iter().any(|c| &c.addr == addr && c.codec == 6)
                        });
                        client_seqs.retain(|addr, _| active_clients.iter().any(|c| &c.addr == addr));
                    }

                    let measure_amplitude = GUI_VISIBLE.load(Ordering::Relaxed)
                        && last_amplitude_update.elapsed() >= Duration::from_millis(40);
                    let needs_pcm = !active_clients.is_empty() || measure_amplitude;
                    if needs_pcm {
                        if (flags & AUDCLNT_BUFFERFLAGS_SILENT.0 as u32) != 0 {
                            pcm_data.clear();
                            pcm_data.resize(frames as usize * 4, 0);
                        } else {
                            let data_len = frames as usize * mix_format.nBlockAlign as usize;
                            let raw_slice = std::slice::from_raw_parts(data_ptr, data_len);
                            convert_to_stereo_i16(raw_slice, frames, mix_format, &mut pcm_data);
                        }

                        if measure_amplitude {
                            set_current_amplitude(calculate_amplitude(&pcm_data));
                            last_amplitude_update = Instant::now();
                        }
                    }

                    let _ = capture_client.ReleaseBuffer(frames);

                    let clients = &active_clients;

                    if !clients.is_empty() {

                    // Precompute the half-rate payload once; all "lite" clients share it.
                    if clients.iter().any(|c| c.codec == 3 || c.codec == 4) {
                        downsample_half(&pcm_data, &mut lite_payload);
                    }

                    // Stateless / per-packet codecs (PCM16, ADPCM, MULAW + lite).
                    for client in clients {
                        if client.codec == 5 || client.codec == 6 {
                            continue; // Stateful/frame codecs are accumulated separately below.
                        }
                        let is_lite = client.codec == 3 || client.codec == 4;
                        let processed_payload: &[u8] = if is_lite { &lite_payload } else { &pcm_data };
                        let target_frames = (processed_payload.len() / 4) as u32;
                        let target_sample_rate = if is_lite { sample_rate / 2 } else { sample_rate };
                        let client_seq = client_seqs.entry(client.addr).or_insert(0);

                        match client.codec {
                            1 | 4 => {
                                let state = adpcm_states
                                    .entry(client.addr)
                                    .or_insert(AdpcmState { left_index: 0, right_index: 0 });
                                let encoded = encode_adpcm(processed_payload, target_frames, state);
                                build_packet(*client_seq, target_sample_rate, target_frames as u16, client.codec, &encoded, &mut packet_scratch);
                                let _ = out_socket.send_to(&packet_scratch, client.addr);
                                *client_seq = client_seq.wrapping_add(1);
                            }
                            2 | 3 => {
                                let encoded = encode_mulaw(processed_payload);
                                build_packet(*client_seq, target_sample_rate, target_frames as u16, client.codec, &encoded, &mut packet_scratch);
                                let _ = out_socket.send_to(&packet_scratch, client.addr);
                                *client_seq = client_seq.wrapping_add(1);
                            }
                            _ => {
                                // PCM16 is large (uncompressed). Split into sub-MTU packets so
                                // one lost IP fragment costs a single small chunk, not the whole
                                // datagram. 320 frames * 4 + 28 header = 1308 bytes (< 1500 MTU).
                                const MAX_PCM_FRAMES: usize = 320;
                                let total = target_frames as usize;
                                let mut start = 0usize;
                                while start < total {
                                    let count = (total - start).min(MAX_PCM_FRAMES);
                                    let chunk = &processed_payload[start * 4..(start + count) * 4];
                                    build_packet(*client_seq, target_sample_rate, count as u16, 0, chunk, &mut packet_scratch);
                                    let _ = out_socket.send_to(&packet_scratch, client.addr);
                                    *client_seq = client_seq.wrapping_add(1);
                                    start += count;
                                }
                            }
                        }
                    }

                    // Opus clients: accumulate into fixed 10 ms frames, encode, send.
                    let has_opus = clients.iter().any(|c| c.codec == 5);
                    let has_flac = clients.iter().any(|c| c.codec == 6);
                    // Opus and FLAC consume the same interleaved samples. Convert once
                    // into a reusable scratch vector even when both codecs are active.
                    if has_opus || has_flac {
                        pcm_bytes_to_i16(&pcm_data, &mut pcm_i16);
                    }
                    if has_opus {
                        for client in clients.iter().filter(|c| c.codec == 5) {
                            let state = opus_states
                                .entry(client.addr)
                                .or_insert_with(|| OpusClientState::new(sample_rate, client.opus_bitrate_bps));
                            state.update_bitrate(client.opus_bitrate_bps);
                            let client_seq = client_seqs.entry(client.addr).or_insert(0);
                            match state.encoder.as_ref() {
                                Some(enc) => {
                                    state.accum.extend(pcm_i16.iter().copied());
                                    while state.accum.len() >= state.frame_total_samples {
                                        state.frame.clear();
                                        state.frame.extend(state.accum.drain(0..state.frame_total_samples));
                                        if let Ok(n) = enc.encode(&state.frame, &mut opus_out) {
                                            build_packet(
                                                *client_seq,
                                                sample_rate,
                                                state.frame_per_channel,
                                                5,
                                                &opus_out[..n],
                                                &mut packet_scratch,
                                            );
                                            let _ = out_socket.send_to(&packet_scratch, client.addr);
                                            *client_seq = client_seq.wrapping_add(1);
                                        }
                                    }
                                }
                                None => {
                                    // Capture rate not supported by Opus — fall back to PCM16.
                                    build_packet(*client_seq, sample_rate, frames as u16, 0, &pcm_data, &mut packet_scratch);
                                    let _ = out_socket.send_to(&packet_scratch, client.addr);
                                    *client_seq = client_seq.wrapping_add(1);
                                }
                            }
                        }
                    }

                    // FLAC clients: accumulate into fixed 10 ms blocks, encode each as a
                    // standalone lossless stream, send. Falls back to PCM16 if a block
                    // fails to encode (so audio never drops out).
                    if has_flac {
                        for client in clients.iter().filter(|c| c.codec == 6) {
                            let state = flac_states
                                .entry(client.addr)
                                .or_insert_with(|| FlacClientState::new(sample_rate));
                            let client_seq = client_seqs.entry(client.addr).or_insert(0);
                            state.accum.extend(pcm_i16.iter().copied());
                            while state.accum.len() >= state.frame_total_samples {
                                state.frame.clear();
                                state.frame.extend(state.accum.drain(0..state.frame_total_samples));
                                match encode_flac(&state.frame, state.frame_per_channel as usize, sample_rate) {
                                    Some(encoded) => {
                                        build_packet(
                                            *client_seq,
                                            sample_rate,
                                            state.frame_per_channel,
                                            6,
                                            &encoded,
                                            &mut packet_scratch,
                                        );
                                        let _ = out_socket.send_to(&packet_scratch, client.addr);
                                    }
                                    None => {
                                        // Encode failed — send the raw block as PCM16 so
                                        // the listener hears it regardless.
                                        flac_fallback_pcm.clear();
                                        flac_fallback_pcm.reserve(state.frame.len() * 2);
                                        for s in &state.frame {
                                            flac_fallback_pcm.extend_from_slice(&s.to_le_bytes());
                                        }
                                        build_packet(
                                            *client_seq,
                                            sample_rate,
                                            state.frame_per_channel,
                                            0,
                                            &flac_fallback_pcm,
                                            &mut packet_scratch,
                                        );
                                        let _ = out_socket.send_to(&packet_scratch, client.addr);
                                    }
                                }
                                *client_seq = client_seq.wrapping_add(1);
                            }
                        }
                    }

                    }
                } else {
                    // Couldn't acquire the capture buffer — stop spinning and let the
                    // outer loop re-poll (and restart if the client is truly dead).
                    break;
                }

                let next_result = capture_client.GetNextPacketSize();
                packet_size = match next_result {
                    Ok(size) => size,
                    Err(_) => break,
                };
            }
        }
    }
    
    unsafe {
        let _ = audio_client.Stop();
        let _ = CloseHandle(capture_event);
        windows::Win32::System::Com::CoTaskMemFree(Some(mix_format_ptr as *const std::ffi::c_void));
    }

    Ok(())
}

// Native Win32 window management for reliable tray hide/show
fn native_minimize_to_tray() {
    let _window_state_guard = WINDOW_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let title: Vec<u16> = "SoundMirror PC Sender\0".encode_utf16().collect();
    unsafe {
        let hwnd = FindWindowW(PCWSTR::null(), PCWSTR(title.as_ptr()));
        if hwnd.0 != 0 {
            // Remove from taskbar (WS_EX_TOOLWINDOW style hides taskbar icon)
            let mut style = GetWindowLongW(hwnd, GWL_EXSTYLE);
            style &= !(WS_EX_APPWINDOW.0 as i32);
            style |= WS_EX_TOOLWINDOW.0 as i32;
            let _ = SetWindowLongW(hwnd, GWL_EXSTYLE, style);

            // First minimize so eframe 0.26 excludes this viewport from redraw
            // scheduling, then hide the still-iconic native window. A minimized
            // TOOLWINDOW without a taskbar button otherwise appears as a tiny title bar
            // at the bottom-left of the desktop. Keeping it iconic while hidden avoids
            // both that artifact and the invisible-window RedrawWindow busy loop.
            let _ = ShowWindow(hwnd, SW_MINIMIZE);
            let _ = ShowWindow(hwnd, SW_HIDE);
        }
    }
}

fn native_hide_if_minimized() {
    let _window_state_guard = WINDOW_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let title: Vec<u16> = "SoundMirror PC Sender\0".encode_utf16().collect();
    unsafe {
        let hwnd = FindWindowW(PCWSTR::null(), PCWSTR(title.as_ptr()));
        if hwnd.0 != 0 && IsIconic(hwnd).as_bool() {
            let mut style = GetWindowLongW(hwnd, GWL_EXSTYLE);
            style &= !(WS_EX_APPWINDOW.0 as i32);
            style |= WS_EX_TOOLWINDOW.0 as i32;
            let _ = SetWindowLongW(hwnd, GWL_EXSTYLE, style);
            GUI_VISIBLE.store(false, Ordering::Release);
            let _ = ShowWindow(hwnd, SW_HIDE);
        }
    }
}

fn native_show_window() {
    let _window_state_guard = WINDOW_STATE_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    let title: Vec<u16> = "SoundMirror PC Sender\0".encode_utf16().collect();
    unsafe {
        let hwnd = FindWindowW(PCWSTR::null(), PCWSTR(title.as_ptr()));
        if hwnd.0 != 0 {
            // Add to taskbar (WS_EX_APPWINDOW style shows taskbar icon)
            let mut style = GetWindowLongW(hwnd, GWL_EXSTYLE);
            style &= !(WS_EX_TOOLWINDOW.0 as i32);
            style |= WS_EX_APPWINDOW.0 as i32;
            let _ = SetWindowLongW(hwnd, GWL_EXSTYLE, style);

            // Restore window
            let _ = ShowWindow(hwnd, SW_RESTORE);
            let _ = SetForegroundWindow(hwnd);
        }
    }
}

fn request_gui_show() {
    SHOW_WINDOW_FLAG.store(true, Ordering::Release);
    // The native window must be visible before request_repaint(): eframe 0.26's
    // Windows runner otherwise turns an expired invisible-window deadline into a
    // RedrawWindow busy-loop.
    native_show_window();
    GUI_VISIBLE.store(true, Ordering::Release);
    if let Some(ctx) = GUI_CONTEXT.get() {
        ctx.request_repaint();
    }
}

fn hide_window_with_followup() {
    native_minimize_to_tray();
    // winit can briefly restore APPWINDOW after a minimize/close transition. Do the
    // two rare follow-up corrections on a short-lived helper instead of waking the
    // invisible eframe viewport and burning a core indefinitely.
    thread::spawn(|| {
        for delay_ms in [50, 150] {
            thread::sleep(Duration::from_millis(delay_ms));
            if GUI_VISIBLE.load(Ordering::Acquire) {
                break;
            }
            native_minimize_to_tray();
        }
    });
}

fn native_window_is_minimized() -> bool {
    let title: Vec<u16> = "SoundMirror PC Sender\0".encode_utf16().collect();
    unsafe {
        let hwnd = FindWindowW(PCWSTR::null(), PCWSTR(title.as_ptr()));
        hwnd.0 != 0 && IsIconic(hwnd).as_bool()
    }
}

// GUI Application Structure using eframe/egui
struct SoundMirrorApp {
    autostart: bool,
    show_window: bool,
    startup_hidden: bool,
}

fn setup_custom_fonts(ctx: &egui::Context) {
    let mut fonts = egui::FontDefinitions::default();
    
    // Install Korean font (Malgun Gothic) from Windows system fonts directory to fix tofu 'ㅁㅁㅁㅁ' boxes
    if let Ok(font_data) = std::fs::read(r"C:\Windows\Fonts\malgun.ttf") {
        fonts.font_data.insert(
            "malgun".to_owned(),
            egui::FontData::from_owned(font_data),
        );
        
        fonts.families.get_mut(&egui::FontFamily::Proportional).unwrap()
            .insert(0, "malgun".to_owned());
            
        fonts.families.get_mut(&egui::FontFamily::Monospace).unwrap()
            .push("malgun".to_owned());
    }
    
    ctx.set_fonts(fonts);
}

impl SoundMirrorApp {
    fn new(cc: &eframe::CreationContext<'_>) -> Self {
        let _ = GUI_CONTEXT.set(cc.egui_ctx.clone());
        setup_custom_fonts(&cc.egui_ctx);

        // Apply Premium Light Mode Styles to egui Context (Matching Android App)
        let mut visuals = egui::Visuals::light();
        visuals.widgets.noninteractive.bg_fill = egui::Color32::from_rgb(244, 247, 251); // Light blue-gray background
        visuals.widgets.inactive.bg_fill = egui::Color32::WHITE;                         // White cards
        visuals.widgets.hovered.bg_fill = egui::Color32::from_rgb(234, 243, 255);        // Light blue hover
        visuals.widgets.active.bg_fill = egui::Color32::from_rgb(0, 122, 255);           // Accent blue
        // Checkbox border visibility
        visuals.widgets.inactive.bg_stroke = egui::Stroke::new(1.5, egui::Color32::from_rgb(190, 198, 210));
        visuals.widgets.hovered.bg_stroke = egui::Stroke::new(1.5, egui::Color32::from_rgb(0, 122, 255));
        cc.egui_ctx.set_visuals(visuals);

        Self {
            autostart: is_autostart_enabled(),
            show_window: false,
            startup_hidden: false,
        }
    }
}

impl eframe::App for SoundMirrorApp {
    fn update(&mut self, ctx: &egui::Context, _frame: &mut eframe::Frame) {
        // NativeOptions starts invisible. Never schedule a repaint while invisible:
        // eframe 0.26 cannot receive RedrawRequested for that window and otherwise
        // spins NtUserRedrawWindow at one full CPU core.
        if !self.startup_hidden {
            self.startup_hidden = true;
            self.show_window = false;
            GUI_VISIBLE.store(false, Ordering::Release);
            hide_window_with_followup();
            return;
        }

        // Intercept viewport close request — hide to tray via native Win32 API
        if ctx.input(|i: &egui::InputState| i.viewport().close_requested()) {
            ctx.send_viewport_cmd(egui::ViewportCommand::CancelClose);
            self.show_window = false;
            GUI_VISIBLE.store(false, Ordering::Release);
            hide_window_with_followup();
        }

        // Treat the title-bar minimize button as "send to tray" as well.
        if self.show_window &&
            (ctx.input(|i| i.viewport().minimized == Some(true)) || native_window_is_minimized())
        {
            self.show_window = false;
            GUI_VISIBLE.store(false, Ordering::Release);
            hide_window_with_followup();
        }

        // Check if background thread requested window show (tray click / menu "open")
        if SHOW_WINDOW_FLAG.compare_exchange(true, false, Ordering::AcqRel, Ordering::Acquire).is_ok() {
            self.show_window = true;
            GUI_VISIBLE.store(true, Ordering::Release);
        }

        // Hidden mode is fully event-driven. Tray/menu handlers make the native
        // window visible first and then wake this context exactly once.
        if !self.show_window {
            return;
        }

        egui::CentralPanel::default().show(ctx, |ui: &mut egui::Ui| {
            ui.vertical_centered(|ui: &mut egui::Ui| {
                ui.add_space(8.0);
                ui.heading(egui::RichText::new("SoundMirror").strong().color(egui::Color32::from_rgb(17, 24, 39)).size(24.0));
                ui.label(egui::RichText::new("PC 사운드 무선 미러링 송신기").color(egui::Color32::from_rgb(107, 114, 128)).size(12.0));
                ui.add_space(10.0);
            });

            let amp = get_current_amplitude();
            let clients = clients_snapshot();
            let connected = !clients.is_empty();

            // 1. Orbital Visualizer Card (White box, matching Android app)
            egui::Frame::none()
                .fill(egui::Color32::WHITE)
                .rounding(8.0)
                .inner_margin(12.0)
                .show(ui, |ui: &mut egui::Ui| {
                    ui.vertical(|ui: &mut egui::Ui| {
                        ui.horizontal(|ui: &mut egui::Ui| {
                            let text = if connected { "● 연결됨" } else { "○ 대기 중" };
                            let color = if connected { egui::Color32::from_rgb(24, 160, 88) } else { egui::Color32::from_rgb(107, 114, 128) };
                            ui.label(egui::RichText::new(text).color(color).strong());
                        });
                        ui.add_space(8.0);

                        // Custom painting for Android-like Orbital Visualizer
                        let (rect, _response) = ui.allocate_exact_size(egui::vec2(ui.available_width(), 140.0), egui::Sense::hover());
                        let painter = ui.painter_at(rect);
                        let center = rect.center();

                        // Pulse calculation
                        let time = ctx.input(|i| i.time) as f32;
                        let pulse = (time * std::f32::consts::PI / 0.9).sin() * 0.5 + 0.5;
                        let ring_amp = if connected { amp } else { 0.08 + pulse * 0.04 };

                        let base_radius = 45.0;

                        // outer blue circle
                        let outer_color = egui::Color32::from_rgba_unmultiplied(0, 122, 255, if connected { 26 } else { 15 });
                        painter.circle_filled(center, base_radius + ring_amp * 28.0, outer_color);

                        // middle blue outline
                        let middle_color = egui::Color32::from_rgba_unmultiplied(0, 122, 255, if connected { 51 } else { 31 });
                        painter.circle_stroke(center, base_radius * 0.82 + ring_amp * 16.0, egui::Stroke::new(2.0, middle_color));

                        // inner white circle
                        painter.circle_filled(center, base_radius * 0.65, egui::Color32::WHITE);

                        if connected {
                            // Render 13 equalizer bars
                            let bars = 13;
                            let bar_area_width = 72.0;
                            let width = bar_area_width / (bars as f32 * 1.6);
                            let gap = width * 0.6;
                            
                            for i in 0..bars {
                                let phase = (i as f32 - 6.0).abs() / 6.0;
                                let h = (12.0 + amp * 40.0 * (1.0 - phase * 0.55)).max(6.0);
                                let x = center.x - (bar_area_width / 2.0) + i as f32 * (width + gap);
                                let y_top = center.y - h / 2.0;
                                let y_bottom = center.y + h / 2.0;
                                
                                let bar_rect = egui::Rect::from_x_y_ranges(x..=(x + width), y_top..=y_bottom);
                                painter.rect_filled(bar_rect, egui::Rounding::same(width / 2.0), egui::Color32::from_rgb(0, 122, 255));
                            }
                        } else {
                            // Render standby text
                            painter.text(
                                center - egui::vec2(0.0, 5.0),
                                egui::Align2::CENTER_CENTER,
                                "대기 중",
                                egui::FontId::proportional(14.0),
                                egui::Color32::from_rgb(17, 24, 39)
                            );
                            painter.text(
                                center + egui::vec2(0.0, 12.0),
                                egui::Align2::CENTER_CENTER,
                                "서버 검색",
                                egui::FontId::proportional(10.5),
                                egui::Color32::from_rgb(156, 163, 175)
                            );
                        }
                    });
                });
            ui.add_space(10.0);

            // 2. Loopback Capture Info Card
            egui::Frame::none()
                .fill(egui::Color32::WHITE)
                .rounding(8.0)
                .inner_margin(12.0)
                .show(ui, |ui: &mut egui::Ui| {
                    ui.vertical(|ui: &mut egui::Ui| {
                        ui.label(egui::RichText::new("루프백 캡처 상태").strong().color(egui::Color32::from_rgb(55, 65, 81)));
                        ui.add_space(6.0);
                        
                        let device_name = get_active_device_name();
                        let sample_rate = get_active_sample_rate();
                        
                        ui.horizontal(|ui: &mut egui::Ui| {
                            ui.label(egui::RichText::new("오디오 장치:").color(egui::Color32::from_rgb(122, 132, 148)));
                            ui.label(egui::RichText::new(&device_name).strong().color(egui::Color32::from_rgb(17, 24, 39)));
                        });
                        ui.horizontal(|ui: &mut egui::Ui| {
                            ui.label(egui::RichText::new("샘플 레이트:").color(egui::Color32::from_rgb(122, 132, 148)));
                            ui.label(egui::RichText::new(format!("{} Hz", sample_rate)).strong().color(egui::Color32::from_rgb(17, 24, 39)));
                        });
                        ui.horizontal(|ui: &mut egui::Ui| {
                            ui.label(egui::RichText::new("네트워크 IP:").color(egui::Color32::from_rgb(122, 132, 148)));
                            ui.label(egui::RichText::new(get_local_ip()).strong().color(egui::Color32::from_rgb(0, 122, 255)));
                        });
                    });
                });
            ui.add_space(10.0);

            // 3. Connected Mobile Clients Card
            egui::Frame::none()
                .fill(egui::Color32::WHITE)
                .rounding(8.0)
                .inner_margin(12.0)
                .show(ui, |ui: &mut egui::Ui| {
                    ui.vertical(|ui: &mut egui::Ui| {
                        ui.label(egui::RichText::new(format!("연결된 스마트폰 ({})", clients.len())).strong().color(egui::Color32::from_rgb(55, 65, 81)));
                        ui.add_space(6.0);
                        if clients.is_empty() {
                            ui.label(egui::RichText::new("대기 중... 스마트폰 앱에서 연결해 주세요.").color(egui::Color32::from_rgb(122, 132, 148)).italics());
                        } else {
                            for client in &clients {
                                let codec_name = match client.codec {
                                    1 => "ADPCM (384kbps)".to_string(),
                                    2 => "MULAW (768kbps)".to_string(),
                                    3 => "MULAW_LITE (384kbps)".to_string(),
                                    4 => "ADPCM_LITE (192kbps)".to_string(),
                                    5 => format!("OPUS ({}kbps)", client.opus_bitrate_bps / 1000),
                                    6 => "FLAC (자동 무손실)".to_string(),
                                    _ => "PCM16 (1536kbps)".to_string(),
                                };
                                ui.horizontal(|ui: &mut egui::Ui| {
                                    ui.label(egui::RichText::new("📱").size(14.0));
                                    ui.label(egui::RichText::new(client.addr.ip().to_string()).strong().color(egui::Color32::from_rgb(17, 24, 39)));
                                    ui.with_layout(egui::Layout::right_to_left(egui::Align::Center), |ui: &mut egui::Ui| {
                                        ui.label(egui::RichText::new(codec_name).color(egui::Color32::from_rgb(0, 122, 255)).strong());
                                    });
                                });
                            }
                        }
                    });
                });
            ui.add_space(10.0);

            // 4. Autostart Settings Card
            egui::Frame::none()
                .fill(egui::Color32::WHITE)
                .rounding(8.0)
                .inner_margin(12.0)
                .show(ui, |ui: &mut egui::Ui| {
                    ui.vertical(|ui: &mut egui::Ui| {
                        let prev = self.autostart;
                        ui.checkbox(&mut self.autostart, "윈도우 시작 시 자동 실행");
                        if self.autostart != prev {
                            let _ = set_autostart(self.autostart);
                        }
                        ui.add_space(4.0);
                        ui.label(egui::RichText::new("💡 창을 닫으면 완전히 꺼지지 않고 트레이로 숨겨집니다.").color(egui::Color32::from_rgb(122, 132, 148)).size(10.5));
                    });
                });
        });

        // Request continuous repaint for smooth visualizer animations
        ctx.request_repaint_after(Duration::from_millis(30));
    }
}

// Load tray icon from embedded PNG bytes
fn create_tray_icon_image() -> tray_icon::Icon {
    let bytes = include_bytes!("../icon.png");
    let img = image::load_from_memory_with_format(bytes, image::ImageFormat::Png)
        .expect("Failed to load embedded icon.png")
        .to_rgba8();
    let (width, height) = img.dimensions();
    tray_icon::Icon::from_rgba(img.into_raw(), width, height).unwrap()
}

fn load_egui_icon() -> egui::IconData {
    let bytes = include_bytes!("../icon.png");
    let img = image::load_from_memory_with_format(bytes, image::ImageFormat::Png)
        .expect("Failed to load embedded icon.png")
        .to_rgba8();
    let (width, height) = img.dimensions();
    egui::IconData {
        rgba: img.into_raw(),
        width,
        height,
    }
}

fn main() {
    // Single-instance guard: a named mutex shared across processes. If one already
    // exists, another sender is running — bring its window to the front and exit so
    // we never get two senders fighting over the control port on one PC.
    let mutex_name: Vec<u16> = "Global\\SoundMirrorSender_SingleInstance\0".encode_utf16().collect();
    let _instance_mutex = unsafe { CreateMutexW(None, true, PCWSTR(mutex_name.as_ptr())) };
    let already_running = unsafe { GetLastError() }
        .err()
        .and_then(|e| WIN32_ERROR::from_error(&e))
        .map(|code| code == ERROR_ALREADY_EXISTS)
        .unwrap_or(false);
    if already_running {
        // Ask the primary process to update its own UI state as well as the native
        // window. A direct ShowWindow here would leave its show_window flag false.
        if let Ok(socket) = UdpSocket::bind("127.0.0.1:0") {
            let _ = socket.send_to(b"SM_SHOW_GUI", ("127.0.0.1", CONTROL_PORT));
        }
        std::process::exit(0);
    }

    // Ensure the firewall lets the phone reach our control port (UAC once).
    ensure_firewall_rule();

    // Cache the primary IP up front so the GUI and beacon have it immediately.
    set_local_ip(primary_ipv4());

    // Long-lived discovery + control + device-watch threads. These persist for the
    // whole app lifetime so the capture loop below can restart freely.
    thread::spawn(beacon_loop);
    thread::spawn(control_loop);
    thread::spawn(device_monitor_loop);

    // Core audio capture loop. Restarts itself when the default output device changes.
    thread::spawn(|| {
        while RUNNING.load(Ordering::Relaxed) {
            if let Err(e) = run_audio() {
                eprintln!("Error running audio loop: {:?}", e);
                thread::sleep(Duration::from_millis(500));
            }
        }
    });

    let icon_data = load_egui_icon();

    // Build System Tray Menu on the main thread so that it is associated with the main thread's message queue.
    let tray_menu = Menu::new();
    let open_item = MenuItem::with_id("open", "열기", true, None);
    let exit_item = MenuItem::with_id("exit", "종료", true, None);
    let _ = tray_menu.append_items(&[&open_item, &exit_item]);

    let _tray_icon = TrayIconBuilder::new()
        .with_menu(Box::new(tray_menu))
        .with_tooltip("SoundMirror Sender")
        .with_icon(create_tray_icon_image())
        .build()
        .ok();

    // Spawn handlers for the events generated by this tray icon
    thread::spawn(|| {
        while let Ok(event) = TrayIconEvent::receiver().recv() {
            if let TrayIconEvent::Click { button: tray_icon::MouseButton::Left, .. } = event {
                request_gui_show();
            }
        }
    });

    thread::spawn(|| {
        while let Ok(event) = MenuEvent::receiver().recv() {
            if event.id == "open" {
                request_gui_show();
            } else if event.id == "exit" {
                RUNNING.store(false, Ordering::Relaxed);
                std::process::exit(0);
            }
        }
    });

    // eframe stops repainting as soon as Windows marks the viewport minimized, so its
    // next update is not guaranteed to run the minimize-to-tray branch above. Watch
    // only while the GUI is visible and hide the iconic native window promptly. In
    // tray mode this backs off to four cheap atomic checks per second.
    thread::spawn(|| {
        while RUNNING.load(Ordering::Relaxed) {
            if GUI_VISIBLE.load(Ordering::Acquire) {
                native_hide_if_minimized();
                thread::sleep(Duration::from_millis(25));
            } else {
                thread::sleep(Duration::from_millis(250));
            }
        }
    });

    // Run the native eframe GUI
    let options = eframe::NativeOptions {
        viewport: egui::ViewportBuilder::default()
            .with_title("SoundMirror PC Sender")
            .with_inner_size([380.0, 560.0])
            .with_resizable(false)
            .with_maximize_button(false)
            .with_visible(false)
            .with_icon(icon_data),
        ..Default::default()
    };

    let _ = eframe::run_native(
        "SoundMirror PC Sender",
        options,
        Box::new(|cc| Box::new(SoundMirrorApp::new(cc))),
    );
}
