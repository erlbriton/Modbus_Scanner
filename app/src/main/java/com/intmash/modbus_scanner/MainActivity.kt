package com.intmash.modbus_scanner

import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.intmash.modbus_scanner.navigation.AppNavHost
import com.intmash.modbus_scanner.ui.MainViewModel
import com.intmash.modbus_scanner.ui.theme.ModbusIDTheme
import com.intmash.modbus_scanner.usb.UsbConnectionManager

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var usbConnection: UsbConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Получаем ViewModel через ViewModelProvider (без делегата by viewModels())
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        // Создаём менеджер USB. Всё, что было в usbReceiver и attachReceiver,
        // теперь внутри UsbConnectionManager.
        usbConnection = UsbConnectionManager(
            activity = this,
            usbManager = usbManager,
            onDeviceReady = { device ->
                viewModel.initializeAndConnect(usbManager, device)
            },
            onDeviceDetached = { device ->
                if (viewModel.isOurDevice(device)) {
                    viewModel.shutdown()
                }
            }
        )
        usbConnection.register()
        usbConnection.checkDevices()

        setContent {
            ModbusIDTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavHost(
                        viewModel = viewModel,
                        isVirtualDevice = IS_VIRTUAL_DEVICE,
                        activityContext = this,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!viewModel.isConnected) {
            usbConnection.tryFallback()
        }
    }

    override fun onDestroy() {
        usbConnection.unregister()
        viewModel.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val IS_VIRTUAL_DEVICE = true
    }
}