package cn.aclin.typebridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_inputs")
data class QuickInputEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val description: String,
    /** encrypted = true 时为 AES-GCM 密文（base64），否则为明文 */
    val text: String,
    val encrypted: Boolean,
    /** 发送时是否在末尾按回车；旧数据默认为 false */
    val enter: Boolean = false
)
