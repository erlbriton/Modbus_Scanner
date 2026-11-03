package com.intmash.modbus_scanner.data

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
 * Все типы данных из INI (TBit, TByte, TWord, TDWord, TFloat, TPrmList)
 * поддерживаются. Каждый тип декодируется своей функцией — легко тестировать
 * и добавлять новые типы.
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
        val scaled = byteValue / param.scale.divisor
        return DecodedValue.Number(
            raw = scaled,
            display = formatNumber(scaled, param.scale, isFloat = false)
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
        val scaled = registerValue / param.scale.divisor
        return DecodedValue.Number(
            raw = scaled,
            display = formatNumber(scaled, param.scale, isFloat = false)
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
        val scaled = rawLong.toDouble() / param.scale.divisor
        return DecodedValue.Number(
            raw = scaled,
            display = formatNumber(scaled, param.scale, isFloat = false)
        )
    }

    /**
     * TFloat — 32-битный IEEE 754 float в двух регистрах.
     *
     * Порядок: big-endian (старший регистр первый). Если у контроллера
     * другой порядок (word-swap или byte-swap) — увидим мусор вместо
     * ожидаемого числа. Тогда поправим здесь.
     */
    private fun decodeFloat(param: ModbusParameter, regs: IntArray, offset: Int): DecodedValue? {
        val highWord = regs[offset] and 0xFFFF
        val lowWord = regs[offset + 1] and 0xFFFF
        val intBits = (highWord shl 16) or lowWord
        val floatValue = Float.fromBits(intBits).toDouble()
        return DecodedValue.Number(
            raw = floatValue,
            display = formatNumber(floatValue, param.scale, isFloat = true)
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
     * Форматирует число с тем количеством знаков после запятой,
     * которое задаёт масштаб.
     *
     *  IntegerScale       → 0 знаков:  "220"
     *  FracDecScale       → 1 знак:    "220.0"
     *  FracHundScale      → 2 знака:   "219.80"
     *  FracThousScale     → 3 знака:   "1523.000"
     *  FracDecThousScale  → 4 знака:   "1.2345"
     *  TFloat (isFloat=true) → 5 знаков: "0.10833"
     *
     * Locale.US — гарантирует, что разделитель — точка (не запятая),
     * чтобы значения были единообразны независимо от языковых настроек
     * телефона. Если понадобится запятая (русский формат) — заменим
     * на Locale("ru", "RU") и потом обратное преобразование при вводе.
     */
    private fun formatNumber(value: Double, scale: ValueScale, isFloat: Boolean): String {
        val decimals = when {
            isFloat -> 5  // TFloat — по договорённости, 5 знаков
            else -> when (scale) {
                ValueScale.IntegerScale -> 0
                ValueScale.FracDecScale -> 1
                ValueScale.FracHundScale -> 2
                ValueScale.FracThousScale -> 3
                ValueScale.FracDecThousScale -> 4
            }
        }
        return String.format(Locale.US, "%.${decimals}f", value)
    }
}

/**
 * DecodedValue — результат декодирования одного параметра.
 *
 * Три варианта:
 *  - Number     — числовое значение (TWord, TByte, TDWord, TFloat). Имеет
 *                 raw (для последующей записи) и display (для UI).
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

    /** Числовое значение (TWord, TByte, TDWord, TFloat). */
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