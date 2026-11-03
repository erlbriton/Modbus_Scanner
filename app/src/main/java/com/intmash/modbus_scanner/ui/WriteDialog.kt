// app/src/main/java/com/intmash/modbus_scanner/ui/WriteDialog.kt
package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.DecodedValue
import com.intmash.modbus_scanner.data.ModbusParameter
import com.intmash.modbus_scanner.data.ModbusType

/**
 * WriteDialog — модальный диалог записи одного параметра в контроллер.
 *
 * Показывает:
 *  - заголовок с именем параметра;
 *  - текущее значение (справочно);
 *  - редактор нового значения (тип редактора зависит от типа параметра);
 *  - при ошибке записи — красный текст под полем;
 *  - кнопки «Отмена» и «Записать».
 *
 * Устроен как "роутер по типу": сам не рисует UI, а вызывает специализированный
 * диалог для конкретного типа данных. Так каждый тип проще поддерживать
 * отдельно, а добавление нового типа сводится к новой ветке when и одной
 * новой Composable-функции.
 *
 * @param param Описание параметра, готовое к Modbus-операциям.
 * @param currentValue Текущее декодированное значение (для предзаполнения).
 *                     null, если первый опрос ещё не завершился.
 * @param errorMessage Текст ошибки записи от ViewModel. Показывается красным
 *                     под полем ввода. null — ошибки нет (или только что
 *                     открыли/повторили попытку).
 * @param onDismiss Колбэк закрытия диалога без записи.
 * @param onConfirm Колбэк подтверждения. Получает значение в "человеческом"
 *                  виде:
 *                    - числовые: значение пользователя (220.5);
 *                    - TPrmList: ключ из словаря (4.0 для «115200»);
 *                    - TBit:     0.0 или 1.0.
 *                  Обратное преобразование в регистры — в ValueEncoder
 *                  (вызывается из ViewModel через onConfirm выше).
 */
@Composable
fun WriteDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    when (param.type) {
        ModbusType.TWord,
        ModbusType.TByte,
        ModbusType.TDWord,
        ModbusType.TFloat -> WriteNumberDialog(
            param, currentValue, errorMessage, onDismiss, onConfirm
        )

        ModbusType.TPrmList -> WritePrmListDialog(
            param, currentValue, errorMessage, onDismiss, onConfirm
        )

        ModbusType.TBit -> WriteBitDialog(
            param, currentValue, errorMessage, onDismiss, onConfirm
        )
    }
}

// =============================================================================
// ЧИСЛОВЫЕ ПАРАМЕТРЫ (TWord, TByte, TDWord, TFloat)
// =============================================================================

/**
 * Диалог ввода числового значения.
 *
 * Поле предзаполняется текущим значением. Клавиатура — Decimal
 * (разрешает цифры, точку, запятую). При подтверждении запятая
 * заменяется точкой. Масштаб применяется позже (в ValueEncoder).
 *
 * Ошибки могут быть двух типов:
 *  - локальная валидация: "Введите число" (текст в поле не парсится);
 *  - ошибка записи от ViewModel: например, "Значение вне диапазона".
 * Показываются по очереди: сначала локальная (пока пользователь не ввёл
 * валидное число), потом ошибка записи (приходит от контроллера).
 */
@Composable
private fun WriteNumberDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val initialText = (currentValue as? DecodedValue.Number)?.display ?: ""
    var inputText by remember { mutableStateOf(initialText) }
    var localError by remember { mutableStateOf<String?>(null) }

    // Что показать под полем: локальная валидация приоритетнее.
    // Если её нет — показываем ошибку записи от ViewModel.
    val displayedError = localError ?: errorMessage

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(param.name) },
        text = {
            Column {
                Text(
                    text = "Текущее: ${currentValue?.display ?: "---"}" +
                            if (param.unit.isNotBlank()) " ${param.unit}" else "",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                OutlinedTextField(
                    value = inputText,
                    onValueChange = {
                        inputText = it
                        localError = null  // сбрасываем локальную ошибку при вводе
                    },
                    label = { Text("Новое значение") },
                    isError = displayedError != null,
                    supportingText = {
                        if (displayedError != null) {
                            Text(displayedError, color = MaterialTheme.colorScheme.error)
                        }
                    },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = inputText.trim().replace(',', '.').toDoubleOrNull()
                if (parsed == null) {
                    localError = "Введите число"
                } else {
                    onConfirm(parsed)
                }
            }) {
                Text("Записать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

// =============================================================================
// СПИСОЧНЫЕ ПАРАМЕТРЫ (TPrmList)
// =============================================================================

/**
 * Диалог выбора значения из списка (TPrmList).
 *
 * Например, для `USB_BPS` в INI заданы варианты x01#9600..x04#115200.
 * Поле readOnly — выбор только из выпадающего списка. Это защищает
 * от опечаток.
 *
 * Ошибка записи показывается красным текстом под полем.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WritePrmListDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val pRmlist = param.pRmlist ?: emptyMap()
    val initialKey = (currentValue as? DecodedValue.ListOption)?.raw
    var selectedKey by remember { mutableStateOf(initialKey) }
    var expanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(param.name) },
        text = {
            Column {
                Text(
                    text = "Текущее: ${currentValue?.display ?: "---"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp)
                ) {
                    OutlinedTextField(
                        value = selectedKey?.let { pRmlist[it] } ?: "",
                        onValueChange = { /* read-only */ },
                        readOnly = true,
                        label = { Text("Новое значение") },
                        isError = errorMessage != null,
                        supportingText = {
                            if (errorMessage != null) {
                                Text(errorMessage, color = MaterialTheme.colorScheme.error)
                            }
                        },
                        trailingIcon = {
                            ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
                        },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        pRmlist.toSortedMap().forEach { (key, label) ->
                            DropdownMenuItem(
                                text = { Text(label) },
                                onClick = {
                                    selectedKey = key
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val key = selectedKey
                    if (key != null) onConfirm(key.toDouble())
                },
                enabled = selectedKey != null
            ) {
                Text("Записать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

// =============================================================================
// БИТОВЫЕ ПАРАМЕТРЫ (TBit)
// =============================================================================

/**
 * Диалог выбора бита (0/1).
 *
 * Основной способ записи TBit — тап по квадратику в карточке (следующим
 * шагом). Этот диалог — резервный путь через долгий тап: если пользователь
 * промахнулся по квадратику, он может открыть диалог и выбрать явно.
 *
 * Две кнопки — «0» и «1». Выбранная акцентная, вторая нейтральная.
 */
@Composable
private fun WriteBitDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    errorMessage: String?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val initialBit = (currentValue as? DecodedValue.Bit)?.raw ?: false
    var selectedBit by remember { mutableStateOf(initialBit) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(param.name) },
        text = {
            Column {
                Text(
                    text = "Текущее: ${if (initialBit) "1" else "0"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.secondary
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { selectedBit = false },
                        colors = if (!selectedBit) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("0")
                    }
                    Button(
                        onClick = { selectedBit = true },
                        colors = if (selectedBit) {
                            ButtonDefaults.buttonColors()
                        } else {
                            ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("1")
                    }
                }
                if (errorMessage != null) {
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onConfirm(if (selectedBit) 1.0 else 0.0)
            }) {
                Text("Записать")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}