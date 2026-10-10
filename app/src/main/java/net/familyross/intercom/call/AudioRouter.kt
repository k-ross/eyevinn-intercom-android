package net.familyross.intercom.call

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Puts the phone in voice-call audio mode while a call is up and picks the
 * output: a Bluetooth headset if there is one, else a wired/USB headset, else
 * the loudspeaker (never the earpiece - this is an intercom, not a phone call).
 */
class AudioRouter(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var active = false
    private var scoStarted = false

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setOnAudioFocusChangeListener { }
        .build()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) = route()
        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) = route()
    }

    @Synchronized
    fun start() {
        if (active) return
        active = true
        audioManager.requestAudioFocus(focusRequest)
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        route()
    }

    @Synchronized
    fun stop() {
        if (!active) return
        active = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            setLegacyRoute(bluetooth = false, speaker = false)
        }
        audioManager.mode = AudioManager.MODE_NORMAL
        audioManager.abandonAudioFocusRequest(focusRequest)
    }

    @Synchronized
    private fun route() {
        if (!active) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val target = audioManager.availableCommunicationDevices.minByOrNull { priority(it.type) }
            if (target != null && audioManager.communicationDevice?.id != target.id) {
                audioManager.setCommunicationDevice(target)
            }
        } else {
            val best = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .minOfOrNull { priority(it.type) } ?: PRIORITY_SPEAKER
            setLegacyRoute(bluetooth = best == PRIORITY_BLUETOOTH, speaker = best >= PRIORITY_SPEAKER)
        }
    }

    @Suppress("DEPRECATION")
    private fun setLegacyRoute(bluetooth: Boolean, speaker: Boolean) {
        if (bluetooth && !scoStarted) {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            scoStarted = true
        } else if (!bluetooth && scoStarted) {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            scoStarted = false
        }
        audioManager.isSpeakerphoneOn = speaker
    }

    private fun priority(type: Int): Int = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
        AudioDeviceInfo.TYPE_BLE_HEADSET -> PRIORITY_BLUETOOTH
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET -> PRIORITY_WIRED
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> PRIORITY_SPEAKER
        else -> PRIORITY_OTHER
    }

    private companion object {
        const val PRIORITY_BLUETOOTH = 0
        const val PRIORITY_WIRED = 1
        const val PRIORITY_SPEAKER = 2
        const val PRIORITY_OTHER = 3
    }
}
