package cn.aclin.typebridge.model

/** 快捷输入分组（单层，不支持嵌套）。 */
data class QuickInputGroup(
    val id: Long = 0,
    val name: String,
    val sortOrder: Int = 0,
    val isDefault: Boolean = false
)
