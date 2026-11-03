package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.PageInfo
import kotlinx.coroutines.launch

/**
 * ParametersScreen — экран со списком параметров выбранной страницы INI-файла.
 *
 * Возможности экрана:
 *  1. Список параметров с именем и единицей измерения ("Uref, V").
 *  2. Клик по карточке — выбор параметра (визуальная подсветка).
 *     Повторный клик на выбранной карточке снимает выбор.
 *  3. Кнопка "F1" в шапке — показывает описание выбранного параметра
 *     в модальном диалоге. Если параметр не выбран или у него нет описания
 *     в INI-файле — показывается короткое уведомление Snackbar.
 *  4. Выбранный параметр и открытый диалог переживают поворот экрана
 *     благодаря rememberSaveable.
 *
 * Архитектура:
 *  - Экран "Stateless" снаружи (принимает page и колбэк onBack),
 *    но "stateful" внутри (сам хранит selectedKey и descriptionText).
 *  - Сравнение параметров везде идёт по `key` (например "p201"), а не по `name`,
 *    чтобы избежать путаницы при дубликатах имён в разных секциях INI-файла.
 *
 * @param page Страница с уже разрешёнными параметрами (парсер поработал).
 * @param onBack Колбэк для кнопки "← Назад" (возврат на ConfigScreen).
 * @param modifier Модификатор компоновки.
 */
@Composable
fun ParametersScreen(
    page: PageInfo,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    // =========================================================================
    // СОСТОЯНИЕ ЭКРАНА
    // =========================================================================

    // selectedKey — "какой параметр сейчас выбран".
    // Тип String? — это key из ParameterInfo (например, "p201" для Uref).
    // null означает "ничего не выбрано".
    //
    // Почему String?, а не сам ParameterInfo:
    //  1) String штатно сериализуется в Bundle — rememberSaveable переживёт
    //     поворот экрана и восстановление процесса без кастомного Saver.
    //  2) Сравнение по key безопаснее, чем по name: если в INI-файле встретятся
    //     два параметра с одинаковым именем в разных секциях,
    //     сравнение по key их не перепутает.
    //
    // var, а не val — потому что значение будет меняться при клике на карточку.
    var selectedKey by rememberSaveable { mutableStateOf<String?>(null) }

    // descriptionText — текст описания, который сейчас показывается в диалоге.
    // null = диалог закрыт, не-null = диалог открыт с этим текстом.
    // Это стандартный "trick" в Compose: переменная-триггер для условного рендера.
    var descriptionText by rememberSaveable { mutableStateOf<String?>(null) }

    // snackbarHostState — управляет показом уведомлений Snackbar внизу экрана.
    // Нужен для случая "у параметра нет описания". remember (без saveable),
    // потому что Snackbar не должен переживать поворот — он эфемерный.
    val snackbarHostState = remember { SnackbarHostState() }

    // rememberCoroutineScope — область видимости корутин, привязанная к этому Composable.
    // Нужна потому, что snackbarHostState.showSnackbar() — suspend-функция,
    // её можно вызывать только из корутины. При уходе с экрана scope отменяется
    // автоматически — утечек не будет.
    val scope = rememberCoroutineScope()

    // =========================================================================
    // КОРНЕВОЙ КОНТЕЙНЕР: Scaffold
    // =========================================================================
    // Scaffold оборачивает весь экран и автоматически размещает Snackbar внизу.
    // innerPadding — внутренние отступы, которые Scaffold "резервирует" под свои
    // элементы. Передаём их в наш Column, чтобы содержимое не перекрывалось
    // со Snackbar.
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
                // Кнопка возврата на предыдущий экран (ConfigScreen).
                TextButton(onClick = onBack) {
                    Text("← Назад", fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // Заголовок страницы. weight(1f) заставляет текст занять
                // всё свободное место, прижимая F1 к правому краю Row.
                Text(
                    text = page.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f)
                )

                // F1 — кнопка "показать описание выбранного параметра".
                // Логика:
                //   - если ничего не выбрано ИЛИ у выбранного нет описания
                //     → показываем Snackbar "У параметра нет описания";
                //   - иначе → открываем AlertDialog с описанием.
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
                // Заглушка, если INI-файл не содержит параметров для этой страницы
                // (например, в [PAGES] есть ссылка, но в секции параметров
                // ничего не найдено — опечатка в имени).
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
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(page.parameters) { param ->
                        // Для каждой карточки вычисляем два производных значения:
                        //
                        // 1) isSelected — выбрана ли ИМЕННО эта карточка.
                        //    Сравниваем по key, не по name.
                        //
                        // 2) label — что показывать в тексте карточки:
                        //    "Uref, V" если единица измерения задана,
                        //    "Uref"    если единица пустая или "*".
                        //    "*" в INI-файле — это маркер "единицы нет".
                        val isSelected = param.key == selectedKey
                        val label = if (param.unit.isNotBlank() && param.unit != "*") {
                            "${param.name}, ${param.unit}"
                        } else {
                            param.name
                        }

                        // Card с onClick — кликабельная карточка Material3.
                        // Автоматически добавляет ripple-эффект при нажатии
                        // и корректно работает с TalkBack (accessibility).
                        Card(
                            onClick = {
                                // Тап по карточке — переключение выбора:
                                //  - если карточка уже выбрана → снимаем выбор (null);
                                //  - если не выбрана → выбираем её.
                                // Прежний выбор при этом автоматически сбрасывается:
                                // selectedKey теперь указывает на новый ключ,
                                // и все остальные карточки при перерисовке
                                // увидят isSelected = false.
                                selectedKey = if (isSelected) null else param.key
                            },
                            modifier = Modifier.fillMaxWidth(),
                            // Цвет контейнера зависит от того, выбрана ли карточка:
                            //  - primaryContainer — заметный акцентный цвет
                            //    (тема сама подбирает подходящий оттенок);
                            //  - surfaceVariant — нейтральный, как у остальных.
                            colors = CardDefaults.cardColors(
                                containerColor = if (isSelected) {
                                    MaterialTheme.colorScheme.primaryContainer
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant
                                }
                            )
                        ) {
                            Text(
                                text = label,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // =========================================================================
    // ДИАЛОГ ОПИСАНИЯ
    // =========================================================================
    // Размещается ПОСЛЕ Scaffold, но в том же Composable, поэтому рисуется
    // поверх всего содержимого — это стандартный приём в Compose для диалогов.
    //
    // Условие показа:
    //   1) descriptionText != null — пользователь нажал F1 и описание есть;
    //   2) currentParam != null — защита от рассогласования: если selectedKey
    //      почему-то указывает на несуществующий параметр (например, INI-файл
    //      перечитался и параметр исчез), диалог не откроется.
    //
    // Так как AlertDialog модальный, пользователь не сможет изменить
    // selectedKey, пока диалог открыт — это гарантирует, что currentParam
    // останется тем же параметром, для которого показано описание.
    val currentParam = page.parameters.find { it.key == selectedKey }
    if (descriptionText != null && currentParam != null) {
        AlertDialog(
            // onDismissRequest — вызывается при тапе вне диалога
            // и при нажатии системной кнопки "Назад".
            // Закрываем диалог — обнуляем descriptionText.
            onDismissRequest = { descriptionText = null },
            // Заголовок диалога — имя параметра без единицы измерения.
            // Мелкий (16sp) и нежирный — акцент должен быть на описании.
            title = {
                Text(
                    text = currentParam.name,
                    fontWeight = FontWeight.Normal,
                    fontSize = 16.sp
                )
            },
            // Тело диалога — описание из INI-файла.
            // Крупное (22sp) и жирное — главный смысловой элемент.
            // "!!" безопасно, потому что строкой выше мы проверили
            // descriptionText != null.
            text = {
                Text(
                    text = descriptionText!!,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp
                )
            },
            // Единственная кнопка "OK". Делает то же самое, что onDismissRequest —
            // закрывает диалог.
            confirmButton = {
                TextButton(onClick = { descriptionText = null }) {
                    Text("OK")
                }
            }
        )
    }
}