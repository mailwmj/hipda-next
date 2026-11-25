package net.jejer.hipda.data.network.cookie

import androidx.datastore.core.CorruptionException
import androidx.datastore.core.Serializer
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.CharacterCodingException
import java.security.GeneralSecurityException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.Cookie

@Serializable
internal data class StoredCookieStore(
    val version: Int = 1,
    val cookies: List<StoredCookie> = emptyList(),
)

@Serializable
internal data class StoredCookie(
    val name: String,
    val value: String,
    val expiresAt: Long,
    val domain: String,
    val path: String,
    val secure: Boolean = false,
    val httpOnly: Boolean = false,
    val persistent: Boolean = false,
    val hostOnly: Boolean = true,
    val sameSite: String? = null,
)

internal object CookieStorePayloadSerializer : Serializer<StoredCookieStore> {
    private val json = Json { ignoreUnknownKeys = true }

    override val defaultValue: StoredCookieStore = StoredCookieStore()

    override suspend fun readFrom(input: InputStream): StoredCookieStore {
        val bytes = input.readBytes()
        if (bytes.isEmpty()) throw CorruptionException("Cookie store payload is empty")
        return try {
            val text = bytes.decodeToString(throwOnInvalidSequence = true)
            json.decodeFromString(StoredCookieStore.serializer(), text)
        } catch (e: Exception) {
            when (e) {
                is SerializationException, is CharacterCodingException, is IllegalArgumentException ->
                    throw CorruptionException("Failed to deserialize cookie store", e)
                else -> throw e
            }
        }
    }

    override suspend fun writeTo(t: StoredCookieStore, output: OutputStream) {
        output.write(json.encodeToString(StoredCookieStore.serializer(), t).encodeToByteArray())
    }
}

internal class DecryptCorruptionSerializer<T>(
    private val delegate: Serializer<T>,
) : Serializer<T> {
    override val defaultValue: T get() = delegate.defaultValue

    override suspend fun readFrom(input: InputStream): T = try {
        delegate.readFrom(input)
    } catch (e: GeneralSecurityException) {
        throw CorruptionException("Failed to decrypt cookie store", e)
    }

    override suspend fun writeTo(t: T, output: OutputStream) = delegate.writeTo(t, output)
}

internal fun Cookie.toStoredCookie(): StoredCookie = StoredCookie(
    name, value, expiresAt, domain, path, secure, httpOnly, persistent, hostOnly, sameSite,
)

internal fun StoredCookie.toOkHttpCookieOrNull(): Cookie? = runCatching {
    Cookie.Builder().name(name).value(value).path(path).apply {
        if (hostOnly) hostOnlyDomain(domain) else domain(domain)
        if (persistent) expiresAt(expiresAt)
        if (secure) secure()
        if (httpOnly) httpOnly()
        sameSite?.let(::sameSite)
    }.build()
}.getOrNull()
