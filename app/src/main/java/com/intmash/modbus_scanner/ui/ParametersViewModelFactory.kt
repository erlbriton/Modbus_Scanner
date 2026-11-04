package com.intmash.modbus_scanner.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.intmash.modbus_scanner.data.ModbusRepository
import com.intmash.modbus_scanner.data.PageInfo

/**
 * Фабрика для создания [ParametersViewModel] с параметрами.
 *
 * Зачем нужна фабрика:
 *  - Стандартный ViewModelProvider умеет создавать только ViewModel
 *    с пустым конструктором (или через SavedStateHandle).
 *  - Наша [ParametersViewModel] требует два аргумента: [repository] и [page].
 *  - Без фабрики мы бы не смогли её создать через `viewModel()` в Compose —
 *    пришлось бы вручную создавать инстанс и хранить его в remember,
 *    что неправильно (не переживёт пересоздание Activity/навигацию).
 *
 * Фабрика создаётся при входе на экран [ParametersScreen] и привязывается
 * к back stack entry этого маршрута. В итоге каждый экземпляр
 * [ParametersViewModel] живёт столько же, сколько экран параметров.
 *
 * @param repository Общий [ModbusRepository], взятый из [MainViewModel].
 *                   Порт уже открыт к моменту создания этой фабрики.
 * @param page       Страница, для которой создаётся ViewModel.
 */
class ParametersViewModelFactory(
    private val repository: ModbusRepository,
    private val page: PageInfo,
    private val slaveAddress: Int
) : ViewModelProvider.Factory {

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (!modelClass.isAssignableFrom(ParametersViewModel::class.java)) {
            throw IllegalArgumentException(
                "ParametersViewModelFactory: неподдерживаемый класс ViewModel: ${modelClass.name}"
            )
        }
        return ParametersViewModel(repository, page, slaveAddress) as T
    }
}