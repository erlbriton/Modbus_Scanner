package com.intmash.modbus_scanner

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import com.intmash.modbus_scanner.ui.ConfigScreen
import com.intmash.modbus_scanner.ui.MainScreen
import com.intmash.modbus_scanner.ui.MainViewModel
import com.intmash.modbus_scanner.ui.SplashScreen
import com.intmash.modbus_scanner.ui.theme.ModbusIDTheme
import kotlinx.coroutines.delay
import java.io.File

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Log.e("USB", "usbReceiver.onReceive: action=${intent.action}")

            if (ACTION_USB_PERMISSION == intent.action) {
                val grantedFromExtra = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

                synchronized(this) {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    Log.e("USB", "device из интента: $device")

                    if (device == null) {
                        Log.e("USB", "device == null в броадкасте")
                        return
                    }

                    val actuallyGranted = usbManager.hasPermission(device)
                    Log.e("USB", "EXTRA_PERMISSION_GRANTED=$grantedFromExtra, hasPermission=$actuallyGranted")

                    Toast.makeText(context, "Разрешение: extra=$grantedFromExtra, sys=$actuallyGranted", Toast.LENGTH_LONG).show()

                    if (grantedFromExtra || actuallyGranted) {
                        Log.e("USB", "Разрешение есть — запускаю initializeAndConnect")
                        viewModel.initializeAndConnect(usbManager, device)
                    } else {
                        Log.e("USB", "Доступ отклонен пользователем (hasPermission=false)")
                    }
                }
            }
        }
    }

    private val attachReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    Log.e("USB", "ACTION_USB_DEVICE_ATTACHED: $device")
                    Toast.makeText(context, "Контроллер подключен", Toast.LENGTH_SHORT).show()
                    checkUsbDevices()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    Log.e("USB", "ACTION_USB_DEVICE_DETACHED: $device")

                    // Реагируем только на отключение НАШЕГО устройства.
                    // Если вынуть флешку из OTG-хаба — не должны терять связь с контроллером.
                    if (device != null && viewModel.isOurDevice(device)) {
                        Toast.makeText(context, "Контроллер отключен", Toast.LENGTH_SHORT).show()
                        viewModel.shutdown()
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        // RECEIVER_EXPORTED — иначе системные броадкасты (в т.ч. ответ на USB-разрешение)
        // на Android 12+/MIUI не доставляются.
        val permissionFilter = IntentFilter(ACTION_USB_PERMISSION)
        ContextCompat.registerReceiver(this, usbReceiver, permissionFilter, ContextCompat.RECEIVER_EXPORTED)

        val attachFilter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        ContextCompat.registerReceiver(this, attachReceiver, attachFilter, ContextCompat.RECEIVER_EXPORTED)

        checkUsbDevices()

        setContent {
            ModbusIDTheme {
                var showSplash by remember { mutableStateOf(true) }
                var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
                var selectedFileName by remember { mutableStateOf<String?>(null) }
                var errorMessage by remember { mutableStateOf<String?>(null) }

                val context = this
                var showFileBrowser by remember { mutableStateOf(false) }
                var currentDir by remember { mutableStateOf(Environment.getExternalStorageDirectory()) }
                var fileList by remember { mutableStateOf<List<File>>(emptyList()) }

                fun refreshFiles(dir: File) {
                    try {
                        if (dir.exists() && dir.isDirectory) {
                            fileList = dir.listFiles()?.sortedWith(
                                compareBy({ !it.isDirectory }, { it.name.lowercase() })
                            ) ?: emptyList()
                        }
                    } catch (e: Exception) {
                        Log.e("FileBrowser", "Ошибка чтения папки: ${e.message}")
                    }
                }

                LaunchedEffect(Unit) {
                    delay(3000)
                    showSplash = false
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    when {
                        showSplash -> {
                            SplashScreen(modifier = Modifier.padding(innerPadding))
                        }
                        showFileBrowser -> {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(innerPadding)
                                    .padding(16.dp)
                            ) {
                                Text(
                                    text = "Путь: ${currentDir.absolutePath}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.secondary
                                )
                                Spacer(modifier = Modifier.height(8.dp))

                                if (currentDir.parentFile != null) {
                                    Button(
                                        onClick = {
                                            currentDir.parentFile?.let {
                                                currentDir = it
                                                refreshFiles(it)
                                            }
                                        },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("📁 .. (Наверх)")
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                }

                                LazyColumn(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    items(fileList.size) { index ->
                                        val file = fileList[index]
                                        Card(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .clickable {
                                                    if (file.isDirectory) {
                                                        currentDir = file
                                                        refreshFiles(file)
                                                    } else if (file.name.endsWith(".ini", ignoreCase = true)) {
                                                        val uri = Uri.fromFile(file)
                                                        selectedFileUri = uri
                                                        selectedFileName = file.name
                                                        errorMessage = null
                                                        showFileBrowser = false
                                                        viewModel.loadIniFile(context, uri)
                                                    }
                                                },
                                            colors = CardDefaults.cardColors(
                                                containerColor = if (file.isDirectory)
                                                    MaterialTheme.colorScheme.surfaceVariant
                                                else
                                                    MaterialTheme.colorScheme.primaryContainer
                                            )
                                        ) {
                                            Text(
                                                text = if (file.isDirectory) "📁 ${file.name}" else "📄 ${file.name}",
                                                modifier = Modifier.padding(12.dp),
                                                fontSize = 14.sp
                                            )
                                        }
                                    }
                                }

                                Spacer(modifier = Modifier.height(8.dp))
                                Button(
                                    onClick = { showFileBrowser = false },
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Text("Закрыть проводник")
                                }
                            }
                        }
                        selectedFileName == null -> {
                            MainScreen(
                                deviceId = viewModel.deviceId,
                                isConnected = viewModel.isConnected,
                                isVirtualDevice = IS_VIRTUAL_DEVICE,
                                onSelectFileClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                                        !Environment.isExternalStorageManager()) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            val intent = Intent(Settings.ACTION_SETTINGS)
                                            context.startActivity(intent)
                                        }
                                    } else {
                                        currentDir = Environment.getExternalStorageDirectory()
                                        refreshFiles(currentDir)
                                        showFileBrowser = true
                                    }
                                },
                                errorMessage = errorMessage,
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                        else -> {
                            ConfigScreen(
                                deviceId = viewModel.deviceId,
                                selectedFileName = selectedFileName!!,
                                pages = viewModel.pages,
                                selectedPage = viewModel.selectedPage,
                                onRefreshIdClick = {
                                    viewModel.refreshDeviceId()
                                },
                                onPageSelected = { page ->
                                    viewModel.selectPage(page)
                                },
                                modifier = Modifier.padding(innerPadding)
                            )
                        }
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Fallback: если системный броадкаст ACTION_USB_PERMISSION не дошёл,
        // но разрешение у нас есть — подхватываем и запускаем подключение.
        if (!viewModel.isConnected) {
            val deviceList = usbManager.deviceList
            for (device in deviceList.values) {
                if (usbManager.hasPermission(device)) {
                    Log.e("USB", "onResume fallback: разрешение есть для $device")
                    viewModel.initializeAndConnect(usbManager, device)
                    break
                }
            }
        }
    }

    private fun checkUsbDevices() {
        val deviceList = usbManager.deviceList
        Log.e("USB", "checkUsbDevices(): найдено устройств — ${deviceList.size}")
        Toast.makeText(this, "Сканирование USB: найдено ${deviceList.size}", Toast.LENGTH_SHORT).show()

        if (deviceList.isEmpty()) {
            Log.e("USB", "Нет доступных USB-устройств")
            return
        }

        for (device in deviceList.values) {
            Log.e("USB", "Найдено устройство: ${device.deviceName}, VendorId: ${device.vendorId}, ProductId: ${device.productId}")

            if (usbManager.hasPermission(device)) {
                Log.e("USB", "Разрешение уже есть, инициализируем подключение")
                viewModel.initializeAndConnect(usbManager, device)
            } else {
                Log.e("USB", "Разрешения нет, запрашиваем у пользователя")

                val requestCode = (device.deviceId.hashCode() and 0xFFFF)

                val permissionIntent = PendingIntent.getBroadcast(
                    this,
                    requestCode,
                    Intent(ACTION_USB_PERMISSION),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        }
    }

    override fun onDestroy() {
        try {
            unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            Log.e("USB", "Ошибка при отписке ресивера: ${e.message}")
        }
        try {
            unregisterReceiver(attachReceiver)
        } catch (e: Exception) {
            Log.e("USB", "Ошибка при отписке attach-ресивера: ${e.message}")
        }
        viewModel.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "com.intmash.modbus_scanner.USB_PERMISSION"
        private const val IS_VIRTUAL_DEVICE = false
    }
}