package cn.aclin.typebridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface QuickInputGroupDao {
    @Query("SELECT * FROM quick_input_groups ORDER BY sortOrder ASC, id ASC")
    suspend fun getAll(): List<QuickInputGroupEntity>

    @Query("SELECT * FROM quick_input_groups WHERE isDefault = 1 ORDER BY id ASC LIMIT 1")
    suspend fun getDefault(): QuickInputGroupEntity?

    @Query("SELECT MAX(sortOrder) FROM quick_input_groups")
    suspend fun maxSortOrder(): Int?

    @Query("SELECT COUNT(*) FROM quick_input_groups WHERE id = :id")
    suspend fun exists(id: Long): Int

    @Insert
    suspend fun insert(group: QuickInputGroupEntity): Long

    @Update
    suspend fun update(group: QuickInputGroupEntity)

    @Query("UPDATE quick_input_groups SET name = :name WHERE id = :id")
    suspend fun updateName(id: Long, name: String)

    @Query("UPDATE quick_input_groups SET sortOrder = :order WHERE id = :id")
    suspend fun updateSortOrder(id: Long, order: Int)

    @Query("UPDATE quick_input_groups SET isDefault = 0 WHERE isDefault = 1 AND id != :keepId")
    suspend fun clearOtherDefaults(keepId: Long)

    @Query("DELETE FROM quick_input_groups WHERE id = :id AND isDefault = 0")
    suspend fun delete(id: Long)
}
