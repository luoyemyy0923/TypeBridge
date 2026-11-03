package cn.aclin.typebridge.model

import kotlinx.serialization.Serializable

@Serializable
data class ActionItem(
    val id: String,
    val name: String,
    val behavior: String,
    val text: String,
    val order: Int,
    val sensitive: Boolean
) {
    val isTextEnter: Boolean
        get() = behavior == "text_enter"

    val subtitle: String
        get() = when {
            sensitive -> "••••••"
            text.isEmpty() -> "使用输入文本"
            else -> text.take(12)
        }
}
