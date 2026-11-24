package net.jejer.hipda.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "browse_history",
    indices = [Index(value = ["last_visited_at"])],
)
data class BrowseHistoryEntity(
    @PrimaryKey
    val tid: String,
    val title: String,
    val fid: String,
    @ColumnInfo(name = "forum_name")
    val forumName: String,
    val author: String,
    @ColumnInfo(name = "last_visited_at")
    val lastVisitedAt: Long,
)

@Dao
interface BrowseHistoryDao {
    @Query(
        """
        SELECT * FROM browse_history
        ORDER BY last_visited_at DESC, tid ASC
        LIMIT MAX(:limit, 0)
        """,
    )
    fun observeRecent(limit: Int): Flow<List<BrowseHistoryEntity>>

    @Upsert
    suspend fun upsert(entity: BrowseHistoryEntity)

    @Query("DELETE FROM browse_history WHERE tid = :tid")
    suspend fun delete(tid: String)

    @Upsert
    suspend fun restore(entity: BrowseHistoryEntity)

    @Query(
        """
        DELETE FROM browse_history
        WHERE tid NOT IN (
            SELECT tid FROM browse_history
            ORDER BY last_visited_at DESC, tid ASC
            LIMIT MAX(:max, 0)
        )
        """,
    )
    suspend fun trimTo(max: Int)
}
