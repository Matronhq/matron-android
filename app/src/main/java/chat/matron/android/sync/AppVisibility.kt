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
