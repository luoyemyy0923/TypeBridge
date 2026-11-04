// 根项目统一声明插件版本（apply false），子模块按需 apply，避免插件被重复加载。
plugins {
    kotlin("jvm") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.11.1" apply false
}
