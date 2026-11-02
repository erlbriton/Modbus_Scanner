package com.intmash.modbus_scanner.navigation

import kotlinx.serialization.Serializable

/**
 * Типобезопасные маршруты приложения.
 * Для каждого экрана — либо data object (без аргументов), либо data class (с аргументами).
 */
@Serializable
data object SplashRoute

@Serializable
data object MainRoute

@Serializable
data object FileBrowserRoute

@Serializable
data class ConfigRoute(val selectedFileName: String)

@Serializable
data class ParametersRoute(val pageId: String)