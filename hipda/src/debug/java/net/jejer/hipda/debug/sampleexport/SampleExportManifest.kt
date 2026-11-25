package net.jejer.hipda.debug.sampleexport

import androidx.annotation.StringRes
import androidx.compose.runtime.Immutable
import net.jejer.hipda.R
import net.jejer.hipda.data.network.cookie.SiteCookieStore
import net.jejer.hipda.parser.sanitize.PageType
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

object SampleExportConstants {
    const val FORUM_BASE_URL_STRING: String = "https://www.4d4y.com/forum/"
    const val LOGIN_URL_STRING: String = "https://www.4d4y.com/forum/logging.php?action=login"
    const val EXPORT_DIR_NAME: String = "sample_exports"
    const val PROVIDER_AUTHORITY_SUFFIX: String = ".debug.sampleexport.provider"
    const val ZIP_FILE_PREFIX: String = "hipda-samples-"
    const val ZIP_FILE_SUFFIX: String = ".zip"
    const val MANIFEST_FILE_NAME: String = "manifest.json"
    const val AUTH_COOKIE_NAME: String = "cdb_auth"

    val FORUM_BASE_URL: HttpUrl = FORUM_BASE_URL_STRING.toHttpUrl()
    val LOGIN_URL: HttpUrl = LOGIN_URL_STRING.toHttpUrl()
}

enum class SampleEntryId(@param:StringRes val titleRes: Int) {
    THREAD_LIST_P1_LASTPOST(R.string.sample_export_item_thread_list_p1),
    THREAD_LIST_P2_LASTPOST(R.string.sample_export_item_thread_list_p2),
    THREAD_LIST_P1_DATELINE(R.string.sample_export_item_thread_list_dateline),
    THREAD_DETAIL_P1(R.string.sample_export_item_thread_detail_p1),
    THREAD_DETAIL_P2(R.string.sample_export_item_thread_detail_p2),
    THREAD_DETAIL_AUTHOR_ONLY(R.string.sample_export_item_thread_detail_author),
    SMS_LIST(R.string.sample_export_item_sms_list),
    SMS_DETAIL(R.string.sample_export_item_sms_detail),
    NOTIFY_LIST(R.string.sample_export_item_notify_list),
    SEARCH_RESULT(R.string.sample_export_item_search_result),
    USER_PROFILE(R.string.sample_export_item_user_profile),
    MY_THREADS(R.string.sample_export_item_my_threads),
    MY_POSTS(R.string.sample_export_item_my_posts),
    MY_FAVORITES(R.string.sample_export_item_my_favorites),
    POST_FORM_NEWTHREAD(R.string.sample_export_item_post_form),
    LOGIN_PAGE(R.string.sample_export_item_login_page),
}

@Immutable
data class SampleSpec(
    val id: SampleEntryId,
    val relativePath: String,
    val pageType: PageType,
    val isPersonalHistory: Boolean = false,
)

/**
 * 写死的只读 GET 样本清单，目录结构与 `hipda/src/test/resources/samples/` 及 `SamplesAreSanitizedTest` 一致。
 */
object SampleExportManifest {
    val entries: List<SampleSpec> = listOf(
        SampleSpec(SampleEntryId.THREAD_LIST_P1_LASTPOST, "thread_list/forum_2_p1_lastpost.html", PageType.THREAD_LIST),
        SampleSpec(SampleEntryId.THREAD_LIST_P2_LASTPOST, "thread_list/forum_2_p2_lastpost.html", PageType.THREAD_LIST),
        SampleSpec(SampleEntryId.THREAD_LIST_P1_DATELINE, "thread_list/forum_2_p1_dateline.html", PageType.THREAD_LIST),
        SampleSpec(SampleEntryId.THREAD_DETAIL_P1, "thread_detail/thread_p1.html", PageType.THREAD_DETAIL),
        SampleSpec(SampleEntryId.THREAD_DETAIL_P2, "thread_detail/thread_p2.html", PageType.THREAD_DETAIL),
        SampleSpec(SampleEntryId.THREAD_DETAIL_AUTHOR_ONLY, "thread_detail/thread_author_only.html", PageType.THREAD_DETAIL),
        SampleSpec(SampleEntryId.SMS_LIST, "sms/sms_list.html", PageType.SMS_LIST),
        SampleSpec(SampleEntryId.SMS_DETAIL, "sms/sms_detail.html", PageType.SMS_DETAIL),
        SampleSpec(SampleEntryId.NOTIFY_LIST, "notify/notify_list.html", PageType.NOTIFY),
        SampleSpec(SampleEntryId.SEARCH_RESULT, "search/search_result.html", PageType.SEARCH),
        SampleSpec(SampleEntryId.USER_PROFILE, "user_info/user_profile.html", PageType.USER_INFO),
        SampleSpec(SampleEntryId.MY_THREADS, "simple_list/my_threads.html", PageType.SIMPLE_LIST, isPersonalHistory = true),
        SampleSpec(SampleEntryId.MY_POSTS, "simple_list/my_posts.html", PageType.SIMPLE_LIST, isPersonalHistory = true),
        SampleSpec(SampleEntryId.MY_FAVORITES, "simple_list/my_favorites.html", PageType.SIMPLE_LIST, isPersonalHistory = true),
        SampleSpec(SampleEntryId.POST_FORM_NEWTHREAD, "post_form/newthread_fid2.html", PageType.POST_FORM),
        SampleSpec(SampleEntryId.LOGIN_PAGE, "login/login_page.html", PageType.LOGIN),
    )

    fun initialItems(): List<SampleExportItemState> = entries.map { SampleExportItemState(spec = it) }
}

@Immutable
sealed interface SampleExportStatus {
    data object Pending : SampleExportStatus
    data object Fetching : SampleExportStatus
    data class Success(val relativePath: String, val byteSize: Int) : SampleExportStatus
    data class Skipped(val reason: String) : SampleExportStatus
    data class Failed(val reason: String, val detail: String? = null) : SampleExportStatus
}

@Immutable
data class SampleExportItemState(
    val spec: SampleSpec,
    val status: SampleExportStatus = SampleExportStatus.Pending,
)

/**
 * 唯一登录态判断依据：检查 [SiteCookieStore] 中是否存在匹配论坛地址且非空的 `cdb_auth` Cookie。
 */
fun isForumLoggedIn(
    siteCookieStore: SiteCookieStore,
    baseUrl: HttpUrl = SampleExportConstants.FORUM_BASE_URL,
): Boolean = siteCookieStore.loadForRequest(baseUrl).any {
    it.name == SampleExportConstants.AUTH_COOKIE_NAME && it.value.isNotBlank()
}
