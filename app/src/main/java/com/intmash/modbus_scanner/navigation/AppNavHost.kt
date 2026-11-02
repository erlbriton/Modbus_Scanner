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
import com.intmash.modbus_scanner.ui.ConfigScreen
import com.intmash.modbus_scanner.ui.FileBrowserScreen
import com.intmash.modbus_scanner.ui.MainScreen
import com.intmash.modbus_scanner.ui.MainViewModel
import com.intmash.modbus_scanner.ui.ParametersScreen
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
                errorMessage = errorMessage
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
                ParametersScreen(
                    page = page,
                    onBack = { navController.popBackStack() }
                )
            } else {
                Text("Страница не найдена")
            }
        }
    }
}