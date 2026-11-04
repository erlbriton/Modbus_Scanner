// app/src/main/java/com/intmash/modbus_scanner/ui/ParametersViewModel.kt
package com.intmash.modbus_scanner.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intmash.modbus_scanner.data.BytePart
import com.intmash.modbus_scanner.data.DecodedValue
import com.intmash.modbus_scanner.data.EncodePlan
import com.intmash.modbus_scanner.data.ModbusParameter
import com.intmash.modbus_scanner.data.ModbusRepository
import com.intmash.modbus_scanner.data.PageInfo
import com.intmash.modbus_scanner.data.ValueDecoder
import com.intmash.modbus_scanner.data.ValueEncoder
import com.intmash.modbus_scanner.data.toModbusParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ParametersViewModel — модель представления экрана параметров.
 *
 * Отвечает за:
 *  - фоновый опрос параметров страницы через Modbus;
 *  - хранение последних прочитанных значений ([values]) для отображения в UI;
 *  - отслеживание состояния связи ([pollingState]);
 *  - запись одного параметра в контроллер ([writeParameter]).
 *
 * НЕ отвечает за:
 *  - открытие/закрытие USB-порта (это делает [MainViewModel] через
 *    [ModbusRepository.openPort]). К моменту создания этой VM порт уже открыт.
 *  - UI — это делает [ParametersScreen].
 *
 * Один экземпляр на один экран ParametersScreen (один маршрут навигации).
 * При переходе на другую страницу создаётся новый экземпляр с другим [page].
 *
 * @param repository Общий экземпляр [ModbusRepository] (создан в [MainViewModel],
 *                   передан через AppNavHost). Порт уже должен быть открыт.
 * @param page Страница, параметры которой опрашиваются.
 */
class ParametersViewModel(
    private val repository: ModbusRepository,
    val page: PageInfo,
    /**
     * Адрес slave в сети Modbus (1..247). Передаётся из настроек соединения
     * через [ParametersViewModelFactory] → [AppNavHost]. Используется во всех
     * Modbus-запросах опроса и записи.
     * Раньше был константой SLAVE_ADDRESS = 1 в companion object.
     */
    private val slaveAddress: Int
) : ViewModel() {

    // =========================================================================
    // ПУБЛИЧНОЕ СОСТОЯНИЕ (для UI)
    // =========================================================================

    /**
     * Последние прочитанные значения параметров, ключ — [ModbusParameter.key] ("p201").
     *
     * SnapshotStateMap — Compose-friendly Map: любое изменение автоматически
     * триггерит перерисовку Composable, которые её читают. UI просто читает
     * `values[param.key]` и не думает про подписки и LiveData.
     *
     * Если параметр ещё не прочитан — ключа в Map нет. UI трактует
     * отсутствие как "---" (нет данных).
     */
    val values: SnapshotStateMap<String, DecodedValue> = mutableStateMapOf()

    /**
     * Состояние опроса:
     *  - [PollingState.Idle]         — опрос не запущен (экран только открылся);
     *  - [PollingState.Polling]      — опрос идёт, последний цикл успешен;
     *  - [PollingState.NoConnection] — 3 провала подряд, показываем баннер.
     */
    var pollingState by mutableStateOf(PollingState.Idle)
        private set

    /** Сколько провалов опроса подряд. При успехе сбрасывается в 0. */
    var consecutiveFailures by mutableStateOf(0)
        private set

    // =========================================================================
    // ВНУТРЕННЕЕ СОСТОЯНИЕ
    // =========================================================================

    /** Активная корутина опроса. null, если опрос остановлен. */
    private var pollingJob: Job? = null

    /**
     * Сгруппированные Modbus-запросы для этой страницы.
     * Вычисляется один раз при создании ViewModel — параметры страницы не меняются.
     *
     * Пример для "Основные данные" (r0004..r0009):
     *   [ReadRequest(0x0004, 6, [Uref, Ilim, Uout, Iout, tRun, WRun])]
     * — один запрос вместо шести.
     */
    private val groupedRequests: List<ReadRequest> =
        groupIntoRequests(page.toModbusParameters())

    // =========================================================================
    // ПУБЛИЧНЫЕ ДЕЙСТВИЯ
    // =========================================================================

    /**
     * Запускает опрос. Идемпотентен: повторный вызов ничего не делает, если
     * опрос уже идёт. Останавливается через [stopPolling] или [onCleared].
     */
    fun startPolling() {
        if (pollingJob?.isActive == true) {
            Log.e("PARAM_POLL", "startPolling: опрос уже идёт, пропускаю")
            return
        }
        pollingJob = viewModelScope.launch(Dispatchers.IO) {
            Log.e(
                "PARAM_POLL",
                "Опрос запущен для страницы '${page.title}' (${groupedRequests.size} запросов)"
            )
            while (isActive) {
                runOneCycle()
            }
        }
    }

    /** Останавливает опрос. Вызывать при уходе с экрана. */
    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.e("PARAM_POLL", "Опрос остановлен")
    }

    /**
     * Записывает значение параметра в контроллер.
     *
     * Вызывается из UI (кнопка "Записать" в диалоге). Выполняется в фоне:
     *  - приостанавливает опрос, чтобы Modbus-обмен записью не пересекался
     *    с циклами чтения;
     *  - вычисляет план записи через [ValueEncoder];
     *  - выполняет Modbus-операции (напрямую или read-modify-write);
     *  - возобновляет опрос;
     *  - сообщает результат через [onResult] на UI-потоке.
     *
     * Важно: обновление значений в [values] не делается здесь — это
     * сделает следующий цикл опроса через ~200 мс. Так мы не доверяем
     * "оптимистичному" обновлению, а видим реальное подтверждённое значение
     * из контроллера. Если контроллер ограничил значение (например, обрезал
     * по своим пределам), пользователь увидит это сразу.
     *
     * @param param Параметр для записи (содержит тип, адрес, масштаб).
     * @param humanValue Значение в "человеческом" виде (то, что ввёл
     *                   пользователь: 220.5 для Uref, 4.0 для "115200" и т.д.).
     * @param onResult Колбэк результата:
     *                 - `null` — успех;
     *                 - строка — текст ошибки для показа пользователю.
     *                 Всегда вызывается на Dispatchers.Main.
     */
    fun writeParameter(
        param: ModbusParameter,
        humanValue: Double,
        onResult: (String?) -> Unit
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            // 1. Останавливаем опрос на время записи.
            val wasPolling = pollingJob?.isActive == true
            if (wasPolling) stopPolling()

            var errorMessage: String? = null

            try {
                // 2. Строим план записи.
                val plan = ValueEncoder.plan(param, humanValue)
                Log.e("PARAM_WRITE", "План для ${param.name}=$humanValue: $plan")

                // 3. Выполняем план в зависимости от типа.
                errorMessage = when (plan) {
                    is EncodePlan.Error -> plan.message
                    is EncodePlan.WriteDirect -> executeWriteDirect(param, plan)
                    is EncodePlan.WriteBit -> executeWriteBit(param, plan)
                    is EncodePlan.WriteByte -> executeWriteByte(param, plan)
                }
            } catch (e: Exception) {
                Log.e("PARAM_WRITE", "Исключение при записи ${param.name}: ${e.message}")
                errorMessage = "Ошибка записи: ${e.message ?: "неизвестная"}"
            } finally {
                // 4. Возобновляем опрос (даже если была ошибка — экран должен
                //    продолжать обновлять значения).
                if (wasPolling) startPolling()

                // 5. Возвращаемся на UI-поток и сообщаем результат.
                withContext(Dispatchers.Main) {
                    onResult(errorMessage)
                }
            }
        }
    }

    // =========================================================================
    // ВЫПОЛНЕНИЕ ПЛАНОВ ЗАПИСИ
    // =========================================================================

    /**
     * Прямая запись: всё уже упаковано в план, отправляем одним запросом 0x10.
     */
    private fun executeWriteDirect(param: ModbusParameter, plan: EncodePlan.WriteDirect): String? {
        val success = repository.writeHoldingRegisters(
            slaveAddr = slaveAddress,
            startRegister = plan.startRegister,
            values = plan.values
        )
        return if (success) {
            Log.e("PARAM_WRITE", "Запись ${param.name} успешна")
            null
        } else {
            "Ошибка записи ${param.name}: контроллер не подтвердил"
        }
    }

    /**
     * Запись бита через read-modify-write.
     *
     * Последовательность:
     *  1. Прочитать регистр (0x03) — текущее значение всех 16 бит.
     *  2. Изменить только нужный бит по маске.
     *  3. Записать весь регистр обратно (0x10).
     *
     * Почему не пишем регистр целиком вслепую: в том же регистре могут
     * быть другие флаги, которые мы бы случайно затёрли. Read-modify-write
     * сохраняет остальные биты неизменными.
     */
    private fun executeWriteBit(param: ModbusParameter, plan: EncodePlan.WriteBit): String? {
        // 1. Читаем текущее значение регистра.
        val registers = repository.readHoldingRegisters(
            slaveAddr = slaveAddress,
            startRegister = plan.register,
            count = 1
        ) ?: return "Ошибка записи ${param.name}: контроллер не отвечает на чтение"

        val current = registers[0] and 0xFFFF
        // 2. Меняем только нужный бит по маске.
        val mask = 1 shl plan.bitNumber
        val newValue = if (plan.bitValue) {
            current or mask       // установить бит
        } else {
            current and mask.inv() // сбросить бит
        }

        // 3. Пишем весь регистр обратно.
        val success = repository.writeHoldingRegisters(
            slaveAddr = slaveAddress,
            startRegister = plan.register,
            values = intArrayOf(newValue)
        )
        return if (success) {
            Log.e(
                "PARAM_WRITE",
                "Запись ${param.name}: reg=0x%04X, бит ${plan.bitNumber} → ${plan.bitValue}"
                    .format(plan.register)
            )
            null
        } else {
            "Ошибка записи ${param.name}: контроллер не подтвердил"
        }
    }

    /**
     * Запись байта через read-modify-write.
     *
     * Аналогично биту, только сохраняем соседний байт. Например,
     * при записи младшего байта (Low) старший байт регистра должен
     * остаться неизменным — иначе затрём соседний параметр.
     */
    private fun executeWriteByte(param: ModbusParameter, plan: EncodePlan.WriteByte): String? {
        val registers = repository.readHoldingRegisters(
            slaveAddr = slaveAddress,
            startRegister = plan.register,
            count = 1
        ) ?: return "Ошибка записи ${param.name}: контроллер не отвечает на чтение"

        val current = registers[0] and 0xFFFF
        val newValue = when (plan.part) {
            BytePart.Low -> (current and 0xFF00) or (plan.byteValue and 0xFF)
            BytePart.High -> (current and 0x00FF) or ((plan.byteValue and 0xFF) shl 8)
        }

        val success = repository.writeHoldingRegisters(
            slaveAddr = slaveAddress,
            startRegister = plan.register,
            values = intArrayOf(newValue)
        )
        return if (success) {
            Log.e(
                "PARAM_WRITE",
                "Запись ${param.name}: reg=0x%04X, ${plan.part} байт → 0x%02X"
                    .format(plan.register, plan.byteValue)
            )
            null
        } else {
            "Ошибка записи ${param.name}: контроллер не подтвердил"
        }
    }

    // =========================================================================
    // ВНУТРЕННЯЯ ЛОГИКА ОПРОСА
    // =========================================================================

    /**
     * Один цикл опроса: последовательно выполняем все ReadRequest,
     * декодируем значения, обновляем [values].
     *
     * Тактика:
     *  1. Если хотя бы один запрос провалился — цикл прерывается,
     *     счётчик провалов растёт, ждём 500 мс (не спамим).
     *  2. Если все запросы успешны — счётчик сбрасывается, ждём 50 мс.
     *  3. Между запросами внутри цикла — пауза 50 мс, чтобы не забивать порт.
     */
    private suspend fun runOneCycle() {
        var hadFailure = false

        for (request in groupedRequests) {
            val registers = repository.readHoldingRegisters(
                slaveAddr = slaveAddress,
                startRegister = request.startRegister,
                count = request.count
            )

            if (registers == null) {
                Log.e(
                    "PARAM_POLL",
                    "Провал запроса: reg=0x%04X, count=${request.count}".format(request.startRegister)
                )
                hadFailure = true
                break
            }

            val batchUpdates = mutableMapOf<String, DecodedValue>()
            for (pw in request.params) {
                val decoded = ValueDecoder.decode(pw.param, registers, pw.offset)
                if (decoded != null) {
                    batchUpdates[pw.param.key] = decoded
                }
            }
            if (batchUpdates.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    values.putAll(batchUpdates)
                }
            }

            // Пауза между запросами внутри одного цикла.
            // 20 мс достаточно для USB-UART при 115200; 50 мс были с запасом.
            // При одном запросе эта пауза всё равно ждётся — поэтому цикл
            // получается 30-50 мс запрос + 20 мс пауза = ~50-70 мс.
            delay(20)
        }

        if (hadFailure) {
            consecutiveFailures += 1
            if (consecutiveFailures >= FAILURES_BEFORE_NO_CONNECTION) {
                withContext(Dispatchers.Main) {
                    pollingState = PollingState.NoConnection
                }
            }
            delay(500)
        } else {
            consecutiveFailures = 0
            withContext(Dispatchers.Main) {
                pollingState = PollingState.Polling
            }
            delay(50)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Только останавливаем опрос. Порт НЕ закрываем — это зона MainViewModel.
        stopPolling()
    }

    companion object {
        /** Сколько провалов подряд до перехода в NoConnection. */
        private const val FAILURES_BEFORE_NO_CONNECTION = 3
    }
}

/** Состояние опроса. */
enum class PollingState {
    /** Опрос не запущен (или остановлен). */
    Idle,

    /** Опрос идёт, последний цикл успешен. */
    Polling,

    /** 3+ провала подряд. Показываем баннер "Нет связи". */
    NoConnection
}

/**
 * Один Modbus-запрос: чтение [count] регистров начиная с [startRegister],
 * и список параметров, которые из этих регистров декодируются.
 */
data class ReadRequest(
    val startRegister: Int,
    val count: Int,
    val params: List<ParamWithOffset>
)

/**
 * Параметр + смещение внутри массива регистров пакета.
 * offset измеряется в регистрах (не в байтах).
 */
data class ParamWithOffset(
    val param: ModbusParameter,
    val offset: Int
)

/**
 * Группирует параметры в минимальное количество Modbus-запросов.
 * Соседние (подряд идущие) регистры объединяются в один запрос.
 * Ограничение Modbus — 125 регистров на запрос.
 */
private fun groupIntoRequests(params: List<ModbusParameter>): List<ReadRequest> {
    if (params.isEmpty()) return emptyList()

    val sorted = params.sortedBy { it.address.number }

    val result = mutableListOf<ReadRequest>()

    var packetStart = sorted[0].address.number
    var packetEnd = packetStart + sorted[0].registerCount
    var packetParams = mutableListOf(ParamWithOffset(sorted[0], 0))

    for (i in 1 until sorted.size) {
        val p = sorted[i]
        val pStart = p.address.number
        val pEnd = pStart + p.registerCount

        if (pStart <= packetEnd) {
            if (pEnd - packetStart > MAX_REGISTERS_PER_REQUEST) {
                result.add(ReadRequest(packetStart, packetEnd - packetStart, packetParams))
                packetStart = pStart
                packetEnd = pEnd
                packetParams = mutableListOf(ParamWithOffset(p, 0))
            } else {
                packetParams.add(ParamWithOffset(p, pStart - packetStart))
                if (pEnd > packetEnd) packetEnd = pEnd
            }
        } else {
            result.add(ReadRequest(packetStart, packetEnd - packetStart, packetParams))
            packetStart = pStart
            packetEnd = pEnd
            packetParams = mutableListOf(ParamWithOffset(p, 0))
        }
    }

    result.add(ReadRequest(packetStart, packetEnd - packetStart, packetParams))
    return result
}

/** Ограничение Modbus RTU: один запрос 0x03 максимум на 125 регистров. */
private const val MAX_REGISTERS_PER_REQUEST = 125