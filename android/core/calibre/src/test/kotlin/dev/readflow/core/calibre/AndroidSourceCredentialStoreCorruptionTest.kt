package dev.readflow.core.calibre

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AndroidSourceCredentialStoreCorruptionTest {

    private lateinit var preferences: SharedPreferences
    private lateinit var store: AndroidSourceCredentialStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
        assertTrue(preferences.edit().clear().commit())
        store = AndroidSourceCredentialStore(context)
    }

    @After
    fun tearDown() {
        assertTrue(preferences.edit().clear().commit())
    }

    @Test
    fun `get fails closed when encrypted journal payload is corrupt`() {
        writeCorruptPayload(GET_SOURCE_ID)

        assertNull(store.get(GET_SOURCE_ID, CREDENTIAL_SCOPE))
    }

    @Test
    fun `sourceIdsWithPending isolates corrupt journal entries`() {
        writeCorruptPayload(PENDING_SOURCE_ID)
        writeCorruptPayload(SECOND_PENDING_SOURCE_ID)

        assertEquals(emptySet<String>(), store.sourceIdsWithPending())
    }

    @Test
    fun `legacy remove purges corrupt journal without decrypting it`() {
        writeCorruptPayload(REMOVE_SOURCE_ID)

        store.remove(REMOVE_SOURCE_ID)

        assertFalse(preferences.contains(preferenceKey(REMOVE_SOURCE_ID)))
        assertNull(store.snapshot(REMOVE_SOURCE_ID))
    }

    private fun writeCorruptPayload(sourceId: String) {
        assertTrue(
            preferences.edit()
                .putString(preferenceKey(sourceId), CORRUPT_PAYLOAD)
                .commit(),
        )
    }

    private fun preferenceKey(sourceId: String): String = "$SOURCE_KEY_PREFIX$sourceId"

    private companion object {
        const val PREFERENCES_NAME = "source_credentials_v1"
        const val SOURCE_KEY_PREFIX = "source."
        const val CORRUPT_PAYLOAD = "not-an-encrypted-credential-journal"
        const val CREDENTIAL_SCOPE = "https://reader.example"
        const val GET_SOURCE_ID = "corrupt-get"
        const val PENDING_SOURCE_ID = "corrupt-pending"
        const val SECOND_PENDING_SOURCE_ID = "corrupt-pending-second"
        const val REMOVE_SOURCE_ID = "corrupt-remove"
    }
}
