package net.jejer.hipda.data.account

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import app.cash.turbine.test
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.PredefinedAeadParameters
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import net.jejer.hipda.data.security.AeadProvider
import net.jejer.hipda.data.security.KeysetBackend
import net.jejer.hipda.data.security.TinkAeadProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class CredentialsStoreTest {

    @get:Rule
    val tempFolder: TemporaryFolder = TemporaryFolder()

    private lateinit var aeadProvider: AeadProvider

    @Before
    fun setUp() {
        TinkAeadProvider.ensureTinkRegistered()
        aeadProvider = TinkAeadProvider(InMemoryKeysetBackend())
    }

    private fun newCredentialsFile(provider: AeadProvider = aeadProvider): File =
        File(tempFolder.newFolder(), provider.encryptedFileName(CredentialsModule.CREDENTIALS_LOGICAL_NAME))

    private fun createStore(
        file: File,
        scope: CoroutineScope,
        provider: AeadProvider = aeadProvider,
    ): CredentialsStore = DataStoreCredentialsStore(
        CredentialsModule.createCredentialsDataStore(
            produceFile = { file },
            aeadProvider = provider,
            scope = scope,
        ),
    )

    @Test
    fun freshInstall_emitsEmptyLoggedOutState() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)

        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())
    }

    @Test
    fun upsert_supportsMultipleAccountsDeterministicOrderingAndInPlaceUpdate() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)
        val alice = sampleAccount(uid = "1002", username = "alice", lastLoginAt = 1_000L)
        val bob = sampleAccount(uid = "1001", username = "bob", lastLoginAt = 3_000L)
        val charlie = sampleAccount(uid = "1003", username = "charlie", lastLoginAt = 3_000L)

        store.accounts.test {
            assertEquals(emptyList<SavedAccount>(), awaitItem())

            store.upsert(alice)
            assertEquals(listOf(alice), awaitItem())

            store.upsert(bob)
            assertEquals(listOf(bob, alice), awaitItem())

            // Same lastLoginAt ties break by uid ascending: 1001 before 1003.
            store.upsert(charlie)
            assertEquals(listOf(bob, charlie, alice), awaitItem())

            // Updating an existing uid replaces in place without duplicates and re-sorts by lastLoginAt.
            val updatedAlice = alice.copy(
                username = "alice_new",
                passwordMd5 = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                securityQuestionId = 3,
                securityAnswer = "beijing",
                lastLoginAt = 5_000L,
            )
            store.upsert(updatedAlice)
            assertEquals(listOf(updatedAlice, bob, charlie), awaitItem())

            cancelAndIgnoreRemainingEvents()
        }

        // upsert alone does not change activeUid or manuallyLoggedOut.
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())
    }

    @Test
    fun upsert_rejectsBlankUidOrUsername() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)

        assertThrowsIllegalArgument {
            store.upsert(sampleAccount(uid = "   ", username = "alice", lastLoginAt = 1L))
        }
        assertThrowsIllegalArgument {
            store.upsert(sampleAccount(uid = "1001", username = "   ", lastLoginAt = 1L))
        }
        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
    }

    @Test
    fun upsert_normalizesUppercasePasswordMd5AndRejectsPlaintextOrInvalidDigest() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)
        val uppercaseDigest = "0123456789ABCDEF0123456789ABCDEF"
        val account = sampleAccount(
            uid = "1001",
            username = "alice",
            passwordMd5 = uppercaseDigest,
            lastLoginAt = 1_000L,
        )

        store.upsert(account)
        assertEquals(
            listOf(account.copy(passwordMd5 = "0123456789abcdef0123456789abcdef")),
            store.accounts.first(),
        )

        val invalidDigests = listOf(
            "",
            "   ",
            "myPlaintextPassword!",
            "0123456789abcdef0123456789abcde", // 31 chars
            "0123456789abcdef0123456789abcdef0", // 33 chars
            "0123456789abcdef0123456789abcdeg", // non-hex char 'g'
        )
        for (badDigest in invalidDigests) {
            val error = assertThrowsIllegalArgument {
                store.upsert(
                    sampleAccount(
                        uid = "1002",
                        username = "bob",
                        passwordMd5 = badDigest,
                        lastLoginAt = 2_000L,
                    ),
                )
            }
            if (badDigest.isNotBlank()) {
                assertFalse(
                    "Validation error must not echo secret input",
                    error.message.orEmpty().contains(badDigest),
                )
            }
        }
        // Existing valid account remains intact after rejected writes.
        assertEquals(listOf("1001"), store.accounts.first().map { it.uid })
    }

    @Test
    fun deserialization_normalizesUppercasePasswordMd5AndFiltersInvalidEntriesWithoutCrashing() = runTest {
        val file = newCredentialsFile()
        val encryptedRawSerializer = aeadProvider.wrapSerializer(
            logicalName = CredentialsModule.CREDENTIALS_LOGICAL_NAME,
            wrappedSerializer = Utf8StringSerializer,
        )
        val payload = """
            {
              "accounts": [
                {
                  "uid": "1001",
                  "username": "upper_user",
                  "passwordMd5": "ABCDEF0123456789ABCDEF0123456789",
                  "securityQuestionId": 0,
                  "securityAnswer": "",
                  "lastLoginAt": 1000
                },
                {
                  "uid": "1002",
                  "username": "plaintext_user",
                  "passwordMd5": "not-an-md5-plaintext",
                  "securityQuestionId": 0,
                  "securityAnswer": "",
                  "lastLoginAt": 2000
                }
              ],
              "activeUid": "1002",
              "manuallyLoggedOut": false
            }
        """.trimIndent()
        val output = ByteArrayOutputStream()
        encryptedRawSerializer.writeTo(payload, output)
        file.writeBytes(output.toByteArray())

        val store = createStore(file, backgroundScope)
        val loaded = store.accounts.first()
        assertEquals(1, loaded.size)
        assertEquals("1001", loaded.single().uid)
        assertEquals("abcdef0123456789abcdef0123456789", loaded.single().passwordMd5)
        // Active UID pointed at filtered invalid account -> safely cleared to null.
        assertNull(store.activeUid.first())
    }

    @Test
    fun setActive_setsActiveUidClearsManuallyLoggedOutAndRejectsUnknownUid() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)
        val alice = sampleAccount(uid = "1001", username = "alice", lastLoginAt = 1_000L)
        val bob = sampleAccount(uid = "1002", username = "bob", lastLoginAt = 2_000L)
        store.upsert(alice)
        store.upsert(bob)
        store.markManualLogout()

        assertTrue(store.manuallyLoggedOut.first())
        assertNull(store.activeUid.first())

        store.setActive(alice.uid)
        assertEquals(alice.uid, store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        // Switching directly to another saved account updates activeUid and keeps manuallyLoggedOut false.
        store.setActive(bob.uid)
        assertEquals(bob.uid, store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        // Unknown uid fails fast without mutating existing state.
        assertThrowsIllegalArgument {
            store.setActive("9999")
        }
        assertThrowsIllegalArgument {
            store.setActive("   ")
        }
        assertEquals(bob.uid, store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())
    }

    @Test
    fun markManualLogout_preservesAccountsClearsActiveUidAndSetsFlag() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)
        val alice = sampleAccount(uid = "1001", username = "alice", lastLoginAt = 1_000L)
        val bob = sampleAccount(uid = "1002", username = "bob", lastLoginAt = 2_000L)
        store.upsert(alice)
        store.upsert(bob)
        store.setActive(bob.uid)

        store.markManualLogout()

        assertEquals(listOf(bob, alice), store.accounts.first())
        assertNull(store.activeUid.first())
        assertTrue(store.manuallyLoggedOut.first())
    }

    @Test
    fun remove_deletesAccountAndClearsActiveUidOnlyWhenRemovingActiveAccount() = runTest {
        val store = createStore(newCredentialsFile(), backgroundScope)
        val alice = sampleAccount(uid = "1001", username = "alice", lastLoginAt = 1_000L)
        val bob = sampleAccount(uid = "1002", username = "bob", lastLoginAt = 2_000L)
        store.upsert(alice)
        store.upsert(bob)
        store.setActive(bob.uid)

        // Removing an inactive account keeps activeUid intact.
        store.remove(alice.uid)
        assertEquals(listOf(bob), store.accounts.first())
        assertEquals(bob.uid, store.activeUid.first())

        // Removing a non-existent uid is a no-op.
        store.remove("9999")
        assertEquals(listOf(bob), store.accounts.first())
        assertEquals(bob.uid, store.activeUid.first())

        // Removing the active account removes it and resets activeUid to null.
        store.remove(bob.uid)
        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
    }

    @Test
    fun recreatingStore_preservesAccountsActiveUidAndManualLogoutState() = runTest {
        val file = newCredentialsFile()
        val alice = sampleAccount(
            uid = "1001",
            username = "alice",
            securityQuestionId = 2,
            securityAnswer = "grandpa",
            lastLoginAt = 1_500L,
        )
        val bob = sampleAccount(uid = "1002", username = "bob", lastLoginAt = 2_500L)

        val scope1 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store1 = createStore(file, scope1)
            store1.upsert(alice)
            store1.upsert(bob)
            store1.setActive(alice.uid)
            assertEquals(alice.uid, store1.activeUid.first())
        } finally {
            scope1.cancel()
            advanceUntilIdle()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store2 = createStore(file, scope2)
            assertEquals(listOf(bob, alice), store2.accounts.first())
            assertEquals(alice.uid, store2.activeUid.first())
            assertFalse(store2.manuallyLoggedOut.first())

            store2.markManualLogout()
            assertTrue(store2.manuallyLoggedOut.first())
        } finally {
            scope2.cancel()
            advanceUntilIdle()
            scope2.coroutineContext[Job]?.join()
        }

        val scope3 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val store3 = createStore(file, scope3)
            assertEquals(listOf(bob, alice), store3.accounts.first())
            assertNull(store3.activeUid.first())
            assertTrue(store3.manuallyLoggedOut.first())
        } finally {
            scope3.cancel()
            advanceUntilIdle()
            scope3.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun zeroByteFileOnDisk_recoversToEmptyStateAndAllowsSubsequentWrites() = runTest {
        // Explicit 0-byte guard inside CredentialsSnapshotSerializer itself.
        try {
            CredentialsSnapshotSerializer.readFrom(ByteArrayInputStream(ByteArray(0)))
            throw AssertionError("Expected CorruptionException for 0-byte payload")
        } catch (_: CorruptionException) {
            // Expected
        }

        // End-to-end 0-byte hx_credentials file on disk recovers cleanly and accepts future writes.
        val file = newCredentialsFile()
        file.writeBytes(ByteArray(0))
        assertEquals(0L, file.length())

        val store = createStore(file, backgroundScope)
        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        val recovered = sampleAccount(uid = "2048", username = "from_empty", lastLoginAt = 8_000L)
        store.upsert(recovered)
        store.setActive(recovered.uid)

        assertEquals(listOf(recovered), store.accounts.first())
        assertEquals(recovered.uid, store.activeUid.first())
        assertTrue(file.length() > 0L)
    }

    @Test
    fun corruptedFileOnDisk_recoversToEmptyStateAndAllowsSubsequentWrites() = runTest {
        val file = newCredentialsFile()
        file.writeBytes(byteArrayOf(0x00, 0xFF.toByte(), 0x42, 0x13, 0x7F, 0x10))

        val store = createStore(file, backgroundScope)

        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        val recoveredAccount = sampleAccount(uid = "2001", username = "recovered", lastLoginAt = 9_000L)
        store.upsert(recoveredAccount)
        store.setActive(recoveredAccount.uid)

        assertEquals(listOf(recoveredAccount), store.accounts.first())
        assertEquals(recoveredAccount.uid, store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())
    }

    @Test
    fun ciphertextEncryptedByOldKeyset_recoversToEmptyStateAndAllowsSubsequentWrites() = runTest {
        val file = newCredentialsFile()
        val oldProvider = TinkAeadProvider(InMemoryKeysetBackend())
        val newProvider = TinkAeadProvider(InMemoryKeysetBackend())

        val scope1 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val oldStore = createStore(file, scope1, provider = oldProvider)
            oldStore.upsert(sampleAccount(uid = "1001", username = "stale", lastLoginAt = 1_000L))
            oldStore.setActive("1001")
        } finally {
            scope1.cancel()
            advanceUntilIdle()
            scope1.coroutineContext[Job]?.join()
        }

        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        try {
            val newStore = createStore(file, scope2, provider = newProvider)
            assertEquals(emptyList<SavedAccount>(), newStore.accounts.first())
            assertNull(newStore.activeUid.first())
            assertFalse(newStore.manuallyLoggedOut.first())

            val fresh = sampleAccount(uid = "3001", username = "fresh", lastLoginAt = 4_000L)
            newStore.upsert(fresh)
            newStore.setActive(fresh.uid)
            assertEquals(listOf(fresh), newStore.accounts.first())
            assertEquals(fresh.uid, newStore.activeUid.first())
        } finally {
            scope2.cancel()
            advanceUntilIdle()
            scope2.coroutineContext[Job]?.join()
        }
    }

    @Test
    fun corruptedJsonInsideValidCiphertext_recoversToEmptyStateAndAllowsSubsequentWrites() = runTest {
        val file = newCredentialsFile()
        val encryptedRawSerializer = aeadProvider.wrapSerializer(
            logicalName = CredentialsModule.CREDENTIALS_LOGICAL_NAME,
            wrappedSerializer = Utf8StringSerializer,
        )
        val output = ByteArrayOutputStream()
        encryptedRawSerializer.writeTo("{not-valid-json", output)
        file.writeBytes(output.toByteArray())

        val store = createStore(file, backgroundScope)
        assertEquals(emptyList<SavedAccount>(), store.accounts.first())
        assertNull(store.activeUid.first())
        assertFalse(store.manuallyLoggedOut.first())

        val alice = sampleAccount(uid = "1001", username = "alice", lastLoginAt = 1_000L)
        store.upsert(alice)
        assertEquals(listOf(alice), store.accounts.first())
    }

    @Test
    fun persistedDiskFile_isEncryptedAndContainsNoPlaintextSecrets() = runTest {
        val file = newCredentialsFile()
        val store = createStore(file, backgroundScope)
        val secretDigest = "e99a18c428cb38d5f260853678922e03"
        val secretAnswer = "MySecretMotherName42"
        val account = SavedAccount(
            uid = "884812",
            username = "secret_user_hipda",
            passwordMd5 = secretDigest,
            securityQuestionId = 1,
            securityAnswer = secretAnswer,
            lastLoginAt = 1_700_000_000_000L,
        )

        store.upsert(account)
        store.setActive(account.uid)

        assertTrue("Expected encrypted credentials file to exist on disk", file.exists())
        val rawBytes = file.readBytes()
        assertTrue("Expected non-empty encrypted payload on disk", rawBytes.isNotEmpty())
        val rawUtf8 = rawBytes.toString(Charsets.UTF_8)
        val rawLatin1 = rawBytes.toString(Charsets.ISO_8859_1)

        val forbiddenFragments = listOf(
            secretDigest,
            secretAnswer,
            account.username,
            account.uid,
            "passwordMd5",
            "securityAnswer",
        )
        for (fragment in forbiddenFragments) {
            assertFalse("Disk file must not contain plaintext '$fragment' (UTF-8)", rawUtf8.contains(fragment))
            assertFalse("Disk file must not contain plaintext '$fragment' (ISO-8859-1)", rawLatin1.contains(fragment))
        }
    }

    @Test
    fun savedAccountToString_redactsPasswordDigestAndSecurityAnswer() {
        val account = SavedAccount(
            uid = "1001",
            username = "alice",
            passwordMd5 = "0123456789abcdef0123456789abcdef",
            securityQuestionId = 4,
            securityAnswer = "TeacherWang",
            lastLoginAt = 1234L,
        )

        val rendered = account.toString()
        assertFalse(rendered.contains(account.passwordMd5))
        assertFalse(rendered.contains(account.securityAnswer))
        assertTrue(rendered.contains(account.uid))
        assertTrue(rendered.contains(account.username))
    }

    @Test
    fun concurrentUpserts_preserveAllAccounts() = runBlocking {
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = createStore(newCredentialsFile(), scope)
            val accounts = (1..12).map { index ->
                sampleAccount(
                    uid = "${1000 + index}",
                    username = "user_$index",
                    lastLoginAt = index * 100L,
                )
            }

            coroutineScope {
                accounts.forEach { account ->
                    launch(Dispatchers.Default) {
                        store.upsert(account)
                    }
                }
            }

            assertEquals(accounts.sortedByDescending { it.lastLoginAt }, store.accounts.first())
        } finally {
            scope.cancel()
            scope.coroutineContext[Job]?.join()
        }
    }

    private fun sampleAccount(
        uid: String,
        username: String,
        passwordMd5: String = "0123456789abcdef0123456789abcdef",
        securityQuestionId: Int = SavedAccount.NO_SECURITY_QUESTION,
        securityAnswer: String = "",
        lastLoginAt: Long,
    ): SavedAccount = SavedAccount(
        uid = uid,
        username = username,
        passwordMd5 = passwordMd5,
        securityQuestionId = securityQuestionId,
        securityAnswer = securityAnswer,
        lastLoginAt = lastLoginAt,
    )

    private suspend fun assertThrowsIllegalArgument(block: suspend () -> Unit): IllegalArgumentException {
        try {
            block()
            throw AssertionError("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            return e
        }
    }

    private object Utf8StringSerializer : Serializer<String> {
        override val defaultValue: String = ""

        override suspend fun readFrom(input: InputStream): String =
            input.readBytes().toString(Charsets.UTF_8)

        override suspend fun writeTo(t: String, output: OutputStream) {
            output.write(t.toByteArray(Charsets.UTF_8))
        }
    }

    private class InMemoryKeysetBackend : KeysetBackend {
        private val aeads = mutableMapOf<String, Aead>()

        override fun buildAead(normalizedKeysetName: String): Aead =
            aeads.getOrPut(normalizedKeysetName) {
                KeysetHandle.generateNew(
                    PredefinedAeadParameters.AES256_GCM,
                    RegistryConfiguration.get(),
                ).getPrimitive(RegistryConfiguration.get(), Aead::class.java)
            }

        override fun clearKeysetEntry(normalizedKeysetName: String) {
            aeads.remove(normalizedKeysetName)
        }

        override fun deleteMasterKeyAlias() {
            aeads.clear()
        }
    }
}
