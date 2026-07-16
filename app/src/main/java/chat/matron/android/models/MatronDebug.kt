package chat.matron.android.models

/// Process-wide gate for high-frequency diagnostic logs that we want to keep in
/// the codebase as breadcrumbs but ship turned off. Mirror of the `debugLog`
/// helper pattern from the web side and `MatronDebug` on Apple.
///
/// Logging is routed through an injectable [sink] so unit tests can observe (or
/// silence) output without pulling in `android.util.Log` (which is unavailable
/// on the plain-JUnit classpath). The default sink writes to `android.util.Log`
/// on-device and is a no-op anywhere the Android runtime is absent.
object MatronDebug {
    /// Sink for a single log line: `(tag, message)`. Swap in tests.
    var sink: (String, String) -> Unit = { tag, message ->
        runCatching { android.util.Log.i(tag, message) }
    }

    /// Default: off. Flip on from app startup, a test, or the debugger.
    @Volatile
    var enabled: Boolean = false

    private const val TAG = "MatronDebug"

    /// Diagnostic log that only fires when [enabled] is true. Use for
    /// high-frequency or debug-only logs kept in the source as living
    /// documentation of the data flow without paying for them in shipped builds.
    /// `message` is a lambda so its interpolation is deferred when the gate is
    /// off (mirrors the Swift `@autoclosure`).
    inline fun diag(message: () -> String) {
        if (!enabled) return
        sink("MatronDebug", message())
    }

    /// Un-gated forensic breadcrumb: always logs. Use for the rare pivotal
    /// events any field incident will be diagnosed from.
    fun breadcrumb(message: String) {
        sink(TAG, message)
    }
}
