package chat.matron.android.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

/// Ported from matron-apple StoragePathsTests (the platform-agnostic
/// path-derivation half). The Context-based accessors are trivial wrappers over
/// `filesDir` and are covered on-device.
class StoragePathsTest {

    @Test
    fun cryptoStoreIsUnderBase() {
        val base = File("/tmp/test-base")
        assertEquals(File(base, "crypto-store"), StoragePaths.cryptoStore(base))
    }

    @Test
    fun searchDbIsUnderBase() {
        val base = File("/tmp/test-base")
        assertEquals(File(base, "matron-search.sqlite"), StoragePaths.searchDb(base))
    }

    @Test
    fun journalDbIsUnderBase() {
        val base = File("/tmp/test-base")
        assertEquals(File(base, "journal.sqlite"), StoragePaths.journalDb(base))
    }
}
