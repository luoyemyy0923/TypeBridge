package cn.aclin.typebridge.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [QuickInputEntity::class, QuickInputGroupEntity::class, HistoryEntity::class],
    version = 3,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun quickInputDao(): QuickInputDao
    abstract fun quickInputGroupDao(): QuickInputGroupDao
    abstract fun historyDao(): HistoryDao

    companion object {
        private const val DB_NAME = "typebridge.db"

        /** v2：快捷输入新增「发送时按回车」，旧数据默认 false。 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE quick_inputs ADD COLUMN enter INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v3：快捷输入支持分组；旧条目全部归入默认分组。 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS quick_input_groups (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "name TEXT NOT NULL, " +
                        "sortOrder INTEGER NOT NULL, " +
                        "isDefault INTEGER NOT NULL)"
                )
                db.execSQL(
                    "INSERT INTO quick_input_groups (name, sortOrder, isDefault) " +
                        "VALUES ('默认分组', 0, 1)"
                )
                db.execSQL(
                    "ALTER TABLE quick_inputs ADD COLUMN groupId INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "ALTER TABLE quick_inputs ADD COLUMN sortOrder INTEGER NOT NULL DEFAULT 0"
                )
                // 旧数据归入默认分组；sortOrder 取 -id 以保持原来的「新的在前」
                db.execSQL(
                    "UPDATE quick_inputs SET groupId = " +
                        "(SELECT id FROM quick_input_groups WHERE isDefault = 1 LIMIT 1), " +
                        "sortOrder = -id"
                )
            }
        }

        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    DB_NAME
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
    }
}
