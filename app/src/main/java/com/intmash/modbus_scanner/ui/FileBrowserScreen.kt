package com.intmash.modbus_scanner.ui

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

/**
 * FileBrowserScreen — собственный файловый браузер приложения.
 *
 * Показывает папки и .ini-файлы. По клику на папку — переход внутрь.
 * По клику на .ini-файл — вызов onFileSelected.
 *
 * @param initialDir Стартовая директория (обычно корень внешнего хранилища).
 * @param onFileSelected Колбэк при выборе .ini-файла.
 * @param onClose Колбэк при нажатии "Закрыть проводник".
 * @param modifier Модификатор компоновки.
 */
@Composable
fun FileBrowserScreen(
    initialDir: File,
    onFileSelected: (File) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    var currentDir by remember { mutableStateOf(initialDir) }
    var fileList by remember { mutableStateOf<List<File>>(emptyList()) }

    // Перечитываем содержимое папки при каждой смене currentDir
    LaunchedEffect(currentDir) {
        fileList = try {
            currentDir.listFiles()?.sortedWith(
                compareBy({ !it.isDirectory }, { it.name.lowercase() })
            ) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text(
            text = "Путь: ${currentDir.absolutePath}",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.secondary
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (currentDir.parentFile != null) {
            Button(
                onClick = { currentDir = currentDir.parentFile!! },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("📁 .. (Наверх)")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(fileList) { file ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            interactionSource = null,
                            indication = LocalIndication.current
                        ) {
                            if (file.isDirectory) {
                                currentDir = file
                            } else if (file.name.endsWith(".ini", ignoreCase = true)) {
                                onFileSelected(file)
                            }
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (file.isDirectory)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = if (file.isDirectory) "📁 ${file.name}" else "📄 ${file.name}",
                        modifier = Modifier.padding(12.dp),
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Button(
            onClick = onClose,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("Закрыть проводник")
        }
    }
}