package dev.readflow.engine

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class AndroidEngineStateStoreTest {

    @TempDir
    lateinit var cacheDir: File

    @Test
    fun `saves loads and evicts engine state under cache dir`() = runTest {
        val store = AndroidEngineStateStore(cacheDir)
        val state = byteArrayOf(1, 2, 3, 4)

        store.save("book-1", state)

        val expected = File(cacheDir, "engine-state/book-1.bin")
        assertTrue(expected.isFile)
        assertArrayEquals(state, expected.readBytes())
        assertArrayEquals(state, store.load("book-1"))

        store.evict("book-1")

        assertFalse(expected.exists())
        assertNull(store.load("book-1"))
    }

    @Test
    fun `normalizes book ids so cache files cannot escape engine state directory`() = runTest {
        val store = AndroidEngineStateStore(cacheDir)
        val state = byteArrayOf(9, 8, 7)

        store.save("../nested/book", state)

        val root = File(cacheDir, "engine-state").canonicalFile
        val files = root.listFiles()?.toList().orEmpty()
        assertEquals(1, files.size)
        assertTrue(files.single().canonicalPath.startsWith(root.path + File.separator))
        assertArrayEquals(state, store.load("../nested/book"))
        assertFalse(File(cacheDir, "../nested/book.bin").canonicalFile.exists())
    }

    @Test
    fun `normalization keeps distinct unsafe book ids from colliding`() = runTest {
        val store = AndroidEngineStateStore(cacheDir)
        val slashState = byteArrayOf(1)
        val questionState = byteArrayOf(2)

        store.save("a/b", slashState)
        store.save("a?b", questionState)

        assertArrayEquals(slashState, store.load("a/b"))
        assertArrayEquals(questionState, store.load("a?b"))

        val root = File(cacheDir, "engine-state")
        assertEquals(2, root.listFiles()?.size)
    }
}
