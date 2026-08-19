package Akari.NCM.player.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbAudioManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _isExclusiveModeEnabled = MutableStateFlow(false)
    val isExclusiveModeEnabled: StateFlow<Boolean> = _isExclusiveModeEnabled.asStateFlow()

    private val _connectedUsbDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val connectedUsbDevice: StateFlow<AudioDeviceInfo?> = _connectedUsbDevice.asStateFlow()

    private val _connectedUsbDeviceName = MutableStateFlow<String?>(null)
    val connectedUsbDeviceName: StateFlow<String?> = _connectedUsbDeviceName.asStateFlow()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action
            Log.i("[AME_USB_AUDIO]", "USB Broadcast received action: $action")
            if (UsbManager.ACTION_USB_DEVICE_ATTACHED == action || UsbManager.ACTION_USB_DEVICE_DETACHED == action) {
                scanUsbAudioDevices()
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scanUsbAudioDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            scanUsbAudioDevices()
        }
    }

    init {
        scanUsbAudioDevices()
        registerListeners()
    }

    private fun registerListeners() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            context.registerReceiver(usbReceiver, filter)
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
        } catch (e: Exception) {
            Log.e("[AME_USB_AUDIO]", "Failed to register USB audio listeners: ${e.message}", e)
        }
    }

    fun scanUsbAudioDevices(): AudioDeviceInfo? {
        val outputDevices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val usbAudioDevice = outputDevices.firstOrNull { device ->
            device.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                    device.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    device.type == AudioDeviceInfo.TYPE_USB_ACCESSORY
        }

        _connectedUsbDevice.value = usbAudioDevice
        _connectedUsbDeviceName.value = usbAudioDevice?.let { getDeviceDisplayName(it) }

        Log.i("[AME_USB_AUDIO]", "Scanned USB Audio Device: name='${_connectedUsbDeviceName.value}', type=${usbAudioDevice?.type}")
        return usbAudioDevice
    }

    fun setExclusiveModeEnabled(enabled: Boolean) {
        _isExclusiveModeEnabled.value = enabled
        Log.i("[AME_USB_AUDIO]", "Exclusive USB Audio mode set to: $enabled")
    }

    fun getDeviceDisplayName(device: AudioDeviceInfo): String {
        val productName = device.productName?.toString()
        if (!productName.isNullOrBlank() && productName != "USB Audio") {
            return productName
        }
        val typeName = when (device.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET -> "USB DAC / 耳麦"
            AudioDeviceInfo.TYPE_USB_DEVICE -> "USB 音频设备"
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> "USB 外设音频"
            else -> "USB Audio"
        }
        return typeName
    }

    fun requestUsbPermission(device: UsbDevice) {
        if (!usbManager.hasPermission(device)) {
            Log.i("[AME_USB_AUDIO]", "Requesting USB permission for device: ${device.deviceName}")
        }
    }
}
