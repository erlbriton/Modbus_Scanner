package com.intmash.modbus_scanner.navigation

import kotlinx.serialization.Serializable

/**
 * Типобезопасные маршруты приложения.
 *
 * Каждый экран — это @Serializable объект (без аргументов) или data class (с аргументами).
 * Благодаря этому компилятор проверяет типы аргументов и названия экранов.
 *
 * Для расширения: добавляйте новые @Serializable объекты/классы сюда.
 */
@Serializable
data object SplashRoute

@Serializable
data object MainRoute

@Serializable
data object FileBrowserRoute

@Serializable
data class ConfigRoute(val selectedFileName: String)