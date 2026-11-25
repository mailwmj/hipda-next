package net.jejer.hipda.data.network.cookie

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import net.jejer.hipda.data.security.AeadProvider

@Module
@InstallIn(SingletonComponent::class)
object CookieStoreModule {

    const val COOKIE_STORE_LOGICAL_NAME: String = "site_cookies"

    internal fun cookieStoreFile(context: Context, aeadProvider: AeadProvider): File =
        context.dataStoreFile(aeadProvider.encryptedFileName(COOKIE_STORE_LOGICAL_NAME))

    internal fun createCookieDataStore(
        produceFile: () -> File,
        aeadProvider: AeadProvider,
        scope: CoroutineScope,
    ): DataStore<StoredCookieStore> = DataStoreFactory.create(
        serializer = DecryptCorruptionSerializer(
            aeadProvider.wrapSerializer(COOKIE_STORE_LOGICAL_NAME, CookieStorePayloadSerializer),
        ),
        corruptionHandler = ReplaceFileCorruptionHandler { StoredCookieStore() },
        scope = scope,
        produceFile = produceFile,
    )

    @Provides
    @Singleton
    fun provideSiteCookieStore(
        @ApplicationContext context: Context,
        aeadProvider: AeadProvider,
    ): SiteCookieStore {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = createCookieDataStore(
            produceFile = { cookieStoreFile(context, aeadProvider) },
            aeadProvider = aeadProvider,
            scope = scope,
        )
        return DataStoreSiteCookieStore(dataStore = dataStore, persistScope = scope)
    }
}
