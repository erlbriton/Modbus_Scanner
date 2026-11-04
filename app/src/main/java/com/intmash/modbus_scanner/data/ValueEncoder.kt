// app/src/main/java/com/intmash/modbus_scanner/data/ValueEncoder.kt
package com.intmash.modbus_scanner.data

import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * ValueEncoder — обратное преобразование к [ValueDecoder].
 *
 * Превращает "человеческое" значение (то, что пользователь ввёл в диалоге
 * записи — Double) в "план" записи по Modbus. План — это декларативное
 * описание, что именно нужно сделать:
 *  - WriteDirect — записать один или два регистра напрямую (TWord, TInteger,
 *    TDWord, TFloat, TPrmList на полном регистре);
 *  - WriteBit — установить/сбросить бит (TBit): требует read-modify-write,
 *    т.к. запись идёт на весь регистр, а нам нужен только один бит;
 *  - WriteByte — заменить младший/старший байт (TByte, TPrmList на байте):
 *    тоже read-modify-write;
 *  - Error — значение невалидно (не влезает в диапазон, ключ не найден
 *    в pRmlist, для TBit введено не 0/1 и т.п.).
 *
 * ValueEncoder НЕ работает с USB/Modbus. Он только вычисляет, что делать.
 * Выполнение плана — задача оркестратора ([com.intmash.modbus_scanner.ui.ParametersViewModel]).
 *
 * Применение масштаба — обратное декодированию:
 *   при чтении: physical = raw × scaleFactor;
 *   при записи: raw      = round(physical / scaleFactor).
 *
 * Проверка диапазонов критична: молча "обрезать" значение до максимума
 * (как сделал бы простой toInt()) — это скрытая ошибка. Пользователь
 * увидел бы "Записано успешно", а в контроллере оказалось бы не то число.
 * Поэтому при выходе за диапазон возвращается Error с понятным сообщением.
 */
object ValueEncoder {

    /**
     * Главная точка входа. Возвращает план записи для [param] и [humanValue].
     * Всегда возвращает какой-то план: WriteDirect, WriteBit, WriteByte
     * или Error. Вызывающий код должен обработать все ветки.
     */
    fun plan(param: ModbusParameter, humanValue: Double): EncodePlan {
        return when (param.type) {
            ModbusType.TWord -> planWord(param, humanValue)
            ModbusType.TInteger -> planInteger(param, humanValue)
            ModbusType.TDWord -> planDWord(param, humanValue)
            ModbusType.TFloat -> planFloat(param, humanValue)
            ModbusType.TByte -> planByte(param, humanValue)
            ModbusType.TBit -> planBit(param, humanValue)
            ModbusType.TPrmList -> planPrmList(param, humanValue)
        }
    }

    // =========================================================================
    // ПРЯМАЯ ЗАПИСЬ (WriteDirect)
    // =========================================================================

    /**
     * TWord — один 16-битный беззнаковый регистр (0..0xFFFF).
     * Значение пользователя делится на scaleFactor и округляется.
     *
     * Пример: scaleFactor = 0.1, human = 220.5 → raw = 2205.
     */
    private fun planWord(param: ModbusParameter, value: Double): EncodePlan {
        val raw = (value / param.scaleFactor).roundToLong()
        if (raw < 0 || raw > 0xFFFF) {
            val maxHuman = 0xFFFF * param.scaleFactor
            return EncodePlan.Error(
                "Значение вне диапазона (0..${formatBound(maxHuman)}${if (param.unit.isNotBlank()) " ${param.unit}" else ""})"
            )
        }
        return EncodePlan.WriteDirect(
            startRegister = param.address.number,
            values = intArrayOf(raw.toInt())
        )
    }

    /**
     * TInteger — один 16-битный регистр СО ЗНАКОМ (-32768..32767).
     * Значение пользователя делится на scaleFactor и округляется.
     * Проверка диапазона — по signed-диапазону Int16.
     *
     * Кодирование в регистр: отрицательные значения хранятся в дополнительном
     * коде: -1 → 0xFFFF, -32768 → 0x8000. Это делается через `and 0xFFFF`.
     *
     * Пример: scaleFactor = 0.1, human = -10.0 → raw = -100 → encoded = 0xFF9C.
     */
    private fun planInteger(param: ModbusParameter, value: Double): EncodePlan {
        val raw = (value / param.scaleFactor).roundToLong()
        if (raw < -32768 || raw > 32767) {
            val minHuman = -32768 * param.scaleFactor
            val maxHuman = 32767 * param.scaleFactor
            return EncodePlan.Error(
                "Значение вне диапазона TInteger (${formatBound(minHuman)}..${formatBound(maxHuman)}${if (param.unit.isNotBlank()) " ${param.unit}" else ""})"
            )
        }
        return EncodePlan.WriteDirect(
            startRegister = param.address.number,
            values = intArrayOf((raw.toInt() and 0xFFFF))
        )
    }

    /**
     * TDWord — два 16-битных регистра (0..0xFFFFFFFF).
     * Порядок регистров совпадает с ValueDecoder.decodeDWord: старший первым.
     */
    private fun planDWord(param: ModbusParameter, value: Double): EncodePlan {
        val raw = (value / param.scaleFactor).roundToLong()
        if (raw < 0 || raw > 0xFFFFFFFFL) {
            return EncodePlan.Error("Значение вне диапазона TDWord (0..4294967295)")
        }
        return EncodePlan.WriteDirect(
            startRegister = param.address.number,
            values = intArrayOf(
                ((raw shr 16) and 0xFFFF).toInt(),
                (raw and 0xFFFF).toInt()
            )
        )
    }

    /**
     * TFloat — IEEE 754 float в двух регистрах.
     * У TFloat масштаб = 1.0 (установлено в ModbusParameterParser),
     * поэтому деление на scaleFactor не искажает значение.
     */
    private fun planFloat(param: ModbusParameter, value: Double): EncodePlan {
        val bits = java.lang.Float.floatToRawIntBits(value.toFloat())
        return EncodePlan.WriteDirect(
            startRegister = param.address.number,
            values = intArrayOf(
                (bits ushr 16) and 0xFFFF,
                bits and 0xFFFF
            )
        )
    }

    // =========================================================================
    // ЗАПИСЬ ЧЕРЕЗ READ-MODIFY-WRITE (WriteBit, WriteByte)
    // =========================================================================

    /**
     * TBit — установить/сбросить один бит в регистре.
     * Пользователь вводит 0 или 1 (в диалоге — две кнопки).
     * Всё остальное считается ошибкой (защита от опечаток, если когда-нибудь
     * ввод станет текстовым).
     */
    private fun planBit(param: ModbusParameter, value: Double): EncodePlan {
        val address = param.address as? RegisterAddress.Bit
            ?: return EncodePlan.Error("TBit: некорректный адрес (ожидался Bit)")
        val bitValue = value.roundToInt()
        if (bitValue != 0 && bitValue != 1) {
            return EncodePlan.Error("TBit: ожидалось 0 или 1, получено $value")
        }
        return EncodePlan.WriteBit(
            register = address.number,
            bitNumber = address.bitNumber,
            bitValue = bitValue == 1
        )
    }

    /**
     * TByte — заменить младший или старший байт в регистре.
     * Пользователь вводит число (0..255 с учётом масштаба).
     */
    private fun planByte(param: ModbusParameter, value: Double): EncodePlan {
        val address = param.address as? RegisterAddress.ByteField
            ?: return EncodePlan.Error("TByte: некорректный адрес (ожидался ByteField)")
        val raw = (value / param.scaleFactor).roundToLong()
        if (raw < 0 || raw > 0xFF) {
            val maxHuman = 0xFF * param.scaleFactor
            return EncodePlan.Error("Значение вне диапазона байта (0..${formatBound(maxHuman)})")
        }
        return EncodePlan.WriteByte(
            register = address.number,
            part = address.part,
            byteValue = raw.toInt()
        )
    }

    /**
     * TPrmList — значение из словаря.
     *  - если адрес — целый регистр: прямая запись ключа в регистр;
     *  - если адрес — половина байта: read-modify-write, ключ должен
     *    помещаться в байт (0..255).
     *
     * humanValue — это ключ словаря (1, 2, 3...). Он приходит из WriteDialog
     * как Double (например, 4.0 для "115200"). Здесь округляем до Int
     * и проверяем, что такой ключ вообще существует в pRmlist.
     */
    private fun planPrmList(param: ModbusParameter, value: Double): EncodePlan {
        val pRmlist = param.pRmlist
            ?: return EncodePlan.Error("TPrmList: у параметра нет словаря значений")

        val key = value.roundToInt()
        if (!pRmlist.containsKey(key)) {
            return EncodePlan.Error("TPrmList: неизвестный ключ $key")
        }

        return when (val address = param.address) {
            is RegisterAddress.Register -> {
                if (key < 0 || key > 0xFFFF) {
                    EncodePlan.Error("TPrmList: ключ $key не помещается в регистр")
                } else {
                    EncodePlan.WriteDirect(
                        startRegister = address.number,
                        values = intArrayOf(key)
                    )
                }
            }
            is RegisterAddress.ByteField -> {
                if (key < 0 || key > 0xFF) {
                    EncodePlan.Error("TPrmList: ключ $key не помещается в байт")
                } else {
                    EncodePlan.WriteByte(
                        register = address.number,
                        part = address.part,
                        byteValue = key
                    )
                }
            }
            is RegisterAddress.Bit -> {
                EncodePlan.Error("TPrmList: не может быть на бите")
            }
        }
    }

    // =========================================================================
    // ВСПОМОГАТЕЛЬНЫЕ
    // =========================================================================

    /**
     * Форматирует границу диапазона для сообщения об ошибке: без лишних
     * нулей после запятой. 220.0 → "220", 220.5 → "220.5".
     * Используется только в Error.message.
     */
    private fun formatBound(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }
}

/**
 * EncodePlan — что нужно сделать для записи значения.
 *
 * Разделение по видам действий позволяет ViewModel-оркестратору
 * выполнить ровно те Modbus-операции, которые нужны, и не делать
 * лишних чтений для случаев прямой записи.
 *
 * Всегда одно из четырёх:
 *  - [WriteDirect] — один или два готовых регистра на запись (без чтения);
 *  - [WriteBit]    — бит, требует read-modify-write (читать регистр, менять
 *                    бит по маске, писать обратно);
 *  - [WriteByte]   — байт, требует read-modify-write (аналогично);
 *  - [Error]       — значение невалидно, запись невозможна.
 */
sealed class EncodePlan {

    /**
     * Прямая запись [values] начиная с адреса [startRegister].
     * Используется для TWord, TInteger, TDWord, TFloat и TPrmList на полном регистре.
     */
    data class WriteDirect(
        val startRegister: Int,
        val values: IntArray
    ) : EncodePlan()

    /**
     * Установить ([bitValue] = true) или сбросить ([bitValue] = false)
     * бит [bitNumber] в регистре [register]. Требует read-modify-write.
     */
    data class WriteBit(
        val register: Int,
        val bitNumber: Int,
        val bitValue: Boolean
    ) : EncodePlan()

    /**
     * Заменить байт в регистре [register]. [part] определяет, какой
     * именно (Low = младший, High = старший). Требует read-modify-write.
     */
    data class WriteByte(
        val register: Int,
        val part: BytePart,
        val byteValue: Int
    ) : EncodePlan()

    /**
     * Значение невалидно, запись не выполняется. [message] — готовый
     * к показу пользователю текст (например, "TBit: ожидалось 0 или 1").
     */
    data class Error(val message: String) : EncodePlan()
}