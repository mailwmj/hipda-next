package net.jejer.hipda.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import kotlinx.serialization.SerializationException
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
    fun toStringList(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return try {
            Json.decodeFromString(stringListSerializer, value)
        } catch (_: SerializationException) {
            emptyList()
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
    }

    @TypeConverter
    fun fromReadingFilterMode(mode: ReadingFilterMode): String = mode.name

    @TypeConverter
    fun toReadingFilterMode(value: String?): ReadingFilterMode =
        if (value.isNullOrBlank()) {
            ReadingFilterMode.NONE
        } else {
            ReadingFilterMode.entries.firstOrNull { it.name == value } ?: ReadingFilterMode.NONE
        }

    private companion object {
        val stringListSerializer = ListSerializer(String.serializer())
    }
}
