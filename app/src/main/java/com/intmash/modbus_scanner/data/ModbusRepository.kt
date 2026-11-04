// ModbusRepository.kt

package com.intmash.modbus_scanner.data

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.ByteArrayOutputStream

class ModbusRepository {

    private var usbPort: UsbSerialPort? = null
    private var usbConnection: UsbDeviceConnection? = null

    private var currentManager: UsbManager? = null
    private var currentDevice: UsbDevice? = null

    /**
     * Скорость, с которой последний раз успешно открывался порт.
     * Нужна для reopen(): когда порт "завис" и MainViewModel пытается
     * переоткрыть его, нужно использовать ту же скорость, что и раньше.
     * Дефолт 115200 — значение по умолчанию и для ConnectionSettings.
     */
    private var lastBaudRate: Int = ConnectionSettings.DEFAULT_BAUD_RATE

    /**
     * Возвращает deviceName текущего открытого устройства или null.
     * Используется для проверки, что detach пришёл именно для нашего устройства.
     * deviceName — стабильное имя физического порта (например, "/dev/bus/usb/001/002"),
     * в отличие от deviceId (Int), которое может меняться.
     */
    fun getCurrentDeviceName(): String? = currentDevice?.deviceName

    /**
     * Открывает USB-порт для связи с контроллером.
     *
     * @param usbManager Системный сервис USB Android.
     * @param device Физическое USB-устройство.
     * @param baudRate Скорость UART (baud rate). Берётся из настроек соединения
     *                 ([ConnectionSettings.getBaudRate]) — пользователь может
     *                 выбрать одну из 6 стандартных скоростей.
     * @return true, если порт открыт и настроен.
     */
    fun openPort(usbManager: UsbManager, device: UsbDevice, baudRate: Int): Boolean {
        Log.e("ModbusRepository", "openPort(): старт, device=$device, baudRate=$baudRate")

        close()

        currentManager = usbManager
        currentDevice = device
        // Запоминаем скорость для reopen(). Если открытие провалится ниже,
        // lastBaudRate всё равно сохранится — при следующем reopen() будет
        // та же скорость. Если пользователь сменил её в настройках и вызвал
        // openPort с новой — она перезапишет старое значение.
        lastBaudRate = baudRate

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        Log.e("ModbusRepository", "openPort(): найдено драйверов — ${availableDrivers.size}")
        val driver = availableDrivers.find { it.device == device }

        if (driver == null) {
            Log.e("ModbusRepository", "Драйвер USB-устройства не найден")
            return false
        }

        // Пауза ПЕРЕД openDevice: после detach/attach ядру нужно время,
        // чтобы освободить файловый дескриптор. Без этой паузы openDevice()
        // может вернуть null при повторном подключении.
        try { Thread.sleep(300) } catch (_: InterruptedException) {}

        val connection: UsbDeviceConnection = usbManager.openDevice(driver.device) ?: run {
            Log.e("ModbusRepository", "openPort(): openDevice вернул null")
            return false
        }
        Log.e("ModbusRepository", "openPort(): openDevice OK")

        usbConnection = connection

        try {
            usbPort = driver.ports[0].apply {
                open(connection)
                dtr = false
                rts = false
                Thread.sleep(100)
                dtr = true
                rts = true
                // Скорость теперь приходит параметром — раньше было жёстко 115200.
                // Формат кадра неизменен: 8 бит данных, 1 стоп-бит, без чётности (8N1).
                setParameters(baudRate, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            }

            Thread.sleep(500)

            Log.e("ModbusRepository", "USB-порт успешно открыт на $baudRate 8N1")
            return true

        } catch (e: Exception) {
            Log.e("ModbusRepository", "Ошибка при открытии порта: ${e.message}")
            close()
            return false
        }
    }

    private fun drainPort(port: UsbSerialPort, timeoutMs: Int = 30) {
        val buf = ByteArray(64)
        try {
            while (port.read(buf, timeoutMs) > 0) {
                // выбрасываем
            }
        } catch (_: Exception) {}
    }

    fun reopen(): Boolean {
        val mgr = currentManager
        val dev = currentDevice
        Log.e("ModbusRepository", "reopen(): mgr=${mgr != null}, dev=${dev != null}, baudRate=$lastBaudRate")
        if (mgr == null || dev == null) return false
        // Используем ту же скорость, с которой порт открывался в прошлый раз.
        // lastBaudRate обновляется в openPort при каждом успешном вызове.
        val ok = openPort(mgr, dev, lastBaudRate)
        Log.e("ModbusRepository", "reopen(): openPort вернул $ok")
        return ok
    }

    /**
     * Отправляет запрос 0x11 (Report Server ID) для чтения идентификатора устройства.
     *
     * @param slaveAddr Адрес slave в сети Modbus (1..247). Берётся из настроек
     *                  соединения (ConnectionSettings.getSlaveAddress) — пользователь
     *                  может изменить через диалог "Настройки".
     * @return Строка идентификатора, либо null при ошибке.
     */
    fun readDeviceId(slaveAddr: Int): String? {
        val port = usbPort ?: run {
            Log.e("ModbusRepository", "readDeviceId(): usbPort == null")
            return null
        }

        Log.e("ModbusRepository", "readDeviceId(): slave=$slaveAddr, isOpen=${port.isOpen}, dtr=${port.dtr}, rts=${port.rts}")

        // Адрес slave теперь приходит параметром — раньше был жёстко 0x01.
        val request = byteArrayOf((slaveAddr and 0xFF).toByte(), 0x11.toByte())
        val crc = calculateCRC16(request)
        val fullRequest = request + crc

        try {
            drainPort(port)
            Thread.sleep(30)
            port.purgeHwBuffers(true, true)

            port.write(fullRequest, 1000)
            Log.e("ModbusRepository", "Запрос отправлен: ${fullRequest.joinToString(" ") { "%02X".format(it) }}")

            val startTime = System.currentTimeMillis()
            val timeoutMs = 1500L
            val accumulatedData = ByteArrayOutputStream()
            val readBuffer = ByteArray(64)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val remainingTime = (timeoutMs - (System.currentTimeMillis() - startTime)).toInt().coerceAtLeast(50)
                val bytesRead = port.read(readBuffer, remainingTime)

                if (bytesRead > 0) {
                    accumulatedData.write(readBuffer, 0, bytesRead)
                    val currentBytes = accumulatedData.toByteArray()
                    Log.e("ModbusRepository", "принято $bytesRead байт, всего ${currentBytes.size}: ${currentBytes.joinToString(" ") { "%02X".format(it) }}")

                    if (currentBytes.size >= 3) {
                        val dataLength = currentBytes[2].toInt() and 0xFF
                        val expectedTotalLength = 3 + dataLength + 2

                        if (currentBytes.size >= expectedTotalLength || currentBytes.size >= 52) {
                            drainPort(port)

                            val slaveId = currentBytes[0].toInt() and 0xFF
                            val funcCode = currentBytes[1].toInt() and 0xFF
                            Log.e("ModbusRepository", "пакет: slaveId=0x%02X, funcCode=0x%02X, dataLength=$dataLength".format(slaveId, funcCode))

                            if (slaveId == (slaveAddr and 0xFF) && funcCode == 0x11) {
                                val payloadEnd = minOf(3 + dataLength, currentBytes.size - 2)
                                var idText = ""
                                for (i in 3 until payloadEnd) {
                                    val b = currentBytes[i].toInt() and 0xFF
                                    if (b >= 32) idText += b.toChar()
                                }
                                val receivedData = idText.trim()
                                if (receivedData.isNotEmpty()) {
                                    Log.e("ModbusRepository", "Успешно прочитан ID: '$receivedData'")
                                    return receivedData
                                }
                            }
                            break
                        }
                    }
                }
            }
            Log.e("ModbusRepository", "Таймаут чтения ответа")
        } catch (e: Exception) {
            Log.e("ModbusRepository", "Ошибка обмена данными: ${e.message}")
        }
        return null
    }

    /**
     * Читает N 16-битных регистров начиная с адреса [startRegister] по протоколу
     * Modbus RTU, функция 0x03 (Read Holding Registers).
     *
     * Это основной запрос для чтения параметров из секций [RAM], [FLASH], [CD].
     * Один запрос может прочитать до 125 регистров за раз, что даёт кратное
     * ускорение опроса: одна транзакция вместо N отдельных.
     *
     * @param slaveAddr Адрес подчинённого устройства. Пока всегда 1 (по договорённости).
     * @param startRegister Начальный адрес (0x0000..0xFFFF).
     * @param count Сколько регистров прочитать (1..125).
     * @return Массив прочитанных значений (каждое 0..0xFFFF), либо null при ошибке.
     *         null возвращается и при отсутствии ответа, и при Modbus-исключении
     *         (например, контроллер ответил кодом 0x02 "Illegal data address").
     */
    fun readHoldingRegisters(slaveAddr: Int, startRegister: Int, count: Int): IntArray? {
        val port = usbPort ?: run {
            Log.e("ModbusRepository", "readHoldingRegisters(): usbPort == null")
            return null
        }

        // Стандарт Modbus ограничивает один кадр 125 регистрами (250 байт данных).
        if (count < 1 || count > 125) {
            Log.e("ModbusRepository", "readHoldingRegisters(): некорректный count=$count")
            return null
        }

        // Тело запроса функции 0x03:
        // [slave][0x03][startHi][startLo][countHi][countLo][CRC16_lo][CRC16_hi]
        val request = byteArrayOf(
            (slaveAddr and 0xFF).toByte(),
            0x03.toByte(),
            ((startRegister shr 8) and 0xFF).toByte(),
            (startRegister and 0xFF).toByte(),
            ((count shr 8) and 0xFF).toByte(),
            (count and 0xFF).toByte()
        )
        val crc = calculateCRC16(request)
        val fullRequest = request + crc

        try {
            // Чистим буфер от "хвостов" предыдущих обменов (например, readDeviceId).
            drainPort(port)
            Thread.sleep(5)
            port.purgeHwBuffers(true, true)

            port.write(fullRequest, 1000)
            Log.e(
                "ModbusRepository",
                "0x03 → slave=$slaveAddr, reg=0x%04X, count=$count, пакет: %s".format(
                    startRegister,
                    fullRequest.joinToString(" ") { "%02X".format(it) }
                )
            )

            // Ожидаемая длина ответа: 3 байта заголовка (slave, func, byteCount)
            // + byteCount байт данных + 2 байта CRC.
            val expectedByteCount = count * 2
            val expectedTotalLength = 3 + expectedByteCount + 2

            val startTime = System.currentTimeMillis()
            // Таймаут одного запроса. Короче, чем в readDeviceId (там 1500 мс):
            // при опросе важно быстро вернуть null, а не ждать секунды при обрыве.
            val timeoutMs = 500L
            val accumulatedData = ByteArrayOutputStream()
            val readBuffer = ByteArray(64)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val remainingTime = (timeoutMs - (System.currentTimeMillis() - startTime))
                    .toInt().coerceAtLeast(50)
                val bytesRead = port.read(readBuffer, remainingTime)

                if (bytesRead > 0) {
                    accumulatedData.write(readBuffer, 0, bytesRead)
                    val currentBytes = accumulatedData.toByteArray()

                    // Modbus Exception: старший бит байта функции = 1 (0x83 для 0x03).
                    // Формат ответа-исключения: [slave][0x83][exceptionCode][CRC16].
                    if (currentBytes.size >= 5 && (currentBytes[1].toInt() and 0x80) != 0) {
                        val exceptionCode = currentBytes[2].toInt() and 0xFF
                        Log.e(
                            "ModbusRepository",
                            "Modbus Exception: func=0x%02X, code=0x%02X".format(
                                currentBytes[1].toInt() and 0xFF,
                                exceptionCode
                            )
                        )
                        drainPort(port)
                        return null
                    }

                    // Обычный ответ — дочитываем до полной длины.
                    if (currentBytes.size >= expectedTotalLength) {
                        drainPort(port)
                        return parseHoldingRegistersResponse(currentBytes, slaveAddr, count)
                    }
                }
            }
            Log.w("ModbusRepository", "readHoldingRegisters: таймаут")
        } catch (e: Exception) {
            Log.e("ModbusRepository", "readHoldingRegisters: ошибка: ${e.message}")
        }
        return null
    }

    /**
     * Разбирает ответ на запрос 0x03 и возвращает массив значений.
     * Формат: [slave][0x03][byteCount][dataHi0][dataLo0]...[CRC16_lo][CRC16_hi].
     *
     * Каждый регистр — 2 байта в big-endian (старший байт приходит первым).
     * Это стандарт Modbus RTU. Если конкретное устройство использует другой порядок,
     * это будет видно по мусорным значениям, и решается заменой порядка.
     */
    private fun parseHoldingRegistersResponse(
        data: ByteArray,
        expectedSlave: Int,
        expectedCount: Int
    ): IntArray? {
        val slave = data[0].toInt() and 0xFF
        if (slave != expectedSlave) {
            Log.e("ModbusRepository", "Неверный slave в ответе: $slave (ожидали $expectedSlave)")
            return null
        }

        val func = data[1].toInt() and 0xFF
        if (func != 0x03) {
            Log.e("ModbusRepository", "Неверный func в ответе: 0x%02X".format(func))
            return null
        }

        val byteCount = data[2].toInt() and 0xFF
        if (byteCount != expectedCount * 2) {
            Log.e(
                "ModbusRepository",
                "Неверный byteCount: $byteCount (ожидали ${expectedCount * 2})"
            )
            return null
        }

        val result = IntArray(expectedCount)
        for (i in 0 until expectedCount) {
            val hi = data[3 + i * 2].toInt() and 0xFF
            val lo = data[3 + i * 2 + 1].toInt() and 0xFF
            result[i] = (hi shl 8) or lo
        }
        return result
    }

    /**
     * Записывает N 16-битных регистров начиная с адреса [startRegister]
     * по протоколу Modbus RTU, функция 0x10 (Write Multiple Registers).
     *
     * Это основной запрос для записи параметров. Один запрос может записать
     * до 123 регистров за раз. Используется:
     *  - напрямую для TWord/TDWord/TFloat (один регистр или два);
     *  - для TByte/TBit — в связке с Read-Modify-Write (сначала прочитать,
     *    изменить бит/байт, потом записать весь регистр через этот метод).
     *
     * Формат запроса:
     *   [slave][0x10][startHi][startLo][countHi][countLo][byteCount][data...][CRC]
     * Формат ответа (эхо заголовка):
     *   [slave][0x10][startHi][startLo][countHi][countLo][CRC]  — ровно 8 байт.
     *
     * @param slaveAddr Адрес подчинённого устройства. Пока всегда 1.
     * @param startRegister Начальный адрес (0x0000..0xFFFF).
     * @param values Массив значений (каждое 0..0xFFFF, младшие 16 бит).
     * @return true при успехе (контроллер подтвердил запись), false при ошибке.
     *         false возвращается и при "нет ответа", и при Modbus-исключении
     *         (например, 0x02 "Illegal data address" — попытка записи в read-only).
     */
    fun writeHoldingRegisters(slaveAddr: Int, startRegister: Int, values: IntArray): Boolean {
        val port = usbPort ?: run {
            Log.e("ModbusRepository", "writeHoldingRegisters(): usbPort == null")
            return false
        }

        val count = values.size
        if (count < 1 || count > 123) {
            Log.e("ModbusRepository", "writeHoldingRegisters(): некорректный count=$count")
            return false
        }

        // Тело запроса функции 0x10:
        // [slave][0x10][startHi][startLo][countHi][countLo][byteCount][dataHi0][dataLo0]...
        val bodySize = 7 + count * 2
        val request = ByteArray(bodySize)
        request[0] = (slaveAddr and 0xFF).toByte()
        request[1] = 0x10.toByte()
        request[2] = ((startRegister shr 8) and 0xFF).toByte()
        request[3] = (startRegister and 0xFF).toByte()
        request[4] = ((count shr 8) and 0xFF).toByte()
        request[5] = (count and 0xFF).toByte()
        request[6] = (count * 2).toByte()  // byteCount
        for (i in 0 until count) {
            val v = values[i] and 0xFFFF
            request[7 + i * 2] = ((v shr 8) and 0xFF).toByte()
            request[7 + i * 2 + 1] = (v and 0xFF).toByte()
        }
        val crc = calculateCRC16(request)
        val fullRequest = request + crc

        try {
            // Чистим буфер от "хвостов" предыдущих обменов.
            drainPort(port)
            Thread.sleep(5)
            port.purgeHwBuffers(true, true)

            port.write(fullRequest, 1000)
            Log.e(
                "ModbusRepository",
                "0x10 → slave=$slaveAddr, reg=0x%04X, count=$count, пакет: %s".format(
                    startRegister,
                    fullRequest.joinToString(" ") { "%02X".format(it) }
                )
            )

            // Ответ на запись 0x10 — фиксированной длины 8 байт (эхо заголовка + CRC).
            val expectedTotalLength = 8

            val startTime = System.currentTimeMillis()
            // Таймаут записи — 500 мс (по договорённости). Стирание FLASH-ячейки
            // может занимать дольше, чем чтение; 500 мс — компромисс.
            val timeoutMs = 500L
            val accumulatedData = ByteArrayOutputStream()
            val readBuffer = ByteArray(64)

            while (System.currentTimeMillis() - startTime < timeoutMs) {
                val remainingTime = (timeoutMs - (System.currentTimeMillis() - startTime))
                    .toInt().coerceAtLeast(50)
                val bytesRead = port.read(readBuffer, remainingTime)

                if (bytesRead > 0) {
                    accumulatedData.write(readBuffer, 0, bytesRead)
                    val currentBytes = accumulatedData.toByteArray()
                    Log.e(
                        "ModbusRepository",
                        "0x10 ← принято $bytesRead байт, всего ${currentBytes.size}: ${
                            currentBytes.joinToString(" ") { "%02X".format(it) }
                        }"
                    )

                    // Modbus Exception: старший бит байта функции = 1 (0x90 для 0x10).
                    // Формат ответа-исключения: [slave][0x90][exceptionCode][CRC16].
                    if (currentBytes.size >= 5 && (currentBytes[1].toInt() and 0x80) != 0) {
                        val exceptionCode = currentBytes[2].toInt() and 0xFF
                        Log.e(
                            "ModbusRepository",
                            "Modbus Exception при записи: func=0x%02X, code=0x%02X".format(
                                currentBytes[1].toInt() and 0xFF,
                                exceptionCode
                            )
                        )
                        drainPort(port)
                        return false
                    }

                    if (currentBytes.size >= expectedTotalLength) {
                        drainPort(port)
                        return parseWriteResponse(currentBytes, slaveAddr, startRegister, count)
                    }
                }
            }
            Log.w("ModbusRepository", "writeHoldingRegisters: таймаут")
        } catch (e: Exception) {
            Log.e("ModbusRepository", "writeHoldingRegisters: ошибка: ${e.message}")
        }
        return false
    }

    /**
     * Разбирает ответ на запрос 0x10 и проверяет, что контроллер подтвердил запись.
     *
     * Ответ 0x10 — это эхо заголовка запроса без данных:
     *   [slave][0x10][startHi][startLo][countHi][countLo][CRC16_lo][CRC16_hi]
     *
     * Проверяем:
     *  - slave совпадает;
     *  - func == 0x10 (исключения уже отсеяны в writeHoldingRegisters);
     *  - startRegister и count в ответе совпадают с тем, что мы отправили.
     *
     * CRC не пересчитываем: контроллер уже ответил, физически данные дошли.
     * Если бы CRC был неверный, большинство устройств вообще не отвечают.
     */
    private fun parseWriteResponse(
        data: ByteArray,
        expectedSlave: Int,
        expectedStart: Int,
        expectedCount: Int
    ): Boolean {
        val slave = data[0].toInt() and 0xFF
        if (slave != expectedSlave) {
            Log.e("ModbusRepository", "0x10: неверный slave в ответе: $slave")
            return false
        }

        val func = data[1].toInt() and 0xFF
        if (func != 0x10) {
            Log.e("ModbusRepository", "0x10: неверный func в ответе: 0x%02X".format(func))
            return false
        }

        val respStart = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val respCount = ((data[4].toInt() and 0xFF) shl 8) or (data[5].toInt() and 0xFF)
        if (respStart != expectedStart || respCount != expectedCount) {
            Log.e(
                "ModbusRepository",
                "0x10: несовпадение эха: start=0x%04X/$expectedStart, count=$respCount/$expectedCount"
                    .format(respStart)
            )
            return false
        }
        Log.e("ModbusRepository", "0x10: запись подтверждена контроллером")
        return true
    }

    /**
     * Закрывает порт. Больше НЕ спит — пауза перенесена в openPort() перед openDevice().
     * Так shutdown() из broadcastReceiver не блокирует главный поток на 300 мс.
     */
    fun close() {
        Log.e("ModbusRepository", "close(): старт")
        try {
            if (usbPort?.isOpen == true) {
                usbPort?.close()
                Log.e("ModbusRepository", "close(): порт закрыт")
            }
        } catch (e: Exception) {
            Log.e("ModbusRepository", "Ошибка при закрытии порта: ${e.message}")
        } finally {
            usbPort = null
        }

        try {
            usbConnection?.close()
            Log.e("ModbusRepository", "close(): соединение закрыто")
        } catch (e: Exception) {
            Log.e("ModbusRepository", "Ошибка при закрытии соединения: ${e.message}")
        } finally {
            usbConnection = null
        }

        Log.e("ModbusRepository", "close(): готово")
    }

    private fun calculateCRC16(data: ByteArray): ByteArray {
        var crc = 0xFFFF
        for (byte in data) {
            crc = crc xor (byte.toInt() and 0xFF)
            for (i in 0 until 8) {
                crc = if ((crc and 0x0001) != 0) {
                    (crc shr 1) xor 0xA001
                } else {
                    crc shr 1
                }
            }
        }
        return byteArrayOf((crc and 0xFF).toByte(), ((crc shr 8) and 0xFF).toByte())
    }
}