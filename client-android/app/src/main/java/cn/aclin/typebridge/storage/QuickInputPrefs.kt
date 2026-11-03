package cn.aclin.typebridge.storage

import android.content.Context

/** 快捷输入分组的界面状态（展开 / 折叠），持久化在加密偏好设置中。 */
object QuickInputPrefs {
    private const val KEY_COLLAPSED = "quick_group_collapsed"

    fun collapsed(context: Context): Set<Long> {
        val raw = PrefsFactory.encrypted(context).getString(KEY_COLLAPSED, null)
            ?: return emptySet()
        return raw.split(',')
            .mapNotNull { it.trim().toLongOrNull() }
            .toSet()
    }

    fun setCollapsed(context: Context, ids: Set<Long>) {
        PrefsFactory.encrypted(context)
            .edit()
            .putString(KEY_COLLAPSED, ids.joinToString(","))
            .apply()
    }
}
