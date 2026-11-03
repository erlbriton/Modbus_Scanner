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
 *  - WriteDirect — записать один или два регистра напрямую (TWord, TDWord,
 *    TFloat, TPrmList на полном регистре);
 *  - WriteBit — установить/сбросить бит (TBit): требует read-modify-write,
 *    т.к. запись идёт на весь регистр, а нам нужен только один бит;
 *  - WriteByte — заменить младший/старший байт (TByte, TPrmList на байте):
 *    тоже read-modify-write;
 *  - Error — значение невалидно (не влезает в диапазон, ключ не найден
 *    в pRmlist, для TBit введено не 0/1 и т.п.).
 *
 * ValueEncoder НЕ работает с USB/Modbus. Он только вычисляет, что делать.
 * Выполнение плана — задача оркестратора ([com.intmash.modbus_scanner.ui.ParametersViewModel]).
 * Это разделение позволяет тестировать преобразование без живого контроллера.
 *
 * Масштаб применяется в обратную сторону, чем при чтении:
 *  - при чтении: human = raw / divisor;
 *  - при записи: raw  = human * divisor (и округление до целого).
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
     * Значение пользователя умножается на divisor масштаба и округляется.
     */
    private fun planWord(param: ModbusParameter, value: Double): EncodePlan {
        val raw = (value * param.scale.divisor).roundToLong()
        if (raw < 0 || raw > 0xFFFF) {
            val maxHuman = 0xFFFF / param.scale.divisor
            return EncodePlan.Error(
                "Значение вне диапазона (0..$maxHuman${if (param.unit.isNotBlank()) " ${param.unit}" else ""})"
            )
        }
        return EncodePlan.WriteDirect(
            startRegister = param.address.number,
            values = intArrayOf(raw.toInt())
        )
    }

    /**
     * TDWord — два 16-битных регистра (0..0xFFFFFFFF).
     * Порядок регистров совпадает с ValueDecoder.decodeDWord: старший первым.
     */
    private fun planDWord(param: ModbusParameter, value: Double): EncodePlan {
        val raw = (value * param.scale.divisor).roundToLong()
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
     * У TFloat масштаба нет (parser выставил IntegerScale = 1.0),
     * поэтому преобразование — просто упаковка float-битов в два регистра.
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
        val raw = (value * param.scale.divisor).roundToLong()
        if (raw < 0 || raw > 0xFF) {
            val maxHuman = 0xFF / param.scale.divisor
            return EncodePlan.Error("Значение вне диапазона байта (0..$maxHuman)")
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
     * Используется для TWord, TDWord, TFloat и TPrmList на полном регистре.
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