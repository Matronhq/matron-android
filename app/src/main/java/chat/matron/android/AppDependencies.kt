package chat.matron.android

import android.content.Context

/**
 * Composition root, mirroring matron-apple's AppDependencies. Services are
 * wired here as the port progresses.
 */
class AppDependencies(val context: Context)
