package net.jejer.hipda.data.account

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
object CredentialsModule {

    const val CREDENTIALS_LOGICAL_NAME: String = "credentials"

    internal fun credentialsDataStoreFile(
        context: Context,
        aeadProvider: AeadProvider,
    ): File = context.dataStoreFile(aeadProvider.encryptedFileName(CREDENTIALS_LOGICAL_NAME))

    internal fun createCredentialsDataStore(
        produceFile: () -> File,
        aeadProvider: AeadProvider,
        scope: CoroutineScope,
    ): DataStore<CredentialsSnapshot> = DataStoreFactory.create(
        serializer = aeadProvider.wrapSerializer(
            logicalName = CREDENTIALS_LOGICAL_NAME,
            wrappedSerializer = CredentialsSnapshotSerializer,
        ),
        corruptionHandler = ReplaceFileCorruptionHandler(
            produceNewData = { CredentialsSnapshot.EMPTY },
        ),
        scope = scope,
        produceFile = produceFile,
    )

    internal fun createCredentialsStore(
        context: Context,
        aeadProvider: AeadProvider,
        scope: CoroutineScope,
    ): CredentialsStore = DataStoreCredentialsStore(
        dataStore = createCredentialsDataStore(
            produceFile = { credentialsDataStoreFile(context, aeadProvider) },
            aeadProvider = aeadProvider,
            scope = scope,
        ),
    )

    @Provides
    @Singleton
    fun provideCredentialsStore(
        @ApplicationContext context: Context,
        aeadProvider: AeadProvider,
    ): CredentialsStore = createCredentialsStore(
        context = context,
        aeadProvider = aeadProvider,
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
    )
}
