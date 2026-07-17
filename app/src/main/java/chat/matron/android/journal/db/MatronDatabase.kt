package chat.matron.android.journal.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/// Room database backing the journal mirror. Schema v1 already includes
/// `parent_convo_id` (the Apple original added it in a v2 migration; a fresh
/// Android app has no installed base, so it ships in v1 and no migration is
/// needed). `exportSchema = false` since there is nothing to migrate from.
@Database(
    entities = [ConversationEntity::class, EventEntity::class, MetaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MatronDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun eventDao(): EventDao
    abstract fun metaDao(): MetaDao

    companion object {
        /// Production, file-backed at the given path.
        fun open(context: Context, file: File): MatronDatabase =
            Room.databaseBuilder(context.applicationContext, MatronDatabase::class.java, file.absolutePath)
                .build()

        /// Test/ephemeral, memory-backed. Cleared when the last connection closes.
        fun inMemory(context: Context): MatronDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, MatronDatabase::class.java)
                .build()
    }
}
