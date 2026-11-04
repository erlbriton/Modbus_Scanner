package com.intmash.modbus_scanner.data

import java.math.BigDecimal
import java.util.Locale

/**
 * ValueDecoder — превращает "сырые" регистры Modbus в "человеческое" значение.
 *
 * Это отдельная ответственность, не пересекающаяся с:
 *  - ModbusParameterParser (парсит INI-файл, создаёт ModbusParameter);
 *  - ModbusRepository (отправляет/принимает байты по проводу);
 *  - UI (отображает готовую строку в карточке).
 *
 * Вход: ModbusParameter + массив прочитанных регистров + смещение.
 * Выход: DecodedValue — обёртка, которая умеет:
 *  - вернуть "готовую к показу" строку (display),
 *  - вернуть "сырое" значение (для TBit — Boolean, для TPrmList — Int),
 *    что пригодится, когда понадобится запись.
 *
 * Все типы данных из INI (TBit, TByte, TWord, TInteger, TDWord, TFloat, TPrmList)
 * поддерживаются. Каждый тип декодируется своей функцией — легко тестировать
 * и добавлять новые типы.
 *
 * Применение масштаба:
 *   physical = signed_or_unsigned_raw × scaleFactor
 *
 * Например, scaleFactor = 0.1, raw = 2200 → physical = 220.0.
 * Поле scaleFactor уже содержит разрешённое значение из [vars] или из
 * числового поля параметра (см. ModbusParameterParser.resolveScale).
 */
object ValueDecoder {

    /**
     * Декодирует параметр [param] из массива регистров [registers],
     * начиная со смещения [offset] (в регистрах, не байтах).
     *
     * Обычно offset = 0, но при групповом чтении (когда одним запросом
     * читаем 6 регистров и декодируем 6 параметров) каждый параметр
     * берёт свой offset.
     *
     * @return DecodedValue или null, если:
     *  - offset вышел за границы массива;
     *  - тип данных не распознан (не должно случиться после ModbusParameterParser);
     *  - для TPrmList значение отсутствует в словаре (что странно, но возможно).
     */
    fun decode(
        param: ModbusParameter,
        registers: IntArray,
        offset: Int = 0
    ): DecodedValue? {
        if (offset < 0) return null
        if (offset + param.registerCount > registers.size) return null

        return when (param.type) {
            ModbusType.TBit -> decodeBit(param, registers, offset)
            ModbusType.TByte -> decodeByte(param, registers, offset)
            ModbusType.TWord -> decodeWord(param, registers, offset)
            ModbusType.TInteger -> decodeInteger(param, registers, offset)
            ModbusType.TDWord -> decodeDWord(param, registers, offset)
            ModbusType.TFloat -> decodeFloat(param, registers, offset)
            ModbusType.TPrmList -> decodePrmList(param, registers, offset)
        }
    }

    // =========================================================================
    // ТИП-СПЕЦИФИЧНЫЕ ДЕКОДЕРЫ
    // =========================================================================

    /**
     * TBit — один бит внутри 16-битного регистра.
     *
     * Пример: p9 = Ready → адрес Bit(0x0001, bitNumber=1).
     * Значение бита 0 = false, 1 = true.
     * Для UI — квадратик (пустой / заполненный). Здесь — только данные.
     *
     * У TBit масштаб не применяется: масштаб в ModbusParameter = 1.0
     * (установлено в ModbusParameterParser).
     */
    private fun decodeBit(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
        val address = param.address as? RegisterAddress.Bit ?: return null
        val registerValue = regs[offset] and 0xFFFF
        val bit = (registerValue shr address.bitNumber) and 1
        return DecodedValue.Bit(
            raw = bit == 1,
            display = if (bit == 1) "1" else "0"
        )
    }

    /**
     * TByte — младший (.L) или старший (.H) байт 16-битного регистра.
     *
     * Пример: p502 = RS485_DVA → адрес ByteField(0xF000, Low).
     * Значение = младшие 8 бит регистра 0xF000.
     * Для записи (позже) — Read-Modify-Write: прочитать весь регистр,
     * заменить один байт, записать целиком.
     */
    private fun decodeByte(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
        val address = param.address as? RegisterAddress.ByteField ?: return null
        val registerValue = regs[offset] and 0xFFFF
        val byteValue = when (address.part) {
            BytePart.Low -> registerValue and 0xFF
            BytePart.High -> (registerValue shr 8) and 0xFF
        }
        val scaled = byteValue * param.scaleFactor
        return DecodedValue.Number(
            raw = scaled,
            display = formatNumber(scaled, param.scaleFactor, isFloat = false)
        )
    }

    /**
     * TWord — обычный 16-битный регистр (без знака, 0..65535).
     *
     * Самый частый тип в INI-файле. Почти все параметры "Основных данных",
     * "Дополнительных данных", "Уставки работы" — это TWord с разными масштабами.
     */
    private fun decodeWord(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
        val registerValue = regs[offset] and 0xFFFF
        val scaled = registerValue * param.scaleFactor
        return DecodedValue.Number(
            raw = scaled,
            display = formatNumber(scaled, param.scaleFactor, isFloat = false)
        )
    }

/**
 * TInteger — 16-битное ЦЕЛОЕ СО ЗНАКОМ (диапазон -32768..32767).
 *
 * Старший бит регистра — знаковый. Значение 0xFFFF = -1, 0x8000 = -32768.
 * При чтении: если raw > 0x7FFF, интерпретируем как отрицательное (raw - 0x10000).
 * Дальше обычное применение масштаба (умножение на scaleFactor).
 *
 * Пример из INI: p5555 = SomeParam/.../TInteger/x1000/r1000/(none)/0,1/2//1/x0000/
 *   → raw 100 (0x0064) → signed 100 → physical 10.0
 *   → raw -100 (0xFF9C) → signed -100 → physical -10.0
*/
private fun decodeInteger(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
val raw = regs[offset] and 0xFFFF
val signed = if (raw > 0x7FFF) raw - 0x10000 else raw
val scaled = signed * param.scaleFactor
return DecodedValue.Number(
raw = scaled,
display = formatNumber(scaled, param.scaleFactor, isFloat = false)
)
}

/**
 * TDWord — 32-битное беззнаковое число в двух регистрах.
 *
 * Порядок: старший регистр первый (big-endian), стандарт Modbus.
 * Если у вашего контроллера порядок другой — увидите это по мусорным
 * значениям (например, огромные числа вместо ожидаемых), тогда
 * поменяем местами regs[offset] и regs[offset+1].
 *
 * Пример: p401 = SN → адрес Register(0xE000), registerCount=2.
*/
private fun decodeDWord(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
val highWord = regs[offset].toLong() and 0xFFFFL
val lowWord = regs[offset + 1].toLong() and 0xFFFFL
val rawLong = (highWord shl 16) or lowWord
val scaled = rawLong.toDouble() * param.scaleFactor
return DecodedValue.Number(
raw = scaled,
display = formatNumber(scaled, param.scaleFactor, isFloat = false)
)
}

/**
 * TFloat — 32-битный IEEE 754 float в двух регистрах.
 *
 * Порядок: big-endian (старший регистр первый). Если у контроллера
 * другой порядок (word-swap или byte-swap) — увидим мусор вместо
 * ожидаемого числа. Тогда поправим здесь.
 *
 * У TFloat масштаб = 1.0 всегда (установлено в ModbusParameterParser).
*/
private fun decodeFloat(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
val highWord = regs[offset] and 0xFFFF
val lowWord = regs[offset + 1] and 0xFFFF
val intBits = (highWord shl 16) or lowWord
val floatValue = Float.fromBits(intBits).toDouble()
return DecodedValue.Number(
raw = floatValue,
display = formatNumber(floatValue, param.scaleFactor, isFloat = true)
)
}

/**
 * TPrmList — значение из словаря. В INI для каждого TPrmList есть
 * набор опций "xNN#отображаемое_имя". Например:
 *   p503 = USB_BPS: x01#9600, x02#19200, x03#57600, x04#115200.
 *
 * Значение, прочитанное из регистра/байта, ищем в pRmlist.
 * Если нашли — возвращаем отображаемое имя ("9600").
 * Если нет — возвращаем "?" (некорректное значение).
 *
 * Масштаб у TPrmList = 1.0 (установлено в ModbusParameterParser),
 * потому что значение — это код варианта, а не физическая величина.
*/
private fun decodePrmList(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
val pRmlist = param.pRmlist ?: return null
val rawValue = when (val addr = param.address) {
is RegisterAddress.Register -> regs[offset] and 0xFFFF
is RegisterAddress.ByteField -> {
val reg = regs[offset] and 0xFFFF
when (addr.part) {
BytePart.Low -> reg and 0xFF
BytePart.High -> (reg shr 8) and 0xFF
}
}
is RegisterAddress.Bit -> return null  // TPrmList не бывает на бите
}
val label = pRmlist[rawValue] ?: "?"
return DecodedValue.ListOption(
raw = rawValue,
label = label,
display = label
)
}

// =========================================================================
// ФОРМАТИРОВАНИЕ ЧИСЕЛ
// =========================================================================

/**
 * Форматирует число с количеством знаков после запятой, определяемым
 * по [scaleFactor]:
 *
 *  scaleFactor = 1.0        → 0 знаков:  "220"
 *  scaleFactor = 0.1        → 1 знак:    "220.5"
 *  scaleFactor = 0.01       → 2 знака:   "219.80"
 *  scaleFactor = 0.001      → 3 знака:   "1523.000"
 *  scaleFactor = 0.0001     → 4 знака:   "1.2345"
 *  scaleFactor = 0.00390625 → 5 знаков:  "3.90625"  (ограничено сверху 5-ю)
 *  scaleFactor = 0.0226     → 4 знака:   "1.2345"
 *
 * Для TFloat ([isFloat] = true) знаков всегда 5 — независимо от scaleFactor
 * (у TFloat scaleFactor = 1.0, но показывать "1" вместо точного значения
 * было бы неправильно).
 *
 * Количество знаков вычисляется через BigDecimal.valueOf(scaleFactor),
 * чтобы избежать float-погрешностей. Например, BigDecimal.valueOf(0.1)
 * точно знает, что это "0.1", scale=1, а не "0.1000000000000000055…".
 *
 * Locale.US — гарантирует точку как разделитель (не запятую),
 * чтобы значения были единообразны независимо от языка телефона.
 * Ввод в диалоге записи принимает и точку, и запятую (см. ValueEncoder).
*/
private fun formatNumber(value: Double, scaleFactor: Double, isFloat: Boolean): String {
val decimals = if (isFloat) {
5
} else {
BigDecimal.valueOf(scaleFactor)
.stripTrailingZeros()
.scale()
.coerceIn(0, 5)
}
return String.format(Locale.US, "%.${decimals}f", value)
}
}

/**
 * DecodedValue — результат декодирования одного параметра.
 *
 * Три варианта:
 *  - Number     — числовое значение (TWord, TInteger, TByte, TDWord, TFloat).
 *                 Имеет raw (для последующей записи) и display (для UI).
 *  - Bit        — бит (TBit). raw = true/false, display = "1"/"0".
 *                 В UI отображается квадратиком.
 *  - ListOption — значение из списка (TPrmList). raw = число (для записи),
 *                 label = отображаемое имя ("9600"), display = то же самое.
 *
 * Все три имеют поле display — готовая строка для отображения в карточке.
 * Это упрощает UI: он не думает "как форматировать", просто берёт display.
*/
sealed class DecodedValue {

/** Готовая к показу строка. Для TBit — "0" или "1". */
abstract val display: String

/** Числовое значение (TWord, TInteger, TByte, TDWord, TFloat). */
data class Number(
val raw: Double,
override val display: String
) : DecodedValue()

/** Бит (TBit). В UI отображается квадратиком, а не текстом. */
data class Bit(
val raw: Boolean,
override val display: String
) : DecodedValue()

/** Значение из списка (TPrmList). Например, "9600", "19200". */
data class ListOption(
val raw: Int,
val label: String,
override val display: String
) : DecodedValue()
}