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

    // --- Состояние поиска устройств в сети Modbus ---

    /**
     * Состояние поиска устройств:
     *  - [SearchState.Idle]      — поиск не запущен;
     *  - [SearchState.Running]   — идёт перебор адресов;
     *  - [SearchState.Completed] — поиск завершён (все адреса проверены
     *                              или пользователь остановил вручную).
     */
    var searchState by mutableStateOf(SearchState.Idle)
        private set

    /**
     * Найденные устройства за текущий (или последний завершённый) поиск.
     * Список очищается при старте нового поиска.
     */
    var foundDevices by mutableStateOf<List<FoundDevice>>(emptyList())
        private set

    /**
     * Адрес, который сейчас проверяется. null — поиск не идёт.
     * UI показывает "Проверяю адрес X..." во время Running.
     */
    var currentSearchAddress by mutableStateOf<Int?>(null)
        private set

    /** Активная корутина поиска. null, если поиск не идёт. */
    private var searchJob: Job? = null

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

    /**
     * Запускает поиск устройств в сети Modbus: перебор адресов 1..247
     * с заданным таймаутом на каждый запрос.
     *
     * Пока идёт поиск, основной polling (чтение ID) останавливается —
     * они используют один USB-порт и не должны пересекаться.
     *
     * @param timeoutMs Таймаут на каждый адрес. 300 мс по умолчанию —
     *                  компромисс между скоростью перебора (≈74 сек на все
     *                  247 адресов) и надёжностью обнаружения на 115200.
     *                  Для других скоростей может понадобиться больше.
     */
    fun startSearch(timeoutMs: Int) {
        // Если поиск уже идёт — не запускаем второй.
        if (searchJob?.isActive == true) {
            Log.e("MainViewModel", "startSearch: поиск уже идёт, пропускаю")
            return
        }

        // Останавливаем основной polling, чтобы не было конфликта за порт.
        pollingJob?.cancel()
        pollingJob = null

        // Сбрасываем предыдущие результаты.
        foundDevices = emptyList()
        searchState = SearchState.Running
        currentSearchAddress = null

        searchJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val found = mutableListOf<FoundDevice>()
                // Адрес 0 — broadcast, 248..255 зарезервированы. Стандарт: 1..247.
                for (address in 1..247) {
                    if (!isActive) break

                    withContext(Dispatchers.Main) {
                        currentSearchAddress = address
                    }

                    val id = repository.readDeviceId(
                        slaveAddr = address,
                        timeoutMs = timeoutMs.toLong()
                    )

                    if (id != null) {
                        Log.e("MainViewModel", "Найдено устройство: адрес=$address, id='$id'")
                        found.add(FoundDevice(address = address, deviceId = id))
                        // Обновляем UI по мере нахождения — пользователь видит
                        // результаты не только после завершения, но и в процессе.
                        withContext(Dispatchers.Main) {
                            foundDevices = found.toList()
                        }
                    }
                }

                withContext(Dispatchers.Main) {
                    searchState = SearchState.Completed
                    currentSearchAddress = null
                }
                Log.e("MainViewModel", "Поиск завершён. Найдено устройств: ${found.size}")

            } catch (e: kotlinx.coroutines.CancellationException) {
                // Отмена — пользователь нажал "Стоп" или экран закрылся.
                // Переводим в Completed (поиск прекращён), сохраняем уже найденные.
                withContext(Dispatchers.Main) {
                    searchState = SearchState.Completed
                    currentSearchAddress = null
                }
                Log.e("MainViewModel", "Поиск отменён. Найдено: ${foundDevices.size}")
                throw e
            } catch (e: Exception) {
                Log.e("MainViewModel", "Ошибка во время поиска: ${e.message}")
                withContext(Dispatchers.Main) {
                    searchState = SearchState.Completed
                    currentSearchAddress = null
                }
            } finally {
                // Возобновляем штатный polling на СТАРОМ адресе,
                // если поиск завершился и никакое устройство не было выбрано.
                // Если пользователь выбрал адрес — applyFoundAddress() сам
                // сделает initializeAndConnect, и polling стартует оттуда.
                //
                // Здесь этого не делаем, чтобы не перебить выбор пользователя.
                // Возобновление делается явно в stopSearch() / applyFoundAddress().
            }
        }
    }

    /**
     * Останавливает активный поиск (пользователь нажал "Стоп").
     * Уже найденные устройства сохраняются в [foundDevices].
     * После остановки возобновляется штатный polling на текущем адресе.
     */
    fun stopSearch() {
        if (searchJob?.isActive == true) {
            searchJob?.cancel()
            searchJob = null
            Log.e("MainViewModel", "stopSearch: поиск отменён пользователем")
        }
        // Если поиск шёл и прервался — polling остановлен, надо вернуть.
        // Но только если сейчас нет поиска и нет подключения к другому адресу.
        if (isConnected) {
            startPolling(needReopen = false)
        }
    }

    /**
     * Применяет адрес найденного устройства: переключает всё приложение
     * на работу с контроллером по этому адресу.
     *
     * Вызывается, когда пользователь тапнул по устройству в результатах поиска.
     * Останавливает поиск (если шёл), обновляет [currentSlaveAddress],
     * переподключается к контроллеру через [initializeAndConnect].
     *
     * @param address Новый адрес slave.
     * @param usbManager Для переподключения.
     * @param device     Для переподключения.
     * @param baudRate   Текущая скорость (не меняется поиском).
     */
    fun applyFoundAddress(
        address: Int,
        usbManager: UsbManager,
        device: UsbDevice,
        baudRate: Int
    ) {
        // Останавливаем поиск, если шёл.
        searchJob?.cancel()
        searchJob = null
        searchState = SearchState.Idle
        currentSearchAddress = null

        // Переподключаемся на выбранный адрес.
        // shutdown() остановит polling (если шёл) и закроет порт.
        shutdown()
        initializeAndConnect(
            usbManager = usbManager,
            device = device,
            baudRate = baudRate,
            slaveAddress = address
        )
    }

    fun shutdown() {
        pollingJob?.cancel()
        pollingJob = null
        searchJob?.cancel()
        searchJob = null
        searchState = SearchState.Idle
        currentSearchAddress = null
        isConnected = false
        deviceId = "Ожидание ответа..."
        repository.close()
    }
}

/**
 * Состояние поиска устройств в сети Modbus.
 */
enum class SearchState {
    /** Поиск не запущен или завершён и закрыт. */
    Idle,

    /** Идёт перебор адресов. */
    Running,

    /** Поиск завершён — все адреса проверены или пользователь нажал "Стоп". */
    Completed
}

/**
 * Найденное устройство в сети Modbus.
 *
 * @property address Адрес slave (1..247), на котором устройство ответило.
 * @property deviceId Строка идентификатора, полученная от устройства
 *                    (например, "00000056 DExS.SMFCB v1.10.6.1...").
 */
data class FoundDevice(
    val address: Int,
    val deviceId: String
)