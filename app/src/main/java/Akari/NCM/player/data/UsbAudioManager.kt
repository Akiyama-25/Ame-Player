package Akari.NCM.player.data

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
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
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
        private const val TAG = "[AME_USB_AUDIO]"
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

    private var activeUsbConnection: UsbDeviceConnection? = null
    private val claimedInterfaces = mutableListOf<UsbInterface>()
    private var isReceiverRegistered = false

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val action = intent?.action
            Log.i(TAG, "USB Broadcast received action: $action")
            when (action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED, UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    scanUsbAudioDevices()
                    if (_isExclusiveModeEnabled.value) {
                        tryClaimExclusiveUsb()
                    }
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    Log.i(TAG, "USB permission result for device ${device?.deviceName}: granted=$granted")
                    if (granted && device != null && _isExclusiveModeEnabled.value) {
                        claimDeviceInterfaces(device)
                    }
                }
            }
        }
    }

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            scanUsbAudioDevices()
            if (_isExclusiveModeEnabled.value) {
                tryClaimExclusiveUsb()
            }
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register USB audio listeners: ${e.message}", e)
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
            val detectedUac = detectUacVersion(matchingUsbDevice)
            val hasPerm = usbManager.hasPermission(matchingUsbDevice)
            val isClaimed = activeUsbConnection != null && claimedInterfaces.isNotEmpty()

            _uacVersion.value = detectedUac
            _usbDeviceDetails.value = UsbDeviceDetails(
                deviceName = matchingUsbDevice.deviceName,
                productName = matchingUsbDevice.productName ?: _connectedUsbDeviceName.value ?: "USB DAC",
                manufacturerName = matchingUsbDevice.manufacturerName,
                vendorId = matchingUsbDevice.vendorId,
                productId = matchingUsbDevice.productId,
                uacVersion = detectedUac,
                isExclusiveClaimed = isClaimed,
                hasPermission = hasPerm
            )
            Log.i(TAG, "Scanned USB Audio: name='${_connectedUsbDeviceName.value}', UAC=$detectedUac, VID:PID=${_usbDeviceDetails.value?.formattedVidPid}")
        } else if (usbAudioDevice != null) {
            _uacVersion.value = "UAC 1.0 / 2.0"
            _usbDeviceDetails.value = UsbDeviceDetails(
                deviceName = "usb_audio",
                productName = _connectedUsbDeviceName.value ?: "USB Audio",
                manufacturerName = null,
                vendorId = 0,
                productId = 0,
                uacVersion = "UAC 1.0 / 2.0",
                isExclusiveClaimed = false,
                hasPermission = false
            )
        } else {
            releaseClaimedUsb()
            _uacVersion.value = "未连接"
            _usbDeviceDetails.value = null
        }

        return usbAudioDevice
    }

    private fun findMatchingUsbDevice(audioDeviceInfo: AudioDeviceInfo?): UsbDevice? {
        val deviceList = usbManager.deviceList.values
        if (deviceList.isEmpty()) return null

        // 1. Try to find device with Audio interface
        val audioDevices = deviceList.filter { device ->
            hasAudioInterface(device)
        }
        if (audioDevices.isEmpty()) return null

        // 2. If single audio device found, return it
        if (audioDevices.size == 1) return audioDevices.first()

        // 3. Match by product name if multiple USB devices present
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
     */
    fun detectUacVersion(usbDevice: UsbDevice): String {
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

        // Also inspect raw descriptor header if connection is available
        val conn = activeUsbConnection
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
                    }
                    idx += len
                }
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
        Log.i(TAG, "Exclusive USB Audio mode toggled: enabled=$enabled")
        if (enabled) {
            tryClaimExclusiveUsb()
        } else {
            releaseClaimedUsb()
        }
    }

    private fun tryClaimExclusiveUsb() {
        val matchingUsb = findMatchingUsbDevice(_connectedUsbDevice.value) ?: return
        if (!usbManager.hasPermission(matchingUsb)) {
            requestUsbPermission(matchingUsb)
        } else {
            claimDeviceInterfaces(matchingUsb)
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
        Log.i(TAG, "Requesting hardware USB permission for: ${device.productName ?: device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun claimDeviceInterfaces(device: UsbDevice) {
        try {
            if (activeUsbConnection == null) {
                activeUsbConnection = usbManager.openDevice(device)
            }
            val conn = activeUsbConnection ?: return
            claimedInterfaces.clear()

            for (i in 0 until device.interfaceCount) {
                val intf = device.getInterface(i)
                if (intf.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                    val claimed = conn.claimInterface(intf, true)
                    if (claimed) {
                        claimedInterfaces.add(intf)
                        Log.i(TAG, "Claimed USB audio interface #$i (subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol})")
                    }
                }
            }

            val currentDetails = _usbDeviceDetails.value
            if (currentDetails != null) {
                _usbDeviceDetails.value = currentDetails.copy(
                    isExclusiveClaimed = claimedInterfaces.isNotEmpty(),
                    hasPermission = true
                )
            }
            Log.i(TAG, "Hardware exclusive claim completed: ${claimedInterfaces.size} interfaces locked.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to claim USB audio interface: ${e.message}", e)
        }
    }

    private fun releaseClaimedUsb() {
        try {
            val conn = activeUsbConnection
            if (conn != null) {
                for (intf in claimedInterfaces) {
                    runCatching { conn.releaseInterface(intf) }
                }
                runCatching { conn.close() }
            }
            activeUsbConnection = null
            claimedInterfaces.clear()

            val currentDetails = _usbDeviceDetails.value
            if (currentDetails != null) {
                _usbDeviceDetails.value = currentDetails.copy(isExclusiveClaimed = false)
            }
            Log.i(TAG, "Released all claimed USB audio interfaces.")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing USB interfaces: ${e.message}", e)
        }
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