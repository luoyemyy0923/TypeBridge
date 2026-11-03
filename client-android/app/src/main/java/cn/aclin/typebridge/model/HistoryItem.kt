package cn.aclin.typebridge.model

import kotlinx.serialization.Serializable

@Serializable
data class HistoryItem(
    val id: String,
    val text: String,
    val sentAt: Long,
    val status: String,
    val enter: Boolean,
    val deviceName: String
)
