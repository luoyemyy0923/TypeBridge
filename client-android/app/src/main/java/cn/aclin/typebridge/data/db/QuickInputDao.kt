package cn.aclin.typebridge.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update

@Dao
interface QuickInputDao {
    @Query("SELECT * FROM quick_inputs ORDER BY id DESC")
    suspend fun getAll(): List<QuickInputEntity>

    @Query("SELECT * FROM quick_inputs WHERE id = :id")
    suspend fun get(id: Long): QuickInputEntity?

    @Insert
    suspend fun insert(item: QuickInputEntity): Long

    @Update
    suspend fun update(item: QuickInputEntity)

    @Query("DELETE FROM quick_inputs WHERE id = :id")
    suspend fun delete(id: Long)
}
