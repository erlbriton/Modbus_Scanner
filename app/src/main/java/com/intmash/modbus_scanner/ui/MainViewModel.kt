package com.intmash.modbus_scanner.ui

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intmash.modbus_scanner.data.ModbusRepository
import com.intmash.modbus_scanner.data.PageInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel : ViewModel() {

    private val repository = ModbusRepository()

    var deviceId by mutableStateOf("Ожидание ответа...")
        private set

    var isConnected by mutableStateOf(false)
        private set

    var pages by mutableStateOf<List<PageInfo>>(emptyList())
        private set

    var selectedPage by mutableStateOf<PageInfo?>(null)
        private set

    private var pollingJob: Job? = null

    // Защита от параллельных initializeAndConnect (attachReceiver + onResume могут сработать одновременно)
    private var isInitializing = false

    fun initializeAndConnect(usbManager: UsbManager, device: UsbDevice) {
        if (isInitializing) {
            Log.e("MainViewModel", "initializeAndConnect: уже идёт инициализация, пропускаю")
            return
        }
        isInitializing = true

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.e("MainViewModel", "initializeAndConnect: устройство $device")
                withContext(Dispatchers.Main) { deviceId = "Инициализация порта..." }

                delay(500)

                val success = repository.openPort(usbManager, device)
                Log.e("MainViewModel", "initializeAndConnect: openPort вернул $success")

                withContext(Dispatchers.Main) {
                    if (success) {
                        isConnected = true
                        deviceId = "Порт открыт. Чтение ID..."
                        startPolling(needReopen = false)
                    } else {
                        // Явный сброс, чтобы fallback в onResume сработал при следующей попытке
                        isConnected = false
                        deviceId = "Ошибка openPort (см. лог)"
                    }
                }
            } finally {
                isInitializing = false
            }
        }
    }

    /**
     * Проверяет, относится ли устройство к тому, с которым мы сейчас работаем.
     * Нужно, чтобы attachReceiver реагировал только на отключение НАШЕГО устройства,
     * а не любого USB в системе.
     */
    fun isOurDevice(device: UsbDevice): Boolean {
        return repository.getCurrentDeviceName() == device.deviceName
    }

    fun refreshDeviceId() {
        deviceId = "Обновление ID..."
        startPolling(needReopen = true)
    }

    fun loadIniFile(context: android.content.Context, uri: Uri) {
        val parser = com.intmash.modbus_scanner.data.IniParser()
        pages = parser.parsePages(context, uri)
        if (pages.isNotEmpty()) {
            selectedPage = pages.first()
        }
    }

    fun selectPage(page: PageInfo) {
        selectedPage = page
    }

    private fun startPolling(needReopen: Boolean) {
        pollingJob?.cancel()

        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            val maxAttempts = 5
            var reopenNext = needReopen

            for (attempt in 1..maxAttempts) {
                if (!isActive) break

                if (reopenNext) {
                    val reopened = repository.reopen()
                    Log.e("MainViewModel", "reopen() перед попыткой $attempt: $reopened")
                    if (!reopened) {
                        delay(500)
                        continue
                    }
                }
                reopenNext = false

                Log.e("MainViewModel", "попытка $attempt: вызываю readDeviceId()")
                val result = repository.readDeviceId()
                Log.e("MainViewModel", "попытка $attempt: readDeviceId вернул '$result'")

                if (result != null) {
                    withContext(Dispatchers.Main) { deviceId = result }
                    return@launch
                }

                reopenNext = true

                if (attempt == maxAttempts) {
                    withContext(Dispatchers.Main) {
                        deviceId = "Не удалось прочитать ID"
                    }
                    return@launch
                }

                delay(500)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pollingJob?.cancel()
        repository.close()
    }

    /**
     * Полное завершение работы: сброс состояния и закрытие порта.
     * ВАЖНО: сбрасываем isConnected — иначе onResume fallback не сработает
     * при повторном подключении контроллера.
     */
    fun shutdown() {
        pollingJob?.cancel()
        pollingJob = null
        isConnected = false
        deviceId = "Ожидание ответа..."
        repository.close()
    }
}