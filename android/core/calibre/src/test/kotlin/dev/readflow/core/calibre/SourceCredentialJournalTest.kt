package dev.readflow.core.calibre

import dev.readflow.extensions.api.SourceCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceCredentialJournalTest {

    private val oldCredentials = SourceCredentials("reader", "old-secret")
    private val newCredentials = SourceCredentials("reader", "new-secret")
    private val oldGrant = CredentialGrant(setOf("https://reader.example"), oldCredentials)
    private val newGrant = CredentialGrant(setOf("https://reader.example:8443"), newCredentials)

    @Test
    fun prepareUsesRevisionCasAndPreservesActiveCredential() {
        val current = CredentialTxnSnapshot(revision = 4, active = oldGrant, pending = null)
        val pending = PendingCredentialMutation.Activate(newGrant)

        val applied = planCredentialPrepare(current, expectedRevision = 4, pending)
        val conflict = planCredentialPrepare(current, expectedRevision = 3, pending)

        assertTrue(applied is CredentialMutationPlan.Write)
        applied as CredentialMutationPlan.Write
        assertEquals(5, applied.snapshot.revision)
        assertEquals(oldGrant, applied.snapshot.active)
        assertEquals(pending, applied.snapshot.pending)
        assertEquals(CredentialMutationPlan.Conflict(current), conflict)
    }

    @Test
    fun reconcileActivatesPendingCredentialOnlyForTargetDescriptor() {
        val current = CredentialTxnSnapshot(
            revision = 8,
            active = oldGrant,
            pending = PendingCredentialMutation.Activate(newGrant),
        )

        val target = planCredentialReconcile(
            current,
            DescriptorBinding.Calibre("https://reader.example:8443"),
        ) as CredentialMutationPlan.Write
        val original = planCredentialReconcile(
            current,
            DescriptorBinding.Calibre("https://reader.example"),
        ) as CredentialMutationPlan.Write

        assertEquals(newGrant, target.snapshot.active)
        assertNull(target.snapshot.pending)
        assertEquals(oldGrant, original.snapshot.active)
        assertNull(original.snapshot.pending)
    }

    @Test
    fun reconcileClearAndRemoveFollowDescriptorAuthority() {
        val clear = CredentialTxnSnapshot(
            revision = 2,
            active = oldGrant,
            pending = PendingCredentialMutation.Clear("https://other.example"),
        )
        val remove = CredentialTxnSnapshot(
            revision = 3,
            active = oldGrant,
            pending = PendingCredentialMutation.RemoveSource,
        )

        val cleared = planCredentialReconcile(
            clear,
            DescriptorBinding.Calibre("https://other.example"),
        ) as CredentialMutationPlan.Write

        assertNull(cleared.snapshot.active)
        assertNull(cleared.snapshot.pending)
        assertEquals(CredentialMutationPlan.Delete, planCredentialReconcile(remove, DescriptorBinding.Absent))
        assertEquals(CredentialMutationPlan.Delete, planCredentialReconcile(remove, DescriptorBinding.OtherAdapter))
    }

    @Test
    fun getProjectionReadsOnlyActiveCredential() {
        val snapshot = CredentialTxnSnapshot(
            revision = 1,
            active = oldGrant,
            pending = PendingCredentialMutation.Activate(newGrant),
        )

        assertEquals(oldCredentials, activeCredentialsForScope(snapshot, "https://reader.example"))
        assertNull(activeCredentialsForScope(snapshot, "https://reader.example:8443"))
    }

    @Test
    fun codecReadsV1AndV2PayloadsAsActiveSnapshots() {
        val v1 = SourceCredentialJournalCodec.decode(
            version = "v1",
            plaintext = """{"scope":"https://reader.example","username":"reader","password":"secret"}""",
        )
        val v2 = SourceCredentialJournalCodec.decode(
            version = "v2",
            plaintext = """{"username":"reader","password":"secret","scopes":["https://reader.example","http://reader.example:8080"]}""",
        )

        assertEquals(0, v1.revision)
        assertEquals(setOf("https://reader.example"), v1.active?.scopes)
        assertEquals(
            setOf("https://reader.example", "http://reader.example:8080"),
            v2.active?.scopes,
        )
        assertNull(v1.pending)
        assertNull(v2.pending)
    }

    @Test
    fun reconcileNarrowsLegacyMultiScopeGrantToDescriptorBinding() {
        val legacy = SourceCredentialJournalCodec.decode(
            version = "v2",
            plaintext = """{"username":"reader","password":"secret","scopes":["https://reader.example","http://reader.example:8080"]}""",
        )

        val plan = planCredentialReconcile(
            legacy,
            DescriptorBinding.Calibre("https://reader.example"),
        )

        assertTrue(
            "a legacy multi-scope grant must be rewritten to the authoritative descriptor scope",
            plan is CredentialMutationPlan.Write,
        )
        plan as CredentialMutationPlan.Write
        assertEquals(setOf("https://reader.example"), plan.snapshot.active?.scopes)
        assertNull(activeCredentialsForScope(plan.snapshot, "http://reader.example:8080"))
    }

    @Test
    fun v3CodecRoundTripsEveryPendingMutation() {
        val pendingMutations = listOf(
            PendingCredentialMutation.Activate(newGrant),
            PendingCredentialMutation.Clear("https://other.example"),
            PendingCredentialMutation.RemoveSource,
        )

        pendingMutations.forEach { pending ->
            val expected = CredentialTxnSnapshot(12, oldGrant, pending)
            val encoded = SourceCredentialJournalCodec.encode(expected)

            assertEquals(expected, SourceCredentialJournalCodec.decode("v3", encoded))
        }
    }
}
