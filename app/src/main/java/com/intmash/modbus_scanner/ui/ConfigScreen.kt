package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.intmash.modbus_scanner.data.PageInfo

/**
 * ConfigScreen — меню страниц параметров, загруженных из выбранного INI-файла.
 *
 * Stateless: данные приходят из ViewModel, действия уходят наружу колбэками.
 * При клике на страницу вызывает [onPageClick] — навигация в AppNavHost переходит
 * на экран параметров этой страницы.
 *
 * @param deviceId Текущий ID контроллера (или статус).
 * @param selectedFileName Имя выбранного INI-файла (для отображения/отладки).
 * @param pages Список страниц из INI-файла.
 * @param onRefreshIdClick Колбэк кнопки "ID" — принудительное обновление ID.
 * @param onPageClick Колбэк выбора страницы.
 * @param modifier Модификатор компоновки.
 */
@Composable
fun ConfigScreen(
    deviceId: String,
    selectedFileName: String,
    pages: List<PageInfo>,
    onRefreshIdClick: () -> Unit,
    onPageClick: (PageInfo) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // --- Верхняя карточка с кнопкой ID и статусом контроллера ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Button(onClick = onRefreshIdClick) {
                    Text("ID", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(
                    horizontalAlignment = Alignment.End,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "Контроллер ID:",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Text(
                        text = deviceId,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        Spacer(modifier = Modifier.height(16.dp))

        // --- Список страниц ---
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            items(pages) { page ->
                Button(
                    onClick = { onPageClick(page) },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF1976D2),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                ) {
                    Text(
                        text = page.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}