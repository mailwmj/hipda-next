package net.jejer.hipda.data.account

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.DataStoreFactory
import androidx.datastore.core.Serializer
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.dataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import net.jejer.hipda.data.security.AeadProvider

/**
 * Tink AEAD-encrypted multi-account credentials store supporting active account selection and
 * manual logout suppression of silent re-login (product-design §7.11).
 */
interface CredentialsStore {

    /**
     * Saved accounts ordered by [SavedAccount.lastLoginAt] descending, breaking ties by
     * [SavedAccount.uid] ascending.
     */
    val accounts: Flow<List<SavedAccount>>

    /** Currently active account UID, or `null` when logged out. */
    val activeUid: Flow<String?>

    /**
     * Whether the user explicitly signed out. When `true`, silent re-login must not run until
     * [setActive] is called after an explicit login or account switch.
     */
    val manuallyLoggedOut: Flow<Boolean>

    /** Inserts a new account or replaces the existing entry with the same [SavedAccount.uid]. */
    suspend fun upsert(account: SavedAccount)

    /**
     * Marks the saved account identified by [uid] as active and clears [manuallyLoggedOut].
     * Throws [IllegalArgumentException] if [uid] is blank or not present in [accounts].
     */
    suspend fun setActive(uid: String)

    /** Keeps all saved accounts, clears [activeUid], and sets [manuallyLoggedOut] to `true`. */
    suspend fun markManualLogout()

    /**
     * Removes the saved account identified by [uid]. If [uid] is currently active, [activeUid] is
     * cleared to `null`; removing an inactive or unknown UID leaves [activeUid] unchanged.
     */
    suspend fun remove(uid: String)
}

@Serializable
internal data class CredentialsSnapshot(
    val accounts: List<SavedAccount> = emptyList(),
    val activeUid: String? = null,
    val manuallyLoggedOut: Boolean = false,
) {
    fun normalized(): CredentialsSnapshot {
        val deduplicated = linkedMapOf<String, SavedAccount>()
        for (account in accounts) {
            if (account.uid.isBlank() || account.username.isBlank()) continue
            val existing = deduplicated[account.uid]
            if (existing == null || account.lastLoginAt >= existing.lastLoginAt) {
                deduplicated[account.uid] = account
            }
        }
        val sortedAccounts = deduplicated.values.sortedWith(ACCOUNT_COMPARATOR)
        val validActiveUid = activeUid
            ?.takeIf { !manuallyLoggedOut && sortedAccounts.any { account -> account.uid == it } }
        return CredentialsSnapshot(
            accounts = sortedAccounts,
            activeUid = validActiveUid,
            manuallyLoggedOut = manuallyLoggedOut,
        )
    }

    companion object {
        val EMPTY: CredentialsSnapshot = CredentialsSnapshot()

        private val ACCOUNT_COMPARATOR: Comparator<SavedAccount> =
            compareByDescending<SavedAccount> { it.lastLoginAt }
                .thenBy { it.uid }
    }
}

internal object CredentialsSnapshotSerializer : Serializer<CredentialsSnapshot> {
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val defaultValue: CredentialsSnapshot = CredentialsSnapshot.EMPTY

    override suspend fun readFrom(input: InputStream): CredentialsSnapshot {
        val payload = input.readBytes().toString(Charsets.UTF_8)
        return try {
            json.decodeFromString(CredentialsSnapshot.serializer(), payload).normalized()
        } catch (e: SerializationException) {
            throw CorruptionException("Failed to deserialize encrypted credentials.", e)
        } catch (e: IllegalArgumentException) {
            throw CorruptionException("Invalid encrypted credentials payload.", e)
        }
    }

    override suspend fun writeTo(t: CredentialsSnapshot, output: OutputStream) {
        val payload = json.encodeToString(CredentialsSnapshot.serializer(), t)
        output.write(payload.toByteArray(Charsets.UTF_8))
    }
}

@Singleton
class DataStoreCredentialsStore @VisibleForTesting internal constructor(
    private val dataStore: DataStore<CredentialsSnapshot>,
) : CredentialsStore {

    @Inject
    constructor(
        @ApplicationContext context: Context,
        aeadProvider: AeadProvider,
    ) : this(
        dataStore = createDataStore(
            produceFile = { credentialsDataStoreFile(context, aeadProvider) },
            aeadProvider = aeadProvider,
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
        ),
    )

    override val accounts: Flow<List<SavedAccount>> = dataStore.data
        .map { it.accounts }
        .distinctUntilChanged()

    override val activeUid: Flow<String?> = dataStore.data
        .map { it.activeUid }
        .distinctUntilChanged()

    override val manuallyLoggedOut: Flow<Boolean> = dataStore.data
        .map { it.manuallyLoggedOut }
        .distinctUntilChanged()

    override suspend fun upsert(account: SavedAccount) {
        require(account.uid.isNotBlank()) { "Account uid must not be blank" }
        require(account.username.isNotBlank()) { "Account username must not be blank" }
        dataStore.updateData { current ->
            val updated = current.accounts.filterNot { it.uid == account.uid } + account
            current.copy(accounts = updated).normalized()
        }
    }

    override suspend fun setActive(uid: String) {
        require(uid.isNotBlank()) { "Account uid must not be blank" }
        dataStore.updateData { current ->
            require(current.accounts.any { it.uid == uid }) {
                "Cannot activate unknown account uid: $uid"
            }
            current.copy(
                activeUid = uid,
                manuallyLoggedOut = false,
            ).normalized()
        }
    }

    override suspend fun markManualLogout() {
        dataStore.updateData { current ->
            current.copy(
                activeUid = null,
                manuallyLoggedOut = true,
            ).normalized()
        }
    }

    override suspend fun remove(uid: String) {
        if (uid.isBlank()) return
        dataStore.updateData { current ->
            val remaining = current.accounts.filterNot { it.uid == uid }
            if (remaining.size == current.accounts.size && current.activeUid != uid) {
                return@updateData current
            }
            current.copy(
                accounts = remaining,
                activeUid = if (current.activeUid == uid) null else current.activeUid,
            ).normalized()
        }
    }

    companion object {
        const val CREDENTIALS_LOGICAL_NAME: String = "credentials"

        internal fun credentialsDataStoreFile(
            context: Context,
            aeadProvider: AeadProvider,
        ): File = context.dataStoreFile(aeadProvider.encryptedFileName(CREDENTIALS_LOGICAL_NAME))

        internal fun createDataStore(
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
    }
}
