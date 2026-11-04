import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
}

compose.desktop {
    application {
        mainClass = "cn.aclin.typebridge.windows.app.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "TypeBridge"
            packageVersion = "2.1.0"
            description = "投字 - 局域网键入桥（Windows 服务端）"
            vendor = "aclin"
            // JNA 依赖 jdk.unsupported（sun.misc.Unsafe）与 java.management，
            // jpackage 的 jlink 裁剪默认不含这些模块，缺失会导致打包后 SendInput /
            // DPAPI 等 native 调用失败（开发模式用完整 JDK 所以看不出问题）。
            modules("jdk.unsupported", "java.management", "java.naming")
            windows {
                iconFile.set(project.file("src/main/resources/app-icon.ico"))
                menuGroup = "投字"
                upgradeUuid = "8a1d5c9e-2f4b-4a6e-b3c1-7d9f0e5a2b1c"
                perUserInstall = true
            }
        }
    }
}
