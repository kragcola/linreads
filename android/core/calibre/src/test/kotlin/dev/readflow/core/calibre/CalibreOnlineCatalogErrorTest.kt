package dev.readflow.core.calibre

import dev.readflow.core.model.ReadflowError
import dev.readflow.core.model.ReadflowResult
import dev.readflow.extensions.api.SourceAdapterIds
import dev.readflow.extensions.api.SourceDescriptor
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibreOnlineCatalogErrorTest {

    @Test
    fun unauthorizedSearchReturnsAnActionableAuthenticationError() = runTest {
        val baseUrl = "http://192.168.1.5:8080"
        val client = CalibreClient(
            baseUrl = baseUrl,
            username = "",
            password = "",
            libraryId = "calibre-library",
            http = defaultCalibreHttpClient(
                engine = MockEngine { respondError(HttpStatusCode.Unauthorized) },
                allowedBaseUrl = baseUrl,
            ),
        )
        val catalog = CalibreOnlineCatalog(
            client = client,
            booksDir = null,
            descriptor = SourceDescriptor(
                id = "source-calibre",
                adapterId = SourceAdapterIds.CALIBRE,
                name = "Calibre",
                configVersion = 1,
                configJson = calibreSourceConfigJson(baseUrl),
                baseUrl = baseUrl,
            ),
        )

        val result = catalog.search("")

        assertTrue(result is ReadflowResult.Failure)
        val error = (result as ReadflowResult.Failure).error
        assertEquals(ReadflowError.Kind.AUTH, error.kind)
        assertEquals(401, error.code)
        assertTrue(error.message.contains("认证失败"))
        assertTrue(error.message.contains("填写或检查用户名和密码"))
        assertFalse(error.message.contains("Client request"))
        catalog.close()
    }
}
