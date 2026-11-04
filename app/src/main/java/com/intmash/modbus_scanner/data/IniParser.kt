package com.intmash.modbus_scanner.data

import android.content.Context
import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.charset.Charset

/**
 * ParameterInfo — описание одного параметра из секций [RAM], [FLASH], [CD] INI-файла.
 *
 * Формат строки в INI (общий вид — name/desc/type/address/register/unit/...):
 *   p201 = Uref/Напряжение задания/TWORD/x0008/r0004/V/FracDecScale/2/0/
 *   p1   = flash_error/описание/TBit/x0000/1/r0000.0/0/1/0/
 *
 * Общие поля:
 *   - key         — внутренний ID (например, "p201")
 *   - name        — имя параметра (используется в [PAGES])
 *   - description — человекочитаемое описание
 *   - type        — тип данных (TBit, TWORD, TDWORD, TByte, TFloat, TPrmList)
 *   - address     — внутренний адрес контроллера (x0008)
 *   - register    — Modbus-адрес (r0004 или r0000.0 для бита)
 *   - unit        — единица измерения (может быть пустой)
 *
 * Остальные поля (масштаб, размер, значение по умолчанию, список и т.д.)
 * не используются при отображении, но сохраняются в [rawFields] для будущего
 * чтения/записи.
 */
data class ParameterInfo(
    val key: String,
    val name: String,
    val description: String,
    val type: String,
    val address: String,
    val register: String,
    val unit: String,
    val rawFields: List<String>
)

/**
 * Ссылка на параметр внутри страницы [PAGES]: "RAM:Uref" → PageParameterRef("RAM", "Uref").
 */
data class PageParameterRef(
    val section: String,
    val name: String
)

/**
 * PageInfo — модель страницы из секции [PAGES].
 * Параметры уже разрешены: каждой ссылке сопоставлен [ParameterInfo] из соответствующей
 * секции INI-файла. Несуществующие параметры просто отбрасываются.
 */
data class PageInfo(
    val id: String,
    val title: String,
    val parameters: List<ParameterInfo>,
    val variables: Map<String, Double>
)

/**
 * IniParser — парсер INI-файла конфигурации контроллера.
 * Читает секцию [PAGES] и секции с параметрами ([RAM], [FLASH], [CD] и любые другие,
 * кроме [DEVICE], [EVENTS], [VARS]), возвращает список страниц с разрешёнными параметрами.
 */
class IniParser {

    fun parsePages(context: Context, uri: Uri): List<PageInfo> {
        val pagesRaw = mutableListOf<Triple<String, String, List<PageParameterRef>>>()
        val parametersBySection = mutableMapOf<String, MutableMap<String, ParameterInfo>>()
        val variables = mutableMapOf<String, Double>()

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                // Читаем весь файл в память целиком. Дальше делаем два прохода по списку
                // строк — это дешевле, чем открывать поток дважды.
                val lines = BufferedReader(
                    InputStreamReader(inputStream, Charset.forName("Windows-1251"))
                ).use { it.readLines() }

                // ПЕРВЫЙ ПРОХОД: секция [vars]. Наполняем словарь шкал.
                // Важно, что делаем это ДО второго прохода: во втором проходе
                // шкалы параметров будут резолвиться через этот словарь.
                parseVarsSection(lines, variables)

                // ВТОРОЙ ПРОХОД: секция [PAGES] и секции с параметрами ([RAM], [FLASH], [CD] и т.д.).
                // Секция [vars] во втором проходе игнорируется (она уже разобрана).
                parseParametersSections(lines, pagesRaw, parametersBySection)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        // Собираем итоговые страницы. Один и тот же словарь variables
        // передаётся во все PageInfo этого INI-файла.
        return pagesRaw.map { (id, title, refs) ->
            val params = refs.mapNotNull { ref ->
                parametersBySection[ref.section]?.get(ref.name)
            }
            PageInfo(id, title, params, variables)
        }
    }

    /**
     * Первый проход: разбирает только секцию [vars].
     *
     * В [vars] могут быть строки вида:
     *   IntegerScale=1
     *   FracDecScale=0,1              ← запятая как десятичный разделитель
     *   CINScale=0,0226
     *   AOUT2K_scale=0.00390625       ← точка тоже допустима
     *
     * Правила разбора:
     *   - ключом считается всё до первого '=' (trim);
     *   - в значении запятая заменяется на точку — поддержка русской локали;
     *   - пустые значения, нечисловые значения и пустые ключи МОЛЧА пропускаются
     *     (по договорённости: не ругаться на «странные» строки в INI-файле).
     *
     * Результат складывается в переданный [out]. Метод ничего не возвращает.
     */
    private fun parseVarsSection(lines: List<String>, out: MutableMap<String, Double>) {
        var inVars = false
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.isEmpty() || line.startsWith(";") -> {
                    // пустые строки и комментарии игнорируем
                }
                line.startsWith("[") && line.endsWith("]") -> {
                    // смена секции: интересна только [vars]
                    val section = line.substring(1, line.length - 1).uppercase()
                    inVars = (section == "VARS")
                }
                inVars && line.contains("=") -> {
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) {
                        val name = parts[0].trim()
                        val valueStr = parts[1].trim().replace(',', '.')
                        if (name.isNotEmpty()) {
                            val value = valueStr.toDoubleOrNull()
                            if (value != null) {
                                out[name] = value
                            }
                            // нечисловые значения молча пропускаем
                        }
                    }
                }
            }
        }
    }

    /**
     * Второй проход: собирает страницы из [PAGES] и параметры из секций
     * с параметрами ([RAM], [FLASH], [CD] и любых других, кроме [DEVICE],
     * [EVENTS], [VARS]).
     *
     * Результаты складываются в [pagesRaw] (сырые страницы до разрешения ссылок)
     * и [parametersBySection] (все параметры, сгруппированные по имени секции).
     * Разрешение ссылок (RAM:Uref → конкретный ParameterInfo) выполняется
     * в [parsePages] после второго прохода.
     */
    private fun parseParametersSections(
        lines: List<String>,
        pagesRaw: MutableList<Triple<String, String, List<PageParameterRef>>>,
        parametersBySection: MutableMap<String, MutableMap<String, ParameterInfo>>
    ) {
        var currentSection = ""
        for (raw in lines) {
            val line = raw.trim()
            when {
                line.startsWith("[") && line.endsWith("]") -> {
                    currentSection = line.substring(1, line.length - 1).uppercase()
                }
                line.isEmpty() || line.startsWith(";") -> {
                    // пропускаем пустые строки и комментарии
                }
                line.contains("=") -> {
                    when (currentSection) {
                        "PAGES" -> parsePageLine(line)?.let { pagesRaw.add(it) }
                        // Служебные секции не содержат Modbus-параметров.
                        // [VARS] уже разобрана в первом проходе.
                        "DEVICE", "EVENTS", "VARS" -> {
                            // не параметры
                        }
                        else -> {
                            if (currentSection.isNotEmpty()) {
                                val param = parseParameterLine(line)
                                if (param != null) {
                                    parametersBySection
                                        .getOrPut(currentSection) { mutableMapOf() }[param.name] = param
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun parsePageLine(line: String): Triple<String, String, List<PageParameterRef>>? {
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return null

        val pageId = parts[0].trim()
        val valuePart = parts[1].trim()

        val subParts = valuePart.split("/", limit = 3)
        val title = if (subParts.isNotEmpty() && subParts[0].isNotBlank()) {
            subParts[0].trim()
        } else {
            pageId
        }

        val rawParams = if (subParts.size > 1) subParts[1] else ""

        val refs = if (rawParams.isNotBlank()) {
            rawParams.split(",").mapNotNull { raw ->
                val trimmed = raw.trim()
                val idx = trimmed.indexOf(":")
                if (idx < 0) null
                else {
                    val section = trimmed.substring(0, idx).trim().uppercase()
                    val name = trimmed.substring(idx + 1).trim()
                    if (section.isNotEmpty() && name.isNotEmpty()) {
                        PageParameterRef(section, name)
                    } else null
                }
            }
        } else {
            emptyList()
        }

        return Triple(pageId, title, refs)
    }

    private fun parseParameterLine(line: String): ParameterInfo? {
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return null

        val key = parts[0].trim()
        val valuePart = parts[1].trim()

        val fields = valuePart.split("/")
        if (fields.size < 4) return null

        val name = fields.getOrElse(0) { "" }.trim()
        if (name.isEmpty()) return null

        val description = fields.getOrElse(1) { "" }.trim()
        val type = fields.getOrElse(2) { "" }.trim()

        return if (type.equals("TBit", ignoreCase = true)) {
            // name/desc/TBit/addr/bit/reg/...
            ParameterInfo(
                key = key,
                name = name,
                description = description,
                type = type,
                address = fields.getOrElse(3) { "" }.trim(),
                register = fields.getOrElse(5) { "" }.trim(),
                unit = "",
                rawFields = fields.drop(6)
            )
        } else {
            // name/desc/type/addr/reg/unit/...
            ParameterInfo(
                key = key,
                name = name,
                description = description,
                type = type,
                address = fields.getOrElse(3) { "" }.trim(),
                register = fields.getOrElse(4) { "" }.trim(),
                unit = fields.getOrElse(5) { "" }.trim(),
                rawFields = fields.drop(6)
            )
        }
    }
}