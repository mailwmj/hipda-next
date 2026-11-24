package net.jejer.hipda.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert

enum class ReadingFilterMode {
    NONE,
    OP,
    USER,
}

@Entity(tableName = "reading_positions")
data class ReadingPositionEntity(
    @PrimaryKey
    val tid: String,
    val page: Int,
    val pid: String,
    @ColumnInfo(name = "offset_px")
    val offsetPx: Int,
    @ColumnInfo(name = "filter_mode")
    val filterMode: ReadingFilterMode = ReadingFilterMode.NONE,
    @ColumnInfo(name = "filter_uid")
    val filterUid: String? = null,
    @ColumnInfo(name = "filter_user_name")
    val filterUserName: String? = null,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface ReadingPositionDao {
    @Query("SELECT * FROM reading_positions WHERE tid = :tid")
    suspend fun get(tid: String): ReadingPositionEntity?

    @Upsert
    suspend fun upsert(position: ReadingPositionEntity)
}
