package net.jejer.hipda.data.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.Serializer
import androidx.datastore.tink.AeadSerializer
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.RegistryConfiguration
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TinkAeadProvider @VisibleForTesting internal constructor(
    private val keysetBackend: KeysetBackend,
) : AeadProvider {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        keysetBackend = AndroidKeysetBackend(context),
    )

    private val aeadCache = ConcurrentHashMap<String, Aead>()
    private val keysetLoadLock = Any()

    override fun getOrCreateAead(keysetName: String): Aead {
        val normalized = encryptedFileName(keysetName)
        aeadCache[normalized]?.let { return it }
        return synchronized(keysetLoadLock) {
            aeadCache[normalized]?.let { return it }
            ensureTinkRegistered()
            val aead = loadOrRecoverAead(normalized)
            aeadCache[normalized] = aead
            aead
        }
    }

    override fun encryptedFileName(logicalName: String): String {
        val trimmed = logicalName.trim()
        require(trimmed.isNotEmpty()) { "Encrypted file name must not be blank" }
        val stem = trimmed.removePrefix(AeadProvider.ENCRYPTED_FILE_PREFIX)
        require(stem.isNotEmpty()) {
            "Encrypted file name must contain a logical name after '${AeadProvider.ENCRYPTED_FILE_PREFIX}': $logicalName"
        }
        require(stem != "." && !stem.contains("..") && VALID_STEM_REGEX.matches(stem)) {
            "Encrypted file name contains invalid characters or path traversal: $logicalName"
        }
        return "${AeadProvider.ENCRYPTED_FILE_PREFIX}$stem"
    }

    override fun associatedDataFor(logicalName: String): ByteArray {
        val normalized = encryptedFileName(logicalName)
        return "$AAD_DOMAIN:$normalized".toByteArray(Charsets.UTF_8)
    }

    override fun <T> wrapSerializer(
        logicalName: String,
        wrappedSerializer: Serializer<T>,
        keysetName: String,
    ): Serializer<T> {
        val normalizedKeysetName = encryptedFileName(keysetName)
        val aad = associatedDataFor(logicalName)
        return LazyAeadSerializer(
            wrappedSerializer = wrappedSerializer,
            aad = aad,
            aeadSupplier = { getOrCreateAead(normalizedKeysetName) },
        )
    }

    private fun loadOrRecoverAead(normalizedKeysetName: String): Aead {
        val firstError = try {
            return keysetBackend.buildAead(normalizedKeysetName)
        } catch (e: Exception) {
            if (!isRecoverableKeysetError(e)) throw e
            e
        }

        // Stage 1: Clear only the affected keyset entry in hx_tink_keysets and retry once,
        // preserving the Android Keystore master key when only the keyset entry was corrupted
        // or restored from a backup encrypted by a different device's key.
        try {
            keysetBackend.clearKeysetEntry(normalizedKeysetName)
        } catch (cleanupError: Exception) {
            firstError.addSuppressed(cleanupError)
            throw firstError
        }

        val secondError = try {
            return keysetBackend.buildAead(normalizedKeysetName)
        } catch (e: Exception) {
            if (e !== firstError) {
                e.addSuppressed(firstError)
            }
            if (!isMasterKeyRecoveryError(e)) throw e
            e
        }

        // Stage 2: Rebuilding under the existing master key still failed with a Keystore/crypto
        // error (e.g. the Android Keystore alias itself was permanently invalidated). Delete the
        // stale master key alias, clear the keyset entry, and perform one final rebuild. If that
        // still fails, fail closed — never degrade to plaintext.
        try {
            keysetBackend.deleteMasterKeyAlias()
            keysetBackend.clearKeysetEntry(normalizedKeysetName)
            aeadCache.clear()
        } catch (cleanupError: Exception) {
            secondError.addSuppressed(cleanupError)
            throw secondError
        }

        return try {
            keysetBackend.buildAead(normalizedKeysetName)
        } catch (thirdError: Exception) {
            thirdError.addSuppressed(secondError)
            throw thirdError
        }
    }

    private fun isRecoverableKeysetError(error: Throwable): Boolean =
        error is GeneralSecurityException ||
            error is IOException ||
            error is IllegalArgumentException ||
            error is ClassCastException ||
            error is ProviderException

    private fun isMasterKeyRecoveryError(error: Throwable): Boolean =
        error is GeneralSecurityException || error is ProviderException

    private class LazyAeadSerializer<T>(
        private val wrappedSerializer: Serializer<T>,
        private val aad: ByteArray,
        private val aeadSupplier: () -> Aead,
    ) : Serializer<T> {
        @Volatile
        private var cachedDelegate: Pair<Aead, Serializer<T>>? = null

        override val defaultValue: T
            get() = wrappedSerializer.defaultValue

        override suspend fun readFrom(input: InputStream): T =
            resolveDelegate().readFrom(input)

        override suspend fun writeTo(t: T, output: OutputStream) {
            resolveDelegate().writeTo(t, output)
        }

        private fun resolveDelegate(): Serializer<T> {
            val aead = aeadSupplier()
            val cached = cachedDelegate
            if (cached != null && cached.first === aead) {
                return cached.second
            }
            val serializer = AeadSerializer(
                aead = aead,
                wrappedSerializer = wrappedSerializer,
                associatedData = aad,
            )
            cachedDelegate = aead to serializer
            return serializer
        }
    }

    companion object {
        private const val AAD_DOMAIN = "net.jejer.hipda.next.tink.v1"
        private val VALID_STEM_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private val tinkInitializer = IdempotentInitializer { AeadConfig.register() }

        fun ensureTinkRegistered() {
            tinkInitializer.ensureInitialized()
        }
    }
}

internal class IdempotentInitializer(
    private val action: () -> Unit,
) {
    @Volatile
    private var initialized: Boolean = false
    private val lock = Any()

    fun ensureInitialized() {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            action()
            initialized = true
        }
    }
}

internal interface KeysetBackend {
    fun buildAead(normalizedKeysetName: String): Aead
    fun clearKeysetEntry(normalizedKeysetName: String)
    fun deleteMasterKeyAlias()
}

internal class AndroidKeysetBackend(
    context: Context,
    private val keysetAeadLoader: (Context, String) -> Aead = ::buildAndroidKeysetAead,
    private val masterKeyDeleter: () -> Unit = ::deleteAndroidKeyStoreMasterKey,
) : KeysetBackend {
    private val appContext: Context = context.applicationContext

    override fun buildAead(normalizedKeysetName: String): Aead =
        keysetAeadLoader(appContext, normalizedKeysetName)

    override fun clearKeysetEntry(normalizedKeysetName: String) {
        val committed = appContext
            .getSharedPreferences(AeadProvider.KEYSET_PREF_FILE, Context.MODE_PRIVATE)
            .edit()
            .remove(normalizedKeysetName)
            .commit()
        if (!committed) {
            throw IOException("Failed to clear corrupted Tink keyset entry: $normalizedKeysetName")
        }
    }

    override fun deleteMasterKeyAlias() {
        masterKeyDeleter()
    }

    companion object {
        private const val ANDROID_KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val KEY_TEMPLATE_NAME = "AES256_GCM"
        internal val MASTER_KEY_ALIAS: String =
            AeadProvider.MASTER_KEY_URI.removePrefix("android-keystore://")

        internal fun buildAndroidKeysetAead(
            appContext: Context,
            normalizedKeysetName: String,
        ): Aead = AndroidKeysetManager.Builder()
            .withSharedPref(
                appContext,
                normalizedKeysetName,
                AeadProvider.KEYSET_PREF_FILE,
            )
            .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE_NAME))
            .withMasterKeyUri(AeadProvider.MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(RegistryConfiguration.get(), Aead::class.java)

        internal fun deleteAndroidKeyStoreMasterKey() {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE_PROVIDER)
            keyStore.load(null)
            if (keyStore.containsAlias(MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MASTER_KEY_ALIAS)
            }
        }
    }
}
