package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCredentials
import dev.readflow.extensions.api.SourceDescriptor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CalibreTailnetCredentialGateTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun persistedTailnetHttpSourceFailsClosedWhenVpnStateIsUnknown() {
        val credentials = RecordingCredentialProvider()

        val opened = openPersistedTailnetSource(
            network = CalibreNetworkSnapshot.Unknown,
            credentialProvider = credentials,
        )

        assertTrue("unknown VPN state must fail closed", opened is ReadflowResult.Failure)
        assertTrue("unknown VPN state must not request stored credentials", credentials.requests.isEmpty())
    }

    @Test
    fun persistedTailnetHttpSourceFailsClosedWhenVpnDoesNotApplyToApp() {
        val credentials = RecordingCredentialProvider()

        val opened = openPersistedTailnetSource(
            network = CalibreNetworkSnapshot.Active(
                vpnAppliesToApp = false,
                internetValidated = true,
            ),
            credentialProvider = credentials,
        )

        assertTrue("inactive VPN must fail closed", opened is ReadflowResult.Failure)
        assertTrue("inactive VPN must not request stored credentials", credentials.requests.isEmpty())
    }

    @Test
    fun persistedTailnetHttpSourceMayResolveStoredCredentialsWhenVpnAppliesToApp() {
        val credentials = RecordingCredentialProvider()

        val opened = openPersistedTailnetSource(
            network = CalibreNetworkSnapshot.Active(
                vpnAppliesToApp = true,
                internetValidated = true,
            ),
            credentialProvider = credentials,
        )

        assertTrue("positive VPN evidence should allow opening the source", opened is ReadflowResult.Success)
        assertEquals(
            listOf(CredentialRequest("tailnet-calibre", "http://100.101.102.103:8080")),
            credentials.requests,
        )
        (opened as ReadflowResult.Success).value.close()
    }

    private fun openPersistedTailnetSource(
        network: CalibreNetworkSnapshot,
        credentialProvider: RecordingCredentialProvider,
    ): ReadflowResult<dev.readflow.extensions.api.OnlineBookCatalog> =
        CalibreSourceAdapterFactory(
            booksDir = tempFolder.root,
            credentialProvider = credentialProvider,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider { network },
        ).open(persistedTailnetDescriptor())

    private fun persistedTailnetDescriptor() = SourceDescriptor(
        id = "tailnet-calibre",
        adapterId = SourceAdapterIds.CALIBRE,
        name = "Tailnet Calibre",
        configVersion = 1,
        configJson = calibreSourceConfigJson("http://100.101.102.103:8080"),
        baseUrl = "http://100.101.102.103:8080",
    )

    private class RecordingCredentialProvider : (String, String) -> SourceCredentials? {
        val requests = mutableListOf<CredentialRequest>()

        override fun invoke(sourceId: String, scope: String): SourceCredentials {
            requests += CredentialRequest(sourceId, scope)
            return SourceCredentials(username = "reader", password = "secret")
        }
    }

    private data class CredentialRequest(
        val sourceId: String,
        val scope: String,
    )
}
