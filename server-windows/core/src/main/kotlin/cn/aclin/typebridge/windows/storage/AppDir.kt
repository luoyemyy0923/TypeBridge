package cn.aclin.typebridge.windows.storage

import java.io.File

/**
 * 应用数据目录：默认 `%APPDATA%\TypeBridge\`（对应 macOS 的 Application Support）。
 * 测试可通过 `-Dtypebridge.dataDir=...` 覆盖，避免污染真实数据。
 */
object AppDir {
    private val dir: File by lazy {
        val f = System.getProperty("typebridge.dataDir")?.let { File(it) }
            ?: File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "TypeBridge")
        f.mkdirs()
        f
    }

    fun get(): File = dir
}
