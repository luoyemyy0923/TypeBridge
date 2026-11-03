package cn.aclin.typebridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "history")
data class HistoryEntity(
    @PrimaryKey val id: String,
    /** 始终以 AES-GCM 密文（base64）保存 */
    val text: String,
    val sentAt: Long,
    val status: String,
    val enter: Boolean,
    val deviceName: String
)
