package net.jejer.hipda.data.account

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

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

    /**
     * Inserts a new account or replaces the existing entry with the same [SavedAccount.uid].
     * Validates non-blank `uid`/`username` and normalizes [SavedAccount.passwordMd5] (must match
     * `^[0-9a-fA-F]{32}$`) to lowercase; throws [IllegalArgumentException] otherwise.
     */
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
            val normalizedMd5 = SavedAccount.normalizePasswordMd5OrNull(account.passwordMd5)
                ?: continue
            val normalizedAccount = if (normalizedMd5 == account.passwordMd5) {
                account
            } else {
                account.copy(passwordMd5 = normalizedMd5)
            }
            val existing = deduplicated[normalizedAccount.uid]
            if (existing == null || normalizedAccount.lastLoginAt >= existing.lastLoginAt) {
                deduplicated[normalizedAccount.uid] = normalizedAccount
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
        val rawBytes = input.readBytes()
        if (rawBytes.isEmpty()) {
            throw CorruptionException("Encrypted credentials payload is empty.")
        }
        val payload = rawBytes.toString(Charsets.UTF_8)
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

class DataStoreCredentialsStore internal constructor(
    private val dataStore: DataStore<CredentialsSnapshot>,
) : CredentialsStore {

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
        val normalizedPasswordMd5 = requireNotNull(
            SavedAccount.normalizePasswordMd5OrNull(account.passwordMd5),
        ) {
            "Account passwordMd5 must be a 32-character hexadecimal MD5 digest"
        }
        val normalizedAccount = if (normalizedPasswordMd5 == account.passwordMd5) {
            account
        } else {
            account.copy(passwordMd5 = normalizedPasswordMd5)
        }
        dataStore.updateData { current ->
            val updated = current.accounts
                .filterNot { it.uid == normalizedAccount.uid } + normalizedAccount
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
}
