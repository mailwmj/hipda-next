package net.jejer.hipda.feature.app

import android.content.Context
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 按 `versionCode` 持久化记录最近已读更新记录版本，驱动版本变化后首次冷启动弹一次更新记录。 */
interface ReleaseNotesPromptStore {
    /** 返回最近已确认阅读的 `versionCode`；首次安装未记录时返回 `0`。 */
    suspend fun getLastSeenVersionCode(): Int

    /** 持久化写入已确认阅读的 `versionCode`，保证同版本后续冷启动不再重复弹出。 */
    suspend fun setLastSeenVersionCode(versionCode: Int)
}

@Singleton
class SharedPrefsReleaseNotesPromptStore internal constructor(
    private val context: Context,
    private val ioDispatcher: CoroutineDispatcher,
) : ReleaseNotesPromptStore {

    @Inject
    constructor(
        @ApplicationContext context: Context,
    ) : this(context = context, ioDispatcher = Dispatchers.IO)

    override suspend fun getLastSeenVersionCode(): Int = withContext(ioDispatcher) {
        prefs().getInt(KEY_LAST_SEEN_VERSION_CODE, 0)
    }

    override suspend fun setLastSeenVersionCode(versionCode: Int) {
        withContext(ioDispatcher) {
            prefs().edit().putInt(KEY_LAST_SEEN_VERSION_CODE, versionCode).commit()
        }
    }

    private fun prefs() = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    companion object {
        internal const val PREFS_NAME: String = "hipda_next_release_notes"
        internal const val KEY_LAST_SEEN_VERSION_CODE: String = "last_seen_version_code"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class ReleaseNotesPromptBindingModule {
    @Binds
    @Singleton
    abstract fun bindReleaseNotesPromptStore(
        impl: SharedPrefsReleaseNotesPromptStore,
    ): ReleaseNotesPromptStore
}
