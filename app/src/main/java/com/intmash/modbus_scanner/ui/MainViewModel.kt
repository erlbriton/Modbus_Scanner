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
import com.intmash.modbus_scanner.data.ModbusParameter
import com.intmash.modbus_scanner.data.ModbusRepository
import com.intmash.modbus_scanner.data.ModbusType
import com.intmash.modbus_scanner.data.PageInfo
import com.intmash.modbus_scanner.data.ParameterInfo
import com.intmash.modbus_scanner.data.RegisterAddress
import com.intmash.modbus_scanner.data.ValueDecoder
import com.intmash.modbus_scanner.data.ValueScale
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

                // ВРЕМЕННЫЙ ТЕСТ (шаг 2, удалить после проверки).
                // Проверяем Modbus-запрос 0x03: читаем 6 регистров страницы
                // «Основные данные» (r0004..r0009, Uref..WRun).
                if (success) {
                    testReadMainRegisters()
                }

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
    /**
     * ВРЕМЕННЫЙ ТЕСТ (шаг 3, удалить после проверки).
     *
     * Проверяет, что ValueDecoder правильно превращает сырые регистры
     * в "человеческие" значения с единицами измерения.
     *
     * Читает 6 регистров «Основных данных» (r0004..r0009), конструирует
     * для них 6 ModbusParameter с реальными типами и масштабами
     * (как в INI-файле), декодирует и выводит в Logcat.
     *
     * Ожидаемый лог:
     *   Uref = 219.9 V
     *   Ilim = 100 A
     *   Uout = 219.80 V
     *   Iout = 5.0 A
     *   tRun = 15234 min
     *   WRun = 145.32 kWh
     */
    private fun testReadMainRegisters() {
        val registers = repository.readHoldingRegisters(
            slaveAddr = 1,
            startRegister = 0x0004,
            count = 6
        )

        if (registers == null) {
            Log.e("PARAM_TEST", "readHoldingRegisters вернул null — нет ответа или ошибка")
            return
        }

        Log.e("PARAM_TEST", "=== Прочитано ${registers.size} регистров (r0004..r0009) ===")

        // Конструируем тестовые ModbusParameter вручную, повторяя то, что есть в INI.
        // В реальном приложении они будут получаться из IniParser → ModbusParameterParser,
        // но для изолированного теста проще задать руками.
        val testParams = listOf(
            makeTestParam("Uref", "V",   ValueScale.FracDecScale,  0x0004, 0),
            makeTestParam("Ilim", "A",   ValueScale.IntegerScale,  0x0005, 1),
            makeTestParam("Uout", "V",   ValueScale.FracHundScale, 0x0006, 2),
            makeTestParam("Iout", "A",   ValueScale.FracDecScale,  0x0007, 3),
            makeTestParam("tRun", "min", ValueScale.IntegerScale,  0x0008, 4),
            makeTestParam("WRun", "kWh", ValueScale.FracHundScale, 0x0009, 5),
        )

        Log.e("PARAM_TEST", "--- Через ValueDecoder ---")
        testParams.forEach { param ->
            val decoded = ValueDecoder.decode(param, registers, param.address.number - 0x0004)
            val display = decoded?.display ?: "?"
            Log.e("PARAM_TEST", "  ${param.name} = $display ${param.unit}".trimEnd())
        }
    }

    /**
     * ВРЕМЕННЫЙ ХЕЛПЕР (шаг 3, удалить после проверки).
     *
     * Создаёт ModbusParameter для теста. Игнорирует поля, которые
     * понадобятся только при реальной записи (описание, source, pRmlist).
     */
    private fun makeTestParam(
        name: String,
        unit: String,
        scale: ValueScale,
        registerAddr: Int,
        regOffset: Int
    ): ModbusParameter {
        val fakeSource = ParameterInfo(
            key = "test_$name",
            name = name,
            description = "",
            type = "TWord",
            address = "",
            register = "r%04X".format(registerAddr),
            unit = unit,
            rawFields = emptyList()
        )
        return ModbusParameter(
            key = "test_$name",
            name = name,
            description = "",
            unit = unit,
            type = ModbusType.TWord,
            scale = scale,
            address = RegisterAddress.Register(registerAddr),
            registerCount = 1,
            pRmlist = null,
            source = fakeSource
        )
    }

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

    fun shutdown() {
        pollingJob?.cancel()
        pollingJob = null
        isConnected = false
        deviceId = "Ожидание ответа..."
        repository.close()
    }
}