package com.intmash.modbus_scanner.ui

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.intmash.modbus_scanner.data.DecodedValue
import com.intmash.modbus_scanner.data.ModbusParameter
import com.intmash.modbus_scanner.data.ModbusRepository
import com.intmash.modbus_scanner.data.PageInfo
import com.intmash.modbus_scanner.data.ValueDecoder
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
 *  - отслеживание состояния связи ([pollingState]).
 *
 * НЕ отвечает за:
 *  - открытие/закрытие USB-порта (это делает [MainViewModel] через
 *    [ModbusRepository.openPort]). К моменту создания этой VM порт уже открыт.
 *  - UI — это делает [ParametersScreen].
 *  - запись параметров — появится позже, в этом же классе.
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
    val page: PageInfo
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
     * Если параметр ещё не прочитан — ключа в Map нет. UI должен трактовать
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
     *
     * Пример для "Уставки связи" (RS485_DVA rF000, RS485_BPS rF001,
     * USB_DVA rF002, USB_BPS rF003):
     *   [ReadRequest(0xF000, 4, [все 4 параметра])]
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

    // =========================================================================
    // ВНУТРЕННЯЯ ЛОГИКА
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
     *
     * Обновления [values] делаются на Dispatchers.Main (батчем на пакет),
     * чтобы Compose гарантированно увидел изменения и не было гонок
     * между UI-потоком и IO-потоком.
     */
    private suspend fun runOneCycle() {
        var hadFailure = false

        for (request in groupedRequests) {
            val registers = repository.readHoldingRegisters(
                slaveAddr = SLAVE_ADDRESS,
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

            // Декодируем все параметры этого пакета и накапливаем в batch,
            // чтобы одним withContext(Dispatchers.Main) переключиться и записать всё.
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

            // Пауза между запросами внутри цикла. 50 мс — компромисс между
            // скоростью и нагрузкой на контроллер. По результатам тестов
            // можно уменьшить (если контроллер успевает) или увеличить.
            delay(50)
        }

        if (hadFailure) {
            consecutiveFailures += 1
            if (consecutiveFailures >= FAILURES_BEFORE_NO_CONNECTION) {
                withContext(Dispatchers.Main) {
                    pollingState = PollingState.NoConnection
                }
            }
            // При ошибке — пауза побольше, чтобы не спамить контроллер.
            delay(500)
        } else {
            consecutiveFailures = 0
            withContext(Dispatchers.Main) {
                pollingState = PollingState.Polling
            }
            // Минимальный межцикловый интервал. Без него цикл съедает 100% CPU
            // и не даёт порту "продышаться" между циклами.
            delay(50)
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Только останавливаем опрос. Порт НЕ закрываем — это зона MainViewModel.
        stopPolling()
    }

    companion object {
        /** Адрес slave контроллера. По договорённости пока всегда 1. */
        private const val SLAVE_ADDRESS = 1

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
 *
 * Пример: пакет r0004..r0009, параметр Ilim (r0005) → offset = 1.
 * ValueDecoder.decode(Ilim, registers, offset=1) возьмёт registers[1].
 */
data class ParamWithOffset(
    val param: ModbusParameter,
    val offset: Int
)

/**
 * Группирует параметры в минимальное количество Modbus-запросов.
 *
 * Принцип: соседние (подряд идущие) регистры объединяются в один запрос.
 * Если следующий параметр начинается сразу за предыдущим — склеиваем.
 * Если есть разрыв — создаём новый запрос.
 *
 * Пример: "Основные данные" (Uref r0004, Ilim r0005, Uout r0006, ...).
 *   Все идут подряд → один ReadRequest(0x0004, 6, [6 параметров]).
 *
 * Пример: "Уставки связи" (RS485_DVA rF000, RS485_BPS rF001,
 * USB_DVA rF002, USB_BPS rF003):
 *   Все подряд → один ReadRequest(0xF000, 4, [4 параметра]).
 *
 * Ограничение Modbus: один запрос максимум 125 регистров. Если пакет
 * получается больше — разбивается на несколько.
 *
 * @param params Параметры страницы. Пустой список → пустой результат.
 * @return Список запросов с параметрами, привязанными к смещениям.
 */
private fun groupIntoRequests(params: List<ModbusParameter>): List<ReadRequest> {
    if (params.isEmpty()) return emptyList()

    // Сортируем по адресу регистра. В INI параметры могут идти в любом
    // порядке, а группировать удобнее по возрастанию адресов.
    val sorted = params.sortedBy { it.address.number }

    val result = mutableListOf<ReadRequest>()

    // Текущий открытый пакет.
    var packetStart = sorted[0].address.number
    var packetEnd = packetStart + sorted[0].registerCount
    var packetParams = mutableListOf(ParamWithOffset(sorted[0], 0))

    for (i in 1 until sorted.size) {
        val p = sorted[i]
        val pStart = p.address.number
        val pEnd = pStart + p.registerCount

        // Склеиваем, если следующий регистр идёт сразу за текущим пакетом
        // (pStart == packetEnd) или перекрывается (pStart < packetEnd,
        // например, два TBit в одном регистре).
        if (pStart <= packetEnd) {
            // Пакет уже достиг лимита Modbus — вынужденно начинаем новый.
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
            // Разрыв — закрываем текущий пакет, начинаем новый.
            result.add(ReadRequest(packetStart, packetEnd - packetStart, packetParams))
            packetStart = pStart
            packetEnd = pEnd
            packetParams = mutableListOf(ParamWithOffset(p, 0))
        }
    }

    // Закрываем последний пакет.
    result.add(ReadRequest(packetStart, packetEnd - packetStart, packetParams))
    return result
}

/** Ограничение Modbus RTU: один запрос 0x03 максимум на 125 регистров. */
private const val MAX_REGISTERS_PER_REQUEST = 125