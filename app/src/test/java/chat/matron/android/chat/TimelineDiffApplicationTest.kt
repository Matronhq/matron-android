package chat.matron.android.chat

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/// Mirror of the in-memory snapshot machine a live timeline listener would run.
/// Kept as a test-only class so it can be driven without a real diff source.
/// Ported from matron-apple's `TimelineDiffApplicationTests`.
class SnapshotApplier {
    private val byID = mutableMapOf<String, TimelineItem>()
    private val order = mutableListOf<String>()

    sealed interface FakeDiff {
        data class Append(val values: List<TimelineItem>) : FakeDiff
        data object Clear : FakeDiff
        data class PushFront(val v: TimelineItem) : FakeDiff
        data class PushBack(val v: TimelineItem) : FakeDiff
        data object PopFront : FakeDiff
        data object PopBack : FakeDiff
        data class Insert(val i: Int, val v: TimelineItem) : FakeDiff
        data class Set(val i: Int, val v: TimelineItem) : FakeDiff
        data class Remove(val i: Int) : FakeDiff
        data class Truncate(val length: Int) : FakeDiff
        data class Reset(val values: List<TimelineItem>) : FakeDiff
    }

    fun apply(diffs: List<FakeDiff>) {
        for (d in diffs) when (d) {
            is FakeDiff.Append -> d.values.forEach { upsertAtEnd(it) }
            FakeDiff.Clear -> { byID.clear(); order.clear() }
            is FakeDiff.PushFront -> insert(d.v, 0)
            is FakeDiff.PushBack -> upsertAtEnd(d.v)
            FakeDiff.PopFront -> if (order.isNotEmpty()) byID.remove(order.removeAt(0))
            FakeDiff.PopBack -> if (order.isNotEmpty()) byID.remove(order.removeAt(order.size - 1))
            is FakeDiff.Insert -> insert(d.v, d.i)
            is FakeDiff.Set -> replace(d.i, d.v)
            is FakeDiff.Remove -> removeAt(d.i)
            is FakeDiff.Truncate -> truncate(d.length)
            is FakeDiff.Reset -> { byID.clear(); order.clear(); d.values.forEach { upsertAtEnd(it) } }
        }
    }

    val snapshot: List<TimelineItem> get() = order.mapNotNull { byID[it] }

    private fun upsertAtEnd(item: TimelineItem) {
        if (byID[item.id] == null) order.add(item.id)
        byID[item.id] = item
    }

    private fun insert(item: TimelineItem, index: Int) {
        val clamped = maxOf(0, minOf(index, order.size))
        if (byID[item.id] != null) order.removeAll { it == item.id }
        val target = minOf(clamped, order.size)
        order.add(target, item.id)
        byID[item.id] = item
    }

    private fun replace(index: Int, item: TimelineItem) {
        if (index !in order.indices) { upsertAtEnd(item); return }
        val oldID = order[index]
        if (oldID != item.id) {
            val existing = order.indexOf(item.id)
            if (existing >= 0) {
                order.removeAt(existing)
                val adjusted = if (existing < index) index - 1 else index
                byID.remove(oldID)
                order[adjusted] = item.id
            } else {
                byID.remove(oldID)
                order[index] = item.id
            }
        }
        byID[item.id] = item
    }

    private fun removeAt(index: Int) {
        if (index !in order.indices) return
        byID.remove(order.removeAt(index))
    }

    private fun truncate(length: Int) {
        if (length >= order.size) return
        val removed = order.subList(length, order.size).toList()
        while (order.size > length) order.removeAt(order.size - 1)
        removed.forEach { byID.remove(it) }
    }
}

private fun mkItem(id: String, body: String = "x") = TimelineItem(
    id = id,
    sender = "@a:s",
    timestamp = Instant.EPOCH,
    kind = TimelineItem.Kind.Text(body, null),
    isOwn = false,
)

class TimelineDiffApplicationTest {
    @Test fun appendAddsToEnd() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2")))))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun pushFrontAddsToHead() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"))), SnapshotApplier.FakeDiff.PushFront(mkItem("0"))))
        assertEquals(listOf("0", "1", "2"), a.snapshot.map { it.id })
    }

    @Test fun pushBackAddsToTail() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"))), SnapshotApplier.FakeDiff.PushBack(mkItem("2"))))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun popFrontRemovesHead() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"))), SnapshotApplier.FakeDiff.PopFront))
        assertEquals(listOf("2", "3"), a.snapshot.map { it.id })
    }

    @Test fun popFrontOnEmptyIsNoOp() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.PopFront))
        assertTrue(a.snapshot.isEmpty())
    }

    @Test fun popBackRemovesTail() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"))), SnapshotApplier.FakeDiff.PopBack))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun popBackOnEmptyIsNoOp() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.PopBack))
        assertTrue(a.snapshot.isEmpty())
    }

    @Test fun insertAtIndex() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("3"))), SnapshotApplier.FakeDiff.Insert(1, mkItem("2"))))
        assertEquals(listOf("1", "2", "3"), a.snapshot.map { it.id })
    }

    @Test fun insertOutOfBoundsClampsToEnd() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"))), SnapshotApplier.FakeDiff.Insert(99, mkItem("2"))))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun setReplacesInPlace() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1", "old"))), SnapshotApplier.FakeDiff.Set(0, mkItem("1", "new"))))
        assertEquals(1, a.snapshot.size)
        assertEquals(TimelineItem.Kind.Text("new", null), a.snapshot[0].kind)
    }

    @Test fun setWithDifferentIDSwapsTheKey() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"))), SnapshotApplier.FakeDiff.Set(0, mkItem("9"))))
        assertEquals(listOf("9", "2"), a.snapshot.map { it.id })
    }

    @Test fun removeAtIndex() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"))), SnapshotApplier.FakeDiff.Remove(1)))
        assertEquals(listOf("1", "3"), a.snapshot.map { it.id })
    }

    @Test fun truncateKeepsFirstN() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"), mkItem("4"))), SnapshotApplier.FakeDiff.Truncate(2)))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun truncateGreaterThanCountIsNoOp() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"))), SnapshotApplier.FakeDiff.Truncate(99)))
        assertEquals(listOf("1", "2"), a.snapshot.map { it.id })
    }

    @Test fun resetReplacesEverything() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"))), SnapshotApplier.FakeDiff.Reset(listOf(mkItem("9"), mkItem("10")))))
        assertEquals(listOf("9", "10"), a.snapshot.map { it.id })
    }

    @Test fun clearEmptiesSnapshot() {
        val a = SnapshotApplier()
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"))), SnapshotApplier.FakeDiff.Clear))
        assertTrue(a.snapshot.isEmpty())
    }

    @Test fun unknownEventTypePreserved() {
        val a = SnapshotApplier()
        val unk = TimelineItem("u1", "@a:s", Instant.EPOCH, TimelineItem.Kind.Unknown("m.room.encryption"), false)
        a.apply(listOf(SnapshotApplier.FakeDiff.Append(listOf(unk))))
        assertEquals(1, a.snapshot.size)
        assertEquals(TimelineItem.Kind.Unknown("m.room.encryption"), a.snapshot[0].kind)
    }

    @Test fun localEchoReplacedByRemotePreservesPosition() {
        val a = SnapshotApplier()
        a.apply(listOf(
            SnapshotApplier.FakeDiff.Append(listOf(mkItem("tx:abc", "hello"), mkItem("evt:1", "later"))),
            SnapshotApplier.FakeDiff.Set(0, mkItem("evt:0", "hello")),
        ))
        assertEquals(listOf("evt:0", "evt:1"), a.snapshot.map { it.id })
    }

    @Test fun insertExistingIDMovesIt() {
        val a = SnapshotApplier()
        a.apply(listOf(
            SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"))),
            SnapshotApplier.FakeDiff.Insert(0, mkItem("3", "moved")),
        ))
        assertEquals(listOf("3", "1", "2"), a.snapshot.map { it.id })
    }

    @Test fun setWithIDAlreadyElsewhereDoesNotDuplicate() {
        val a = SnapshotApplier()
        a.apply(listOf(
            SnapshotApplier.FakeDiff.Append(listOf(mkItem("1"), mkItem("2"), mkItem("3"))),
            SnapshotApplier.FakeDiff.Set(0, mkItem("3", "moved")),
        ))
        val ids = a.snapshot.map { it.id }
        assertEquals(listOf("3", "2"), ids)
        assertEquals(ids.size, ids.toSet().size)
    }
}
