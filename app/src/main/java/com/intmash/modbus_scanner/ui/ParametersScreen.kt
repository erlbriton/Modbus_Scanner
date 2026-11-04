// app/src/main/java/com/intmash/modbus_scanner/ui/ParametersScreen.kt

package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.DecodedValue
import com.intmash.modbus_scanner.data.ModbusParameter
import com.intmash.modbus_scanner.data.ModbusParameterParser
import com.intmash.modbus_scanner.data.PageInfo
import kotlinx.coroutines.launch

/**
 * ParametersScreen — экран параметров выбранной страницы INI-файла.
 *
 * Возможности:
 *  1. Три колонки в каждой карточке: имя | единица | значение.
 *     Значение обновляется автоматически из [ParametersViewModel].
 *  2. Обычный тап по карточке — выбор параметра (подсветка).
 *     Повторный тап снимает выбор.
 *  3. Долгий тап по карточке — открывает диалог записи значения.
 *  4. Кнопка "F1" в шапке — описание выбранного параметра (AlertDialog).
 *  5. Баннер "Нет связи" внизу, когда [ParametersViewModel] зафиксировала
 *     3 провала опроса подряд.
 *  6. Для TBit-параметров значение показывается квадратиком:
 *     заполненный = 1, контур = 0.
 *
 * @param page Страница с уже разрешёнными параметрами из INI.
 * @param parametersViewModel ViewModel опроса и значений (живёт в навигации).
 * @param onBack Колбэк кнопки "← Назад".
 * @param modifier Модификатор компоновки.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ParametersScreen(
    page: PageInfo,
    parametersViewModel: ParametersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // =========================================================================
    // ЛОКАЛЬНОЕ UI-СОСТОЯНИЕ
    // =========================================================================

    // selectedKey — "какой параметр сейчас выбран". Тип String? — key из
    // ParameterInfo ("p201"). null = ничего не выбрано.
    // rememberSaveable — переживает поворот экрана (String штатно Bundle).
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    // descriptionText — текст описания, открытого в AlertDialog (F1).
    // null = диалог закрыт, не-null = диалог открыт с этим текстом.
    var descriptionText by rememberSaveable { mutableStateOf<String?>(null) }

    // snackbarHostState — управляет Snackbar'ами внизу экрана.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // writeParam — параметр, для которого открыт диалог записи.
    // null = диалог закрыт. Не-null = открыт диалог для этого параметра.
    // Тип — ModbusParameter (а не ParameterInfo): диалогу нужны тип,
    // масштаб, словарь TPrmList и адрес — всё уже разобрано парсером.
    // remember (не saveable): при повороте экрана диалог закрывается —
    // открытый диалог с введённым значением после поворота мог бы
    // привести к случайной записи, риск выше пользы.
    var writeParam by remember { mutableStateOf<ModbusParameter?>(null) }

    // writeError — текст ошибки последней попытки записи, если провалилась.
    // null = ошибки нет (или диалог закрыт/ещё не открывался).
    // Не saveable: ошибка эфемерна, при повороте экрана сбрасывается
    // (диалог всё равно закрывается). Показывается красным под полем.
    var writeError by remember { mutableStateOf<String?>(null) }

    // =========================================================================
    // КОРНЕВОЙ КОНТЕЙНЕР
    // =========================================================================
    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // =================================================================
            // ШАПКА: "← Назад" | Заголовок страницы | "F1"
            // =================================================================
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                TextButton(onClick = onBack) {
                    Text("← Назад", fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = page.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                Button(
                    onClick = {
                        val selected = page.parameters.find { it.key == selectedKey }
                        if (selected == null || selected.description.isBlank()) {
                            scope.launch {
                                snackbarHostState.showSnackbar("У параметра нет описания")
                            }
                        } else {
                            descriptionText = selected.description
                        }
                    },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text("F1", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            // =================================================================
            // СПИСОК ПАРАМЕТРОВ
            // =================================================================
            if (page.parameters.isEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "Параметры не найдены",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            } else {
                // weight(1f): список занимает всё оставшееся место,
                // оставляя место внизу под баннер "Нет связи".
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(page.parameters) { param ->
                        val isSelected = param.key == selectedKey

                        // decodedValue — текущее значение из ViewModel.
                        // null, пока первый цикл опроса не прошёл.
                        val decodedValue = parametersViewModel.values[param.key]

                        // valueText — для числовых и списочных параметров.
                        // Для TBit не используется (там квадратик).
                        val valueText = decodedValue?.display ?: "---"

                        // isTBitParam — тип TBit отображается квадратиком.
                        val isTBitParam = param.type.equals("TBit", ignoreCase = true)

                        // bitRaw — состояние квадратика. true = заполнен,
                        // false = контур. Нет значения → пустой квадрат.
                        val bitRaw = (decodedValue as? DecodedValue.Bit)?.raw ?: false

                        // Card без onClick: обычный Card с кастомным
                        // combinedClickable в modifier. Обрабатывает и обычный
                        // тап (выбор параметра), и долгий (диалог записи).
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .combinedClickable(
                                    onClick = {
                                        //  Log.e("TAP_TEST", "onClick: ${param.key} ${param.name}")
                                        selectedKey = if (isSelected) null else param.key
                                    },
                                    onLongClick = {
                                        // Разбираем ParameterInfo → ModbusParameter.
                                        // null (некорректное описание в INI) — диалог
                                        // не открываем.
                                        //   Log.e("TAP_TEST", "onLongClick: ${param.key} ${param.name}")
                                        writeParam =
                                            ModbusParameterParser.parse(param, page.variables)
                                    }
                                ),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    Color(0xFFFF9800)   // светло-оранжевый (выбранная)
                                } else {
                                    Color(0xFF275ED5)   // очень светлый серый (обычная)
                                }
                            )
                        ) {
                            // Три колонки: имя (2) | единица (1) | значение (1).
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Имя параметра.
                                Text(
                                    text = param.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp,
                                    modifier = Modifier.weight(2f)
                                )

                                // 2. Единица измерения (по центру).
                                Text(
                                    text = param.unit,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )

                                // 3. Значение (справа).
                                // Для TBit — квадратик, для остальных — текст.
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (isTBitParam) {
                                        // Разбираем параметр в Modbus-форму один раз
                                        // (нужен для записи). remember, чтобы не парсить
                                        // заново при каждой перерисовке списка.
                                        // Может быть null, если описание в INI с ошибкой —
                                        // тогда тап по квадратику ничего не делает.
                                        val modbusParam = remember(param.key, page.variables) {
                                            ModbusParameterParser.parse(param, page.variables)
                                        }

                                        // --- Визуальные настройки квадратика TBit. ---
                                        // Собраны в одном месте, чтобы легко подстроить
                                        // под дизайн. Меняются независимо друг от друга:
                                        //  - bitSize: визуальный размер (26.dp — удобно тапать);
                                        //  - bitBorderColor: цвет рамки (зелёный при 1, серый при 0);
                                        //  - bitFillColor: цвет заливки (зелёный при 1, пусто при 0).
                                        // Пример: если нужен акцентный цвет темы вместо зелёного,
                                        // замените Color(0xFF2E7D32) на MaterialTheme.colorScheme.primary.
                                        val bitSize = 30.dp
                                        val bitBorderColor = if (bitRaw) {
                                            Color(0xFF19F21D)   // тёмно-зелёный
                                        } else {
                                            Color(0xFF9E9E9E)   // серый
                                        }
                                        val bitFillColor = if (bitRaw) {
                                            Color(0xFF2E7D32)
                                        } else {
                                            Color.Transparent
                                        }

                                        Box(
                                            modifier = Modifier
                                                .size(bitSize)
                                                .border(
                                                    width = 2.dp,
                                                    color = bitBorderColor
                                                )
                                                .background(color = bitFillColor)
                                                .clickable(enabled = modbusParam != null) {
                                                    val mp = modbusParam ?: return@clickable
                                                    // Тап по квадратику — запись противоположного значения
                                                    // БЕЗ открытия диалога. Это удобнее, чем каждый раз
                                                    // открывать WriteDialog с двумя кнопками.
                                                    //
                                                    // По договорённости: «ждём успеха», без оптимистичного
                                                    // переключения. Значение в UI обновится после
                                                    // успешной записи (ближайший опрос подхватит его).
                                                    //
                                                    // Если запись провалилась — открываем WriteDialog
                                                    // с текстом ошибки, чтобы пользователь увидел причину
                                                    // и мог повторить через стандартный диалог.
                                                    parametersViewModel.writeParameter(
                                                        param = mp,
                                                        humanValue = if (bitRaw) 0.0 else 1.0
                                                    ) { error ->
                                                        if (error != null) {
                                                            writeParam = mp
                                                            writeError = error
                                                        }
                                                    }
                                                }
                                        )
                                    } else {
                                        Text(
                                            text = valueText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 18.sp,
                                            textAlign = TextAlign.End
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================================================================
            // БАННЕР "НЕТ СВЯЗИ"
            // =================================================================
            if (parametersViewModel.pollingState == PollingState.NoConnection) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "Нет связи",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }

    // =========================================================================
    // ДИАЛОГ ОПИСАНИЯ (F1)
    // =========================================================================
    val currentParam = page.parameters.find { it.key == selectedKey }
    if (descriptionText != null && currentParam != null) {
        AlertDialog(
            onDismissRequest = { descriptionText = null },
            title = {
                Text(
                    text = currentParam.name,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp
                )
            },
            text = {
                Text(
                    text = descriptionText!!,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { descriptionText = null }) {
                    Text("OK")
                }
            }
        )
    }

    // =========================================================================
    // ДИАЛОГ ЗАПИСИ (долгий тап по карточке)
    // =========================================================================
    // currentValue — текущее значение из ViewModel (для предзаполнения).
    // onConfirm — ЗАГЛУШКА: реальная запись будет следующим шагом.
    val writeParamValue = writeParam
    if (writeParamValue != null) {
        WriteDialog(
            param = writeParamValue,
            currentValue = parametersViewModel.values[writeParamValue.key],
            errorMessage = writeError,
            onDismiss = {
                writeParam = null
                writeError = null
            },
            onConfirm = { value ->
                // Сбрасываем старую ошибку при новой попытке.
                writeError = null
                // Запускаем запись через ViewModel. Колбэк придёт на Main
                // через ~0.3–1 с (пока идёт Modbus-обмен).
                parametersViewModel.writeParameter(
                    param = writeParamValue,
                    humanValue = value
                ) { error ->
                    if (error == null) {
                        // Успех — закрываем диалог. Значение в карточке
                        // обновится через следующий цикл опроса (~200 мс).
                        writeParam = null
                        writeError = null
                    } else {
                        // Ошибка — оставляем диалог открытым,
                        // показываем текст под полем.
                        writeError = error
                    }
                }
            }
        )
    }
}