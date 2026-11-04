// ConnectionSettingsDialog.kt

package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.ConnectionSettings

/**
 * ConnectionSettingsDialog — модальный диалог выбора скорости и адреса Modbus.
 *
 * Stateless по отношению к хранилищу: принимает текущие значения
 * через [initialBaudRate] / [initialSlaveAddress], а на подтверждении
 * отдаёт новые значения через [onConfirm]. Сохранение в [ConnectionSettings] —
 * задача вызывающей стороны (MainScreen).
 *
 * Что внутри:
 *  - Скорость — выпадающий список из [ConnectionSettings.SUPPORTED_BAUD_RATES].
 *    Пользователь не может ввести недопустимое значение: только выбрать из списка.
 *  - Адрес — текстовое поле с цифровой клавиатурой. Фильтр во время ввода:
 *    только цифры, не более 3 символов. Валидация диапазона 1..247 — при нажатии
 *    "Сохранить": если вне диапазона, показываем красную ошибку под полем
 *    и не закрываем диалог.
 *
 * @param initialBaudRate Текущая скорость (из SharedPreferences). Предзаполнение.
 * @param initialSlaveAddress Текущий адрес (из SharedPreferences). Предзаполнение.
 * @param onConfirm Колбэк при нажатии "Сохранить": (новая скорость, новый адрес).
 * @param onDismiss Колбэк при закрытии без сохранения (тап вне диалога,
 *                  кнопка "Отмена", системная "Назад").
 */
@Composable
fun ConnectionSettingsDialog(
    initialBaudRate: Int,
    initialSlaveAddress: Int,
    onConfirm: (baudRate: Int, slaveAddress: Int) -> Unit,
    onDismiss: () -> Unit
) {
    // Текущая выбранная скорость. Инициализируется из initialBaudRate.
    // Ключ remember — initialBaudRate, чтобы при повторном открытии диалога
    // с другими initial-значениями состояние обновилось.
    var selectedBaudRate by remember(initialBaudRate) {
        mutableStateOf(initialBaudRate)
    }

    // Текст в поле адреса. Храним строкой, а не Int, чтобы:
    //  - разрешить промежуточное пустое состояние (пока пользователь стирает);
    //  - точно контролировать валидацию (после toIntOrNull).
    var slaveAddressText by remember(initialSlaveAddress) {
        mutableStateOf(initialSlaveAddress.toString())
    }

    // Флаг раскрытия выпадающего списка скоростей.
    var baudRateMenuExpanded by remember { mutableStateOf(false) }

    // Текст ошибки валидации (null = ошибки нет).
    var errorText by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        // containerColor — цвет фона всего диалога.
        // Примеры:
        //   Color(0xFFFFFFFF)         — белый
        //   Color(0xFFF5F5F5)         — очень светлый серый
        //   Color(0xFFFFF8E1)         — кремовый
        //   MaterialTheme.colorScheme.surfaceVariant — из темы
        containerColor = Color(0xFF172843),
        title = { Text("Настройки соединения") },
        text = {
            Column {
                // ================== Скорость ==================
                Text(
                    text = "Скорость, бод",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                Box(modifier = Modifier.fillMaxWidth()) {
                    // Кнопка-триггер выпадающего списка. Показывает текущую
                    // скорость и стрелку вниз. Клик открывает меню.
                    OutlinedButton(
                        onClick = { baudRateMenuExpanded = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = selectedBaudRate.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = null
                        )
                    }

                    // Само выпадающее меню. Появляется «прикреплённым»
                    // к родительскому Box — под кнопкой.
                    DropdownMenu(
                        expanded = baudRateMenuExpanded,
                        onDismissRequest = { baudRateMenuExpanded = false }
                    ) {
                        ConnectionSettings.SUPPORTED_BAUD_RATES.forEach { rate ->
                            DropdownMenuItem(
                                text = { Text(rate.toString()) },
                                onClick = {
                                    selectedBaudRate = rate
                                    baudRateMenuExpanded = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // ================== Адрес ==================
                Text(
                    text = "Адрес в сети Modbus (1..247)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                OutlinedTextField(
                    value = slaveAddressText,
                    onValueChange = { newValue ->
                        // Фильтр ввода:
                        //  - только цифры;
                        //  - не более 3 символов (247 — максимум, 3 символа).
                        // Если ввод не проходит фильтр, ничего не меняем.
                        if (newValue.all { it.isDigit() } && newValue.length <= 3) {
                            slaveAddressText = newValue
                            // Сбрасываем ошибку при любом вводе: пользователь
                            // исправляется, не надо «залипать» на красном.
                            errorText = null
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    isError = errorText != null,
                    supportingText = if (errorText != null) {
                        {
                            Text(
                                text = errorText!!,
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    } else null
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // Валидация адреса перед сохранением.
                    val address = slaveAddressText.toIntOrNull()
                    if (address == null ||
                        address < ConnectionSettings.MIN_SLAVE_ADDRESS ||
                        address > ConnectionSettings.MAX_SLAVE_ADDRESS
                    ) {
                        // Не закрываем диалог, показываем ошибку.
                        errorText = "Адрес должен быть от " +
                                "${ConnectionSettings.MIN_SLAVE_ADDRESS} до " +
                                "${ConnectionSettings.MAX_SLAVE_ADDRESS}"
                    } else {
                        // Всё корректно — отдаём значения наверх.
                        onConfirm(selectedBaudRate, address)
                    }
                }
            ) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}