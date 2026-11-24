package net.jejer.hipda.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

@Database(
    entities = [
        DraftEntity::class,
        BrowseHistoryEntity::class,
        ReadingPositionEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
@TypeConverters(HiDatabaseConverters::class)
abstract class HiDatabase : RoomDatabase() {
    abstract fun draftDao(): DraftDao
    abstract fun browseHistoryDao(): BrowseHistoryDao
    abstract fun readingPositionDao(): ReadingPositionDao

    companion object {
        const val DATABASE_NAME: String = "hipda_next.db"
    }
}

class HiDatabaseConverters {
    @TypeConverter
    fun fromStringList(value: List<String>): String =
        Json.encodeToString(stringListSerializer, value)

    @TypeConverter
    fun toStringList(value: String): List<String> =
        if (value.isBlank()) {
            emptyList()
        } else {
            Json.decodeFromString(stringListSerializer, value)
        }

    private companion object {
        val stringListSerializer = ListSerializer(String.serializer())
    }
}
