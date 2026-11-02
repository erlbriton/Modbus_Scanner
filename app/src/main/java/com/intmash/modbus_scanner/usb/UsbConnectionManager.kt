package com.intmash.modbus_scanner.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat

class UsbConnectionManager(
    private val activity: ComponentActivity,
    private val usbManager: UsbManager,
    private val onDeviceReady: (UsbDevice) -> Unit,
    private val onDeviceDetached: (UsbDevice) -> Unit
) {
    private var registered = false

    private val permissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_USB_PERMISSION) return
            val grantedFromExtra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            synchronized(this) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                if (device == null) return

                val actuallyGranted = usbManager.hasPermission(device)
                if (grantedFromExtra || actuallyGranted) {
                    onDeviceReady(device)
                }
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> checkDevices()
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    if (device != null) onDeviceDetached(device)
                }
            }
        }
    }

    fun register() {
        if (registered) return
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(activity, permissionReceiver, permissionFilter, ContextCompat.RECEIVER_EXPORTED)

        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(activity, attachReceiver, attachFilter, ContextCompat.RECEIVER_EXPORTED)
        registered = true
    }

    fun unregister() {
        if (!registered) return
        try { activity.unregisterReceiver(permissionReceiver) } catch (_: Exception) {}
        try { activity.unregisterReceiver(attachReceiver) } catch (_: Exception) {}
        registered = false
    }

    fun checkDevices() {
        for (device in usbManager.deviceList.values) {
            if (usbManager.hasPermission(device)) {
                onDeviceReady(device)
            } else {
                requestPermissionFor(device)
            }
        }
    }

    fun tryFallback() {
        for (device in usbManager.deviceList.values) {
            if (usbManager.hasPermission(device)) {
                onDeviceReady(device)
                return
            }
        }
    }

    private fun requestPermissionFor(device: UsbDevice) {
        val requestCode = device.deviceId.hashCode() and 0xFFFF
        val permissionIntent = PendingIntent.getBroadcast(
            activity,
            requestCode,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.intmash.modbus_scanner.USB_PERMISSION"
    }
}