package com.intmash.modbus_scanner.navigation

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import androidx.lifecycle.viewmodel.compose.viewModel
import com.intmash.modbus_scanner.ui.ConfigScreen
import com.intmash.modbus_scanner.ui.FoundDevice
import com.intmash.modbus_scanner.ui.FileBrowserScreen
import com.intmash.modbus_scanner.ui.MainScreen
import com.intmash.modbus_scanner.ui.MainViewModel
import com.intmash.modbus_scanner.ui.ParametersScreen
import com.intmash.modbus_scanner.ui.ParametersViewModel
import com.intmash.modbus_scanner.ui.ParametersViewModelFactory
import com.intmash.modbus_scanner.ui.SplashScreen
import kotlinx.coroutines.delay

/**
 * Корневой NavHost приложения.
 * Связывает типобезопасные маршруты с Composable-экранами.
 */
@Composable
fun AppNavHost(
    viewModel: MainViewModel,
    isVirtualDevice: Boolean,
    activityContext: android.content.Context,
    // Колбэк «настройки сохранены».
    onSettingsSaved: () -> Unit = {},
    // Колбэк «выбрано найденное устройство»: применяет адрес и переподключается.
    // Реализация — в MainActivity (там есть usbManager и connectionSettings).
    onApplyAddress: (FoundDevice) -> Unit = {},
    // Адрес slave в сети Modbus, актуальный на момент композиции.
    slaveAddress: Int = 1,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    var errorMessage by remember { mutableStateOf<String?>(null) }

    NavHost(
        navController = navController,
        startDestination = SplashRoute,
        modifier = modifier
    ) {
        // --- 1. Заставка ---
        composable<SplashRoute> {
            SplashScreen()
            LaunchedEffect(Unit) {
                delay(3000)
                navController.navigate(MainRoute) {
                    popUpTo(SplashRoute) { inclusive = true }
                }
            }
        }

        // --- 2. Главный экран ---
        composable<MainRoute> {
            MainScreen(
                deviceId = viewModel.deviceId,
                isConnected = viewModel.isConnected,
                isVirtualDevice = isVirtualDevice,
                onSelectFileClick = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                        !Environment.isExternalStorageManager()
                    ) {
                        try {
                            activityContext.startActivity(
                                Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                            )
                        } catch (e: Exception) {
                            activityContext.startActivity(Intent(Settings.ACTION_SETTINGS))
                        }
                    } else {
                        navController.navigate(FileBrowserRoute)
                    }
                },
                errorMessage = errorMessage,
                onSettingsSaved = onSettingsSaved,

                // Поиск устройств: состояние из MainViewModel, действия — его методы.
                searchState = viewModel.searchState,
                foundDevices = viewModel.foundDevices,
                currentSearchAddress = viewModel.currentSearchAddress,
                onStartSearch = { timeoutMs -> viewModel.startSearch(timeoutMs) },
                onStopSearch = { viewModel.stopSearch() },
                onApplyAddress = onApplyAddress
            )
        }

        // --- 3. Файловый браузер ---
        composable<FileBrowserRoute> {
            FileBrowserScreen(
                initialDir = Environment.getExternalStorageDirectory(),
                onFileSelected = { file ->
                    val uri = Uri.fromFile(file)
                    errorMessage = null
                    viewModel.loadIniFile(activityContext, uri)
                    navController.navigate(ConfigRoute(file.name))
                },
                onClose = { navController.popBackStack() }
            )
        }

        // --- 4. Меню страниц ---
        composable<ConfigRoute> { backStackEntry ->
            val route: ConfigRoute = backStackEntry.toRoute()
            ConfigScreen(
                deviceId = viewModel.deviceId,
                selectedFileName = route.selectedFileName,
                pages = viewModel.pages,
                onRefreshIdClick = { viewModel.refreshDeviceId() },
                onPageClick = { page ->
                    navController.navigate(ParametersRoute(pageId = page.id))
                }
            )
        }

        // --- 5. Экран параметров выбранной страницы ---
        composable<ParametersRoute> { backStackEntry ->
            val route: ParametersRoute = backStackEntry.toRoute()
            val page = viewModel.pages.find { it.id == route.pageId }
            if (page != null) {
                // Создаём ViewModel параметров через фабрику, привязанную к этому
                // back stack entry. key = pageId гарантирует, что при переходах
                // между страницами у каждой будет свой экземпляр VM (и свой опрос).
                // key включает slaveAddress: при смене адреса в настройках
                // Compose уничтожает старый экземпляр VM (его onCleared
                // останавливает polling) и создаёт новый — с новым адресом.
                // Без этого старый опрос продолжал бы слать запросы на старый
                // адрес параллельно с MainViewModel — конфликт обмена.
                val parametersViewModel: ParametersViewModel = viewModel(
                    key = "parameters_${route.pageId}_$slaveAddress",
                    factory = ParametersViewModelFactory(
                        repository = viewModel.repository,
                        page = page,
                        slaveAddress = slaveAddress
                    )
                )
                // Запускаем опрос при входе на экран. Останавливается автоматически,
                // когда экземпляр VM уничтожается (onCleared при выходе из back stack).
                // LaunchedEffect с ключом = parametersViewModel: срабатывает один раз
                // при создании композиции и повторно — только если VM поменяется.
                LaunchedEffect(parametersViewModel) {
                    parametersViewModel.startPolling()
                }
                ParametersScreen(
                    page = page,
                    parametersViewModel = parametersViewModel,
                    onBack = { navController.popBackStack() }
                )
            } else {
                Text("Страница не найдена")
            }
        }
    }
}