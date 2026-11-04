package com.intmash.modbus_scanner

import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.intmash.modbus_scanner.navigation.AppNavHost
import com.intmash.modbus_scanner.ui.MainViewModel
import com.intmash.modbus_scanner.ui.theme.ModbusIDTheme
import com.intmash.modbus_scanner.usb.UsbConnectionManager
import com.intmash.modbus_scanner.data.ConnectionSettings
import com.intmash.modbus_scanner.ui.FoundDevice


class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel
    private lateinit var usbConnection: UsbConnectionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Получаем ViewModel через ViewModelProvider (без делегата by viewModels())
        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        val usbManager = getSystemService(USB_SERVICE) as UsbManager

        // Создаём менеджер USB. Всё, что было в usbReceiver и attachReceiver,
        // теперь внутри UsbConnectionManager.
        // Читаем настройки один раз при старте.
        // Если пользователь меняет скорость — применится при следующем
        // переподключении контроллера (или перезапуске приложения).
        // Автоматическое переподключение после сохранения — следующий подшаг.
        val connectionSettings = ConnectionSettings(this)

        // State с текущим адресом slave. Нужен, чтобы AppNavHost перерисовался
        // при смене адреса — тогда ParametersViewModel создастся заново
        // со свежим адресом (через key). Изначально читается из SharedPreferences.
        var slaveAddressState by mutableStateOf(connectionSettings.getSlaveAddress())

        usbConnection = UsbConnectionManager(
            activity = this,
            usbManager = usbManager,
            onDeviceReady = { device ->
                viewModel.initializeAndConnect(
                    usbManager = usbManager,
                    device = device,
                    baudRate = connectionSettings.getBaudRate(),
                    slaveAddress = connectionSettings.getSlaveAddress()
                )
            },
            onDeviceDetached = { device ->
                if (viewModel.isOurDevice(device)) {
                    viewModel.shutdown()
                }
            }
        )
        usbConnection.register()
        usbConnection.checkDevices()

        // Лямбда «настройки сохранены»: переподключаемся к контроллеру
        // с новой скоростью из SharedPreferences.
        //
        // Логика:
        //  1. Находим текущее устройство (то, с которым работали).
        //  2. Если устройство не найдено (например, кабель выдернут) — ничего
        //     не делаем: reconnect бессмысленен, при следующем подключении
        //     новая скорость применится автоматически (см. onDeviceReady).
        //  3. Иначе — останавливаем опрос через viewModel.shutdown() (он же
        //     закрывает порт), затем заново вызываем initializeAndConnect
        //     с новой скоростью.
        val reconnectWithCurrentSettings = {
            // Обновляем State адреса. Это триггерит рекомпозицию AppNavHost,
            // и ParametersViewModel (если открыт) пересоздастся через key.
            // Старый экземпляр вызовет onCleared → остановит свой polling.
            slaveAddressState = connectionSettings.getSlaveAddress()

            val currentDevice = usbManager.deviceList.values.firstOrNull {
                viewModel.isOurDevice(it)
            }
            if (currentDevice != null) {
                viewModel.shutdown()   // Останавливает опрос, закрывает порт
                viewModel.initializeAndConnect(
                    usbManager = usbManager,
                    device = currentDevice,
                    baudRate = connectionSettings.getBaudRate(),
                    slaveAddress = connectionSettings.getSlaveAddress()
                )
            }
        }

        // Лямбда «пользователь выбрал устройство из результатов поиска»:
        // сохраняем новый адрес в SharedPreferences, обновляем State
        // (для рекомпозиции AppNavHost и пересоздания ParametersViewModel),
        // переподключаемся по новому адресу через MainViewModel.applyFoundAddress.
        val onApplyAddressHandler: (FoundDevice) -> Unit = { found ->
            // 1. Сохраняем новый адрес в настройки.
            connectionSettings.setSlaveAddress(found.address)
            // 2. Обновляем State — AppNavHost перерисуется, ParametersViewModel
            //    пересоздастся через key.
            slaveAddressState = found.address
            // 3. Переподключаемся к контроллеру по новому адресу.
            //    applyFoundAddress внутри остановит поиск, сделает shutdown
            //    и заново initializeAndConnect с новым адресом.
            val currentDevice = usbManager.deviceList.values.firstOrNull {
                viewModel.isOurDevice(it)
            }
            if (currentDevice != null) {
                viewModel.applyFoundAddress(
                    address = found.address,
                    usbManager = usbManager,
                    device = currentDevice,
                    baudRate = connectionSettings.getBaudRate()
                )
            }
        }

        setContent {
            ModbusIDTheme {
                // Чтение State внутри setContent — Compose «подписывается»
                // на изменения slaveAddressState. При его изменении
                // весь блок перерисуется, и AppNavHost получит новый адрес.
                val currentSlaveAddress = slaveAddressState

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppNavHost(
                        viewModel = viewModel,
                        isVirtualDevice = IS_VIRTUAL_DEVICE,
                        activityContext = this,
                        onSettingsSaved = reconnectWithCurrentSettings,
                        onApplyAddress = onApplyAddressHandler,
                        slaveAddress = currentSlaveAddress,
                        modifier = Modifier.padding(innerPadding)
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Если связи нет — прогоняем полное сканирование USB.
        // Это покрывает сценарий "приложение было в фоне, контроллер переподключили".
        // checkDevices() сам разберётся:
        //  - если разрешение уже есть → сразу onDeviceReady (подключение);
        //  - если разрешения нет → запросит его у пользователя;
        //  - если устройств нет → ничего не делает.
        if (!viewModel.isConnected) {
            usbConnection.checkDevices()
        }
    }

    override fun onDestroy() {
        usbConnection.unregister()
        viewModel.shutdown()
        super.onDestroy()
    }

    companion object {
        private const val IS_VIRTUAL_DEVICE = true
    }
}