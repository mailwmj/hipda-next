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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TinkAeadProvider @VisibleForTesting internal constructor(
    private val aeadFactory: (normalizedKeysetName: String) -> Aead,
) : AeadProvider {

    @Inject
    constructor(@ApplicationContext context: Context) : this(
        aeadFactory = { normalizedKeysetName ->
            ensureTinkRegistered()
            AndroidKeysetManager.Builder()
                .withSharedPref(
                    context.applicationContext,
                    normalizedKeysetName,
                    AeadProvider.KEYSET_PREF_FILE,
                )
                .withKeyTemplate(KeyTemplates.get(KEY_TEMPLATE_NAME))
                .withMasterKeyUri(AeadProvider.MASTER_KEY_URI)
                .build()
                .keysetHandle
                .getPrimitive(RegistryConfiguration.get(), Aead::class.java)
        },
    )

    private val aeadCache = ConcurrentHashMap<String, Aead>()

    override fun getOrCreateAead(keysetName: String): Aead {
        val normalized = encryptedFileName(keysetName)
        return aeadCache.computeIfAbsent(normalized) { key ->
            ensureTinkRegistered()
            aeadFactory(key)
        }
    }

    override fun encryptedFileName(logicalName: String): String {
        val trimmed = logicalName.trim()
        require(trimmed.isNotEmpty()) { "Encrypted file name must not be blank" }
        require(!trimmed.contains('/') && !trimmed.contains('\\') && !trimmed.contains("..")) {
            "Encrypted file name must not contain path separators or traversal: $logicalName"
        }
        return if (trimmed.startsWith(AeadProvider.ENCRYPTED_FILE_PREFIX)) {
            trimmed
        } else {
            "${AeadProvider.ENCRYPTED_FILE_PREFIX}$trimmed"
        }
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
        val aead = getOrCreateAead(keysetName)
        val aad = associatedDataFor(logicalName)
        return AeadSerializer(
            aead = aead,
            wrappedSerializer = wrappedSerializer,
            associatedData = aad,
        )
    }

    companion object {
        private const val KEY_TEMPLATE_NAME = "AES256_GCM"
        private const val AAD_DOMAIN = "net.jejer.hipda.next.tink.v1"
        private val tinkRegistered = AtomicBoolean(false)

        fun ensureTinkRegistered() {
            if (tinkRegistered.compareAndSet(false, true)) {
                AeadConfig.register()
            }
        }
    }
}
