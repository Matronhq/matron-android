package chat.matron.android.viewmodels

import kotlinx.coroutines.delay

/// Polls [condition] on the current (cooperative) dispatcher until it holds or
/// the timeout elapses — the Kotlin analogue of the Apple suites' `waitUntil`
/// helper. View-model background tasks run on the same runBlocking event loop, so
/// each `delay(10)` pumps them a step forward.
suspend fun waitUntil(timeoutMs: Long = 2000, condition: () -> Boolean) {
    val steps = (timeoutMs / 10).toInt().coerceAtLeast(1)
    repeat(steps) {
        if (condition()) return
        delay(10)
    }
}
