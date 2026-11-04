//   modbus_scanner/ui/MainViewModel.kt

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

/**
 * MainViewModel — модель представления уровня Activity.
 *
 * Хранит состояние подключения к USB-контроллеру и список страниц,
 * полученных из выбранного INI-файла. Все блокирующие операции (USB-обмен)
 * выполняются на Dispatchers.IO.
 */
class MainViewModel : ViewModel() {

    // Репозиторий открыт для чтения другим ViewModel (например, ParametersViewModel).
    // Экземпляр один на всё приложение: его создаёт MainViewModel при своём создании,
    // а ParametersViewModel берёт ссылку через viewModel.repository из AppNavHost.
    // Управление жизненным циклом (close) остаётся за MainViewModel — в его onCleared().
    val repository = ModbusRepository()

    var deviceId by mutableStateOf("Ожидание ответа...")
        private set

    var isConnected by mutableStateOf(false)
        private set

    var pages by mutableStateOf<List<PageInfo>>(emptyList())
        private set

    private var pollingJob: Job? = null
    private var isInitializing = false

    /**
     * Текущий адрес slave в сети Modbus. Обновляется при каждом
     * initializeAndConnect(...) из настроек (ConnectionSettings.getSlaveAddress).
     * Используется в startPolling при чтении ID через readDeviceId.
     */
    private var currentSlaveAddress: Int = 1

    /**
     * Инициализирует и открывает USB-порт, запускает опрос для чтения ID.
     *
     * @param baudRate Скорость UART. Берётся из настроек соединения
     *                 (ConnectionSettings.getBaudRate) — пользователь может
     *                 изменить через диалог "Настройки". Без этого параметра
     *                 порт всегда открывался бы на захардкоженных 115200.
     */
    /**
     * Инициализирует и открывает USB-порт, запускает опрос для чтения ID.
     *
     * @param baudRate Скорость UART (из настроек).
     * @param slaveAddress Адрес slave в сети Modbus (из настроек, 1..247).
     *                     Используется во всех дальнейших Modbus-запросах
     *                     (в т.ч. readDeviceId).
     */
    fun initializeAndConnect(
        usbManager: UsbManager,
        device: UsbDevice,
        baudRate: Int,
        slaveAddress: Int
    ) {
        if (isInitializing) {
            Log.e("MainViewModel", "initializeAndConnect: уже идёт инициализация, пропускаю")
            return
        }
        isInitializing = true
        // Запоминаем адрес для всех последующих Modbus-запросов в этой сессии.
        currentSlaveAddress = slaveAddress

        viewModelScope.launch(Dispatchers.IO) {
            try {
                Log.e("MainViewModel", "initializeAndConnect: устройство $device, baudRate=$baudRate, slave=$slaveAddress")
                withContext(Dispatchers.Main) { deviceId = "Инициализация порта..." }

                delay(500)

                val success = repository.openPort(usbManager, device, baudRate)
                Log.e("MainViewModel", "initializeAndConnect: openPort вернул $success")

                withContext(Dispatchers.Main) {
                    if (success) {
                        isConnected = true
                        deviceId = "Порт открыт. Чтение ID..."
                        startPolling(needReopen = false)
                    } else {
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
     * ВРЕМЕННЫЙ ТЕСТ (шаг 2). Удалить после проверки.
     *
     * Читает 6 регистров «Основных данных» (r0004..r0009) одним Modbus-запросом
     * и выводит результат в Logcat с расшифровкой.
     *
     * Ожидаемые соответствия (из INI-файла):
     *   r0004 = Uref  (FracDecScale, /10)   → В
     *   r0005 = Ilim  (IntegerScale, /1)    → А
     *   r0006 = Uout  (FracHundScale, /100) → В
     *   r0007 = Iout  (FracDecScale, /10)   → А
     *   r0008 = tRun  (IntegerScale, /1)    → min
     *   r0009 = WRun  (FracHundScale, /100) → kWh
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

                Log.e("MainViewModel", "попытка $attempt: вызываю readDeviceId(slave=$currentSlaveAddress)")
                val result = repository.readDeviceId(currentSlaveAddress)
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

    fun shutdown() {
        pollingJob?.cancel()
        pollingJob = null
        isConnected = false
        deviceId = "Ожидание ответа..."
        repository.close()
    }
}