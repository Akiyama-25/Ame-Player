package Akari.NCM.player.data

import Akari.NCM.player.core.AppLogger
import Akari.NCM.player.core.UsbDeviceDetails
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UsbAudioManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "AME_USB_AUDIO"
        private const val ACTION_USB_PERMISSION = "Akari.NCM.player.USB_PERMISSION"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager

    private val _isExclusiveModeEnabled = MutableStateFlow(false)
    val isExclusiveModeEnabled: StateFlow<Boolean> = _isExclusiveModeEnabled.asStateFlow()

    private val _connectedUsbDevice = MutableStateFlow<AudioDeviceInfo?>(null)
    val connectedUsbDevice: StateFlow<AudioDeviceInfo?> = _connectedUsbDevice.asStateFlow()

    private val _connectedUsbDeviceName = MutableStateFlow<String?>(null)
    val connectedUsbDeviceName: StateFlow<String?> = _connectedUsbDeviceName.asStateFlow()

    private val _uacVersion = MutableStateFlow<String?>("未连接")
    val uacVersion: StateFlow<String?> = _uacVersion.asStateFlow()

    private val _usbDeviceDetails = MutableStateFlow<UsbDeviceDetails?>(null)
    val usbDeviceDetails: StateFlow<UsbDeviceDetails?> = _usbDeviceDetails.asStateFlow()

    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action
            AppLogger.i(TAG, "USB Broadcast received action: $action")
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanUsbAudioDevices()
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    AppLogger.i(TAG, "USB permission broadcast result for device ${device?.deviceName}: granted=$granted")
                    scanUsbAudioDevices()
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.i(TAG, "AudioDeviceCallback: output device added (${addedDevices?.size ?: 0} devices)")
            scanUsbAudioDevices()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.i(TAG, "AudioDeviceCallback: output device removed (${removedDevices?.size ?: 0} devices)")
            scanUsbAudioDevices()
        }
    }

    init {
        scanUsbAudioDevices()
        registerListeners()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerListeners() {
        try {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            audioManager.registerAudioDeviceCallback(audioDeviceCallback, null)
            isReceiverRegistered = true
            AppLogger.i(TAG, "Registered USB audio broadcast and AudioDeviceCallback listeners")
        } catch (e: Exception) {
            AppLogger.e(TAG, "Failed to register USB audio listeners: ${e.message}", e)
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

        val matchingUsbDevice = findMatchingUsbDevice(usbAudioDevice)
        if (matchingUsbDevice != null) {
            val hasPerm = usbManager.hasPermission(matchingUsbDevice)
            val detectedUac = detectUacVersion(matchingUsbDevice, hasPerm)

            _uacVersion.value = detectedUac
            _usbDeviceDetails.value = UsbDeviceDetails(
                deviceName = matchingUsbDevice.deviceName,
                productName = matchingUsbDevice.productName ?: _connectedUsbDeviceName.value ?: "USB DAC",
                manufacturerName = matchingUsbDevice.manufacturerName,
                vendorId = matchingUsbDevice.vendorId,
                productId = matchingUsbDevice.productId,
                uacVersion = detectedUac,
                isExclusiveClaimed = _isExclusiveModeEnabled.value,
                hasPermission = hasPerm
            )
            AppLogger.i(TAG, "Scanned USB Audio: name='${_connectedUsbDeviceName.value}', UAC=$detectedUac, VID:PID=${_usbDeviceDetails.value?.formattedVidPid}, hasPerm=$hasPerm, isExclusive=${_isExclusiveModeEnabled.value}")
        } else if (usbAudioDevice != null) {
            _uacVersion.value = "UAC 1.0 / 2.0"
            _usbDeviceDetails.value = UsbDeviceDetails(
                deviceName = "usb_audio",
                productName = _connectedUsbDeviceName.value ?: "USB Audio",
                manufacturerName = null,
                vendorId = 0,
                productId = 0,
                uacVersion = "UAC 1.0 / 2.0",
                isExclusiveClaimed = _isExclusiveModeEnabled.value,
                hasPermission = false
            )
            AppLogger.i(TAG, "Scanned USB Audio: AudioDeviceInfo detected='${_connectedUsbDeviceName.value}', but UsbDevice not matched in deviceList")
        } else {
            _uacVersion.value = "未连接"
            _usbDeviceDetails.value = null
            AppLogger.i(TAG, "No USB audio output device connected")
        }

        return usbAudioDevice
    }

    private fun findMatchingUsbDevice(audioDeviceInfo: AudioDeviceInfo?): UsbDevice? {
        val deviceList = usbManager.deviceList.values
        if (deviceList.isEmpty()) return null

        val audioDevices = deviceList.filter { device ->
            hasAudioInterface(device)
        }
        if (audioDevices.isEmpty()) return null
        if (audioDevices.size == 1) return audioDevices.first()

        if (audioDeviceInfo != null) {
            val targetName = audioDeviceInfo.productName?.toString()?.trim()
            if (!targetName.isNullOrBlank()) {
                val matched = audioDevices.firstOrNull {
                    it.productName?.trim().equals(targetName, ignoreCase = true)
                }
                if (matched != null) return matched
            }
        }

        return audioDevices.firstOrNull()
    }

    private fun hasAudioInterface(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                return true
            }
        }
        return false
    }

    /**
     * UAC 1.0 / 2.0 / 3.0 detection based on USB Audio Class specification:
     * - UAC 1.0: Interface protocol = 0x00 (IP_VERSION_00_10)
     * - UAC 2.0: Interface protocol = 0x20 (IP_VERSION_02_00)
     * - UAC 3.0: Interface protocol = 0x30 (IP_VERSION_03_00)
     *
     * If USB device permission is available, safely inspects raw descriptors (without claiming
     * interfaces or detaching the Linux kernel snd-usb-audio driver) to confirm bcdADC.
     */
    fun detectUacVersion(usbDevice: UsbDevice, hasPerm: Boolean): String {
        var hasUac2 = false
        var hasUac3 = false
        var hasUac1 = false

        for (i in 0 until usbDevice.interfaceCount) {
            val intf = usbDevice.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                when (intf.interfaceProtocol) {
                    0x20 -> hasUac2 = true
                    0x30 -> hasUac3 = true
                    0x00 -> hasUac1 = true
                }
            }
        }

        if (hasPerm) {
            var conn: UsbDeviceConnection? = null
            try {
                conn = usbManager.openDevice(usbDevice)
                if (conn != null) {
                    val raw = conn.rawDescriptors
                    if (raw != null && raw.size > 5) {
                        var idx = 0
                        while (idx < raw.size - 4) {
                            val len = raw[idx].toInt() and 0xFF
                            if (len < 2 || idx + len > raw.size) break
                            val type = raw[idx + 1].toInt() and 0xFF
                            // CS_INTERFACE = 0x24, HEADER = 0x01
                            if (type == 0x24 && raw[idx + 2].toInt() and 0xFF == 0x01) {
                                val bcdL = raw[idx + 3].toInt() and 0xFF
                                val bcdH = raw[idx + 4].toInt() and 0xFF
                                if (bcdH == 0x02 && bcdL == 0x00) return "UAC 2.0"
                                if (bcdH == 0x01 && bcdL == 0x00) return "UAC 1.0"
                                if (bcdH == 0x03) return "UAC 3.0"
                            }
                            idx += len
                        }
                    }
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Non-fatal: could not read raw descriptors: ${e.message}")
            } finally {
                try {
                    conn?.close()
                } catch (ignored: Exception) {}
            }
        }

        return when {
            hasUac2 -> "UAC 2.0"
            hasUac3 -> "UAC 3.0"
            hasUac1 -> "UAC 1.0"
            else -> "UAC 1.0 / 2.0"
        }
    }

    fun setExclusiveModeEnabled(enabled: Boolean) {
        _isExclusiveModeEnabled.value = enabled
        AppLogger.i(TAG, "Exclusive USB Audio mode toggled: enabled=$enabled")
        val currentDetails = _usbDeviceDetails.value
        if (currentDetails != null) {
            _usbDeviceDetails.value = currentDetails.copy(isExclusiveClaimed = enabled)
        }
        if (enabled) {
            val matchingUsb = findMatchingUsbDevice(_connectedUsbDevice.value)
            if (matchingUsb != null && !usbManager.hasPermission(matchingUsb)) {
                requestUsbPermission(matchingUsb)
            }
        }
    }

    fun requestUsbPermission(device: UsbDevice) {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val intent = Intent(ACTION_USB_PERMISSION).apply {
            setPackage(context.packageName)
        }
        val permissionIntent = PendingIntent.getBroadcast(context, 0, intent, flags)
        AppLogger.i(TAG, "Requesting hardware USB permission for: ${device.productName ?: device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
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
}