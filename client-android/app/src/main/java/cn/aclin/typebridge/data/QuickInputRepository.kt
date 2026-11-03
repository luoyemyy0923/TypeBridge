package cn.aclin.typebridge.data

import android.content.Context
import cn.aclin.typebridge.data.db.AppDatabase
import cn.aclin.typebridge.data.db.QuickInputEntity
import cn.aclin.typebridge.model.QuickInput
import cn.aclin.typebridge.model.maskText
import cn.aclin.typebridge.network.Crypto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class QuickInputRepository {

    suspend fun list(context: Context): List<QuickInput> = withContext(Dispatchers.IO) {
        val key = DbKeyStore.getOrCreate(context)
        AppDatabase.get(context).quickInputDao().getAll().map { entity ->
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
                enter = entity.enter
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
        val key = DbKeyStore.getOrCreate(context)
        val storedText = if (input.encrypted) {
            Crypto.b64(Crypto.encrypt(key, input.text.toByteArray(), DbKeyStore.QUICK_INPUT_AAD))
        } else {
            input.text
        }
        val entity = QuickInputEntity(
            id = input.id,
            description = input.description,
            text = storedText,
            encrypted = input.encrypted,
            enter = input.enter
        )
        val dao = AppDatabase.get(context).quickInputDao()
        if (input.id == 0L) {
            dao.insert(entity)
        } else {
            dao.update(entity)
        }
    }

    suspend fun delete(context: Context, id: Long) = withContext(Dispatchers.IO) {
        AppDatabase.get(context).quickInputDao().delete(id)
    }

    private fun decrypt(key: ByteArray, encoded: String): String = runCatching {
        val raw = Crypto.b64d(encoded) ?: error("invalid base64")
        Crypto.decrypt(key, raw, DbKeyStore.QUICK_INPUT_AAD).decodeToString()
    }.getOrDefault(DECRYPT_FAILED)

    private companion object {
        const val DECRYPT_FAILED = "[无法解密]"
    }
}
