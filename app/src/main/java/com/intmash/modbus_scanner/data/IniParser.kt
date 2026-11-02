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
    val parameters: List<ParameterInfo>
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

        try {
            context.contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream, Charset.forName("Windows-1251"))).use { reader ->
                    var currentSection = ""
                    var line: String? = reader.readLine()

                    while (line != null) {
                        val trimmed = line.trim()

                        when {
                            trimmed.startsWith("[") && trimmed.endsWith("]") -> {
                                currentSection = trimmed.substring(1, trimmed.length - 1).uppercase()
                            }
                            trimmed.isEmpty() || trimmed.startsWith(";") -> {
                                // пропускаем пустые строки и комментарии
                            }
                            trimmed.contains("=") -> {
                                when (currentSection) {
                                    "PAGES" -> parsePageLine(trimmed)?.let { pagesRaw.add(it) }
                                    "DEVICE", "EVENTS", "VARS" -> {
                                        // не параметры
                                    }
                                    else -> {
                                        if (currentSection.isNotEmpty()) {
                                            val param = parseParameterLine(trimmed)
                                            if (param != null) {
                                                parametersBySection
                                                    .getOrPut(currentSection) { mutableMapOf() }[param.name] = param
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return emptyList()
        }

        return pagesRaw.map { (id, title, refs) ->
            val params = refs.mapNotNull { ref ->
                parametersBySection[ref.section]?.get(ref.name)
            }
            PageInfo(id, title, params)
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