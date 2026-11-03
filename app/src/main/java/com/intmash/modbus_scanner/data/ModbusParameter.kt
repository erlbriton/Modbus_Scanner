package com.intmash.modbus_scanner.data

/**
 * ModbusParameter — параметр, подготовленный к чтению и записи по протоколу Modbus RTU.
 *
 * Это "прослойка" между "как параметр описан в INI-файле" ([ParameterInfo])
 * и "как с ним работать по проводу" (регистр, тип, масштаб).
 *
 * Зачем отдельный класс, а не сразу [ParameterInfo]:
 *  1) [ParameterInfo] — это "сырое" описание из INI, где всё строки.
 *     ModbusParameter — это уже типизированная структура:
 *     registerNumber: Int, bitNumber: Int?, scale как enum.
 *  2) Многие поля из INI (маска бита, размер, значения по умолчанию)
 *     нам не нужны для Modbus — мы их не тащим.
 *  3) Парсинг INI и подготовка к Modbus — разные задачи, разные файлы.
 *     Если завтра формат INI поменяется, адаптируется только парсер.
 *
 * Один ModbusParameter всегда соответствует одному "физическому" параметру
 * контроллера (одному значению в одном регистре/бите).
 *
 * @property key           Уникальный ключ параметра из INI (например, "p201").
 *                         Используется как идентификатор в UI и во внутренних Map.
 * @property name          Человекочитаемое имя параметра (например, "Uref").
 * @property description   Описание из INI ("Напряжение задания"). Может быть пустым.
 * @property unit          Единица измерения ("V", "A"). Пустая строка, если единицы нет.
 *                         В INI маркер "нет единицы" — это "*", преобразуем в "".
 * @property type          Тип данных (TBit / TByte / TWord / TDWord / TFloat / TPrmList).
 * @property scale         Масштаб. Значение, прочитанное из регистра, делится на scale.divisor
 *                         (или умножается на divisor при записи — обратное преобразование).
 * @property address       Адрес (регистр, бит или половина байта). См. [RegisterAddress].
 * @property registerCount Сколько 16-битных регистров занимает параметр (1 или 2).
 * @property pRmlist       Для TPrmList — словарь "число из регистра → отображаемая строка".
 *                         Например, {1: "9600", 2: "19200"}. Для остальных типов — null.
 * @property source        Оригинальный [ParameterInfo] из INI. Нужен для UI (F1 показывает
 *                         описание, а description уже здесь, но key/name/unit тоже уже здесь —
 *                         source пригодится на будущее, если понадобится какое-то поле из INI).
 */
data class ModbusParameter(
    val key: String,
    val name: String,
    val description: String,
    val unit: String,
    val type: ModbusType,
    val scale: ValueScale,
    val address: RegisterAddress,
    val registerCount: Int,
    val pRmlist: Map<Int, String>?,
    val source: ParameterInfo
)

/**
 * Адрес параметра в памяти контроллера.
 *
 * В INI-файле адрес задаётся строкой ("r0004", "r0000.0", "rC004.L"), и от формы зависит
 * способ обращения по Modbus:
 *   - Register    → обычный 16-битный регистр. Читаем функцией 0x03, пишем 0x10.
 *   - Bit         → один бит внутри регистра. Читаем как регистр (0x03),
 *                   извлекаем бит, при записи — Read-Modify-Write (прочитать весь регистр,
 *                   изменить один бит, записать обратно функцией 0x10).
 *   - ByteField   → младший (.L) или старший (.H) байт регистра.
 *                   Аналогично Bit — при записи Read-Modify-Write.
 *
 * Поле [number] — номер регистра (0x0000..0xFFFF). Всегда в hex.
 */
sealed class RegisterAddress {

    /** Номер 16-битного регистра (0x0000..0xFFFF). */
    abstract val number: Int

    /**
     * Обычный 16-битный регистр.
     * Пример из INI: "r0004" → Register(0x0004).
     */
    data class Register(override val number: Int) : RegisterAddress()

    /**
     * Один бит внутри регистра.
     * Пример из INI: "r0000.0" → Bit(0x0000, bitNumber=0); "r0001.A" → Bit(0x0001, bitNumber=0xA=10).
     * Номер бита — от 0 (младший) до 15 (старший).
     */
    data class Bit(override val number: Int, val bitNumber: Int) : RegisterAddress()

    /**
     * Младший или старший байт 16-битного регистра.
     * Пример из INI: "rC004.L" → ByteField(0xC004, BytePart.Low);
     *               "rC004.H" → ByteField(0xC004, BytePart.High).
     */
    data class ByteField(override val number: Int, val part: BytePart) : RegisterAddress()
}

/**
 * Какая половина 16-битного регистра используется при работе с TByte.
 *   - Low  = младший байт (младшие 8 бит регистра),
 *   - High = старший байт (старшие 8 бит регистра).
 */
enum class BytePart {
    Low,
    High
}

/**
 * Тип данных параметра. Значения совпадают с обозначениями в INI-файле
 * (TBIT, TBYTE, TWORD, TDWORD, TFLOAT, TPRMLIST), но с "человеческим" регистром.
 */
enum class ModbusType {
    /** Один бит. Отображается квадратиком, записывается через Read-Modify-Write. */
    TBit,

    /** Один байт (младший или старший в регистре). Read-Modify-Write. */
    TByte,

    /** Один 16-битный регистр без знака. Обычно целое значение с масштабом. */
    TWord,

    /** Два 16-битных регистра (32 бита), без знака. */
    TDWord,

    /** 32-битный IEEE 754 float (два регистра). */
    TFloat,

    /** Значение из списка (например, скорость 9600/19200). Список — в [ModbusParameter.pRmlist]. */
    TPrmList
}

/**
 * Масштаб значения.
 *
 * Контроллер передаёт значения как целые числа, а пользователю показывают "человеческое"
 * значение с масштабом. Например, напряжение Uref = 220.0 В хранится как целое 2200,
 * потому что масштаб = 0.1 (FracDecScale).
 *
 * При ЧТЕНИИ:   физическое = сырое / divisor
 * При ЗАПИСИ:   сырое      = физическое * divisor  (и округлить до Int)
 *
 * Значение divisor — константа, привязана к enum. Меняется только если появится
 * новый масштаб в INI — тогда добавляем строку в enum и в парсер.
 */
enum class ValueScale(val divisor: Double) {
    /** Масштаб отсутствует. 1 → 1. */
    IntegerScale(1.0),

    /** Разделить на 10. 2200 → 220.0. */
    FracDecScale(10.0),

    /** Разделить на 100. 21980 → 219.80. */
    FracHundScale(100.0),

    /** Разделить на 1000. 1523000 → 1523.0. */
    FracThousScale(1000.0),

    /** Разделить на 10000. 0 → 0.0. */
    FracDecThousScale(10000.0)
}