// ConnectionSettings.kt

package com.intmash.modbus_scanner.data

import android.content.Context

/**
 * ConnectionSettings — хранилище настроек Modbus-соединения.
 *
 * Две настройки, которые нужны для подключения к контроллеру:
 *  - скорость (baud rate): 9600..230400;
 *  - адрес в сети Modbus (slave address): 1..247.
 *
 * Хранение через SharedPreferences — стандартный подход для 2-3 значений.
 * Настройки сохраняются даже при убийстве процесса и переживают
 * перезагрузку телефона. Пользователь выбирает один раз — дальше
 * приложение автоматически подключается с этими параметрами.
 *
 * API простое: get/set для каждой настройки. Класс создаётся заново
 * там, где нужно (не Singleton), но данные — одни и те же: общий
 * SharedPreferences-файл. Это потокобезопасно, потому что SharedPreferences
 * внутренне синхронизирован.
 *
 * @param context Любой Context. Внутри берётся applicationContext,
 *                чтобы случайно не удержать Activity в памяти.
 */
class ConnectionSettings(context: Context) {

    /**
     * applicationContext — не удерживает Activity. Если передать Activity,
     * то класс, который переживёт Activity (например, ViewModel), мог бы
     * её случайно «запомнить» и создать утечку памяти. applicationContext
     * от этого защищён.
     */
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** Текущая скорость (baud rate). По умолчанию 115200. */
    fun getBaudRate(): Int = prefs.getInt(KEY_BAUD_RATE, DEFAULT_BAUD_RATE)

    /**
     * Сохраняет скорость. Не валидирует — предполагается, что вызывающий
     * код передаёт значение из [SUPPORTED_BAUD_RATES] (например, из
     * выпадающего списка в UI, где другой выбор невозможен).
     */
    fun setBaudRate(value: Int) {
        prefs.edit().putInt(KEY_BAUD_RATE, value).apply()
    }

    /** Текущий адрес в сети Modbus. По умолчанию 1. */
    fun getSlaveAddress(): Int = prefs.getInt(KEY_SLAVE_ADDRESS, DEFAULT_SLAVE_ADDRESS)

    /**
     * Сохраняет адрес. Не валидирует — предполагается, что вызывающий
     * код проверил диапазон 1..247 до вызова.
     */
    fun setSlaveAddress(value: Int) {
        prefs.edit().putInt(KEY_SLAVE_ADDRESS, value).apply()
    }

    companion object {
        /** Имя SharedPreferences-файла. Не менять после релиза — иначе настройки "пропадут". */
        private const val PREFS_NAME = "connection_settings"

        private const val KEY_BAUD_RATE = "baud_rate"
        private const val KEY_SLAVE_ADDRESS = "slave_address"

        /** Скорость по умолчанию при первом запуске. */
        const val DEFAULT_BAUD_RATE = 115200

        /** Адрес по умолчанию при первом запуске. */
        const val DEFAULT_SLAVE_ADDRESS = 1

        /**
         * Поддерживаемые скорости. Используется в выпадающем списке диалога.
         * Порядок — по возрастанию, как в стандартах Modbus.
         */
        val SUPPORTED_BAUD_RATES = listOf(9600, 19200, 38400, 57600, 115200, 230400)

        /** Минимальный допустимый адрес в сети Modbus. */
        const val MIN_SLAVE_ADDRESS = 1

        /** Максимальный допустимый адрес в сети Modbus (247 по стандарту). */
        const val MAX_SLAVE_ADDRESS = 247
    }
}