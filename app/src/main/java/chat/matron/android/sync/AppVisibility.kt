package chat.matron.android.sync

import android.app.ActivityManager
import android.content.Context

/**
 * Whether this process currently has visible UI. ActivityManager importance
 * rather than ProcessLifecycleOwner: the two callers (the catch-up worker and
 * the search-backfill sweep, both off the main thread) need a synchronous
 * point-in-time check, not a lifecycle stream, and this avoids pulling the
 * lifecycle-process artifact in for it.
 */
fun isAppProcessInForeground(context: Context): Boolean {
    val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return false
    val myPid = android.os.Process.myPid()
    return am.runningAppProcesses?.any {
        it.pid == myPid && it.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
    } ?: false
}

/**
 * Whether the main app's window is started (between [MainActivity]'s
 * `onStart` and `onStop`). The share screen runs in the same process, so
 * [isAppProcessInForeground] can't tell the two apart; a share that started
 * the sync engine reads this before stopping it, so it never takes the
 * visible app offline.
 */
object MainAppVisibility {
    /// A count, not a flag: when the activity is recreated, the old
    /// instance's `onStop` can land after the new one's `onStart`.
    private val started = java.util.concurrent.atomic.AtomicInteger(0)

    val isStarted: Boolean get() = started.get() > 0

    fun noteStarted() {
        started.incrementAndGet()
    }

    fun noteStopped() {
        started.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}
