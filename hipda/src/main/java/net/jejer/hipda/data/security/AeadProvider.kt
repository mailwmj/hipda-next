package net.jejer.hipda.data.security

import androidx.datastore.core.Serializer
import com.google.crypto.tink.Aead

/**
 * Unified Tink AEAD provider shared by encrypted persistence modules (e.g. SiteCookieStore and
 * CredentialsStore). All encrypted files and Tink keysets share the [ENCRYPTED_FILE_PREFIX] prefix
 * so backup exclusion rules can reliably exclude Keystore-bound files.
 */
interface AeadProvider {

    /**
     * Returns a cached or newly created Tink [Aead] primitive backed by the Android Keystore
     * master key for [keysetName].
     */
    fun getOrCreateAead(keysetName: String = DEFAULT_KEYSET_NAME): Aead

    /**
     * Normalizes [logicalName] into a safe encrypted file name guaranteed to start with
     * [ENCRYPTED_FILE_PREFIX]. Throws [IllegalArgumentException] if [logicalName] is blank or
     * contains path separators / traversal segments.
     */
    fun encryptedFileName(logicalName: String): String

    /**
     * Derives deterministic associated data (AAD) bytes bound to [logicalName] so ciphertext
     * cannot be swapped between different encrypted stores sharing the same keyset.
     */
    fun associatedDataFor(logicalName: String): ByteArray

    /**
     * Wraps [wrappedSerializer] with Tink AEAD encryption using [associatedDataFor] for
     * [logicalName].
     */
    fun <T> wrapSerializer(
        logicalName: String,
        wrappedSerializer: Serializer<T>,
        keysetName: String = DEFAULT_KEYSET_NAME,
    ): Serializer<T>

    companion object {
        const val ENCRYPTED_FILE_PREFIX: String = "hx_"
        const val KEYSET_PREF_FILE: String = "${ENCRYPTED_FILE_PREFIX}tink_keysets"
        const val DEFAULT_KEYSET_NAME: String = "${ENCRYPTED_FILE_PREFIX}master_aead_keyset"
        const val MASTER_KEY_URI: String = "android-keystore://${ENCRYPTED_FILE_PREFIX}master_key"
    }
}
