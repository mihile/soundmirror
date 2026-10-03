package com.soundmirror.app

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class OutputRouteLabelTest {
    @Test fun distinguishesBluetoothUsbAndBuiltInOutputs() {
        for (type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_BLE_BROADCAST)) {
            assertEquals("블루투스", outputRouteLabel(type))
        }
        assertEquals("USB DAC", outputRouteLabel(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals("USB 헤드셋", outputRouteLabel(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals("스피커", outputRouteLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))
        assertEquals("스피커", outputRouteLabel(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE))
        assertEquals("수화부", outputRouteLabel(AudioDeviceInfo.TYPE_BUILTIN_EARPIECE))
    }

    @Test fun neverAssumesUnknownRouteIsSpeakerOrBluetooth() {
        assertEquals("출력 확인 중", outputRouteLabel(null))
        assertEquals("기타 출력", outputRouteLabel(AudioDeviceInfo.TYPE_UNKNOWN))
        assertEquals("유선 이어폰", outputRouteLabel(AudioDeviceInfo.TYPE_WIRED_HEADPHONES))
        assertEquals("HDMI", outputRouteLabel(AudioDeviceInfo.TYPE_HDMI_EARC))
    }
}
