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

data class CredentialGrant(
    val scopes: Set<String>,
    val credentials: SourceCredentials,
) {
    init {
        require(scopes.isNotEmpty() && scopes.none(String::isBlank)) {
            "Credential scopes must not be empty or blank"
        }
        require(!credentials.isEmpty) { "Credential grant must not be empty" }
    }
}

data class CredentialTxnSnapshot(
    val revision: Long,
    val active: CredentialGrant?,
    val pending: PendingCredentialMutation?,
) {
    init {
        require(revision >= 0L) { "Credential revision must not be negative" }
    }
}

sealed interface DescriptorBinding {
    data object Absent : DescriptorBinding
    data class Calibre(val scope: String) : DescriptorBinding {
        init {
            require(scope.isNotBlank()) { "Calibre credential scope must not be blank" }
        }
    }
    data object OtherAdapter : DescriptorBinding
}

sealed interface PendingCredentialMutation {
    data class Activate(val target: CredentialGrant) : PendingCredentialMutation
    data class Clear(val targetScope: String) : PendingCredentialMutation {
        init {
            require(targetScope.isNotBlank()) { "Target credential scope must not be blank" }
        }
    }
    data object RemoveSource : PendingCredentialMutation
}

sealed interface CredentialMutationOutcome {
    data class Committed(val snapshot: CredentialTxnSnapshot?) : CredentialMutationOutcome
    data class Conflict(val observed: CredentialTxnSnapshot?) : CredentialMutationOutcome
    data class Failed(
        val observed: CredentialTxnSnapshot?,
        val cause: Throwable,
    ) : CredentialMutationOutcome
    data class Indeterminate(
        val observed: CredentialTxnSnapshot?,
        val cause: Throwable,
    ) : CredentialMutationOutcome
}

class IndeterminateCredentialMutationException(
    val outcome: CredentialMutationOutcome.Indeterminate,
) : IllegalStateException("Credential mutation outcome is indeterminate", outcome.cause)

class FailedCredentialMutationException(
    val outcome: CredentialMutationOutcome.Failed,
) : IllegalStateException("Credential mutation failed", outcome.cause)

class ConflictingCredentialMutationException(
    val outcome: CredentialMutationOutcome.Conflict,
) : IllegalStateException("Credential mutation revision conflict")

/** Keeps source authentication material outside source descriptors and the Room database. */
interface SourceCredentialStore {
    fun get(sourceId: String, scope: String): SourceCredentials?

    /** Legacy compatibility API. Transactional callers must use the outcome-returning overload. */
    fun put(sourceId: String, scope: String, credentials: SourceCredentials)

    /** Legacy compatibility API. Transactional callers must use [prepare] and [reconcile]. */
    fun put(sourceId: String, scopes: Set<String>, credentials: SourceCredentials) {
        if (scopes.isEmpty() || credentials.isEmpty) {
            remove(sourceId)
            return
        }
        require(scopes.size == 1) { "Credential store does not support atomic multi-scope writes" }
        put(sourceId, scopes.single(), credentials)
    }

    /** Legacy compatibility API. Transactional callers must use the outcome-returning overload. */
    fun remove(sourceId: String)

    fun snapshot(sourceId: String): CredentialTxnSnapshot? = null

    fun prepare(
        sourceId: String,
        expectedRevision: Long,
        pending: PendingCredentialMutation,
    ): CredentialMutationOutcome = unsupportedJournalOutcome(snapshot(sourceId))

    fun reconcile(
        sourceId: String,
        binding: DescriptorBinding,
    ): CredentialMutationOutcome = unsupportedJournalOutcome(snapshot(sourceId))

    fun put(
        sourceId: String,
        scopes: Set<String>,
        credentials: SourceCredentials,
        expectedRevision: Long,
    ): CredentialMutationOutcome = unsupportedJournalOutcome(snapshot(sourceId))

    fun remove(
        sourceId: String,
        expectedRevision: Long,
    ): CredentialMutationOutcome = unsupportedJournalOutcome(snapshot(sourceId))

    fun sourceIdsWithPending(): Set<String> = emptySet()
}

object NoOpSourceCredentialStore : SourceCredentialStore {
    override fun get(sourceId: String, scope: String): SourceCredentials? = null
    override fun put(sourceId: String, scope: String, credentials: SourceCredentials) = Unit
    override fun put(sourceId: String, scopes: Set<String>, credentials: SourceCredentials) = Unit
    override fun remove(sourceId: String) = Unit
    override fun prepare(
        sourceId: String,
        expectedRevision: Long,
        pending: PendingCredentialMutation,
    ): CredentialMutationOutcome = CredentialMutationOutcome.Committed(null)
    override fun reconcile(
        sourceId: String,
        binding: DescriptorBinding,
    ): CredentialMutationOutcome = CredentialMutationOutcome.Committed(null)
    override fun put(
        sourceId: String,
        scopes: Set<String>,
        credentials: SourceCredentials,
        expectedRevision: Long,
    ): CredentialMutationOutcome = CredentialMutationOutcome.Committed(null)
    override fun remove(
        sourceId: String,
        expectedRevision: Long,
    ): CredentialMutationOutcome = CredentialMutationOutcome.Committed(null)
}

/** AES/GCM encrypted SharedPreferences payload with a non-exportable Android Keystore key. */
class AndroidSourceCredentialStore(context: Context) : SourceCredentialStore {
    private val preferences = context.applicationContext.getSharedPreferences(
        PREFERENCES_NAME,
        Context.MODE_PRIVATE,
    )

    override fun get(sourceId: String, scope: String): SourceCredentials? = synchronized(STORE_LOCK) {
        require(sourceId.isNotBlank()) { "Source id must not be blank" }
        require(scope.isNotBlank()) { "Credential scope must not be blank" }
        activeCredentialsForScope(readSnapshotSafelyLocked(sourceId), scope)
    }

    override fun snapshot(sourceId: String): CredentialTxnSnapshot? = synchronized(STORE_LOCK) {
        readSnapshotLocked(sourceId)
    }

    override fun prepare(
        sourceId: String,
        expectedRevision: Long,
        pending: PendingCredentialMutation,
    ): CredentialMutationOutcome = synchronized(STORE_LOCK) {
        applyPlanLocked(
            sourceId,
            planCredentialPrepare(readSnapshotLocked(sourceId), expectedRevision, pending),
        )
    }

    override fun reconcile(
        sourceId: String,
        binding: DescriptorBinding,
    ): CredentialMutationOutcome = synchronized(STORE_LOCK) {
        applyPlanLocked(
            sourceId,
            planCredentialReconcile(readSnapshotLocked(sourceId), binding),
        )
    }

    override fun put(
        sourceId: String,
        scopes: Set<String>,
        credentials: SourceCredentials,
        expectedRevision: Long,
    ): CredentialMutationOutcome = synchronized(STORE_LOCK) {
        val current = readSnapshotLocked(sourceId)
        val observedRevision = current?.revision ?: 0L
        if (expectedRevision != observedRevision) {
            CredentialMutationOutcome.Conflict(current)
        } else {
            val active = if (scopes.isEmpty() || credentials.isEmpty) {
                null
            } else {
                CredentialGrant(scopes, credentials)
            }
            val next = CredentialTxnSnapshot(observedRevision + 1L, active, pending = null)
            persistSnapshotLocked(sourceId, next, current)
        }
    }

    override fun remove(
        sourceId: String,
        expectedRevision: Long,
    ): CredentialMutationOutcome = synchronized(STORE_LOCK) {
        val current = readSnapshotLocked(sourceId)
        val observedRevision = current?.revision ?: 0L
        if (expectedRevision != observedRevision) {
            CredentialMutationOutcome.Conflict(current)
        } else {
            persistSnapshotLocked(sourceId, target = null, previous = current)
        }
    }

    override fun sourceIdsWithPending(): Set<String> = synchronized(STORE_LOCK) {
        preferences.all.keys.asSequence()
            .filter { it.startsWith(SOURCE_KEY_PREFIX) }
            .map { it.removePrefix(SOURCE_KEY_PREFIX) }
            .filter { sourceId -> readSnapshotSafelyLocked(sourceId)?.pending != null }
            .toSet()
    }

    override fun put(sourceId: String, scope: String, credentials: SourceCredentials) {
        require(scope.isNotBlank()) { "Credential scope must not be blank" }
        put(sourceId, setOf(scope), credentials)
    }

    override fun put(sourceId: String, scopes: Set<String>, credentials: SourceCredentials) = synchronized(STORE_LOCK) {
        val current = readSnapshotLocked(sourceId)
        put(sourceId, scopes, credentials, current?.revision ?: 0L).requireLegacySuccess()
    }

    override fun remove(sourceId: String) = synchronized(STORE_LOCK) {
        persistSnapshotLocked(sourceId, target = null, previous = null).requireLegacySuccess()
    }

    private fun applyPlanLocked(
        sourceId: String,
        plan: CredentialMutationPlan,
    ): CredentialMutationOutcome = when (plan) {
        is CredentialMutationPlan.Write -> persistSnapshotLocked(
            sourceId = sourceId,
            target = plan.snapshot,
            previous = readSnapshotLocked(sourceId),
        )
        is CredentialMutationPlan.NoChange -> CredentialMutationOutcome.Committed(plan.snapshot)
        is CredentialMutationPlan.Conflict -> CredentialMutationOutcome.Conflict(plan.observed)
        CredentialMutationPlan.Delete -> persistSnapshotLocked(
            sourceId = sourceId,
            target = null,
            previous = readSnapshotLocked(sourceId),
        )
    }

    private fun persistSnapshotLocked(
        sourceId: String,
        target: CredentialTxnSnapshot?,
        previous: CredentialTxnSnapshot?,
    ): CredentialMutationOutcome {
        val key = preferenceKey(sourceId)
        val encoded = if (target == null) {
            null
        } else {
            try {
                encrypt(target)
            } catch (error: Throwable) {
                return CredentialMutationOutcome.Failed(previous, error)
            }
        }
        val committed = try {
            val editor = preferences.edit()
            if (encoded == null) editor.remove(key) else editor.putString(key, encoded)
            editor.commit()
        } catch (error: Throwable) {
            return CredentialMutationOutcome.Indeterminate(readSnapshotSafelyLocked(sourceId), error)
        }
        return if (committed) {
            CredentialMutationOutcome.Committed(target)
        } else {
            CredentialMutationOutcome.Indeterminate(
                observed = readSnapshotSafelyLocked(sourceId),
                cause = CredentialCommitFailedException(),
            )
        }
    }

    private fun encrypt(snapshot: CredentialTxnSnapshot): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        val plaintext = SourceCredentialJournalCodec.encode(snapshot).encodeToByteArray()
        val ciphertext = cipher.doFinal(plaintext)
        return listOf(
            PAYLOAD_VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(ciphertext, Base64.NO_WRAP),
        ).joinToString(":")
    }

    private fun readSnapshotLocked(sourceId: String): CredentialTxnSnapshot? {
        val encoded = preferences.getString(preferenceKey(sourceId), null) ?: return null
        return decrypt(encoded)
    }

    private fun readSnapshotSafelyLocked(sourceId: String): CredentialTxnSnapshot? =
        runCatching { readSnapshotLocked(sourceId) }.getOrNull()

    private fun decrypt(encoded: String): CredentialTxnSnapshot {
        val parts = encoded.split(':', limit = 3)
        require(parts.size == 3 && parts[0] in SUPPORTED_PAYLOAD_VERSIONS) {
            "Unsupported credential payload"
        }
        val iv = Base64.decode(parts[1], Base64.NO_WRAP)
        val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        val plaintext = cipher.doFinal(ciphertext).decodeToString()
        return SourceCredentialJournalCodec.decode(parts[0], plaintext)
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
        return "$SOURCE_KEY_PREFIX$sourceId"
    }

    private companion object {
        // SharedPreferences CAS must serialize across store instances in this process.
        val STORE_LOCK = Any()
        const val PREFERENCES_NAME = "source_credentials_v1"
        const val SOURCE_KEY_PREFIX = "source."
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "readflow.source.credentials.v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
        const val PAYLOAD_VERSION = "v3"
        val SUPPORTED_PAYLOAD_VERSIONS = setOf("v1", "v2", PAYLOAD_VERSION)
    }
}

internal sealed interface CredentialMutationPlan {
    data class Write(val snapshot: CredentialTxnSnapshot) : CredentialMutationPlan
    data class NoChange(val snapshot: CredentialTxnSnapshot?) : CredentialMutationPlan
    data class Conflict(val observed: CredentialTxnSnapshot?) : CredentialMutationPlan
    data object Delete : CredentialMutationPlan
}

internal fun planCredentialPrepare(
    current: CredentialTxnSnapshot?,
    expectedRevision: Long,
    pending: PendingCredentialMutation,
): CredentialMutationPlan {
    val observedRevision = current?.revision ?: 0L
    if (expectedRevision != observedRevision) return CredentialMutationPlan.Conflict(current)
    return CredentialMutationPlan.Write(
        CredentialTxnSnapshot(
            revision = observedRevision + 1L,
            active = current?.active,
            pending = pending,
        ),
    )
}

internal fun planCredentialReconcile(
    current: CredentialTxnSnapshot?,
    binding: DescriptorBinding,
): CredentialMutationPlan {
    if (current == null) return CredentialMutationPlan.NoChange(null)
    if (binding == DescriptorBinding.Absent || binding == DescriptorBinding.OtherAdapter) {
        return CredentialMutationPlan.Delete
    }
    binding as DescriptorBinding.Calibre
    val activeMatches = current.active?.scopes?.contains(binding.scope) == true
    val selectedActive = when (val pending = current.pending) {
        is PendingCredentialMutation.Activate -> when {
            binding.scope in pending.target.scopes -> pending.target
            activeMatches -> current.active
            else -> null
        }
        is PendingCredentialMutation.Clear -> when {
            binding.scope == pending.targetScope -> null
            activeMatches -> current.active
            else -> null
        }
        PendingCredentialMutation.RemoveSource -> current.active.takeIf { activeMatches }
        null -> current.active.takeIf { activeMatches }
    }
    val nextActive = selectedActive?.let { grant ->
        if (grant.scopes == setOf(binding.scope)) {
            grant
        } else {
            CredentialGrant(setOf(binding.scope), grant.credentials)
        }
    }
    if (current.pending == null && nextActive == current.active) {
        return CredentialMutationPlan.NoChange(current)
    }
    return CredentialMutationPlan.Write(
        CredentialTxnSnapshot(
            revision = current.revision + 1L,
            active = nextActive,
            pending = null,
        ),
    )
}

internal fun activeCredentialsForScope(
    snapshot: CredentialTxnSnapshot?,
    scope: String,
): SourceCredentials? = snapshot?.active
    ?.takeIf { scope in it.scopes }
    ?.credentials

internal object SourceCredentialJournalCodec {
    private val json = Json { ignoreUnknownKeys = false }

    fun encode(snapshot: CredentialTxnSnapshot): String = json.encodeToString(
        StoredJournalV3(
            revision = snapshot.revision,
            active = snapshot.active?.toStored(),
            pending = snapshot.pending?.toStored(),
        ),
    )

    fun decode(version: String, plaintext: String): CredentialTxnSnapshot = when (version) {
        "v1", "v2" -> json.decodeFromString<StoredLegacyCredentials>(plaintext).toSnapshot()
        "v3" -> json.decodeFromString<StoredJournalV3>(plaintext).toSnapshot()
        else -> error("Unsupported credential payload")
    }

    private fun StoredLegacyCredentials.toSnapshot(): CredentialTxnSnapshot {
        val effectiveScopes = scopes.ifEmpty { scope?.let(::setOf).orEmpty() }
        val credentials = SourceCredentials(username, password)
        val active = if (effectiveScopes.isEmpty() || credentials.isEmpty) {
            null
        } else {
            CredentialGrant(effectiveScopes, credentials)
        }
        return CredentialTxnSnapshot(revision = 0L, active = active, pending = null)
    }

    private fun StoredJournalV3.toSnapshot(): CredentialTxnSnapshot = CredentialTxnSnapshot(
        revision = revision,
        active = active?.toGrant(),
        pending = pending?.toMutation(),
    )

    private fun CredentialGrant.toStored() = StoredGrant(
        scopes = scopes,
        username = credentials.username,
        password = credentials.password,
    )

    private fun StoredGrant.toGrant() = CredentialGrant(
        scopes = scopes,
        credentials = SourceCredentials(username, password),
    )

    private fun PendingCredentialMutation.toStored(): StoredPending = when (this) {
        is PendingCredentialMutation.Activate -> StoredPending(
            type = PENDING_ACTIVATE,
            target = target.toStored(),
        )
        is PendingCredentialMutation.Clear -> StoredPending(
            type = PENDING_CLEAR,
            targetScope = targetScope,
        )
        PendingCredentialMutation.RemoveSource -> StoredPending(type = PENDING_REMOVE_SOURCE)
    }

    private fun StoredPending.toMutation(): PendingCredentialMutation = when (type) {
        PENDING_ACTIVATE -> PendingCredentialMutation.Activate(
            requireNotNull(target) { "Activate mutation is missing its target credential" }.toGrant(),
        )
        PENDING_CLEAR -> PendingCredentialMutation.Clear(
            requireNotNull(targetScope) { "Clear mutation is missing its target scope" },
        )
        PENDING_REMOVE_SOURCE -> PendingCredentialMutation.RemoveSource
        else -> error("Unsupported pending credential mutation")
    }

    private const val PENDING_ACTIVATE = "activate"
    private const val PENDING_CLEAR = "clear"
    private const val PENDING_REMOVE_SOURCE = "remove-source"
}

@Serializable
private data class StoredLegacyCredentials(
    val username: String,
    val password: String,
    val scope: String? = null,
    val scopes: Set<String> = emptySet(),
)

@Serializable
private data class StoredJournalV3(
    val revision: Long,
    val active: StoredGrant? = null,
    val pending: StoredPending? = null,
)

@Serializable
private data class StoredGrant(
    val scopes: Set<String>,
    val username: String,
    val password: String,
)

@Serializable
private data class StoredPending(
    val type: String,
    val target: StoredGrant? = null,
    val targetScope: String? = null,
)

private class CredentialCommitFailedException : IllegalStateException(
    "Failed to durably commit source credentials",
)

private fun unsupportedJournalOutcome(
    observed: CredentialTxnSnapshot?,
): CredentialMutationOutcome = CredentialMutationOutcome.Failed(
    observed = observed,
    cause = UnsupportedOperationException("Credential store does not support journal transactions"),
)

private fun CredentialMutationOutcome.requireLegacySuccess() {
    when (this) {
        is CredentialMutationOutcome.Committed -> Unit
        is CredentialMutationOutcome.Conflict -> throw ConflictingCredentialMutationException(this)
        is CredentialMutationOutcome.Failed -> throw FailedCredentialMutationException(this)
        is CredentialMutationOutcome.Indeterminate -> throw IndeterminateCredentialMutationException(this)
    }
}
