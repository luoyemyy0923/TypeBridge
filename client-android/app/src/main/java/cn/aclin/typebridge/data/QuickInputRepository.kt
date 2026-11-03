package cn.aclin.typebridge.data

import android.content.Context
import androidx.room.withTransaction
import cn.aclin.typebridge.data.db.AppDatabase
import cn.aclin.typebridge.data.db.QuickInputDao
import cn.aclin.typebridge.data.db.QuickInputEntity
import cn.aclin.typebridge.data.db.QuickInputGroupEntity
import cn.aclin.typebridge.model.QuickInput
import cn.aclin.typebridge.model.QuickInputGroup
import cn.aclin.typebridge.model.maskText
import cn.aclin.typebridge.network.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Collections

class QuickInputRepository {

    suspend fun listGroups(context: Context): List<QuickInputGroup> = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        ensureDefaultGroup(db)
        db.quickInputGroupDao().getAll().map { it.toModel() }
    }

    suspend fun list(context: Context): List<QuickInput> = withContext(Dispatchers.IO) {
        val key = DbKeyStore.getOrCreate(context)
        val db = AppDatabase.get(context)
        val defaultId = ensureDefaultGroup(db).id
        val groupIds = db.quickInputGroupDao().getAll().map { it.id }.toSet()
        db.quickInputDao().getAll().map { entity ->
            QuickInput(
                id = entity.id,
                description = entity.description,
                // 加密记录不把明文带进内存，只保留首尾字符的遮罩预览
                text = if (entity.encrypted) {
                    decrypt(key, entity.text).let {
                        if (it == DECRYPT_FAILED) it else maskText(it)
                    }
                } else {
                    entity.text
                },
                encrypted = entity.encrypted,
                enter = entity.enter,
                groupId = entity.groupId.takeIf { it in groupIds } ?: defaultId,
                sortOrder = entity.sortOrder
            )
        }
    }

    /** 发送时按需解密；明文记录直接返回存储值。找不到返回 null。 */
    suspend fun textForSend(context: Context, id: Long): String? = withContext(Dispatchers.IO) {
        val entity = AppDatabase.get(context).quickInputDao().get(id) ?: return@withContext null
        if (!entity.encrypted) return@withContext entity.text
        decrypt(DbKeyStore.getOrCreate(context), entity.text)
            .takeIf { it != DECRYPT_FAILED }
    }

    suspend fun save(context: Context, input: QuickInput) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.quickInputDao()
        val key = DbKeyStore.getOrCreate(context)
        val storedText = if (input.encrypted) {
            Crypto.b64(Crypto.encrypt(key, input.text.toByteArray(), DbKeyStore.QUICK_INPUT_AAD))
        } else {
            input.text
        }
        val groupId = resolveGroupId(db, input.groupId)
        val base = QuickInputEntity(
            id = input.id,
            description = input.description,
            text = storedText,
            encrypted = input.encrypted,
            enter = input.enter,
            groupId = groupId
        )
        if (input.id == 0L) {
            dao.insert(base.copy(sortOrder = nextSortOrder(dao, groupId)))
        } else {
            val existing = dao.get(input.id) ?: return@withContext
            val order = if (existing.groupId == groupId) {
                existing.sortOrder
            } else {
                nextSortOrder(dao, groupId)
            }
            dao.update(base.copy(sortOrder = order))
        }
    }

    suspend fun delete(context: Context, id: Long) = withContext(Dispatchers.IO) {
        AppDatabase.get(context).quickInputDao().delete(id)
    }

    /** 只调整所属分组，不动文本（用于加密条目修改分组）。 */
    suspend fun moveToGroup(context: Context, id: Long, groupId: Long) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.quickInputDao()
        val existing = dao.get(id) ?: return@withContext
        val target = resolveGroupId(db, groupId)
        if (existing.groupId == target) return@withContext
        dao.updatePlacement(id, target, nextSortOrder(dao, target))
    }

    // MARK: - 分组

    suspend fun saveGroup(context: Context, group: QuickInputGroup) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.quickInputGroupDao()
        val name = group.name.trim()
        if (name.isEmpty()) return@withContext
        if (group.id == 0L) {
            dao.insert(
                QuickInputGroupEntity(
                    name = name,
                    sortOrder = (dao.maxSortOrder() ?: -1) + 1,
                    isDefault = false
                )
            )
        } else {
            dao.updateName(group.id, name)
        }
    }

    /** 删除分组：组内条目全部移动到默认分组；默认分组不可删除。 */
    suspend fun deleteGroup(context: Context, id: Long) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val groupDao = db.quickInputGroupDao()
        if (groupDao.getDefault()?.id == id) return@withContext
        val default = ensureDefaultGroup(db)
        val itemDao = db.quickInputDao()
        val offset = (itemDao.maxSortOrder(default.id) ?: -1) + 1 -
            (itemDao.minSortOrder(id) ?: 0)
        db.withTransaction {
            itemDao.moveAllToGroup(id, default.id, offset)
            groupDao.delete(id)
        }
    }

    /** 分组上移/下移；默认分组同样可排序（但不能删除）。 */
    suspend fun moveGroup(context: Context, id: Long, up: Boolean) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.quickInputGroupDao()
        val ordered = dao.getAll()
            .sortedWith(compareBy({ it.sortOrder }, { it.id }))
            .toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return@withContext
        val target = if (up) index - 1 else index + 1
        if (target !in ordered.indices) return@withContext
        Collections.swap(ordered, index, target)
        db.withTransaction {
            ordered.forEachIndexed { i, group -> dao.updateSortOrder(group.id, i) }
        }
    }

    /** 组内条目上移/下移。 */
    suspend fun moveItem(context: Context, id: Long, up: Boolean) = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val dao = db.quickInputDao()
        val item = dao.get(id) ?: return@withContext
        val ordered = dao.listByGroup(item.groupId).toMutableList()
        val index = ordered.indexOfFirst { it.id == id }
        if (index < 0) return@withContext
        val target = if (up) index - 1 else index + 1
        if (target !in ordered.indices) return@withContext
        Collections.swap(ordered, index, target)
        db.withTransaction {
            ordered.forEachIndexed { i, entry -> dao.updateSortOrder(entry.id, i) }
        }
    }

    /** 整理数据：未分组 / 分组已失效的条目全部移入默认分组，返回移动条数。 */
    suspend fun tidyUp(context: Context): Int = withContext(Dispatchers.IO) {
        val db = AppDatabase.get(context)
        val groupDao = db.quickInputGroupDao()
        val itemDao = db.quickInputDao()
        val default = ensureDefaultGroup(db)
        val moved = itemDao.countInvalid()
        db.withTransaction {
            groupDao.clearOtherDefaults(default.id)
            itemDao.moveInvalidToDefault(default.id)
        }
        // 移动后组内排序可能重复，按当前顺序重新编号
        db.withTransaction {
            itemDao.listByGroup(default.id).forEachIndexed { i, entry ->
                itemDao.updateSortOrder(entry.id, i)
            }
        }
        moved
    }

    // MARK: - 内部工具

    private suspend fun ensureDefaultGroup(db: AppDatabase): QuickInputGroupEntity {
        val dao = db.quickInputGroupDao()
        dao.getDefault()?.let { return it }
        val id = dao.insert(
            QuickInputGroupEntity(name = DEFAULT_GROUP_NAME, sortOrder = 0, isDefault = true)
        )
        return QuickInputGroupEntity(
            id = id,
            name = DEFAULT_GROUP_NAME,
            sortOrder = 0,
            isDefault = true
        )
    }

    private suspend fun resolveGroupId(db: AppDatabase, requested: Long): Long {
        if (requested != 0L && db.quickInputGroupDao().exists(requested) > 0) return requested
        return ensureDefaultGroup(db).id
    }

    private suspend fun nextSortOrder(dao: QuickInputDao, groupId: Long): Int =
        (dao.maxSortOrder(groupId) ?: -1) + 1

    private fun decrypt(key: ByteArray, encoded: String): String = runCatching {
        val raw = Crypto.b64d(encoded) ?: error("invalid base64")
        Crypto.decrypt(key, raw, DbKeyStore.QUICK_INPUT_AAD).decodeToString()
    }.getOrDefault(DECRYPT_FAILED)

    private fun QuickInputGroupEntity.toModel() = QuickInputGroup(
        id = id,
        name = name,
        sortOrder = sortOrder,
        isDefault = isDefault
    )

    private companion object {
        const val DECRYPT_FAILED = "[无法解密]"
        const val DEFAULT_GROUP_NAME = "默认分组"
    }
}
