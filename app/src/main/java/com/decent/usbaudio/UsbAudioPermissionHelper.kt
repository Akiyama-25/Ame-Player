package com.decent.usbaudio

import Akari.NCM.player.data.ThemePrefs
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log

/**
 * Helper for handling USB_DEVICE_ATTACHED intents and permissions.
 */
object UsbAudioPermissionHelper {

    private const val TAG = "UsbAudioPermission"

    /**
     * Handle a USB_DEVICE_ATTACHED intent.
     * Crucial: Only claims device interfaces if USB Exclusive mode is enabled.
     * When exclusive mode is OFF, never claims interfaces to preserve system ALSA audio routing.
     */
    fun handleIntent(context: Context, intent: Intent): UsbDevice? {
        if (intent.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return null

        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        } ?: return null
        Log.i(TAG, "USB_DEVICE_ATTACHED: ${device.productName}")

        // 仅在用户明确开启 USB 独占开关时才接管设备；未开启时绝不占用接口，保证系统正常输出
        if (!ThemePrefs.usbExclusiveMode) {
            Log.i(TAG, "USB Exclusive mode is disabled. Ignoring attach event to let system ALSA driver play sound.")
            return null
        }

        val usbAudioDevice = UsbAudioDevice.getInstance(context)
        val audioDevice = usbAudioDevice.findUsbAudioDevice() ?: return null

        if (usbAudioDevice.hasPermission(audioDevice)) {
            val info = usbAudioDevice.openDevice(audioDevice)
            if (info != null) {
                Log.i(TAG, "USB audio device claimed: ${info.deviceName}")
                return audioDevice
            }
        } else {
            usbAudioDevice.requestPermission(audioDevice) { granted ->
                if (granted && ThemePrefs.usbExclusiveMode) {
                    val info = usbAudioDevice.openDevice(audioDevice)
                    Log.i(TAG, "Permission granted, device claimed: ${info?.deviceName}")
                }
            }
        }
        return null
    }
}
