package cn.aclin.typebridge.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "quick_input_groups")
data class QuickInputGroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** 组排序，越小越靠前；默认分组固定排在最前 */
    val sortOrder: Int = 0,
    /** 默认分组不可删除，删除其他分组时组内条目会移动到这里 */
    val isDefault: Boolean = false
)
