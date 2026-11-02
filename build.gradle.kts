plugins {
    id("com.android.application") version "9.1.1" apply false
    // Поднимаем KGP до 2.4.10, чтобы встроенный компилятор читал метаданные stdlib 2.4.x
    id("org.jetbrains.kotlin.android") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.10" apply false
}