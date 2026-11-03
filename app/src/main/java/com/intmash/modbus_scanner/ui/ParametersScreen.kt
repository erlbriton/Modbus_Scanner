//modbus_scanner/ui/ParametersScreen.kt

package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.intmash.modbus_scanner.data.PageInfo
import kotlinx.coroutines.launch

/**
 * ParametersScreen — экран со списком параметров выбранной страницы INI-файла
 * и их текущими значениями, обновляемыми опросом через [ParametersViewModel].
 *
 * Возможности экрана:
 *  1. Список параметров в три колонки: имя | единица | значение.
 *     Значение прижато к правому краю, обновляется автоматически.
 *     Пока значение не прочитано — показывается "---".
 *  2. Клик по карточке — выбор параметра (визуальная подсветка).
 *     Повторный клик снимает выбор.
 *  3. Кнопка "F1" в шапке — показывает описание выбранного параметра.
 *  4. Баннер "Нет связи" внизу, когда 3 цикла опроса подряд провалились.
 *
 * Архитектура:
 *  - Экран "stateless" снаружи: данные и действия приходят через аргументы.
 *  - Сам хранит только локальное UI-состояние: selectedKey, descriptionText.
 *  - Значения параметров и состояние опроса живут в [ParametersViewModel],
 *    которая создаётся на уровне навигации (в AppNavHost) и живёт столько же,
 *    сколько открыт экран.
 *
 * @param page Страница с уже разрешёнными параметрами (парсер поработал).
 * @param parametersViewModel ViewModel, управляющая опросом и хранящая значения.
 * @param onBack Колбэк кнопки "← Назад" (возврат на ConfigScreen).
 * @param modifier Модификатор компоновки.
 */
@Composable
fun ParametersScreen(
    page: PageInfo,
    parametersViewModel: ParametersViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // =========================================================================
    // ЛОКАЛЬНОЕ UI-СОСТОЯНИЕ ЭКРАНА
    // =========================================================================

    // selectedKey — "какой параметр сейчас выбран". Тип String? — это key
    // из ParameterInfo (например, "p201" для Uref). null = ничего не выбрано.
    //
    // Почему String?, а не сам ParameterInfo: String штатно сериализуется
    // в Bundle через rememberSaveable и переживает поворот экрана без
    // кастомного Saver.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    // descriptionText — текст, который сейчас показывается в диалоге F1.
    // null = диалог закрыт, не-null = диалог открыт с этим текстом.
    var descriptionText by rememberSaveable { mutableStateOf<String?>(null) }

    // snackbarHostState — управляет Snackbar'ами внизу экрана (например,
    // "У параметра нет описания"). remember (не saveable) — Snackbar эфемерный.
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // =========================================================================
    // КОРНЕВОЙ КОНТЕЙНЕР: Scaffold
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

                // Заголовок. weight(1f) съедает всё свободное место,
                // прижимая F1 к правому краю.
                Text(
                    text = page.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // F1 — показать описание выбранного параметра.
                //  - не выбрано / нет описания → Snackbar;
                //  - есть → открыть AlertDialog.
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
                // weight(1f) вместо fillMaxSize — список занимает всё
                // оставшееся место, а под ним ещё может быть баннер "Нет связи".
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    items(page.parameters) { param ->
                        // isSelected — выбрана ли ИМЕННО эта карточка (сравнение по key).
                        val isSelected = param.key == selectedKey

                        // decodedValue — текущее значение из ViewModel.
                        // null, пока первый цикл опроса не прошёл (или связь потеряна).
                        val decodedValue = parametersViewModel.values[param.key]

                        // valueText — что показывать в правой колонке для числовых
                        // и списочных параметров. Для TBit не используется (там квадратик).
                        val valueText = decodedValue?.display ?: "---"

                        // isTBitParam — этот параметр отображается квадратиком, а не текстом.
                        // Определяем по типу из INI ("TBit"). Строковое сравнение
                        // без учёта регистра — чтобы не тащить ModbusParameter в UI-слой.
                        val isTBitParam = param.type.equals("TBit", ignoreCase = true)

                        // bitRaw — состояние квадратика: true = заполнен ("1"),
                        // false = контур ("0"). Если значение ещё не прочитано
                        // (decodedValue = null) — трактуем как false (пустой квадрат).
                        val bitRaw = (decodedValue as? DecodedValue.Bit)?.raw ?: false

                        Card(
                            onClick = {
                                selectedKey = if (isSelected) null else param.key
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            // Три колонки внутри карточки: имя | единица | значение.
                            // Пропорции 2:1:1 — имя обычно самое длинное,
                            // единица короткая, значение короткое (но может быть
                            // многозначным, поэтому одна треть — с запасом).
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // 1. Имя параметра (левая колонка, выравнивание влево).
                                Text(
                                    text = param.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    modifier = Modifier.weight(2f)
                                )

                                // 2. Единица измерения (средняя колонка, по центру).
                                //    В INI маркер "нет единицы" — "*"; в ModbusParameter
                                //    он уже превращён в пустую строку.
                                Text(
                                    text = param.unit,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.secondary,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.weight(1f)
                                )

                                // 3. Значение (правая колонка, прижато к правому краю).
                                // Для TBit — квадратик: заполненный = "1", контур = "0".
                                // Для остальных типов — текст (например, "219.9").
                                Box(
                                    modifier = Modifier.weight(1f),
                                    contentAlignment = Alignment.CenterEnd
                                ) {
                                    if (isTBitParam) {
                                        // Квадрат 18×18 dp прижат к правому краю.
                                        // Граница всегда onSurface — читается на светлой
                                        // и тёмной теме, а также на подсвеченной карточке
                                        // (primaryContainer).
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .border(
                                                    width = 2.dp,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                                .background(
                                                    color = if (bitRaw) {
                                                        MaterialTheme.colorScheme.onSurface
                                                    } else {
                                                        Color.Transparent
                                                    }
                                                )
                                        )
                                    } else {
                                        Text(
                                            text = valueText,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 14.sp,
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
            // Показывается, когда ParametersViewModel зафиксировала
            // FAILURES_BEFORE_NO_CONNECTION провалов подряд. Исчезает
            // автоматически при первом успешном цикле.
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
    // Рисуется поверх содержимого — стандартный приём в Compose.
    // Условие: descriptionText != null (кнопка F1 установила его) и
    // currentParam != null (защита от рассогласования selectedKey ↔ параметр).
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
}