//  app/src/main/java/com/intmash/modbus_scanner/ui/WriteDialog.kt
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
 *  - заголовок с именем параметра (Uref, USB_BPS, Ready и т.д.);
 *  - текущее значение (справочно, чтобы пользователь видел, от чего отталкивается);
 *  - редактор нового значения (тип редактора зависит от типа параметра);
 *  - кнопки «Отмена» и «Записать».
 *
 * Устроен как "роутер по типу": сам не рисует UI, а вызывает специализированный
 * диалог для конкретного типа данных. Так каждый тип проще поддерживать
 * отдельно, а добавление нового типа сводится к новой ветке when и одной
 * новой Composable-функции.
 *
 * Реальная отправка Modbus-запроса на этом шаге НЕ делается: [onConfirm]
 * только сообщает вызывающему коду, что пользователь ввёл такое-то значение.
 * Логика записи (преобразование в регистры, отправка, обработка ошибок)
 * появится отдельным шагом — так проще отлаживать UI и логику по отдельности.
 *
 * @param param Описание параметра, готовое к Modbus-операциям. Содержит
 *              имя, единицу, тип, масштаб и (для TPrmList) словарь значений.
 * @param currentValue Текущее декодированное значение. Может быть null,
 *                     если первый опрос ещё не завершился — тогда диалог
 *                     откроется с пустым полем (или без предвыбранного варианта).
 * @param onDismiss Колбэк закрытия диалога без записи (кнопка «Отмена»,
 *                  тап вне диалога, системная кнопка «Назад»).
 * @param onConfirm Колбэк подтверждения. Получает значение в "человеческом"
 *                  виде:
 *                    - числовые типы: значение пользователя (например, 220.5);
 *                    - TPrmList:     ключ из словаря (например, 4.0 для «115200»);
 *                    - TBit:         0.0 или 1.0.
 *                  Обратное преобразование в "сырые" регистры — задача
 *                  вызывающего кода (следующий шаг).
 */
@Composable
fun WriteDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    when (param.type) {
        ModbusType.TWord,
        ModbusType.TByte,
        ModbusType.TDWord,
        ModbusType.TFloat -> WriteNumberDialog(param, currentValue, onDismiss, onConfirm)

        ModbusType.TPrmList -> WritePrmListDialog(param, currentValue, onDismiss, onConfirm)

        ModbusType.TBit -> WriteBitDialog(param, currentValue, onDismiss, onConfirm)
    }
}

// =============================================================================
// ЧИСЛОВЫЕ ПАРАМЕТРЫ (TWord, TByte, TDWord, TFloat)
// =============================================================================

/**
 * Диалог ввода числового значения.
 *
 * Поле предзаполняется текущим значением (если оно есть) — пользователь
 * может стереть и ввести новое, или слегка поправить существующее.
 *
 * Клавиатура — `KeyboardType.Decimal`: разрешает ввод цифр, точки и запятой.
 * При подтверждении запятая заменяется на точку (для единообразия парсинга).
 *
 * Масштаб (÷10, ÷100 и т.д.) здесь не применяется — пользователь вводит
 * "человеческое" значение (220.5 В), а не сырое (2205). Приведение к сырому
 * значению с учётом `param.scale` будет на шаге записи.
 */
@Composable
private fun WriteNumberDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    // Предзаполнение поля текущим значением.
    // DecodedValue.Number.display уже отформатирован с правильным числом
    // знаков после запятой (например, "219.9"), парсится обратно без потерь.
    val initialText = (currentValue as? DecodedValue.Number)?.display ?: ""
    var inputText by remember { mutableStateOf(initialText) }
    var errorText by remember { mutableStateOf<String?>(null) }

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
                        errorText = null  // сбрасываем ошибку при вводе
                    },
                    label = { Text("Новое значение") },
                    isError = errorText != null,
                    supportingText = {
                        if (errorText != null) {
                            Text(errorText!!, color = MaterialTheme.colorScheme.error)
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
                // Заменяем запятую на точку — пользователи вводят по-разному.
                // toDoubleOrNull вернёт null, если строка не число.
                val parsed = inputText.trim().replace(',', '.').toDoubleOrNull()
                if (parsed == null) {
                    errorText = "Введите число"
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
 * Например, для `USB_BPS` в INI заданы варианты x01#9600, x02#19200,
 * x03#57600, x04#115200. В диалоге — выпадающий список с этими вариантами,
 * выбранный вариант — ключ из словаря, который передаётся в onConfirm.
 *
 * ExposedDropdownMenuBox — компонент Material3 для выпадающих списков.
 * Привязан к OutlinedTextField, но поле readOnly — ввод текста запрещён,
 * выбор только из списка. Это защищает от опечаток.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WritePrmListDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
    onDismiss: () -> Unit,
    onConfirm: (Double) -> Unit
) {
    val pRmlist = param.pRmlist ?: emptyMap()

    // Текущий выбранный ключ. При первом открытии — из currentValue,
    // если оно задано. Иначе null (пользователь должен выбрать вариант).
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
                        onValueChange = { /* read-only, изменения игнорируются */ },
                        readOnly = true,
                        label = { Text("Новое значение") },
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
                        // Сортируем по ключу — чтобы список отображался
                        // в стабильном порядке (9600, 19200, 57600, 115200).
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
                // Кнопка неактивна, пока ничего не выбрано.
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
 * Основной способ записи TBit — тап по квадратику в карточке (реализуем
 * отдельным шагом). Этот диалог — резервный путь через долгий тап по
 * карточке: если пользователь промахнулся по маленькому квадратику,
 * он может открыть диалог и выбрать явно.
 *
 * Две кнопки — «0» и «1». Выбранная подсвечена акцентным цветом,
 * вторая — нейтральным (surfaceVariant). Это даёт ясную визуальную
 * обратную связь без чекбоксов и радиокнопок.
 */
@Composable
private fun WriteBitDialog(
    param: ModbusParameter,
    currentValue: DecodedValue?,
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
                    // Кнопка "0": акцентная, если бит выбран в 0, иначе серая.
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
                    // Кнопка "1": акцентная, если бит выбран в 1, иначе серая.
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