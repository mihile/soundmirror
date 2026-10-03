package com.soundmirror.app

import android.media.AudioDeviceInfo

// Classify the AudioTrack's actual route, not a list of merely connected devices.
internal fun outputRouteLabel(type: Int?): String = when (type) {
    null -> "출력 확인 중"
    AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
    AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER,
    AudioDeviceInfo.TYPE_BLE_BROADCAST -> "블루투스"
    AudioDeviceInfo.TYPE_USB_DEVICE -> "USB DAC"
    AudioDeviceInfo.TYPE_USB_HEADSET -> "USB 헤드셋"
    AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 오디오"
    AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE -> "스피커"
    AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "수화부"
    AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "유선 이어폰"
    AudioDeviceInfo.TYPE_HDMI, AudioDeviceInfo.TYPE_HDMI_ARC, AudioDeviceInfo.TYPE_HDMI_EARC -> "HDMI"
    AudioDeviceInfo.TYPE_LINE_ANALOG, AudioDeviceInfo.TYPE_LINE_DIGITAL -> "외부 오디오"
    AudioDeviceInfo.TYPE_HEARING_AID -> "보청기"
    AudioDeviceInfo.TYPE_REMOTE_SUBMIX -> "원격 출력"
    else -> "기타 출력"
}
