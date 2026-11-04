plugins {
    kotlin("jvm")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    // JSON（与安卓端一致，帧协议 + @Serializable 持久化）
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.10.0")
    // WebSocket 服务端（对应 macOS FlyingFox）；TypeBridgeWebSocketServer 公开继承它，故用 api 暴露给 app
    api("org.java-websocket:Java-WebSocket:1.5.7")
    // Win32 互操作（SendInput / DPAPI / 注册表）
    implementation("net.java.dev.jna:jna:5.14.0")
    implementation("net.java.dev.jna:jna-platform:5.14.0")
    // 二维码
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.zxing:javase:3.5.3")

    testImplementation("junit:junit:4.13.2")
}

tasks.test {
    useJUnit()
}
