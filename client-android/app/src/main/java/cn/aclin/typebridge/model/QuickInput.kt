package cn.aclin.typebridge.model

/**
 * 本机快捷输入。
 * [text] 仅对 [encrypted] = false 的记录是明文；加密记录在内存中只保留遮罩后的预览文本
 * （首尾字符之间全部用 * 代替），真正明文只在发送瞬间从数据库取出。
 */
data class QuickInput(
    val id: Long = 0,
    val description: String,
    val text: String,
    val encrypted: Boolean,
    /** 发送时是否在末尾按回车 */
    val enter: Boolean = false,
    /** 所属分组；0 表示未分组（展示时会归入默认分组） */
    val groupId: Long = 0,
    /** 组内排序，越小越靠前 */
    val sortOrder: Int = 0
)

/** 只保留首尾两个字符，中间全部替换为 *（加密内容在内存中的展示形式）。 */
fun maskText(text: String): String {
    val chars = text.trimEnd('\n')
    return when {
        chars.length <= 2 -> "*".repeat(chars.length)
        else -> chars.first().toString() + "*".repeat(chars.length - 2) + chars.last()
    }
}
