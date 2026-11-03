package cn.aclin.typebridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface QuickInputDao {
    @Query("SELECT * FROM quick_inputs ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<QuickInputEntity>

    @Query("SELECT * FROM quick_inputs WHERE id = :id")
    suspend fun get(id: Long): QuickInputEntity?

    @Query("SELECT * FROM quick_inputs WHERE groupId = :groupId ORDER BY sortOrder ASC, id ASC")
    suspend fun listByGroup(groupId: Long): List<QuickInputEntity>

    @Query("SELECT MAX(sortOrder) FROM quick_inputs WHERE groupId = :groupId")
    suspend fun maxSortOrder(groupId: Long): Int?

    @Query("SELECT MIN(sortOrder) FROM quick_inputs WHERE groupId = :groupId")
    suspend fun minSortOrder(groupId: Long): Int?

    @Query("UPDATE quick_inputs SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    /** 仅更新元信息（描述 / 回车 / 分组 / 排序），不动文本与加密标记。 */
    @Query(
        "UPDATE quick_inputs SET description = :description, enter = :enter, " +
            "groupId = :groupId, sortOrder = :sortOrder WHERE id = :id"
    )
    suspend fun updateMeta(
        id: Long,
        description: String,
        enter: Boolean,
        groupId: Long,
        sortOrder: Int
    )

    /** 把整组条目移动到另一组，并整体平移排序，避免与目标组已有顺序冲突。 */
    @Query(
        "UPDATE quick_inputs SET groupId = :toGroupId, sortOrder = sortOrder + :offset " +
            "WHERE groupId = :fromGroupId"
    )
    suspend fun moveAllToGroup(fromGroupId: Long, toGroupId: Long, offset: Int)

    @Query(
        "UPDATE quick_inputs SET groupId = :defaultGroupId " +
            "WHERE groupId = 0 OR groupId NOT IN (SELECT id FROM quick_input_groups)"
    )
    suspend fun moveInvalidToDefault(defaultGroupId: Long)

    @Query(
        "SELECT COUNT(*) FROM quick_inputs WHERE groupId = 0 " +
            "OR groupId NOT IN (SELECT id FROM quick_input_groups)"
    )
    suspend fun countInvalid(): Int

    @Insert
    suspend fun insert(item: QuickInputEntity): Long

    @Update
    suspend fun update(item: QuickInputEntity)

    @Query("DELETE FROM quick_inputs WHERE id = :id")
    suspend fun delete(id: Long)
}
