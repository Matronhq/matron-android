package chat.matron.android.journal

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LegacyStoreFileMoveTest {
    private lateinit var dir: File
    private lateinit var legacy: File
    private lateinit var target: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("legacy-move").toFile()
        legacy = File(dir, "alice.sqlite")
        target = File(dir, "alice.d7.sqlite")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun File.side(suffix: String) = File(path + suffix)

    @Test
    fun noOldFile_opensTheNewName() {
        assertEquals(target, LegacyStoreFileMove.adopt(legacy, target))
        assertFalse(target.exists())
    }

    @Test
    fun movesTheDatabaseWithItsSideFiles() {
        legacy.writeText("main")
        legacy.side("-wal").writeText("wal")
        legacy.side("-shm").writeText("shm")

        assertEquals(target, LegacyStoreFileMove.adopt(legacy, target))

        assertEquals("main", target.readText())
        assertEquals("wal", target.side("-wal").readText())
        assertEquals("shm", target.side("-shm").readText())
        assertFalse(legacy.exists())
        assertFalse(legacy.side("-wal").exists())
        assertFalse(legacy.side("-shm").exists())
    }

    @Test
    fun movesTheMainFileLast() {
        legacy.writeText("main")
        legacy.side("-wal").writeText("wal")
        legacy.side("-journal").writeText("journal")
        val order = mutableListOf<String>()
        LegacyStoreFileMove.adopt(legacy, target, move = { from, to -> order += from.name; from.renameTo(to) })
        assertEquals("alice.sqlite", order.last())
        assertEquals(listOf("alice.sqlite-wal", "alice.sqlite-journal", "alice.sqlite"), order)
    }

    @Test
    fun aDatabaseAlreadyAtTheNewName_isLeftAlone() {
        legacy.writeText("old")
        target.writeText("new")
        assertEquals(target, LegacyStoreFileMove.adopt(legacy, target))
        assertEquals("old", legacy.readText())
        assertEquals("new", target.readText())
    }

    @Test
    fun finishesAnInterruptedMove() {
        // The last launch moved the -wal, then died before the main file.
        legacy.writeText("main")
        target.side("-wal").writeText("wal")
        assertEquals(target, LegacyStoreFileMove.adopt(legacy, target))
        assertEquals("main", target.readText())
        assertEquals("wal", target.side("-wal").readText())
    }

    @Test
    fun aFailedMove_putsTheSideFilesBack_andOpensTheOldFile() {
        legacy.writeText("main")
        legacy.side("-wal").writeText("wal")
        legacy.side("-shm").writeText("shm")
        val result = LegacyStoreFileMove.adopt(legacy, target, move = { from, to ->
            if (from == legacy) throw IOException("disk full")
            check(from.renameTo(to))
        })
        assertEquals(legacy, result)
        assertEquals("main", legacy.readText())
        assertEquals("wal", legacy.side("-wal").readText())
        assertEquals("shm", legacy.side("-shm").readText())
        assertFalse(target.exists())
        assertFalse(target.side("-wal").exists())
    }

    @Test
    fun aSideFileThatCannotBePutBack_opensNeither() {
        legacy.writeText("main")
        legacy.side("-wal").writeText("wal")
        var moves = 0
        val result = LegacyStoreFileMove.adopt(legacy, target, move = { from, to ->
            moves += 1
            // The -wal moves, the main file fails, and the -wal cannot go back.
            if (moves == 1) check(from.renameTo(to)) else throw IOException("read-only")
        })
        assertNull("an in-memory database for this launch", result)
        // Both left as they are for the next launch to finish.
        assertTrue(legacy.exists())
        assertTrue(target.side("-wal").exists())
        assertEquals(target, LegacyStoreFileMove.adopt(legacy, target))
        assertEquals("wal", target.side("-wal").readText())
    }
}
