package net.jejer.hipda.data.local

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import java.io.File
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.jejer.hipda.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class HiDatabaseDaoTest {

    private lateinit var database: HiDatabase
    private lateinit var draftDao: DraftDao
    private lateinit var browseHistoryDao: BrowseHistoryDao
    private lateinit var readingPositionDao: ReadingPositionDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        database = Room.inMemoryDatabaseBuilder(context, HiDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        draftDao = database.draftDao()
        browseHistoryDao = database.browseHistoryDao()
        readingPositionDao = database.readingPositionDao()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun draft_upsertObservedAndDeleteEmitsNull() = runTest {
        val key = DraftKey.reply("1579403")
        val initialDraft = DraftEntity(
            key = key.value,
            subject = "",
            typeId = "",
            content = "第一版回复内容",
            quotePid = "88001",
            attachments = listOf("att_1", "att_2"),
            updatedAt = 1_700_000_000_000L,
        )
        val updatedDraft = initialDraft.copy(
            content = "更新后的回复内容",
            quotePid = null,
            attachments = listOf("att_3", "att,\"special\"[id]"),
            updatedAt = 1_700_000_005_000L,
        )

        draftDao.observe(key).test {
            assertNull(awaitItem())

            draftDao.upsert(initialDraft)
            assertEquals(initialDraft, awaitItem())

            draftDao.upsert(updatedDraft)
            assertEquals(updatedDraft, awaitItem())

            draftDao.delete(key)
            assertNull(awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun draft_multipleBusinessKeysAreIsolatedAndEmptyAttachmentsRoundTrip() = runTest {
        val newDraft = DraftEntity(
            key = DraftKey.newThread("6").value,
            subject = "出闲置键盘",
            typeId = "7",
            content = "九成新，包邮",
            quotePid = null,
            attachments = emptyList(),
            updatedAt = 1_000L,
        )
        val editDraft = DraftEntity(
            key = DraftKey.edit("99001").value,
            subject = "修改后的标题",
            typeId = "1",
            content = "修改后的正文",
            quotePid = null,
            attachments = listOf("img_10"),
            updatedAt = 2_000L,
        )

        draftDao.upsert(newDraft)
        draftDao.upsert(editDraft)

        draftDao.observe(newDraft.key).test {
            assertEquals(newDraft, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        draftDao.delete(newDraft.key)

        draftDao.observe(editDraft.key).test {
            assertEquals(editDraft, awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun browseHistory_observeRecentOrderedDescendingAndUpsertMovesToTop() = runTest {
        val oldest = BrowseHistoryEntity(
            tid = "101",
            title = "最早看的帖子",
            fid = "2",
            forumName = "Discovery",
            author = "alice",
            lastVisitedAt = 100L,
        )
        val newest = BrowseHistoryEntity(
            tid = "103",
            title = "最新看的帖子",
            fid = "6",
            forumName = "Buy & Sell",
            author = "charlie",
            lastVisitedAt = 300L,
        )
        val middle = BrowseHistoryEntity(
            tid = "102",
            title = "中间看的帖子",
            fid = "7",
            forumName = "Geek Talks",
            author = "bob",
            lastVisitedAt = 200L,
        )

        browseHistoryDao.upsert(oldest)
        browseHistoryDao.upsert(newest)
        browseHistoryDao.upsert(middle)

        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(listOf(newest, middle, oldest), awaitItem())

            val revisitedOldest = oldest.copy(title = "重看后更新标题", lastVisitedAt = 400L)
            browseHistoryDao.upsert(revisitedOldest)
            assertEquals(listOf(revisitedOldest, newest, middle), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.observeRecent(limit = 2).test {
            assertEquals(listOf("101", "103"), awaitItem().map { it.tid })
            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.observeRecent(limit = 0).test {
            assertEquals(emptyList<BrowseHistoryEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.observeRecent(limit = -1).test {
            assertEquals(emptyList<BrowseHistoryEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun browseHistory_deleteAndRestoreReturnsEntityToOriginalPosition() = runTest {
        val first = BrowseHistoryEntity(
            tid = "201",
            title = "第一条",
            fid = "2",
            forumName = "Discovery",
            author = "u1",
            lastVisitedAt = 300L,
        )
        val second = BrowseHistoryEntity(
            tid = "202",
            title = "第二条",
            fid = "2",
            forumName = "Discovery",
            author = "u2",
            lastVisitedAt = 200L,
        )
        val third = BrowseHistoryEntity(
            tid = "203",
            title = "第三条",
            fid = "2",
            forumName = "Discovery",
            author = "u3",
            lastVisitedAt = 100L,
        )

        browseHistoryDao.upsert(first)
        browseHistoryDao.upsert(second)
        browseHistoryDao.upsert(third)

        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(listOf(first, second, third), awaitItem())

            browseHistoryDao.delete(second.tid)
            assertEquals(listOf(first, third), awaitItem())

            browseHistoryDao.restore(second)
            assertEquals(listOf(first, second, third), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun browseHistory_trimToRetainsOnlyMostRecentEntries() = runTest {
        val entries = (1..5).map { index ->
            BrowseHistoryEntity(
                tid = "30$index",
                title = "帖子 $index",
                fid = "2",
                forumName = "Discovery",
                author = "author$index",
                lastVisitedAt = index * 100L,
            )
        }
        entries.forEach { browseHistoryDao.upsert(it) }

        browseHistoryDao.trimTo(max = 3)
        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(listOf("305", "304", "303"), awaitItem().map { it.tid })
            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.trimTo(max = 10)
        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(listOf("305", "304", "303"), awaitItem().map { it.tid })
            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.trimTo(max = 0)
        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(emptyList<BrowseHistoryEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }

        browseHistoryDao.upsert(entries.first())
        browseHistoryDao.trimTo(max = -1)
        browseHistoryDao.observeRecent(limit = 10).test {
            assertEquals(emptyList<BrowseHistoryEntity>(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun readingPosition_getReturnsNullWhenAbsentAndUpsertOverwrites() = runTest {
        assertNull(readingPositionDao.get("4001"))

        val firstPosition = ReadingPositionEntity(
            tid = "4001",
            page = 1,
            pid = "80001",
            offsetPx = 148,
            authorFilterUid = "12345",
            updatedAt = 1_000L,
        )
        readingPositionDao.upsert(firstPosition)
        assertEquals(firstPosition, readingPositionDao.get("4001"))

        val updatedPosition = ReadingPositionEntity(
            tid = "4001",
            page = 4,
            pid = "80120",
            offsetPx = 0,
            authorFilterUid = null,
            updatedAt = 2_000L,
        )
        readingPositionDao.upsert(updatedPosition)
        assertEquals(updatedPosition, readingPositionDao.get("4001"))

        val otherThreadPosition = ReadingPositionEntity(
            tid = "4002",
            page = 2,
            pid = "90005",
            offsetPx = 32,
            authorFilterUid = null,
            updatedAt = 1_500L,
        )
        readingPositionDao.upsert(otherThreadPosition)
        assertEquals(updatedPosition, readingPositionDao.get("4001"))
        assertEquals(otherThreadPosition, readingPositionDao.get("4002"))
    }

    @Test
    fun databaseModule_providesDatabaseNamedHipdaNextAndAllDaos() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val providedDb = DatabaseModule.provideHiDatabase(context)
        try {
            assertEquals("hipda_next.db", HiDatabase.DATABASE_NAME)
            assertEquals("hipda_next.db", providedDb.openHelper.databaseName)
            assertNotNull(DatabaseModule.provideDraftDao(providedDb))
            assertNotNull(DatabaseModule.provideBrowseHistoryDao(providedDb))
            assertNotNull(DatabaseModule.provideReadingPositionDao(providedDb))
        } finally {
            providedDb.close()
        }
    }

    @Test
    fun converters_roundTripEmptySpecialCharactersAndBlankFallback() {
        val converters = HiDatabaseConverters()

        val emptyJson = converters.fromStringList(emptyList())
        assertEquals("[]", emptyJson)
        assertEquals(emptyList<String>(), converters.toStringList(emptyJson))
        assertEquals(emptyList<String>(), converters.toStringList(""))
        assertEquals(emptyList<String>(), converters.toStringList("   "))

        val complex = listOf("1001", "a,b", "quote\"here", "bracket[1]")
        val encoded = converters.fromStringList(complex)
        assertEquals(complex, converters.toStringList(encoded))
    }

    @Test
    fun exportedSchema_matchesVersion1IdentityHash() {
        val schemaFile = File("schemas/net.jejer.hipda.data.local.HiDatabase/1.json")
        assertTrue("Expected exported Room schema at ${schemaFile.absolutePath}", schemaFile.isFile)
        val root = Json.parseToJsonElement(schemaFile.readText()).jsonObject
        val dbJson = root.getValue("database").jsonObject
        assertEquals(1, dbJson.getValue("version").jsonPrimitive.int)
        val schemaHash = dbJson.getValue("identityHash").jsonPrimitive.content

        val runtimeHash = database.openHelper.readableDatabase.query(
            "SELECT identity_hash FROM room_master_table WHERE id = 42",
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            cursor.getString(0)
        }
        assertEquals(schemaHash, runtimeHash)
    }
}
