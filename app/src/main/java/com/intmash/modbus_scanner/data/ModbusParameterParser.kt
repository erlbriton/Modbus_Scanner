// intmash/modbus_scanner/data/ModbusParameterParser.kt

package com.intmash.modbus_scanner.data

/**
 * ModbusParameterParser — преобразует "сырой" [ParameterInfo] (как распарсил INI-файл)
 * в типизированный [ModbusParameter] (готовый к Modbus-операциям).
 *
 * Ответственность класса — ТОЛЬКО парсинг. Он ничего не знает про USB, Modbus-запросы
 * и UI. Это позволяет легко тестировать его отдельно и переиспользовать.
 *
 * Если параметр в INI описан с ошибкой (неизвестный тип, битый регистр, TByte без .L/.H),
 * парсер возвращает null. Такой параметр просто не попадёт в список отображаемых,
 * и не сломает UI.
 *
 * Масштаб ([ModbusParameter.scaleFactor]) резолвится в два этапа:
 *   - если в INI-строке параметра в поле масштаба стоит ЧИСЛО ("0.00390625" или "0,00390625"),
 *     оно берётся напрямую (запятая заменяется на точку);
 *   - если там ИМЯ шкалы ("CINScale", "FracDecScale"), значение ищется в словаре [vars],
 *     который передаётся в [parse] как Map<String, Double>;
 *   - если поля нет, имя не найдено, значение пустое или битое — молча считаем 1.0
 *     (без масштабирования).
 */
object ModbusParameterParser {

    /**
     * Главная точка входа. Возвращает [ModbusParameter] или null, если параметр
     * не удалось разобрать (неизвестный тип, некорректный адрес, несовпадение
     * типа и формы адреса).
     *
     * @param param Описание параметра из INI-файла.
     * @param variables Словарь из секции [vars] конкретного INI-файла.
     *                  Если параметр указывает на имя шкалы, значение берётся оттуда.
     *                  Если в INI нет [vars] или параметр использует числовое значение,
     *                  этот аргумент можно передать пустым (пустой Map) — будет 1.0.
     */
    fun parse(param: ParameterInfo, variables: Map<String, Double>): ModbusParameter? {
        // 1. Разбираем тип. Неизвестный тип — параметр пропускается.
        val type = parseType(param.type) ?: return null

        // 2. Разбираем адрес регистра (Register / Bit / ByteField).
        val address = parseRegister(param.register) ?: return null

        // 3. Проверяем соответствие "тип ↔ форма адреса":
        //    TBit требует Bit, TByte требует ByteField,
        //    TWord/TInteger/TDWord/TFloat требуют Register,
        //    TPrmList может быть и Register, и ByteField.
        if (!isAddressValidForType(type, address)) return null

        // 4. Определяем масштаб.
        //    У TBit/TFloat/TPrmList масштаб не применяется — они всегда физические:
        //      - TBit: 0/1 без масштаба;
        //      - TFloat: сам по себе физическое значение (IEEE 754);
        //      - TPrmList: значение — код из списка, не число.
        //    У остальных типов масштаб берём из rawFields[0] с резолвингом через variables.
        val scaleFactor = when (type) {
            ModbusType.TBit, ModbusType.TFloat, ModbusType.TPrmList -> 1.0
            else -> resolveScale(param.rawFields.getOrNull(0) ?: "", variables)
        }

        // 5. Размер: сколько 16-битных регистров занимает параметр.
        //    TDWord и TFloat — 2 регистра (32 бита). Остальные — 1.
        val registerCount = when (type) {
            ModbusType.TDWord, ModbusType.TFloat -> 2
            else -> 1
        }

        // 6. Для TPrmList — разбираем словарь "значение → отображаемое имя".
        //    Для остальных типов pRmlist = null.
        val pRmlist = if (type == ModbusType.TPrmList) parsePrmList(param.rawFields) else null

        // 7. Единица измерения: в INI маркер "нет единицы" — это "*".
        //    В ModbusParameter пустая строка значит "нет единицы".
        val unit = if (param.unit == "*") "" else param.unit

        return ModbusParameter(
            key = param.key,
            name = param.name,
            description = param.description,
            unit = unit,
            type = type,
            scaleFactor = scaleFactor,
            address = address,
            registerCount = registerCount,
            pRmlist = pRmlist,
            source = param
        )
    }

    /**
     * Разбирает строку типа из INI ("TWORD", "TBit", "TFloat", "TInteger" и т.п.)
     * в enum [ModbusType]. Регистр не важен — приводим к верхнему.
     */
    private fun parseType(raw: String): ModbusType? = when (raw.uppercase()) {
        "TBIT" -> ModbusType.TBit
        "TBYTE" -> ModbusType.TByte
        "TWORD" -> ModbusType.TWord
        "TINTEGER" -> ModbusType.TInteger
        "TDWORD" -> ModbusType.TDWord
        "TFLOAT" -> ModbusType.TFloat
        "TPRMLIST" -> ModbusType.TPrmList
        else -> null
    }

    /**
     * Резолвит строку масштаба из INI-строки параметра в числовое значение.
     *
     * Логика:
     *   1. Пусто → 1.0 (без масштаба).
     *   2. Похоже на число ("0.00390625" или "0,00390625") → это число.
     *   3. Имя в [variables] ("CINScale", "FracDecScale") → значение из словаря.
     *   4. Иначе → 1.0 (молча, по договорённости — не ругаемся на неизвестные шкалы).
     *
     * Запятая как десятичный разделитель поддерживается: "0,1" → 0.1.
     */
    private fun resolveScale(raw: String, variables: Map<String, Double>): Double {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return 1.0

        // Сначала пробуем как число (замена запятой на точку).
        val numeric = trimmed.replace(',', '.').toDoubleOrNull()
        if (numeric != null) return numeric

        // Затем как имя из [vars].
        return variables[trimmed] ?: 1.0
    }

    /**
     * Проверяет, что форма адреса допустима для данного типа.
     *
     * Без этой проверки, например, TBit с адресом "r0004" (без ".N") попал бы в UI
     * и при запросе чтения вернул бы непонятно что. Лучше отбраковать на этапе парсинга.
     */
    private fun isAddressValidForType(type: ModbusType, address: RegisterAddress): Boolean {
        return when (type) {
            ModbusType.TBit -> address is RegisterAddress.Bit
            ModbusType.TByte -> address is RegisterAddress.ByteField
            ModbusType.TWord, ModbusType.TInteger, ModbusType.TDWord, ModbusType.TFloat ->
                address is RegisterAddress.Register
            // TPrmList может быть и байтом, и полным регистром:
            //   p405 = .../TPrmList/xE009/rC004.H/...   ← половина байта (высокий)
            //   p503 = .../TPrmList/xF003/r2001.H/...   ← половина байта
            // На будущее, если встретится полный регистр — не отбраковываем.
            ModbusType.TPrmList ->
                address is RegisterAddress.Register || address is RegisterAddress.ByteField
        }
    }

    /**
     * Разбирает строку адреса из INI в один из вариантов [RegisterAddress].
     *
     * Поддерживаемые форматы:
     *   "r0004"    → Register(0x0004)
     *   "r0000.0"  → Bit(0x0000, 0)          (бит 0 младшего байта)
     *   "r0000.A"  → Bit(0x0000, 10)         (hex 'A' = 10)
     *   "rC004.L"  → ByteField(0xC004, Low)  (младший байт)
     *   "rC004.H"  → ByteField(0xC004, High) (старший байт)
     *
     * Всё, что не подходит под эти форматы, возвращает null.
     */
    private fun parseRegister(raw: String): RegisterAddress? {
        val trimmed = raw.trim()
        if (trimmed.length < 2) return null

        // Первый символ должен быть "r" (регистр). Регистр буквы не важен.
        if (!trimmed[0].equals('r', ignoreCase = true)) return null

        val body = trimmed.substring(1)

        if (body.contains('.')) {
            // Форма "rXXXX.SUFFIX" — бит или половина байта.
            val dotIndex = body.indexOf('.')
            val hexPart = body.substring(0, dotIndex)
            val suffix = body.substring(dotIndex + 1)

            val number = hexPart.toIntOrNull(16) ?: return null

            // Порядок проверок важен. Суффиксы L и H в hex-системе — это
            // числа 11 и 12, но в INI они обозначают половину байта
            // (Low/High), а не номер бита. Поэтому сначала проверяем
            // L/H, и только если не совпало — пытаемся распарсить
            // суффикс как hex-число (номер бита 0..15).
            when (suffix.uppercase()) {
                "L" -> return RegisterAddress.ByteField(number, BytePart.Low)
                "H" -> return RegisterAddress.ByteField(number, BytePart.High)
            }

            // Номер бита в шестнадцатеричной системе (0..F → 0..15).
            // Примеры: "0" → 0, "9" → 9, "A" → 10, "F" → 15.
            val bitNumber = suffix.toIntOrNull(16)
            if (bitNumber != null && bitNumber in 0..15) {
                return RegisterAddress.Bit(number, bitNumber)
            }
            return null
        }

        // Форма "rXXXX" без суффикса — обычный регистр.
        val number = body.toIntOrNull(16) ?: return null
        return RegisterAddress.Register(number)
    }

    /**
     * Разбирает список вариантов для TPrmList.
     *
     * Пример rawFields для p405 (USB_BPS):
     *   ["", "x01#9600", "x02#19200", "x03#57600", "x04#115200", "x04", ""]
     *
     * Поле [0] — всегда пустое (место масштаба).
     * Поля [1..N-1] — варианты в формате "xNN#значение".
     * Последнее непустое поле без "#" — это значение по умолчанию (нам не нужно).
     *
     * Возвращает {1: "9600", 2: "19200", ...} или null, если вариантов нет.
     */
    private fun parsePrmList(rawFields: List<String>): Map<Int, String>? {
        val result = mutableMapOf<Int, String>()
        for (i in 1 until rawFields.size) {
            val field = rawFields[i].trim()
            if (field.isEmpty()) continue

            // Поле с "#" — это вариант. Поле без "#" — default, пропускаем.
            if (!field.contains('#')) continue

            val parts = field.split('#', limit = 2)
            if (parts.size != 2) continue

            // parts[0] — "xNN", parts[1] — отображаемое значение ("9600").
            val idStr = parts[0].removePrefix("x").removePrefix("X")
            val id = idStr.toIntOrNull(16) ?: continue
            val label = parts[1].trim()
            result[id] = label
        }
        return result.ifEmpty { null }
    }
}

/**
 * Удобное расширение: преобразовать все параметры страницы в [ModbusParameter].
 * Параметры, которые не удалось разобрать, молча пропускаются — чтобы одна ошибка
 * в INI-файле не ломала отображение всей страницы.
 *
 * Словарь шкал [PageInfo.variables] (из секции [vars]) передаётся в парсер,
 * чтобы строковые имена масштабов ("CINScale") резолвились в числовые значения.
 */
fun PageInfo.toModbusParameters(): List<ModbusParameter> =
    parameters.mapNotNull { param ->
        ModbusParameterParser.parse(param, this.variables)
    }