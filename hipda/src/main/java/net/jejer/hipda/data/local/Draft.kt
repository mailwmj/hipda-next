package net.jejer.hipda.data.local

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * Business key identifying a draft slot: `reply:<tid>`, `new:<fid>`, or `edit:<pid>`.
 */
@JvmInline
value class DraftKey private constructor(val value: String) {

    enum class Kind(val prefix: String) {
        REPLY("reply"),
        NEW("new"),
        EDIT("edit"),
    }

    val kind: Kind
        get() = when (value.substringBefore(SEPARATOR)) {
            Kind.REPLY.prefix -> Kind.REPLY
            Kind.NEW.prefix -> Kind.NEW
            Kind.EDIT.prefix -> Kind.EDIT
            else -> error("Invalid DraftKey value: $value")
        }

    val targetId: String
        get() = value.substringAfter(SEPARATOR)

    companion object {
        private const val SEPARATOR = ':'
        private const val MAX_ID_LENGTH = 19

        fun reply(tid: String): DraftKey = create(Kind.REPLY, tid, "tid")

        fun newThread(fid: String): DraftKey = create(Kind.NEW, fid, "fid")

        fun edit(pid: String): DraftKey = create(Kind.EDIT, pid, "pid")

        fun parse(raw: String): DraftKey? {
            val separatorIndex = raw.indexOf(SEPARATOR)
            if (separatorIndex <= 0 || separatorIndex != raw.lastIndexOf(SEPARATOR)) {
                return null
            }
            val prefix = raw.substring(0, separatorIndex)
            val id = raw.substring(separatorIndex + 1)
            val validPrefix = prefix == Kind.REPLY.prefix ||
                prefix == Kind.NEW.prefix ||
                prefix == Kind.EDIT.prefix
            if (!validPrefix || !isValidId(id)) {
                return null
            }
            return DraftKey(raw)
        }

        private fun create(kind: Kind, id: String, label: String): DraftKey {
            require(isValidId(id)) { "Invalid $label for DraftKey: '$id'" }
            return DraftKey("${kind.prefix}$SEPARATOR$id")
        }

        private fun isValidId(id: String): Boolean =
            id.length in 1..MAX_ID_LENGTH &&
                id[0] in '1'..'9' &&
                id.all { it in '0'..'9' } &&
                id.toLongOrNull() != null
    }
}

@Entity(tableName = "drafts")
data class DraftEntity(
    @PrimaryKey
    val key: String,
    val subject: String = "",
    @ColumnInfo(name = "type_id")
    val typeId: String = "",
    val content: String = "",
    @ColumnInfo(name = "quote_pid")
    val quotePid: String? = null,
    val attachments: List<String> = emptyList(),
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)

@Dao
interface DraftDao {
    @Query("SELECT * FROM drafts WHERE `key` = :key")
    fun observe(key: String): Flow<DraftEntity?>

    @Upsert
    suspend fun upsert(draft: DraftEntity)

    @Query("DELETE FROM drafts WHERE `key` = :key")
    suspend fun delete(key: String)
}

fun DraftDao.observe(key: DraftKey): Flow<DraftEntity?> = observe(key.value)

suspend fun DraftDao.delete(key: DraftKey) = delete(key.value)
