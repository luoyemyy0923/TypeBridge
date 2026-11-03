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
    val enter: Boolean = false,
    /** 所属分组；0 表示未分组（读取时会归入默认分组） */
    val groupId: Long = 0,
    /** 组内排序，越小越靠前 */
    val sortOrder: Int = 0
)
