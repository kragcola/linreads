package dev.readflow.core.calibre

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.readflow.extensions.api.SourceCredentials
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keeps source authentication material outside source descriptors and the Room database. */
interface SourceCredentialStore {
    fun get(sourceId: String, scope: String): SourceCredentials?
    fun put(sourceId: String, scope: String, credentials: SourceCredentials)
    fun remove(sourceId: String)
}

object NoOpSourceCredentialStore : SourceCredentialStore {
    override fun get(sourceId: String, scope: String): SourceCredentials? = null
    override fun put(sourceId: String, scope: String, credentials: SourceCredentials) = Unit
    override fun remove(sourceId: String) = Unit
}

/** AES/GCM encrypted SharedPreferences payload with a non-exportable Android Keystore key. */
class AndroidSourceCredentialStore(context: Context) : SourceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )
    private val lock = Any()

    override fun get(sourceId: String, scope: String): SourceCredentials? = synchronized(lock) {
        require(scope.isNotBlank()) { "Credential scope must not be blank" }
        val encoded = preferences.getString(preferenceKey(sourceId), null)
            ?: return@synchronized null
        val stored = decrypt(encoded)
        stored.takeIf { it.scope == scope }?.let { SourceCredentials(it.username, it.password) }
    }

    override fun put(sourceId: String, scope: String, credentials: SourceCredentials) = synchronized(lock) {
        require(scope.isNotBlank()) { "Credential scope must not be blank" }
        if (credentials.isEmpty) {
            require(preferences.edit().remove(preferenceKey(sourceId)).commit()) {
                "Failed to clear source credentials"
            }
        } else {
            require(preferences.edit().putString(preferenceKey(sourceId), encrypt(scope, credentials)).commit()) {
                "Failed to persist source credentials"
            }
        }
    }

    override fun remove(sourceId: String) = synchronized(lock) {
        require(preferences.edit().remove(preferenceKey(sourceId)).commit()) {
            "Failed to remove source credentials"
        }
    }

    private fun encrypt(scope: String, credentials: SourceCredentials): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val plaintext = credentialJson.encodeToString(
            StoredCredentials(scope, credentials.username, credentials.password),
        ).encodeToByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(
            PAYLOAD_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }

    private fun decrypt(encoded: String): StoredCredentials {
        val parts = encoded.split(':', limit = 3)
        require(parts.size == 3 && parts[0] == PAYLOAD_VERSION) { "Unsupported credential payload" }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        return credentialJson.decodeFromString(
            cipher.doFinal(ciphertext).decodeToString(),
        )
    }

    private fun encryptionKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    .build(),
            )
            generateKey()
        }
    }

    private fun preferenceKey(sourceId: String): String {
        require(sourceId.isNotBlank()) { "Source id must not be blank" }
        return "source.$sourceId"
    }

    @Serializable
    private data class StoredCredentials(
        val scope: String,
        val username: String,
        val password: String,
    )

    private companion object {
        const val PREFERENCES_NAME = "source_credentials_v1"
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "readflow.source.credentials.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PAYLOAD_VERSION = "v1"
        val credentialJson = Json { ignoreUnknownKeys = false }
    }
}
