// MainScreen.kt
package com.intmash.modbus_scanner.ui

import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.ConnectionSettings

/**
 * MainScreen — главный экран приложения, который отображается до выбора INI-файла.
 *
 * Содержит:
 *  - Идентификатор контроллера (Device ID), полученный по Modbus.
 *  - Кнопку выбора INI-файла (активна только при наличии связи).
 *  - Две кнопки-иконки внизу:
 *      * «Настройки» — открывает диалог выбора скорости и адреса Modbus
 *        (значения сохраняются в [ConnectionSettings], SharedPreferences).
 *      * «Поиск» (лупа) — пока заглушка, работа будет описана на следующем шаге.
 *
 * @param deviceId Строка с текущим ID контроллера, полученным по Modbus.
 * @param isConnected Флаг успешности физического подключения к порту.
 * @param isVirtualDevice Флаг режима отладки/виртуального устройства.
 * @param onSelectFileClick Колбэк нажатия кнопки выбора INI-файла.
 * @param errorMessage Текст ошибки (если была) при выборе файла, либо null.
 * @param modifier Модификатор компоновки.
 */
@Composable
fun MainScreen(
    deviceId: String,
    isConnected: Boolean,
    isVirtualDevice: Boolean,
    onSelectFileClick: () -> Unit,
    errorMessage: String?,
    modifier: Modifier = Modifier
) {
    // --- Контекст и хранилище настроек ---
    // LocalContext.current — текущий Context (Activity/MainActivity).
    // remember — создаём ConnectionSettings один раз за жизнь Composable,
    // а не при каждой перерисовке (иначе будет лишний доступ к SharedPreferences).
    val context = LocalContext.current
    val connectionSettings = remember { ConnectionSettings(context) }

    // Флаг «диалог настроек открыт». rememberSaveable — переживает
    // поворот экрана (диалог останется открытым при повороте).
    var showSettingsDialog by rememberSaveable { mutableStateOf(false) }

    // Box — контейнер, центрирующий всё содержимое по центру экрана.
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp)
        ) {
            // Метка-подпись "Device ID:"
            Text(
                text = "Device ID:",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Отображение самого значения Device ID
            Text(
                text = deviceId,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(32.dp))

            HorizontalDivider(modifier = Modifier.width(200.dp))

            Spacer(modifier = Modifier.height(32.dp))

            // --- БИЗНЕС-ЛОГИКА И УСЛОВНЫЙ РЕНДЕРИНГ UI ---

            // Проверяем, получен ли реальный ID контроллера (исключая служебные тексты).
            val isIdReceived = deviceId != "Ожидание ответа..." &&
                    deviceId != "Ошибка подключения к порту" &&
                    isConnected

            // Разрешаем выбор INI-файла либо если устройство виртуальное,
            // либо если получена реальная связь.
            val canSelectIni = isVirtualDevice || isIdReceived

            if (canSelectIni) {
                Button(onClick = onSelectFileClick) {
                    Text("Выберите ini файл", fontSize = 16.sp)
                }

                if (errorMessage != null) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                Text(
                    text = "Ожидание ответа от контроллера...\nВыбор INI заблокирован",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }

            // --- Кнопки "Настройки" и "Поиск" ---
            // Размещены ВСЕГДА, вне if/else: настройки нужны как раз тогда,
            // когда связи нет — чтобы попробовать другую скорость/адрес.
            Spacer(modifier = Modifier.height(32.dp))

            // --- Настройки размера и цвета иконок ---
            // Здесь и только здесь меняются размеры и цвета.
            // iconGraphicSize — размер самой картинки (шестерёнки/лупы) в dp.
            // iconTouchSize   — размер круглой зоны нажатия вокруг иконки в dp.
            //                   Должен быть >= iconGraphicSize (иначе зона нажатия
            //                   меньше картинки, что неудобно для пальца).
            //                   Рекомендация Material: минимум 48.dp.
            // settingsIconColor / searchIconColor — цвета иконок. Любой Color(...).
            val iconGraphicSize = 52.dp
            val iconTouchSize = 80.dp
            val settingsIconColor = Color(0xFF1976D2)  // синий
            val searchIconColor = Color(0xFF388E3C)    // зелёный

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Кнопка "Настройки"
                IconButton(
                    onClick = { showSettingsDialog = true },
                    modifier = Modifier.size(iconTouchSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Настройки соединения",
                        tint = settingsIconColor,
                        modifier = Modifier.size(iconGraphicSize)
                    )
                }

                // Кнопка "Поиск"
                IconButton(
                    onClick = { /* TODO: работа поиска */ },
                    modifier = Modifier.size(iconTouchSize)
                ) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Поиск",
                        tint = searchIconColor,
                        modifier = Modifier.size(iconGraphicSize)
                    )
                }
            }
        }
    }

    // --- Диалог настроек ---
    // Открывается по кнопке "Настройки". При подтверждении сохраняет
    // выбранные значения в SharedPreferences и закрывается.
    if (showSettingsDialog) {
        ConnectionSettingsDialog(
            initialBaudRate = connectionSettings.getBaudRate(),
            initialSlaveAddress = connectionSettings.getSlaveAddress(),
            onConfirm = { newBaudRate, newAddress ->
                // Сохраняем в SharedPreferences.
                connectionSettings.setBaudRate(newBaudRate)
                connectionSettings.setSlaveAddress(newAddress)
                // Закрываем диалог.
                showSettingsDialog = false
                // Показываем Toast для обратной связи: пользователь видит,
                // что настройки применились. Автоматическое переподключение
                // с новыми параметрами будет отдельным шагом — пока изменения
                // вступят в силу при следующем физическом переподключении
                // контроллера или перезапуске приложения.
                Toast.makeText(
                    context,
                    "Сохранено: $newBaudRate бод, адрес $newAddress",
                    Toast.LENGTH_SHORT
                ).show()
            },
            onDismiss = { showSettingsDialog = false }
        )
    }
}