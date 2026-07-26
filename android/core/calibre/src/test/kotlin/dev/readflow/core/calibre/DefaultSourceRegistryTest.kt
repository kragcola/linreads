package dev.readflow.core.calibre

import dev.readflow.core.database.PersistedBookSource
import dev.readflow.core.database.SourceConfigStore
import dev.readflow.core.model.BookFormat
import dev.readflow.core.model.BookMeta
import dev.readflow.core.model.FontChoice
import dev.readflow.core.model.ReadflowResult
import dev.readflow.core.model.ReaderReadingMode
import dev.readflow.core.model.ThemeMode
import dev.readflow.core.model.TxtEncoding
import dev.readflow.core.prefs.SettingsRepository
import dev.readflow.extensions.api.BUILTIN_CALIBRE_SOURCE_ID
import dev.readflow.extensions.api.DefaultSourceAdapterRegistry
import dev.readflow.extensions.api.OnlineBookCatalog
import dev.readflow.extensions.api.OnlineCatalogEntry
import dev.readflow.extensions.api.OnlineCatalogFilter
import dev.readflow.extensions.api.SourceAdapterFactory
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceCapabilities
import dev.readflow.extensions.api.SourceDescriptor
import dev.readflow.extensions.api.SourceKind
import dev.readflow.extensions.api.SourceCredentials
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultSourceRegistryTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun observeSourcesCombinesBuiltinCalibreAndUserSources() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-json-1",
                    kind = SourceKind.JSON_HTTP.name,
                    name = "JSON LAN",
                    baseUrl = "http://192.168.1.10:9000/catalog.json",
                    enabled = true,
                    sortOrder = 0,
                    createdAt = 1L,
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreCatalogFactory = { FakeCatalog(it) },
            genericCatalogFactory = { FakeCatalog(checkNotNull(it.kind), it.baseUrl) },
        )

        val sources = registry.observeSources().first()
        val sourcesById = sources.associateBy(SourceDescriptor::id)
        assertEquals(2, sources.size)
        assertEquals(true, sourcesById.getValue(BUILTIN_CALIBRE_SOURCE_ID).enabled)
        assertEquals(SourceKind.JSON_HTTP, sourcesById.getValue("source-json-1").kind)
    }

    @Test
    fun openCatalogRoutesBySourceId() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-opds",
                    kind = SourceKind.OPDS.name,
                    name = "OPDS",
                    baseUrl = "http://192.168.1.20:8080/opds",
                    enabled = true,
                ),
            ),
        )
        val opened = mutableListOf<String>()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreCatalogFactory = { descriptor ->
                opened += "calibre:${descriptor.baseUrl}"
                FakeCatalog(descriptor)
            },
            genericCatalogFactory = { descriptor ->
                opened += "generic:${descriptor.id}"
                FakeCatalog(checkNotNull(descriptor.kind), descriptor.baseUrl)
            },
        )

        val calibre = registry.openCatalog(BUILTIN_CALIBRE_SOURCE_ID)
        assertTrue(calibre is ReadflowResult.Success)
        val generic = registry.openCatalog("source-opds")
        assertTrue(generic is ReadflowResult.Success)
        assertEquals(
            listOf("calibre:http://192.168.1.5:8080", "generic:source-opds"),
            opened,
        )
    }

    @Test
    fun addUserSourceAcceptsCalibreAndRejectsInvalidHttpPublicHosts() = runTest {
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
        )

        val calibreAdd = registry.addUserSource(SourceKind.CALIBRE, "Calibre", "http://192.168.1.5:8080")
        assertTrue(calibreAdd is ReadflowResult.Success)

        val publicHttp = registry.addUserSource(
            SourceKind.JSON_HTTP,
            "Public",
            "http://example.com/catalog.json",
        )
        assertTrue(publicHttp is ReadflowResult.Failure)

        val ok = registry.addUserSource(
            SourceKind.JSON_HTTP,
            "LAN JSON",
            "http://192.168.1.30:8080/catalog.json",
        )
        assertTrue(ok is ReadflowResult.Success)
        assertEquals("LAN JSON", (ok as ReadflowResult.Success).value.name)
        assertEquals(SourceKind.JSON_HTTP, ok.value.kind)
    }

    @Test
    fun calibreCredentialsFollowAddUpdateAndDeleteLifecycleWithoutEnteringConfigJson() = runTest {
        val credentials = InMemorySourceCredentialStore()
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val added = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Protected Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson("http://192.168.1.5:8080"),
            credentials = SourceCredentials("reader", "initial-secret"),
        ) as ReadflowResult.Success

        assertEquals(SourceCredentials("reader", "initial-secret"), registry.sourceCredentials(added.value.id))
        assertTrue(store.getUserSource(added.value.id)?.configJson?.contains("initial-secret") == false)

        val updated = registry.updateUserSource(
            sourceId = added.value.id,
            name = "Updated Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson("http://192.168.1.6:8080"),
            credentials = SourceCredentials("reader-2", "updated-secret"),
        )

        assertTrue(updated is ReadflowResult.Success)
        assertEquals(SourceCredentials("reader-2", "updated-secret"), registry.sourceCredentials(added.value.id))
        assertEquals("Updated Calibre", store.getUserSource(added.value.id)?.name)
        assertTrue(store.getUserSource(added.value.id)?.configJson?.contains("updated-secret") == false)

        assertTrue(registry.removeUserSource(added.value.id) is ReadflowResult.Success)
        assertEquals(null, registry.sourceCredentials(added.value.id))
    }

    @Test
    fun addUserSourceCredentialPrepareFailureDoesNotCreateDescriptor() = runTest {
        val credentials = InMemorySourceCredentialStore(
            prepareOutcome = { _, _, _, observed ->
                CredentialMutationOutcome.Failed(
                    observed = observed,
                    cause = IllegalStateException("credential prepare failed"),
                )
            },
        )
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Protected Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson("https://reader.example.com/opds"),
            credentials = SourceCredentials("reader", "secret"),
        )

        assertTrue("credential prepare failure must fail the add", result is ReadflowResult.Failure)
        assertEquals("credential prepare must be attempted once", 1, credentials.prepareCalls)
        assertEquals(
            "a failed credential prepare must not create a descriptor",
            emptyList<PersistedBookSource>(),
            store.observeUserSources().first(),
        )
    }

    @Test
    fun addUserSourceDescriptorWriteThatCommitsThenThrowsIsIdempotentOnRetry() = runTest {
        val baseUrl = "https://reader.example.com/opds"
        val configJson = calibreSourceConfigJson(baseUrl)
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failDescriptorWrite = true
        val store = InMemorySourceConfigStore(
            afterUpsert = {
                if (failDescriptorWrite) {
                    failDescriptorWrite = false
                    error("descriptor write failed after commit")
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = InMemorySourceCredentialStore(),
        )

        val first = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Protected Calibre",
            configVersion = 1,
            configJson = configJson,
            credentials = expectedCredentials,
        )

        assertTrue(
            "a descriptor write observed committed after throwing must return success",
            first is ReadflowResult.Success,
        )
        val firstDescriptor = (first as ReadflowResult.Success).value

        val retried = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Protected Calibre retry",
            configVersion = 1,
            configJson = configJson,
            credentials = expectedCredentials,
        )

        assertTrue("retrying the committed add must return success", retried is ReadflowResult.Success)
        val retriedDescriptor = (retried as ReadflowResult.Success).value
        assertEquals(
            "retrying the same canonical config must reuse the committed descriptor",
            firstDescriptor.id,
            retriedDescriptor.id,
        )
        assertEquals(
            "an indeterminate add retry must not create a duplicate descriptor",
            listOf(firstDescriptor.id),
            store.observeUserSources().first().map { it.id },
        )
    }

    @Test
    fun crossOriginUpdateKeepsPreparedCredentialsUnreadableUntilDescriptorSwitch() = runTest {
        val sourceId = "source-cross-origin-update"
        val oldUrl = "https://old.example.com/opds"
        val newUrl = "https://new.example.com/opds"
        val oldScope = calibreCredentialScopeForRequestUrl(oldUrl)
        val newScope = calibreCredentialScopeForRequestUrl(newUrl)
        val oldCredentials = SourceCredentials("reader", "old-secret")
        val newCredentials = SourceCredentials("reader", "new-secret")
        val credentials = InMemorySourceCredentialStore().apply {
            put(sourceId, oldScope, oldCredentials)
        }
        var pendingBeforeDescriptorSwitch: PendingCredentialMutation? = null
        var oldCredentialsBeforeDescriptorSwitch: SourceCredentials? = null
        var newCredentialsBeforeDescriptorSwitch: SourceCredentials? = null
        val store = InMemorySourceConfigStore(
            initial = listOf(calibreSource(sourceId, oldUrl)),
            beforeUpsert = { source ->
                if (source.id == sourceId && source.baseUrl == newUrl) {
                    pendingBeforeDescriptorSwitch = credentials.snapshot(sourceId)?.pending
                    oldCredentialsBeforeDescriptorSwitch = credentials.get(sourceId, oldScope)
                    newCredentialsBeforeDescriptorSwitch = credentials.get(sourceId, newScope)
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.updateUserSource(
            sourceId = sourceId,
            name = "Moved Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(newUrl),
            credentials = newCredentials,
        )

        assertTrue("cross-origin update must succeed", result is ReadflowResult.Success)
        val pendingActivation = pendingBeforeDescriptorSwitch as? PendingCredentialMutation.Activate
        assertTrue(
            "new credentials must be journaled before the descriptor switches",
            pendingActivation != null,
        )
        assertEquals(setOf(newScope), pendingActivation?.target?.scopes)
        assertEquals(newCredentials, pendingActivation?.target?.credentials)
        assertEquals(
            "the old descriptor scope must remain readable before the switch",
            oldCredentials,
            oldCredentialsBeforeDescriptorSwitch,
        )
        assertEquals(
            "pending credentials must not be readable before the descriptor switches",
            null,
            newCredentialsBeforeDescriptorSwitch,
        )
        assertEquals("successful update must clear its pending journal", null, credentials.snapshot(sourceId)?.pending)
        assertEquals(
            "successful cross-origin update must retain only the new scope",
            setOf(newScope),
            credentials.snapshot(sourceId)?.active?.scopes,
        )
        assertEquals(null, credentials.get(sourceId, oldScope))
        assertEquals(newCredentials, credentials.get(sourceId, newScope))
    }

    @Test
    fun removeAlreadyAbsentDescriptorSucceedsAndObserveSourcesConvergesPendingCleanup() = runTest {
        val sourceId = "source-interrupted-remove"
        val sourceUrl = "https://reader.example.com/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failNextAbsentReconcile = false
        val credentials = InMemorySourceCredentialStore(
            reconcileOutcome = { candidateId, binding, observed ->
                if (
                    candidateId == sourceId &&
                    binding == DescriptorBinding.Absent &&
                    failNextAbsentReconcile
                ) {
                    failNextAbsentReconcile = false
                    CredentialMutationOutcome.Failed(
                        observed = observed,
                        cause = IllegalStateException("credential cleanup failed"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(
                sourceId,
                calibreCredentialScopeForRequestUrl(sourceUrl),
                expectedCredentials,
            )
            val current = checkNotNull(snapshot(sourceId))
            assertTrue(
                prepare(
                    sourceId = sourceId,
                    expectedRevision = current.revision,
                    pending = PendingCredentialMutation.RemoveSource,
                ) is CredentialMutationOutcome.Committed,
            )
            failNextAbsentReconcile = true
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val removed = registry.removeUserSource(sourceId)

        assertTrue("removing an already absent descriptor must be idempotent", removed is ReadflowResult.Success)
        assertTrue(
            "failed final cleanup must leave the durable remove intent pending",
            credentials.snapshot(sourceId)?.pending is PendingCredentialMutation.RemoveSource,
        )

        assertEquals(emptyList<SourceDescriptor>(), registry.observeSources().first())

        assertEquals(
            "the next observation must reconcile credentials against the absent descriptor",
            null,
            credentials.snapshot(sourceId),
        )
        assertEquals(emptySet<String>(), credentials.sourceIdsWithPending())
        assertEquals(
            "remove and recovery must both reconcile against descriptor absence",
            listOf(DescriptorBinding.Absent, DescriptorBinding.Absent),
            credentials.reconcileBindings,
        )
    }

    @Test
    fun addUserSourceDoesNotCreateDescriptorWhenCredentialPrepareIsIndeterminate() = runTest {
        val baseUrl = "http://192.168.1.5:8080"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var preparedSourceId: String? = null
        val credentials = InMemorySourceCredentialStore(
            afterPrepareOutcome = { sourceId, pending, committed ->
                if (
                    pending is PendingCredentialMutation.Activate &&
                    pending.target.credentials == expectedCredentials
                ) {
                    preparedSourceId = sourceId
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential prepare outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        )
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Protected Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(baseUrl),
            credentials = expectedCredentials,
        )

        assertTrue("indeterminate credential prepare must fail the add", result is ReadflowResult.Failure)
        val sourceId = checkNotNull(preparedSourceId)
        assertEquals(
            "an indeterminate prepare must not create the descriptor",
            null,
            store.getUserSource(sourceId),
        )
        registry.observeSources().first()
        assertEquals("recovery must discard credentials for the absent descriptor", null, credentials.snapshot(sourceId))
    }

    @Test
    fun addUserSourceDoesNotCreateDescriptorWhenCredentialClearPrepareIsIndeterminate() = runTest {
        val baseUrl = "http://192.168.1.5:8080"
        val clearedCredentials = SourceCredentials("", "")
        var preparedSourceId: String? = null
        val credentials = InMemorySourceCredentialStore(
            afterPrepareOutcome = { sourceId, pending, committed ->
                if (pending is PendingCredentialMutation.Clear) {
                    preparedSourceId = sourceId
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential clear prepare outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        )
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Calibre without credentials",
            configVersion = 1,
            configJson = calibreSourceConfigJson(baseUrl),
            credentials = clearedCredentials,
        )

        assertTrue("indeterminate credential clear prepare must fail the add", result is ReadflowResult.Failure)
        val sourceId = checkNotNull(preparedSourceId)
        assertEquals(
            "an indeterminate clear prepare must not create the descriptor",
            null,
            store.getUserSource(sourceId),
        )
        registry.observeSources().first()
        assertEquals(null, credentials.snapshot(sourceId))
    }

    @Test
    fun updateUserSourceKeepsOldDescriptorWhenCredentialPrepareIsIndeterminate() = runTest {
        val oldUrl = "http://192.168.1.5:8080"
        val newUrl = "http://192.168.1.6:8080"
        val sourceId = "source-update-put-after-commit"
        val oldCredentials = SourceCredentials("reader", "old-secret")
        val newCredentials = SourceCredentials("reader", "new-secret")
        val credentials = InMemorySourceCredentialStore(
            afterPrepareOutcome = { _, pending, committed ->
                if (
                    pending is PendingCredentialMutation.Activate &&
                    pending.target.credentials == newCredentials
                ) {
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential update prepare outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(sourceId, calibreCredentialScopeForRequestUrl(oldUrl), oldCredentials)
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, oldUrl)))
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.updateUserSource(
            sourceId = sourceId,
            name = "Updated Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(newUrl),
            credentials = newCredentials,
        )

        assertTrue("indeterminate credential prepare must fail the update", result is ReadflowResult.Failure)
        assertEquals(
            "descriptor must not switch after an indeterminate credential prepare",
            oldUrl,
            store.getUserSource(sourceId)?.baseUrl,
        )
        assertEquals(oldCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun updateUserSourceKeepsOldDescriptorWhenCredentialClearPrepareIsIndeterminate() = runTest {
        val oldUrl = "http://192.168.1.5:8080"
        val newUrl = "http://192.168.1.6:8080"
        val sourceId = "source-update-clear-after-commit"
        val oldCredentials = SourceCredentials("reader", "old-secret")
        val clearedCredentials = SourceCredentials("", "")
        val credentials = InMemorySourceCredentialStore(
            afterPrepareOutcome = { _, pending, committed ->
                if (pending is PendingCredentialMutation.Clear) {
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential clear prepare outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(sourceId, calibreCredentialScopeForRequestUrl(oldUrl), oldCredentials)
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, oldUrl)))
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.updateUserSource(
            sourceId = sourceId,
            name = "Updated Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(newUrl),
            credentials = clearedCredentials,
        )

        assertTrue("indeterminate credential clear prepare must fail the update", result is ReadflowResult.Failure)
        assertEquals(
            "descriptor must not switch after an indeterminate clear prepare",
            oldUrl,
            store.getUserSource(sourceId)?.baseUrl,
        )
        assertEquals(oldCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun openingBareHttpsMagicDnsSourcePersistsOnlyVerifiedFallback() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val credentials = InMemorySourceCredentialStore().apply {
            // Older releases persisted credentials under the original HTTPS origin before
            // MagicDNS URLs were canonicalized to the direct HTTP :8080 endpoint.
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, servedUrl)))
        val opened = mutableListOf<SourceDescriptor>()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe { hint ->
                assertEquals(servedUrl, hint)
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
            calibreCatalogFactory = { descriptor ->
                opened += descriptor
                FakeCatalog(descriptor)
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(directUrl, opened.single().baseUrl)
        assertEquals(directUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(calibreSourceConfigJson(directUrl), store.getUserSource(sourceId)?.configJson)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun automaticEndpointMigrationDoesNotStageCredentialsForAnUntrustedOrigin() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val untrustedUrl = "https://other.tailnet.ts.net/opds"
        val sourceId = "source-tailscale-untrusted-origin"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val credentials = InMemorySourceCredentialStore().apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, servedUrl)))
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = untrustedUrl, bookCount = 1)
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(untrustedUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(null, registry.sourceCredentials(sourceId))
    }

    @Test
    fun clearingCredentialsDuringEndpointProbeCannotRestoreAStaleCredentialSnapshot() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale-race"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val probeStarted = CompletableDeferred<Unit>()
        val finishProbe = CompletableDeferred<Unit>()
        val credentials = InMemorySourceCredentialStore().apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(calibreSource(sourceId, servedUrl))),
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = object : CalibreEndpointProbe {
                override suspend fun probe(hint: String): CalibreProbeResult =
                    error("Credential-aware probe required")

                override suspend fun probe(
                    hint: String,
                    credentials: SourceCredentials?,
                ): CalibreProbeResult {
                    assertEquals(servedUrl, hint)
                    assertEquals(expectedCredentials, credentials)
                    probeStarted.complete(Unit)
                    finishProbe.await()
                    return CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
                }
            },
        )

        val opening = async { registry.openCatalog(sourceId) }
        probeStarted.await()
        val clearing = async { registry.clearSourceCredentials(sourceId) }
        runCurrent()
        finishProbe.complete(Unit)

        assertTrue(opening.await() is ReadflowResult.Success)
        assertTrue(clearing.await() is ReadflowResult.Success)
        assertEquals(null, registry.sourceCredentials(sourceId))
    }

    @Test
    fun addingBareHttpsMagicDnsSourceKeepsItsOriginalEndpointUntilVerified() = runTest {
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        val added = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Tailscale Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson("https://reader.tailnet.ts.net/opds"),
        ) as ReadflowResult.Success

        assertEquals("https://reader.tailnet.ts.net/opds", added.value.baseUrl)
        assertEquals(
            calibreSourceConfigJson("https://reader.tailnet.ts.net/opds"),
            store.getUserSource(added.value.id)?.configJson,
        )
    }

    @Test
    fun legacyBareHttpsMagicDnsSettingKeepsItsOriginalEndpointUntilVerified() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = servedUrl)
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                "https://reader.tailnet.ts.net",
                expectedCredentials,
            )
        }
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        registry.observeSources().first()

        assertEquals(servedUrl, settings.calibreBaseUrl.value)
        assertEquals(servedUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(expectedCredentials, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun failedMagicDnsNegotiationLeavesStoredEndpointAndCredentialsUntouched() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val sourceId = "source-tailscale-failed"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val credentials = InMemorySourceCredentialStore().apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, servedUrl)))
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Failure(
                    message = "无法通过 Tailscale 连接服务器",
                    nextStep = "打开 Tailscale 后重试",
                    attempts = listOf(CalibreProbeAttempt(servedUrl, "连接超时")),
                )
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Failure)
        assertEquals(servedUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(calibreSourceConfigJson(servedUrl), store.getUserSource(sourceId)?.configJson)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun sourcePersistenceFailureAfterMutationKeepsTheCommittedVerifiedFallback() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale-persist-failure"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failVerifiedWrite = true
        val store = InMemorySourceConfigStore(
            initial = listOf(calibreSource(sourceId, servedUrl)),
            afterUpsert = { source ->
                if (source.baseUrl == directUrl && failVerifiedWrite) {
                    failVerifiedWrite = false
                    error("source persistence failed after mutation")
                }
            },
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(directUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(calibreSourceConfigJson(directUrl), store.getUserSource(sourceId)?.configJson)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun credentialPersistenceFailureAfterMutationRestoresTheOriginalScope() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale-credential-failure"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failVerifiedScope = false
        val credentials = InMemorySourceCredentialStore(
            afterPrepareOutcome = { _, pending, committed ->
                if (
                    pending is PendingCredentialMutation.Activate &&
                    calibreCredentialScopeForRequestUrl(directUrl) in pending.target.scopes &&
                    failVerifiedScope
                ) {
                    failVerifiedScope = false
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential prepare outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
            failVerifiedScope = true
        }
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, servedUrl)))
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Failure)
        assertEquals(servedUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun committedSourceWriteDoesNotAttemptCredentialRollbackAfterThrowing() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale-rollback-failure"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failOldScopeWrite = false
        val credentials = InMemorySourceCredentialStore(
            reconcileOutcome = { _, binding, observed ->
                if (
                    binding == DescriptorBinding.Calibre(calibreCredentialScopeForRequestUrl(servedUrl)) &&
                    observed?.pending != null &&
                    failOldScopeWrite
                ) {
                    failOldScopeWrite = false
                    CredentialMutationOutcome.Failed(
                        observed = observed,
                        cause = IllegalStateException("credential rollback failed"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
            failOldScopeWrite = true
        }
        var failVerifiedWrite = true
        val store = InMemorySourceConfigStore(
            initial = listOf(calibreSource(sourceId, servedUrl)),
            afterUpsert = { source ->
                if (source.baseUrl == directUrl && failVerifiedWrite) {
                    failVerifiedWrite = false
                    error("source persistence failed after mutation")
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
        )

        val result = registry.openCatalog(sourceId)

        assertTrue(result is ReadflowResult.Success)
        assertTrue("a committed descriptor must not reconcile back to the old binding", failOldScopeWrite)
        assertEquals(directUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun cancellationAfterCommittedSourceWriteKeepsDescriptorAndCredentialsAligned() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val sourceId = "source-tailscale-cancelled-rollback"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failOldScopeRestore = false
        val credentials = InMemorySourceCredentialStore(
            reconcileOutcome = { _, binding, observed ->
                if (
                    binding == DescriptorBinding.Calibre(calibreCredentialScopeForRequestUrl(servedUrl)) &&
                    observed?.pending != null &&
                    failOldScopeRestore
                ) {
                    failOldScopeRestore = false
                    CredentialMutationOutcome.Failed(
                        observed = observed,
                        cause = IllegalStateException("credential rollback failed before restoring old scope"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(sourceId, "https://reader.tailnet.ts.net", expectedCredentials)
            failOldScopeRestore = true
        }
        val cancellation = CancellationException("source write cancelled")
        var cancelVerifiedWrite = true
        val store = InMemorySourceConfigStore(
            initial = listOf(calibreSource(sourceId, servedUrl)),
            afterUpsert = { source ->
                if (source.baseUrl == directUrl && cancelVerifiedWrite) {
                    cancelVerifiedWrite = false
                    throw cancellation
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
        )

        val thrown = runCatching { registry.openCatalog(sourceId) }.exceptionOrNull()

        assertTrue(thrown === cancellation)
        assertTrue("a committed descriptor must not reconcile back to the old binding", failOldScopeRestore)
        assertEquals(directUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
    }

    @Test
    fun builtinSettingFailureAfterMutationKeepsRoomAuthoritativeEndpoint() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val settings = FakeSettingsRepository(
            calibreUrl = null,
            failAfterSettingCalibreUrl = directUrl,
        )
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, servedUrl)),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
            calibreEndpointProbe = CalibreEndpointProbe {
                CalibreProbeResult.Success(baseUrl = directUrl, bookCount = 1)
            },
        )

        val result = registry.openCatalog(BUILTIN_CALIBRE_SOURCE_ID)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(directUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(directUrl, settings.calibreBaseUrl.value)
    }

    @Test
    fun editingBuiltinCalibreAlsoUpdatesLegacyUrlSoItIsNotReverted() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )
        registry.observeSources().first()

        val updated = registry.updateUserSource(
            sourceId = BUILTIN_CALIBRE_SOURCE_ID,
            name = "Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson("http://192.168.1.6:8080"),
        )
        registry.openCatalog(BUILTIN_CALIBRE_SOURCE_ID)

        assertTrue(updated is ReadflowResult.Success)
        assertEquals("http://192.168.1.6:8080", settings.calibreBaseUrl.value)
        assertEquals("http://192.168.1.6:8080", store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
    }

    @Test
    fun migratedCalibreCanBeRemovedWithoutBeingRecreated() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-x",
                    kind = SourceKind.OPDS.name,
                    name = "X",
                    baseUrl = "http://10.0.0.2:8080/opds",
                    enabled = true,
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        registry.observeSources().first()
        assertTrue(registry.removeUserSource(BUILTIN_CALIBRE_SOURCE_ID) is ReadflowResult.Success)
        assertEquals(null, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID))
        assertEquals("", settings.calibreBaseUrl.value)
        assertTrue(registry.removeUserSource("source-x") is ReadflowResult.Success)
        assertEquals(null, store.getUserSource("source-x"))
    }

    @Test
    fun removingAlreadyAbsentBuiltinWritesLegacyTombstoneAndPreventsReimport() = runTest {
        val legacyUrl = "http://192.168.1.5:8080"
        val settings = FakeSettingsRepository(calibreUrl = legacyUrl)
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        val removed = registry.removeUserSource(BUILTIN_CALIBRE_SOURCE_ID)

        assertTrue("removing an already absent builtin must be idempotent", removed is ReadflowResult.Success)
        assertEquals(
            "confirmed descriptor absence must replace the stale legacy URL with a delete tombstone",
            "",
            settings.calibreBaseUrl.value,
        )
        assertEquals(emptyList<SourceDescriptor>(), registry.observeSources().first())
        assertEquals(
            "the stale legacy URL must not recreate the deleted builtin",
            null,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID),
        )
    }

    @Test
    fun observeSourcesFailsClosedWhenAbsentBuiltinCredentialSnapshotCannotBeRead() = runTest {
        val legacyUrl = "http://192.168.1.5:8080"
        val settings = FakeSettingsRepository(calibreUrl = legacyUrl)
        val store = InMemorySourceConfigStore()
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { sourceId ->
                if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                    IllegalStateException("credential journal keystore read failed")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val observation = runCatching { registry.observeSources().first() }

        assertEquals(
            "a credential journal read failure must not reimport the absent builtin into Room",
            null,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID),
        )
        assertTrue(
            "fail-closed observation must not emit a rebuilt builtin",
            observation.isFailure ||
                observation.getOrNull().orEmpty().none { it.id == BUILTIN_CALIBRE_SOURCE_ID },
        )
        assertEquals("a failed journal read must not rewrite the legacy mirror", legacyUrl, settings.calibreBaseUrl.value)
    }

    @Test
    fun removeUserSourcePurgesUnreadableCredentialJournalAndDeletesDescriptor() = runTest {
        val sourceId = "source-corrupt-journal-remove"
        val sourceUrl = "https://reader.example.com/opds"
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, sourceUrl)))
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { candidateId ->
                if (candidateId == sourceId) {
                    IllegalStateException("credential journal is corrupt")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.removeUserSource(sourceId)

        assertTrue("explicit source removal must recover from an unreadable journal", result is ReadflowResult.Success)
        assertEquals(null, store.getUserSource(sourceId))
        assertEquals("unreadable credentials must be purged once", 1, credentials.legacyRemoveCalls)
        assertEquals(null, credentials.snapshot(sourceId))
    }

    @Test
    fun clearSourceCredentialsPurgesUnreadableCredentialJournal() = runTest {
        val sourceId = "source-corrupt-journal-clear"
        val sourceUrl = "https://reader.example.com/opds"
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, sourceUrl)))
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { candidateId ->
                if (candidateId == sourceId) {
                    IllegalStateException("credential journal is corrupt")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.clearSourceCredentials(sourceId)

        assertTrue("explicit credential clear must recover from an unreadable journal", result is ReadflowResult.Success)
        assertEquals(sourceUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals("unreadable credentials must be purged once", 1, credentials.legacyRemoveCalls)
        assertEquals(null, credentials.snapshot(sourceId))
    }

    @Test
    fun updateUserSourceWithExplicitCredentialsReplacesUnreadableCredentialJournal() = runTest {
        val sourceId = "source-corrupt-journal-update"
        val oldUrl = "https://old.example.com/opds"
        val newUrl = "https://new.example.com/opds"
        val expectedCredentials = SourceCredentials("reader", "new-secret")
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, oldUrl)))
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { candidateId ->
                if (candidateId == sourceId) {
                    IllegalStateException("credential journal is corrupt")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.updateUserSource(
            sourceId = sourceId,
            name = "Recovered Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(newUrl),
            credentials = expectedCredentials,
        )

        assertTrue("explicit replacement credentials must recover the update", result is ReadflowResult.Success)
        assertEquals(newUrl, store.getUserSource(sourceId)?.baseUrl)
        assertEquals("the corrupt journal must be purged before replacement", 1, credentials.legacyRemoveCalls)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
        assertEquals(null, credentials.snapshot(sourceId)?.pending)
        assertEquals(
            setOf(calibreCredentialScopeForRequestUrl(newUrl)),
            credentials.snapshot(sourceId)?.active?.scopes,
        )
    }

    @Test
    fun addCanonicalCalibreWithExplicitCredentialsReplacesUnreadableCredentialJournal() = runTest {
        val sourceId = "source-corrupt-journal-canonical-add"
        val sourceUrl = "https://reader.example.com/opds"
        val expectedCredentials = SourceCredentials("reader", "new-secret")
        val store = InMemorySourceConfigStore(listOf(calibreSource(sourceId, sourceUrl)))
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { candidateId ->
                if (candidateId == sourceId) {
                    IllegalStateException("credential journal is corrupt")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.addUserSource(
            adapterId = SourceAdapterIds.CALIBRE,
            name = "Recovered Calibre",
            configVersion = 1,
            configJson = calibreSourceConfigJson(sourceUrl),
            credentials = expectedCredentials,
        )

        assertTrue("canonical add with replacement credentials must recover", result is ReadflowResult.Success)
        val descriptor = (result as ReadflowResult.Success).value
        assertEquals("canonical retry must reuse the existing descriptor", sourceId, descriptor.id)
        assertEquals(
            "canonical retry must not create a duplicate descriptor",
            listOf(sourceId),
            store.observeUserSources().first().map { it.id },
        )
        assertEquals("the corrupt journal must be purged exactly once", 1, credentials.legacyRemoveCalls)
        assertEquals(expectedCredentials, registry.sourceCredentials(sourceId))
        assertEquals(null, credentials.snapshot(sourceId)?.pending)
        assertEquals(
            setOf(calibreCredentialScopeForRequestUrl(sourceUrl)),
            credentials.snapshot(sourceId)?.active?.scopes,
        )
    }

    @Test
    fun blankLegacySettingCleansInterruptedBuiltinDeletion() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, "http://192.168.1.5:8080")),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                "http://192.168.1.5:8080",
                SourceCredentials("reader", "secret"),
            )
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = ""),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val sources = registry.observeSources().first()

        assertTrue(sources.isEmpty())
        assertEquals(null, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID))
        assertEquals(null, credentials.get(BUILTIN_CALIBRE_SOURCE_ID, "http://192.168.1.5:8080"))
    }

    @Test
    fun blankLegacyTombstonePurgesUnreadableBuiltinJournalAndDeletesDescriptor() = runTest {
        val sourceUrl = "http://192.168.1.5:8080"
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, sourceUrl)),
        )
        val credentials = InMemorySourceCredentialStore(
            snapshotFailure = { sourceId ->
                if (sourceId == BUILTIN_CALIBRE_SOURCE_ID) {
                    IllegalStateException("credential journal is corrupt")
                } else {
                    null
                }
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = ""),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val sources = registry.observeSources().first()

        assertTrue("blank legacy tombstone must suppress the interrupted builtin", sources.isEmpty())
        assertEquals(
            "blank legacy tombstone must delete the stale Room descriptor",
            null,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID),
        )
        assertEquals("the unreadable builtin journal must be purged once", 1, credentials.legacyRemoveCalls)
        assertEquals(null, credentials.snapshot(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun firstRunDiscoversAndPersistsBuiltinCalibre() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = null)
        val store = InMemorySourceConfigStore()
        var discoveryCalls = 0
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(1, discoveryCalls)
        assertEquals("http://192.168.2.1:8080", settings.calibreBaseUrl.value)
        assertEquals(BUILTIN_CALIBRE_SOURCE_ID, sources.single().id)
        assertEquals("http://192.168.2.1:8080", sources.single().baseUrl)
    }

    @Test
    fun deletedBuiltinCalibreNeverRunsDiscoveryAgain() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "")
        var discoveryCalls = 0
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        assertTrue(registry.observeSources().first().isEmpty())
        assertEquals(0, discoveryCalls)
        assertEquals("", settings.calibreBaseUrl.value)
    }

    @Test
    fun existingCalibreSourceSuppressesAutomaticDiscovery() = runTest {
        var discoveryCalls = 0
        val existing = calibreSource("source-calibre-user", "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(existing)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(0, discoveryCalls)
        assertEquals(listOf("source-calibre-user"), sources.map(SourceDescriptor::id))
    }

    @Test
    fun nullLegacySettingPreservesExistingBuiltinCalibreSource() = runTest {
        var discoveryCalls = 0
        val builtin = calibreSource(BUILTIN_CALIBRE_SOURCE_ID, "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(builtin)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.NotFound
            },
        )

        val sources = registry.observeSources().first()

        assertEquals(0, discoveryCalls)
        assertEquals(listOf(BUILTIN_CALIBRE_SOURCE_ID), sources.map(SourceDescriptor::id))
    }

    @Test
    fun cancelledDiscoveryIsRetriedByTheNextCollector() = runTest {
        var discoveryCalls = 0
        val firstDiscoveryStarted = CompletableDeferred<Unit>()
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                if (discoveryCalls == 1) {
                    firstDiscoveryStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                    CalibreDiscoveryResult.NotFound
                } else {
                    CalibreDiscoveryResult.Found(
                        baseUrl = "http://192.168.2.1:8080",
                        serviceName = "Books in calibre",
                    )
                }
            },
        )

        registry.observeSources().first()
        firstDiscoveryStarted.await()
        val retry = async {
            registry.observeSources().first { sources -> sources.isNotEmpty() }
        }
        runCurrent()
        val retriedAfterCancellation = discoveryCalls == 2
        retry.cancel()

        assertTrue("cancelled discovery must not consume the one allowed attempt", retriedAfterCancellation)
    }

    @Test
    fun discoveryCancelledWhileWaitingForSourceWriteLockIsRetried() = runTest {
        var discoveryCalls = 0
        val sourceWriteStarted = CompletableDeferred<Unit>()
        val sourceWriteGate = CompletableDeferred<Unit>()
        val store = InMemorySourceConfigStore(
            beforeNextSortOrder = {
                sourceWriteStarted.complete(Unit)
                sourceWriteGate.await()
            },
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryCalls += 1
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )
        val manualAdd = async {
            registry.addUserSource(
                SourceKind.OPDS,
                "OPDS",
                "http://192.168.1.20:8080/opds",
            )
        }
        sourceWriteStarted.await()

        registry.observeSources().first()
        sourceWriteGate.complete(Unit)
        manualAdd.await()
        val retry = async {
            registry.observeSources().first { sources ->
                sources.any { it.id == BUILTIN_CALIBRE_SOURCE_ID }
            }
        }
        runCurrent()
        val retriedAfterLockCancellation = discoveryCalls == 2
        retry.cancel()

        assertTrue(
            "cancellation while waiting for the source lock must leave discovery retryable",
            retriedAfterLockCancellation,
        )
    }

    @Test
    fun manualCalibreAddDuringDiscoverySuppressesBuiltinPersistence() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = null)
        val store = InMemorySourceConfigStore()
        val discoveryStarted = CompletableDeferred<Unit>()
        val discoveryGate = CompletableDeferred<Unit>()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryStarted.complete(Unit)
                discoveryGate.await()
                CalibreDiscoveryResult.Found(
                    baseUrl = "http://192.168.2.1:8080",
                    serviceName = "Books in calibre",
                )
            },
        )
        val observation = launch { registry.observeSources().collect() }
        discoveryStarted.await()

        val manual = registry.addUserSource(
            SourceKind.CALIBRE,
            "My Calibre",
            "http://192.168.1.5:8080",
        )
        discoveryGate.complete(Unit)
        runCurrent()
        observation.cancelAndJoin()

        assertTrue(manual is ReadflowResult.Success)
        assertEquals(null, settings.calibreBaseUrl.value)
        assertEquals(1, store.observeUserSources().first().count { it.adapterId == SourceAdapterIds.CALIBRE })
    }

    @Test
    fun pendingDiscoveryDoesNotDelayExistingSources() = runTest {
        val discoveryGate = CompletableDeferred<Unit>()
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-opds",
                    kind = SourceKind.OPDS.name,
                    name = "OPDS",
                    baseUrl = "http://192.168.1.20:8080/opds",
                    enabled = true,
                    adapterId = SourceAdapterIds.OPDS,
                    configVersion = 1,
                    configJson = httpCatalogSourceConfigJson("http://192.168.1.20:8080/opds"),
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryGate.await()
                CalibreDiscoveryResult.NotFound
            },
        )

        val observation = async { registry.observeSources().first() }
        runCurrent()
        val emittedBeforeDiscoveryFinished = observation.isCompleted
        discoveryGate.complete(Unit)
        runCurrent()

        assertTrue("existing sources must render while Bonjour is pending", emittedBeforeDiscoveryFinished)
        assertEquals(listOf("source-opds"), observation.await().map(SourceDescriptor::id))
    }

    @Test
    fun openingExistingSourceDoesNotWaitForDiscovery() = runTest {
        val discoveryGate = CompletableDeferred<Unit>()
        val source = PersistedBookSource(
            id = "source-opds",
            kind = SourceKind.OPDS.name,
            name = "OPDS",
            baseUrl = "http://192.168.1.20:8080/opds",
            enabled = true,
            adapterId = SourceAdapterIds.OPDS,
            configVersion = 1,
            configJson = httpCatalogSourceConfigJson("http://192.168.1.20:8080/opds"),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(source)),
            booksDir = tempFolder.root,
            calibreServiceDiscovery = CalibreServiceDiscovery {
                discoveryGate.await()
                CalibreDiscoveryResult.NotFound
            },
        )

        val opening = async { registry.openCatalog(source.id) }
        runCurrent()
        val openedBeforeDiscoveryFinished = opening.isCompleted
        discoveryGate.complete(Unit)
        runCurrent()

        assertTrue("opening a configured source must not wait for Bonjour", openedBeforeDiscoveryFinished)
        assertTrue(opening.await() is ReadflowResult.Success)
    }

    @Test
    fun twoCalibreSourcesOpenWithTheirOwnDescriptors() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(
                calibreSource("source-calibre-a", "http://192.168.1.5:8080"),
                calibreSource("source-calibre-b", "http://192.168.1.6:8080"),
            ),
        )
        val opened = mutableListOf<SourceDescriptor>()
        val adapter = object : SourceAdapterFactory {
            override val adapterId = SourceAdapterIds.CALIBRE
            override val latestConfigVersion = 1
            override fun capabilities(configVersion: Int, configJson: String) =
                SourceCapabilities(canSearch = true, canDownload = true)
            override fun validate(configVersion: Int, configJson: String) = ReadflowResult.Success(Unit)
            override fun open(descriptor: SourceDescriptor): ReadflowResult<OnlineBookCatalog> {
                opened += descriptor
                return ReadflowResult.Success(FakeCatalog(descriptor))
            }
        }
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            sourceAdapters = DefaultSourceAdapterRegistry(setOf(adapter)),
        )

        val first = registry.openCatalog("source-calibre-a")
        val second = registry.openCatalog("source-calibre-b")

        assertTrue(first is ReadflowResult.Success)
        assertTrue(second is ReadflowResult.Success)
        assertEquals(listOf("source-calibre-a", "source-calibre-b"), opened.map(SourceDescriptor::id))
        assertEquals(
            listOf("http://192.168.1.5:8080", "http://192.168.1.6:8080"),
            opened.map(SourceDescriptor::baseUrl),
        )
    }

    @Test
    fun compatibilityRegistryPreservesCalibreSourceDescriptor() = runTest {
        val source = calibreSource("source-calibre-user", "http://192.168.1.5:8080")
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = InMemorySourceConfigStore(listOf(source)),
            booksDir = tempFolder.root,
        )

        val result = registry.openCatalog(source.id)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(source.id, (result as ReadflowResult.Success).value.descriptor.id)
    }

    @Test
    fun unknownAdapterIsDescribedAsDisabledAndFailsClosed() = runTest {
        val store = InMemorySourceConfigStore(
            listOf(
                PersistedBookSource(
                    id = "source-unknown",
                    kind = "THIRD_PARTY",
                    name = "Unknown",
                    baseUrl = "https://example.com/catalog",
                    enabled = true,
                    adapterId = "missing-adapter",
                    configJson = "{}",
                ),
            ),
        )
        val registry = DefaultSourceRegistry(
            settings = FakeSettingsRepository(calibreUrl = null),
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            sourceAdapters = DefaultSourceAdapterRegistry(emptySet()),
        )

        assertEquals(false, registry.observeSources().first().single().enabled)
        assertTrue(registry.openCatalog("source-unknown") is ReadflowResult.Failure)
    }

    @Test
    fun legacyCalibreImportOnlyCreatesTheBuiltinOnce() = runTest {
        val settings = FakeSettingsRepository(calibreUrl = "http://192.168.1.5:8080")
        val store = InMemorySourceConfigStore()
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
        )

        registry.observeSources().first()
        registry.observeSources().first()
        assertEquals(1, store.upsertCalls)

        settings.calibreBaseUrl.value = "http://192.168.1.6:8080"
        registry.observeSources().first()
        registry.observeSources().first()

        val builtin = store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
        assertEquals(1, store.upsertCalls)
        assertEquals("http://192.168.1.5:8080", builtin?.baseUrl)
        assertEquals(calibreSourceConfigJson("http://192.168.1.5:8080"), builtin?.configJson)
        assertEquals("http://192.168.1.5:8080", settings.calibreBaseUrl.value)
    }

    @Test
    fun staleLegacySettingCannotOverwriteBuiltinConfigLibraryOrCredentials() = runTest {
        val builtinUrl = "http://192.168.1.5:8080"
        val staleLegacyUrl = "http://192.168.1.6:8080"
        val libraryId = "library-with-spaces"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val builtin = calibreSource(BUILTIN_CALIBRE_SOURCE_ID, builtinUrl).copy(
            configJson = calibreSourceConfigJson(builtinUrl, libraryId),
        )
        val settings = FakeSettingsRepository(calibreUrl = staleLegacyUrl)
        val store = InMemorySourceConfigStore(listOf(builtin))
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(builtinUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            calibreCatalogFactory = { FakeCatalog(it) },
        )

        val opened = registry.openCatalog(BUILTIN_CALIBRE_SOURCE_ID)

        assertTrue(opened is ReadflowResult.Success)
        assertEquals(builtin, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID))
        assertEquals(expectedCredentials, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
        assertEquals(builtinUrl, settings.calibreBaseUrl.value)
    }

    @Test
    fun builtinRepairsMissingOrInvalidLegacyMirror() = runTest {
        val builtinUrl = "http://192.168.1.5:8080"
        listOf<String?>(null, "not-a-calibre-url").forEach { legacyUrl ->
            val settings = FakeSettingsRepository(calibreUrl = legacyUrl)
            val store = InMemorySourceConfigStore(
                listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, builtinUrl)),
            )
            val registry = DefaultSourceRegistry(
                settings = settings,
                sourceConfigStore = store,
                booksDir = tempFolder.root,
            )

            registry.observeSources().first()

            assertEquals(builtinUrl, settings.calibreBaseUrl.value)
            assertEquals(builtinUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        }
    }

    @Test
    fun verifiedBuiltinEndpointUpdatePreservesLibraryAndSameHostCredentials() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val libraryId = "main-library"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = servedUrl)
        val store = InMemorySourceConfigStore(
            listOf(
                calibreSource(BUILTIN_CALIBRE_SOURCE_ID, servedUrl).copy(
                    configJson = calibreSourceConfigJson(servedUrl, libraryId),
                ),
            ),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(servedUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        )

        val result = registry.persistVerifiedCalibreEndpoint(directUrl)

        assertTrue(result is ReadflowResult.Success)
        val updated = store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)
        assertEquals(directUrl, updated?.baseUrl)
        assertEquals(calibreSourceConfigJson(directUrl, libraryId), updated?.configJson)
        assertEquals(directUrl, settings.calibreBaseUrl.value)
        assertEquals(expectedCredentials, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun verifiedEndpointRetryReconcilesWhenDescriptorAlreadyMatchesTarget() = runTest {
        val oldUrl = "https://reader.tailnet.ts.net/opds"
        val newUrl = "http://reader.tailnet.ts.net:8080/opds"
        val oldScope = calibreCredentialScopeForRequestUrl(oldUrl)
        val newScope = calibreCredentialScopeForRequestUrl(newUrl)
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failNextFinalReconcile = false
        val credentials = InMemorySourceCredentialStore(
            reconcileOutcome = { sourceId, binding, observed ->
                if (
                    sourceId == BUILTIN_CALIBRE_SOURCE_ID &&
                    binding == DescriptorBinding.Calibre(newScope) &&
                    failNextFinalReconcile
                ) {
                    failNextFinalReconcile = false
                    CredentialMutationOutcome.Failed(
                        observed = observed,
                        cause = IllegalStateException("final credential reconcile failed"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(BUILTIN_CALIBRE_SOURCE_ID, oldScope, expectedCredentials)
            failNextFinalReconcile = true
        }
        val settings = FakeSettingsRepository(calibreUrl = oldUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, oldUrl)),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        )

        val first = registry.persistVerifiedCalibreEndpoint(newUrl)

        assertTrue("failed final credential reconcile must be reported", first is ReadflowResult.Failure)
        assertEquals(
            "the verified descriptor remains authoritative after finalization fails",
            newUrl,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl,
        )
        assertTrue(
            "failed final reconcile must retain the activation intent",
            credentials.snapshot(BUILTIN_CALIBRE_SOURCE_ID)?.pending is PendingCredentialMutation.Activate,
        )
        assertEquals(expectedCredentials, credentials.get(BUILTIN_CALIBRE_SOURCE_ID, oldScope))
        assertEquals(null, credentials.get(BUILTIN_CALIBRE_SOURCE_ID, newScope))

        val retried = registry.persistVerifiedCalibreEndpoint(newUrl)

        assertTrue("retry at the already-persisted endpoint must succeed", retried is ReadflowResult.Success)
        assertEquals(null, credentials.snapshot(BUILTIN_CALIBRE_SOURCE_ID)?.pending)
        assertEquals(
            "retry must activate only the descriptor's current origin",
            setOf(newScope),
            credentials.snapshot(BUILTIN_CALIBRE_SOURCE_ID)?.active?.scopes,
        )
        assertEquals(null, credentials.get(BUILTIN_CALIBRE_SOURCE_ID, oldScope))
        assertEquals(expectedCredentials, credentials.get(BUILTIN_CALIBRE_SOURCE_ID, newScope))
        assertTrue(
            "the already-matching retry must still reconcile the pending activation",
            credentials.reconcileBindings.count { it == DescriptorBinding.Calibre(newScope) } >= 2,
        )
    }

    @Test
    fun verifiedEndpointIndeterminateFinalReconcileKeepsCommittedDescriptor() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failFinalReconcile = false
        val credentials = InMemorySourceCredentialStore(
            afterReconcileOutcome = { _, binding, committed ->
                if (
                    binding == DescriptorBinding.Calibre(calibreCredentialScopeForRequestUrl(directUrl)) &&
                    failFinalReconcile
                ) {
                    failFinalReconcile = false
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("final credential reconcile outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(servedUrl),
                expectedCredentials,
            )
            failFinalReconcile = true
        }
        val settings = FakeSettingsRepository(calibreUrl = servedUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, servedUrl)),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        )

        registry.persistVerifiedCalibreEndpoint(directUrl)

        assertEquals(
            "indeterminate final reconcile must not roll back the verified descriptor",
            directUrl,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl,
        )
        assertEquals(directUrl, settings.calibreBaseUrl.value)
        assertEquals(expectedCredentials, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun verifiedEndpointIndeterminateCredentialClearReconcileKeepsCommittedDescriptor() = runTest {
        val oldUrl = "https://old.tailnet.ts.net/opds"
        val newUrl = "https://new.tailnet.ts.net/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        var failCredentialReconcile = false
        val credentials = InMemorySourceCredentialStore(
            afterReconcileOutcome = { _, binding, committed ->
                if (
                    binding == DescriptorBinding.Calibre(calibreCredentialScopeForRequestUrl(newUrl)) &&
                    failCredentialReconcile
                ) {
                    failCredentialReconcile = false
                    CredentialMutationOutcome.Indeterminate(
                        observed = committed,
                        cause = IllegalStateException("credential clear reconcile outcome is indeterminate"),
                    )
                } else {
                    null
                }
            },
        ).apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(oldUrl),
                expectedCredentials,
            )
            failCredentialReconcile = true
        }
        val settings = FakeSettingsRepository(calibreUrl = oldUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, oldUrl)),
        )
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        registry.persistVerifiedCalibreEndpoint(newUrl)

        assertEquals(
            "indeterminate credential clear reconcile must not roll back the verified descriptor",
            newUrl,
            store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl,
        )
        assertEquals(newUrl, settings.calibreBaseUrl.value)
        assertEquals(null, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun verifiedBuiltinFallbackClearsCredentialsWithoutPositiveVpnEvidence() = runTest {
        val servedUrl = "https://reader.tailnet.ts.net/opds"
        val directUrl = "http://reader.tailnet.ts.net:8080/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = servedUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, servedUrl)),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(servedUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = UnknownCalibreNetworkSnapshotProvider,
        )

        val result = registry.persistVerifiedCalibreEndpoint(directUrl)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(directUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(
            null,
            credentials.get(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(servedUrl),
            ),
        )
    }

    @Test
    fun verifiedBuiltinEndpointDoesNotForwardCredentialsToDifferentSameHostPort() = runTest {
        val oldUrl = "https://reader.tailnet.ts.net"
        val newUrl = "http://reader.tailnet.ts.net:8081"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = oldUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, oldUrl)),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(oldUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        )

        val result = registry.persistVerifiedCalibreEndpoint(newUrl)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(newUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(null, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun verifiedBuiltinEndpointUpdateDoesNotForwardCredentialsToAnotherHost() = runTest {
        val oldUrl = "https://old.tailnet.ts.net/opds"
        val newUrl = "https://new.tailnet.ts.net/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = oldUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, oldUrl)),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(oldUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.persistVerifiedCalibreEndpoint(newUrl)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(newUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(null, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun tailnetHttpCredentialsFailClosedWithoutAppVpnEvidence() = runTest {
        val expectedCredentials = SourceCredentials("reader", "secret")
        val cases = listOf(
            Triple(
                "100.x HTTP with unknown network",
                "http://100.64.0.1:8080",
                CalibreNetworkSnapshot.Unknown,
            ),
            Triple(
                "100.x HTTP without app VPN",
                "http://100.64.0.1:8080",
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = false, internetValidated = true),
            ),
            Triple(
                "MagicDNS HTTP with unknown network",
                "http://reader.tailnet.ts.net:8080",
                CalibreNetworkSnapshot.Unknown,
            ),
            Triple(
                "MagicDNS HTTP without app VPN",
                "http://reader.tailnet.ts.net:8080",
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = false, internetValidated = true),
            ),
        )

        val observations = cases.mapIndexed { index, (label, baseUrl, network) ->
            val sourceId = "tailnet-http-$index"
            val credentials = InMemorySourceCredentialStore().apply {
                put(
                    sourceId,
                    calibreCredentialScopeForRequestUrl(baseUrl),
                    expectedCredentials,
                )
            }
            val registry = DefaultSourceRegistry(
                settings = FakeSettingsRepository(calibreUrl = null),
                sourceConfigStore = InMemorySourceConfigStore(listOf(calibreSource(sourceId, baseUrl))),
                booksDir = tempFolder.root,
                credentialStore = credentials,
                networkSnapshotProvider = CalibreNetworkSnapshotProvider { network },
            )

            Triple(label, credentials, runCatching { registry.sourceCredentials(sourceId) })
        }

        assertEquals(
            "tailnet HTTP credential reads without app VPN evidence must not query credential storage",
            List(observations.size) { 0 },
            observations.map { it.second.getCalls },
        )
        assertTrue(
            "tailnet HTTP credentials without app VPN evidence must be unavailable: " +
                observations
                    .filterNot { it.third.isFailure || it.third.getOrNull() == null }
                    .joinToString { it.first },
            observations.all { it.third.isFailure || it.third.getOrNull() == null },
        )
    }

    @Test
    fun credentialsRemainReadableForTrustedAndNonTailnetEndpoints() = runTest {
        val expectedCredentials = SourceCredentials("reader", "secret")
        val appVpn = CalibreNetworkSnapshot.Active(
            vpnAppliesToApp = true,
            internetValidated = true,
        )
        val noAppVpn = CalibreNetworkSnapshot.Active(
            vpnAppliesToApp = false,
            internetValidated = true,
        )
        val cases = listOf(
            Triple("100.x HTTP with app VPN", "http://100.64.0.1:8080", appVpn),
            Triple("MagicDNS HTTP with app VPN", "http://reader.tailnet.ts.net:8080", appVpn),
            Triple("LAN HTTP with unknown network", "http://192.168.1.5:8080", CalibreNetworkSnapshot.Unknown),
            Triple("LAN HTTP without app VPN", "http://192.168.1.5:8080", noAppVpn),
            Triple("HTTPS with unknown network", "https://reader.example.com", CalibreNetworkSnapshot.Unknown),
            Triple("HTTPS without app VPN", "https://reader.example.com", noAppVpn),
        )

        val observations = cases.mapIndexed { index, (label, baseUrl, network) ->
            val sourceId = "readable-credentials-$index"
            val credentials = InMemorySourceCredentialStore().apply {
                put(
                    sourceId,
                    calibreCredentialScopeForRequestUrl(baseUrl),
                    expectedCredentials,
                )
            }
            val registry = DefaultSourceRegistry(
                settings = FakeSettingsRepository(calibreUrl = null),
                sourceConfigStore = InMemorySourceConfigStore(listOf(calibreSource(sourceId, baseUrl))),
                booksDir = tempFolder.root,
                credentialStore = credentials,
                networkSnapshotProvider = CalibreNetworkSnapshotProvider { network },
            )

            Triple(label, credentials, runCatching { registry.sourceCredentials(sourceId) })
        }

        observations.forEach { (label, credentials, read) ->
            assertTrue("$label credential read must not throw: ${read.exceptionOrNull()}", read.isSuccess)
            assertEquals("$label credentials", expectedCredentials, read.getOrNull())
            assertEquals("$label credential store reads", 1, credentials.getCalls)
        }
    }

    @Test
    fun equivalentIpv6SpellingDoesNotClearCredentials() = runTest {
        val compressedUrl = "http://[fd7a:115c:a1e0::1]:8080"
        val expandedUrl = "http://[fd7a:115c:a1e0:0:0:0:0:1]:8080"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(calibreUrl = compressedUrl)
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, compressedUrl)),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(compressedUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
            networkSnapshotProvider = CalibreNetworkSnapshotProvider {
                CalibreNetworkSnapshot.Active(vpnAppliesToApp = true, internetValidated = true)
            },
        )

        val result = registry.persistVerifiedCalibreEndpoint(expandedUrl)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(expandedUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(expectedCredentials, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    @Test
    fun legacyMirrorFailureAfterCrossHostUpdateKeepsRoomAndCredentialsAligned() = runTest {
        val oldUrl = "https://old.tailnet.ts.net/opds"
        val newUrl = "https://new.tailnet.ts.net/opds"
        val expectedCredentials = SourceCredentials("reader", "secret")
        val settings = FakeSettingsRepository(
            calibreUrl = oldUrl,
            failAfterSettingCalibreUrl = newUrl,
        )
        val store = InMemorySourceConfigStore(
            listOf(calibreSource(BUILTIN_CALIBRE_SOURCE_ID, oldUrl)),
        )
        val credentials = InMemorySourceCredentialStore().apply {
            put(
                BUILTIN_CALIBRE_SOURCE_ID,
                calibreCredentialScopeForRequestUrl(oldUrl),
                expectedCredentials,
            )
        }
        val registry = DefaultSourceRegistry(
            settings = settings,
            sourceConfigStore = store,
            booksDir = tempFolder.root,
            credentialStore = credentials,
        )

        val result = registry.persistVerifiedCalibreEndpoint(newUrl)

        assertTrue(result is ReadflowResult.Success)
        assertEquals(newUrl, store.getUserSource(BUILTIN_CALIBRE_SOURCE_ID)?.baseUrl)
        assertEquals(newUrl, settings.calibreBaseUrl.value)
        assertEquals(null, registry.sourceCredentials(BUILTIN_CALIBRE_SOURCE_ID))
    }

    private class FakeCatalog(
        kind: SourceKind,
        baseUrl: String,
    ) : OnlineBookCatalog {
        constructor(descriptor: SourceDescriptor) : this(
            kind = checkNotNull(descriptor.kind),
            baseUrl = descriptor.baseUrl,
        ) {
            sourceDescriptor = descriptor
        }

        private var sourceDescriptor = SourceDescriptor(
            id = "fake",
            kind = kind,
            name = "fake",
            baseUrl = baseUrl,
        )
        override val descriptor: SourceDescriptor
            get() = sourceDescriptor

        override suspend fun search(
            query: String,
            filter: OnlineCatalogFilter,
            offset: Int,
            limit: Int,
        ) = ReadflowResult.Success(emptyList<OnlineCatalogEntry>())

        override suspend fun download(entry: OnlineCatalogEntry) =
            ReadflowResult.Success(
                BookMeta(id = "d", title = "t", author = "a", format = BookFormat.EPUB),
            )

    }

    private class InMemorySourceConfigStore(
        initial: List<PersistedBookSource> = emptyList(),
        private val beforeNextSortOrder: (suspend () -> Unit)? = null,
        private val beforeUpsert: (suspend (PersistedBookSource) -> Unit)? = null,
        private val afterUpsert: (suspend (PersistedBookSource) -> Unit)? = null,
    ) : SourceConfigStore {
        private val sources = MutableStateFlow(initial)
        var upsertCalls = 0
            private set

        override fun observeUserSources(): Flow<List<PersistedBookSource>> = sources

        override suspend fun getUserSource(id: String): PersistedBookSource? =
            sources.value.firstOrNull { it.id == id }

        override suspend fun upsertUserSource(source: PersistedBookSource) {
            upsertCalls += 1
            beforeUpsert?.invoke(source)
            sources.value = sources.value.filterNot { it.id == source.id } + source
            afterUpsert?.invoke(source)
        }

        override suspend fun deleteUserSource(id: String) {
            sources.value = sources.value.filterNot { it.id == id }
        }

        override suspend fun nextSortOrder(): Int {
            beforeNextSortOrder?.invoke()
            return (sources.value.maxOfOrNull { it.sortOrder } ?: -1) + 1
        }
    }

    private class InMemorySourceCredentialStore(
        private val prepareOutcome: (
            (
                String,
                Long,
                PendingCredentialMutation,
                CredentialTxnSnapshot?,
            ) -> CredentialMutationOutcome?
        )? = null,
        private val reconcileOutcome: (
            (String, DescriptorBinding, CredentialTxnSnapshot?) -> CredentialMutationOutcome?
        )? = null,
        private val afterPrepareOutcome: (
            (String, PendingCredentialMutation, CredentialTxnSnapshot?) -> CredentialMutationOutcome?
        )? = null,
        private val afterReconcileOutcome: (
            (String, DescriptorBinding, CredentialTxnSnapshot?) -> CredentialMutationOutcome?
        )? = null,
        private val snapshotFailure: ((String) -> Throwable?)? = null,
    ) : SourceCredentialStore {
        private val snapshots = mutableMapOf<String, CredentialTxnSnapshot>()
        private val purgedSnapshotFailures = mutableSetOf<String>()
        var getCalls = 0
            private set
        var prepareCalls = 0
            private set
        var legacyRemoveCalls = 0
            private set
        val reconcileBindings = mutableListOf<DescriptorBinding>()

        override fun get(sourceId: String, scope: String): SourceCredentials? {
            getCalls += 1
            return activeCredentialsForScope(snapshots[sourceId], scope)
        }

        override fun snapshot(sourceId: String): CredentialTxnSnapshot? {
            if (sourceId !in purgedSnapshotFailures) {
                snapshotFailure?.invoke(sourceId)?.let { throw it }
            }
            return snapshots[sourceId]
        }

        override fun put(sourceId: String, scope: String, credentials: SourceCredentials) =
            put(sourceId, setOf(scope), credentials)

        override fun put(sourceId: String, scopes: Set<String>, credentials: SourceCredentials) {
            if (scopes.isEmpty() || credentials.isEmpty) {
                remove(sourceId)
                return
            }
            val revision = (snapshots[sourceId]?.revision ?: 0L) + 1L
            snapshots[sourceId] = CredentialTxnSnapshot(
                revision = revision,
                active = CredentialGrant(scopes, credentials),
                pending = null,
            )
        }

        override fun remove(sourceId: String) {
            legacyRemoveCalls += 1
            purgedSnapshotFailures += sourceId
            snapshots.remove(sourceId)
        }

        override fun prepare(
            sourceId: String,
            expectedRevision: Long,
            pending: PendingCredentialMutation,
        ): CredentialMutationOutcome {
            prepareCalls += 1
            val current = snapshots[sourceId]
            prepareOutcome?.invoke(sourceId, expectedRevision, pending, current)?.let { return it }
            val plan = planCredentialPrepare(current, expectedRevision, pending)
            val outcome = applyPlan(sourceId, plan)
            if (outcome is CredentialMutationOutcome.Committed) {
                afterPrepareOutcome?.invoke(sourceId, pending, outcome.snapshot)?.let { return it }
            }
            return outcome
        }

        override fun reconcile(
            sourceId: String,
            binding: DescriptorBinding,
        ): CredentialMutationOutcome {
            reconcileBindings += binding
            val current = snapshots[sourceId]
            reconcileOutcome?.invoke(sourceId, binding, current)?.let { return it }
            val plan = planCredentialReconcile(current, binding)
            val outcome = applyPlan(sourceId, plan)
            if (outcome is CredentialMutationOutcome.Committed) {
                afterReconcileOutcome?.invoke(sourceId, binding, outcome.snapshot)?.let { return it }
            }
            return outcome
        }

        override fun put(
            sourceId: String,
            scopes: Set<String>,
            credentials: SourceCredentials,
            expectedRevision: Long,
        ): CredentialMutationOutcome {
            val current = snapshots[sourceId]
            if (expectedRevision != (current?.revision ?: 0L)) {
                return CredentialMutationOutcome.Conflict(current)
            }
            val active = if (scopes.isEmpty() || credentials.isEmpty) {
                null
            } else {
                CredentialGrant(scopes, credentials)
            }
            val next = CredentialTxnSnapshot(
                revision = expectedRevision + 1L,
                active = active,
                pending = null,
            )
            snapshots[sourceId] = next
            return CredentialMutationOutcome.Committed(next)
        }

        override fun remove(
            sourceId: String,
            expectedRevision: Long,
        ): CredentialMutationOutcome {
            val current = snapshots[sourceId]
            if (expectedRevision != (current?.revision ?: 0L)) {
                return CredentialMutationOutcome.Conflict(current)
            }
            snapshots.remove(sourceId)
            return CredentialMutationOutcome.Committed(null)
        }

        override fun sourceIdsWithPending(): Set<String> = snapshots
            .filterValues { it.pending != null }
            .keys

        private fun applyPlan(
            sourceId: String,
            plan: CredentialMutationPlan,
        ): CredentialMutationOutcome = when (plan) {
            is CredentialMutationPlan.Write -> {
                snapshots[sourceId] = plan.snapshot
                CredentialMutationOutcome.Committed(plan.snapshot)
            }
            is CredentialMutationPlan.NoChange -> CredentialMutationOutcome.Committed(plan.snapshot)
            is CredentialMutationPlan.Conflict -> CredentialMutationOutcome.Conflict(plan.observed)
            CredentialMutationPlan.Delete -> {
                snapshots.remove(sourceId)
                CredentialMutationOutcome.Committed(null)
            }
        }
    }

    private fun calibreSource(id: String, baseUrl: String) = PersistedBookSource(
        id = id,
        kind = "CALIBRE",
        name = id,
        baseUrl = baseUrl,
        enabled = true,
        adapterId = SourceAdapterIds.CALIBRE,
        configVersion = 1,
        configJson = calibreSourceConfigJson(baseUrl),
    )

    private class FakeSettingsRepository(
        calibreUrl: String? = "http://192.168.1.5:8080",
        failAfterSettingCalibreUrl: String? = null,
    ) : SettingsRepository {
        private var failAfterSettingCalibreUrl = failAfterSettingCalibreUrl
        override val calibreBaseUrl = MutableStateFlow(calibreUrl)
        override val fontSize = MutableStateFlow(18)
        override val lineSpacing = MutableStateFlow(1.75f)
        override val readingMode = MutableStateFlow(ReaderReadingMode.SCROLL)
        override val themeMode = MutableStateFlow(ThemeMode.SYSTEM)
        override val deviceId = MutableStateFlow("device")
        override val engineOverrides = MutableStateFlow(emptyMap<BookFormat, String>())
        override val useSourceHanFont = MutableStateFlow(true)
        override val txtEncoding = MutableStateFlow(TxtEncoding.AUTO)
        override val fontChoice = MutableStateFlow<FontChoice>(FontChoice.SourceHan)
        override val readerGuideShown = MutableStateFlow(true)
        override val pageFlipStyle = MutableStateFlow(dev.readflow.core.model.PageFlipStyle.SLIDE)
        override suspend fun setCalibreBaseUrl(url: String) {
            calibreBaseUrl.value = url
            if (url == failAfterSettingCalibreUrl) {
                failAfterSettingCalibreUrl = null
                error("setting persistence failed after mutation")
            }
        }
        override suspend fun clearCalibreBaseUrl() {
            calibreBaseUrl.value = null
        }
        override suspend fun setFontSize(size: Int) = Unit
        override suspend fun setLineSpacing(multiplier: Float) = Unit
        override suspend fun setReadingMode(mode: ReaderReadingMode) = Unit
        override suspend fun setThemeMode(mode: ThemeMode) = Unit
        override suspend fun setEngineOverride(format: BookFormat, engineId: String?) = Unit
        override suspend fun setUseSourceHanFont(enabled: Boolean) = Unit
        override suspend fun setTxtEncoding(encoding: TxtEncoding) = Unit
        override suspend fun setFontChoice(choice: FontChoice) = Unit
        override suspend fun setReaderGuideShown(shown: Boolean) = Unit
        override suspend fun setPageFlipStyle(style: dev.readflow.core.model.PageFlipStyle) = Unit
    }
}
