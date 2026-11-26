package net.jejer.hipda.feature.app

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import net.jejer.hipda.data.local.ReadingFilterMode
import net.jejer.hipda.data.local.ReadingPositionEntity
import net.jejer.hipda.data.network.access.AccessPriority
import net.jejer.hipda.data.threaddetail.PollVoteResult
import net.jejer.hipda.data.threaddetail.ThreadDetailError
import net.jejer.hipda.data.threaddetail.ThreadDetailFilter
import net.jejer.hipda.data.threaddetail.ThreadDetailFloorItem
import net.jejer.hipda.data.threaddetail.ThreadDetailLoadResult
import net.jejer.hipda.data.threaddetail.ThreadDetailRepository
import net.jejer.hipda.feature.common.ThreadNavArgs
import net.jejer.hipda.parser.common.DiscuzPageMeta
import net.jejer.hipda.parser.common.DiscuzSessionInfo
import net.jejer.hipda.parser.common.PromptCounts
import net.jejer.hipda.parser.threaddetail.ContentBlock
import net.jejer.hipda.parser.threaddetail.InlineSpan
import net.jejer.hipda.parser.threaddetail.PostFloor
import net.jejer.hipda.parser.threaddetail.ThreadDetailPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** `#4` 端到端闭环验证：`NextAppHostViewModel.openThread` 对 `openLastPage = true` 复用末页解析路由与回退/取消语义。 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class OpenThreadLastPageRoutingTest {

    @Test
    fun openThread_withOpenLastPageTrue_resolvesLastPageLastPostPidAndClearsFlag() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val repo = RecordingThreadDetailRepo(
                onLoadLastPage = { tid ->
                    successLastPage(tid = tid, title = "服务器返回标题", fid = 6, forumName = "Buy & Sell", lastPid = "998877")
                },
            )
            val vm = NextAppHostViewModel(MultiPaneTestHarness().settingsRepo, repo)

            // 1. 常规单击（openLastPage = false）：不触发 loadLastPage，直接打开
            vm.openThread(ThreadNavArgs(tid = "1001", title = "普通单击帖", fid = 2, forumName = "Discovery", author = "alice"))
            advanceUntilIdle()
            assertEquals(emptyList<String>(), repo.lastPageTids)
            assertEquals("1001", vm.state.value.selectedThread?.tid)
            assertNull(vm.state.value.selectedThread?.targetPid)
            assertFalse(vm.state.value.selectedThread!!.openLastPage)

            // 2. 长按直达末页（openLastPage = true）：走 loadLastPage 解析末页最后一条 pid 并打开
            vm.openThread(
                ThreadNavArgs(
                    tid = "3468951",
                    title = "列表标题",
                    fid = 6,
                    forumName = "Buy & Sell",
                    author = "bob",
                    targetPid = "111",
                    openLastPage = true,
                ),
            )
            advanceUntilIdle()

            assertEquals(listOf("3468951"), repo.lastPageTids)
            val opened = requireNotNull(vm.state.value.selectedThread)
            assertEquals("3468951", opened.tid)
            assertEquals("服务器返回标题", opened.title)
            assertEquals(6, opened.fid)
            assertEquals("Buy & Sell", opened.forumName)
            assertEquals("bob", opened.author)
            assertEquals("998877", opened.targetPid)
            assertFalse("解析落盘后 openLastPage 必须复位为 false 避免重复解析", opened.openLastPage)
        } finally {
            Dispatchers.resetMain()
        }
    }

    @Test
    fun openThread_openLastPageFailureFallsBackToMetadata_andNewerOpenCancelsStaleResolve() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        try {
            val gate = CompletableDeferred<ThreadDetailLoadResult>()
            var failFirst = true
            val repo = RecordingThreadDetailRepo(
                onLoadLastPage = { tid ->
                    if (failFirst) {
                        failFirst = false
                        ThreadDetailError.NetworkError("timeout")
                    } else {
                        gate.await()
                    }
                },
            )
            val vm = NextAppHostViewModel(MultiPaneTestHarness().settingsRepo, repo)

            // 1. loadLastPage 失败时回退至原帖子元数据并清除旧 targetPid，确保页面仍可打开展示重试态
            vm.openThread(ThreadNavArgs(tid = "5001", title = "回退保留标题", fid = 2, forumName = "Discovery", author = "carol", targetPid = "old_pid", openLastPage = true))
            advanceUntilIdle()
            val fallback = requireNotNull(vm.state.value.selectedThread)
            assertEquals("5001", fallback.tid)
            assertEquals("回退保留标题", fallback.title)
            assertEquals(2, fallback.fid)
            assertNull(fallback.targetPid)
            assertFalse(fallback.openLastPage)

            // 2. 在途末页解析被新打开的帖子打断后，陈旧结果不得覆盖新选中帖子
            vm.openThread(ThreadNavArgs(tid = "6001", title = "慢网末页帖", fid = 2, openLastPage = true))
            advanceUntilIdle()
            vm.openThread(ThreadNavArgs(tid = "6002", title = "后点击的新帖", fid = 7, openLastPage = false))
            gate.complete(successLastPage("6001", "陈旧末页帖", 2, "Discovery", "888888"))
            advanceUntilIdle()

            assertEquals("6002", vm.state.value.selectedThread?.tid)
            assertEquals("后点击的新帖", vm.state.value.selectedThread?.title)
        } finally {
            Dispatchers.resetMain()
        }
    }

    private fun successLastPage(
        tid: String,
        title: String,
        fid: Int,
        forumName: String,
        lastPid: String,
    ): ThreadDetailLoadResult.Success {
        val meta = DiscuzPageMeta(DiscuzSessionInfo(true, "42", "HiUser"), "fh", null, PromptCounts())
        val floor = PostFloor(
            postId = lastPid,
            floor = 42,
            page = 5,
            uid = "88",
            author = "replier",
            avatarUrl = null,
            timePost = "刚刚",
            isWarned = false,
            postStatus = null,
            signature = null,
            poll = null,
            blocks = listOf(ContentBlock.Text(listOf(InlineSpan.Plain("最新回复")))),
        )
        return ThreadDetailLoadResult.Success(
            page = ThreadDetailPage(
                tid = tid,
                fid = fid,
                title = title,
                page = 5,
                lastPage = 5,
                floors = listOf(floor),
                postsPerPageEstimate = 50,
                meta = meta,
                hasPostList = true,
            ),
            forumName = forumName,
            targetPostId = lastPid,
        )
    }

    private class RecordingThreadDetailRepo(
        private val onLoadLastPage: suspend (String) -> ThreadDetailLoadResult,
    ) : ThreadDetailRepository {
        val lastPageTids = mutableListOf<String>()

        override suspend fun loadPage(tid: String, page: Int, filter: ThreadDetailFilter, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            ThreadDetailError.InvalidPage("unused")

        override suspend fun loadLastPage(tid: String, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult {
            lastPageTids += tid
            return onLoadLastPage(tid)
        }

        override suspend fun loadByPostId(pid: String, tid: String?, recordHistory: Boolean, forumName: String?, priority: AccessPriority): ThreadDetailLoadResult =
            ThreadDetailError.InvalidPage("unused")

        override suspend fun getReadingPosition(tid: String): ReadingPositionEntity? = null
        override suspend fun saveReadingPosition(tid: String, page: Int, pid: String, offsetPx: Int, filterMode: ReadingFilterMode, filterUid: String?, filterUserName: String?) = Unit
        override fun observeFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = flowOf(emptyList())
        override fun observeVisibleFloors(floorsFlow: Flow<List<PostFloor>>): Flow<List<ThreadDetailFloorItem>> = flowOf(emptyList())
        override fun mapFloorsWithBlacklist(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<ThreadDetailFloorItem> = emptyList()
        override fun filterBlockedFloors(floors: List<PostFloor>, blockedUsernames: Set<String>?): List<PostFloor> = emptyList()
        override suspend fun submitPollVote(fid: Int, tid: String, formhash: String, optionIds: List<String>, maxChoices: Int?, priority: AccessPriority): PollVoteResult =
            ThreadDetailError.InvalidInput("unused")
    }
}
