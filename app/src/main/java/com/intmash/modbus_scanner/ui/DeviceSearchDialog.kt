// DeviceSearchDialog.kt
package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * DeviceSearchDialog — модальный диалог поиска устройств в сети Modbus.
 *
 * Назначение: перебрать адреса 1..247 на текущей скорости, найти все
 * отвечающие устройства, дать пользователю выбрать одно — приложение
 * переключится на его адрес.
 *
 * Полностью stateless относительно поиска:
 *  - состояние ([searchState], [foundDevices], [currentSearchAddress])
 *    приходит из [MainViewModel] через [MainScreen];
 *  - действия ([onStartSearch], [onStopSearch], [onApplyAddress])
 *    делегируются наверх.
 *
 * Внутри хранится только UI-состояние поля "Таймаут, мс" и текст ошибки
 * валидации этого поля.
 *
 * Поведение:
 *  - "Начать поиск" активна только в [SearchState.Idle] / [SearchState.Completed];
 *  - "Стоп" активна только в [SearchState.Running];
 *  - поле таймаута заблокировано во время поиска;
 *  - по мере нахождения устройств список пополняется в реальном времени;
 *  - тап по устройству → [onApplyAddress] (MainViewModel переключит адрес);
 *  - закрытие диалога во время поиска автоматически останавливает поиск.
 *
 * @param searchState Текущее состояние поиска.
 * @param foundDevices Уже найденные устройства (обновляется в процессе).
 * @param currentSearchAddress Адрес, который проверяется прямо сейчас (для индикатора).
 * @param currentBaudRate Текущая скорость — для подсказки внизу.
 * @param initialTimeoutMs Таймаут по умолчанию (300 мс).
 * @param onStartSearch Запустить поиск с указанным таймаутом.
 * @param onStopSearch Остановить активный поиск.
 * @param onApplyAddress Выбрать найденное устройство — приложение переключится на его адрес.
 * @param onDismiss Закрыть диалог.
 */
@Composable
fun DeviceSearchDialog(
    searchState: SearchState,
    foundDevices: List<FoundDevice>,
    currentSearchAddress: Int?,
    currentBaudRate: Int,
    initialTimeoutMs: Int = 300,
    onStartSearch: (timeoutMs: Int) -> Unit,
    onStopSearch: () -> Unit,
    onApplyAddress: (FoundDevice) -> Unit,
    onDismiss: () -> Unit
) {
    // Поле таймаута — текст, а не Int, чтобы разрешить промежуточное пустое
    // состояние при редактировании. rememberSaveable — переживает поворот экрана.
    var timeoutText by rememberSaveable { mutableStateOf(initialTimeoutMs.toString()) }
    var timeoutError by remember { mutableStateOf<String?>(null) }

    val isRunning = searchState == SearchState.Running

    // Закрытие диалога (тап вне, системная «Назад», кнопка «Закрыть»):
    // если поиск идёт — сначала останавливаем, потом закрываем.
    val close = {
        if (isRunning) onStopSearch()
        onDismiss()
    }

    Dialog(onDismissRequest = close) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            // tonalElevation = 0.dp убирает тональный оттенок Material 3,
            // чтобы цвет фона отображался ровно таким, каким вы его задали
            // в параметре color. Если оставить 6.dp — поверх color
            // накладывается дополнительный tint, и цвет "плывёт".
            tonalElevation = 0.dp,
            // color — цвет фона окна. Примеры:
            //   Color(0xFFFFFFFF)         — белый (для светлой темы)
            //   Color(0xFFF5F5F5)         — очень светлый серый
            //   Color(0xFFFFF8E1)         — кремовый
            //   MaterialTheme.colorScheme.surfaceVariant — серый из темы
            //   MaterialTheme.colorScheme.primaryContainer — голубоватый
            color = Color(0xFF205B43),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                // ===================== Заголовок =====================
                Text(
                    text = "Поиск устройств в сети Modbus",
                    style = MaterialTheme.typography.titleMedium
                )

                Spacer(modifier = Modifier.height(16.dp))

                // ===================== Кнопка «Пуск» / «СТОП» =====================
                // Отдельная строка над полем таймаута. fillMaxWidth делает
                // её горизонтальной (широкой) на весь диалог.
                // Текст меняется по состоянию: "Пуск" → "СТОП" во время поиска.
                Button(
                    onClick = {
                        if (isRunning) {
                            // Идёт поиск — кнопка работает как «Стоп».
                            onStopSearch()
                        } else {
                            // Не идёт — валидируем таймаут и запускаем.
                            val t = timeoutText.toIntOrNull()
                            if (t == null || t !in 50..5000) {
                                timeoutError = "Таймаут: от 50 до 5000 мс"
                            } else {
                                onStartSearch(t)
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                ) {
                    Text(
                        text = if (isRunning) "СТОП" else "Пуск",
                        fontSize = 16.sp
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ===================== Строка таймаута =====================
                // Отдельно от кнопки — только поле ввода с подписью.
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("Таймаут, мс:", fontSize = 14.sp)

                    OutlinedTextField(
                        value = timeoutText,
                        onValueChange = { v ->
                            if (v.all { it.isDigit() } && v.length <= 5) {
                                timeoutText = v
                                timeoutError = null
                            }
                        },
                        enabled = !isRunning,
                        singleLine = true,
                        // Меняем размер коробки: ширина 80 dp, высота 44 dp.
                        // Стандартная высота OutlinedTextField — 56 dp,
                        // поэтому визуально он «подрос» из-за внутренних отступов.
                        modifier = Modifier
                            .width(80.dp)
                            .height(50.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }

                // ===================== Ошибка валидации таймаута =====================
                if (timeoutError != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = timeoutError!!,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // ===================== Строка статуса =====================
                val statusText = when {
                    isRunning ->
                        "Идёт поиск... Проверяю адрес ${currentSearchAddress ?: "—"}"
                    searchState == SearchState.Completed ->
                        "Поиск завершён. Найдено: ${foundDevices.size}"
                    else ->
                        "Готов к поиску. Диапазон адресов: 1–247."
                }
                Text(
                    text = statusText,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(8.dp))

                // ===================== Подсказка про скорость =====================
                Text(
                    text = "Поиск выполняется на текущей скорости $currentBaudRate бод. " +
                            "Для смены скорости закройте поиск и откройте настройки.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )

                // ===================== Список найденных устройств =====================
                if (foundDevices.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        text = "Найденные устройства (тап для выбора):",
                        style = MaterialTheme.typography.labelMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(foundDevices) { dev ->
                            Card(
                                onClick = { onApplyAddress(dev) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer
                                )
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "Адрес: ${dev.address}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = dev.deviceId,
                                        fontSize = 12.sp
                                    )
                                }
                            }
                        }
                    }
                }

                // ===================== Кнопка «Закрыть» =====================
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = close) {
                        Text("Закрыть")
                    }
                }
            }
        }
    }
}