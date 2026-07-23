package dev.readflow.extensions.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteBookIdsTest {

    @Test
    fun stableRemoteBookIdIsDeterministicAndFilesystemSafe() {
        val a = stableRemoteBookId("source/with spaces!", "remote@id/1")
        val b = stableRemoteBookId("source/with spaces!", "remote@id/1")
        assertEquals(a, b)
        assertTrue(a.startsWith("remote-"))
        assertFalse(a.contains(" "))
        assertFalse(a.contains("@"))
        assertFalse(a.contains("/"))
        assertTrue(a.matches(Regex("remote-[a-zA-Z0-9_-]+-[a-zA-Z0-9_-]+-[0-9a-f]{8}")))
    }

    @Test
    fun longRemoteIdsWithSamePrefixDoNotCollideAfterTruncation() {
        val prefix = "x".repeat(50)
        val id1 = stableRemoteBookId("src", prefix + "AAA-unique-tail-1")
        val id2 = stableRemoteBookId("src", prefix + "BBB-unique-tail-2")
        assertNotEquals(id1, id2)
        // Sanitized remote segment alone would truncate to the same 40 chars without the hash.
        val safe1 = id1.removePrefix("remote-").substringAfter("-").substringBeforeLast("-")
        val safe2 = id2.removePrefix("remote-").substringAfter("-").substringBeforeLast("-")
        assertEquals(safe1, safe2)
        val hash1 = id1.substringAfterLast("-")
        val hash2 = id2.substringAfterLast("-")
        assertNotEquals(hash1, hash2)
        assertEquals(8, hash1.length)
        assertEquals(8, hash2.length)
    }

    @Test
    fun differentSourcesWithSameRemoteIdDoNotCollide() {
        val a = stableRemoteBookId("source-a", "book-1")
        val b = stableRemoteBookId("source-b", "book-1")
        assertNotEquals(a, b)
    }
}
