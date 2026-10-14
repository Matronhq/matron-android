package chat.matron.android.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.platform.UriHandler
import chat.matron.android.models.MatronDebug

// The platform edges of shareable tracker links
// (`https://<journal>/u/<user>/<num>`): reading one off an incoming intent,
// keeping in-app taps on one inside the app, and sending the links this app
// cannot show to a browser without the system handing them straight back.

/// The http(s) URL an `ACTION_VIEW` intent carries — an App Link the system
/// routed here — or `null` for any other intent (the launcher's MAIN).
fun incomingViewLink(action: String?, data: String?): String? {
    if (action != Intent.ACTION_VIEW || data == null) return null
    val scheme = data.substringBefore(':', "").lowercase()
    return data.takeIf { scheme == "https" || scheme == "http" }
}

/// The [UriHandler] every surface under the signed-in shell gets: a tracker
/// link on this session's journal ([isTrackerLink]) opens in-app through
/// [openTrackerLink]; everything else goes to [platform] as before. One
/// override at the shell covers every link opener — markdown bodies,
/// tables, item link chips — so none of them can miss it.
class TrackerLinkUriHandler(
    private val platform: UriHandler,
    private val isTrackerLink: (String) -> Boolean,
    private val openTrackerLink: (String) -> Unit,
) : UriHandler {
    override fun openUri(uri: String) {
        if (isTrackerLink(uri)) openTrackerLink(uri) else platform.openUri(uri)
    }
}

/// Opens a link outside the app, in a browser. Plain `ACTION_VIEW` is not
/// enough for a tracker link: once the journal's host is verified for this
/// app, the system would route it straight back here.
object BrowserHandoff {
    /// The package to open the link in: the default browser when there is
    /// one, else the first other app that can view it, never [ownPackage].
    /// `null` when no other app can — the caller then gives up quietly.
    /// [defaultBrowser] is `"android"` when the user picked no default (the
    /// system's chooser resolves the query).
    fun pickBrowser(defaultBrowser: String?, candidates: List<String>, ownPackage: String): String? {
        val others = candidates.filter { it != ownPackage }
        return defaultBrowser?.takeIf { it != ownPackage && it != "android" && it in others } ?: others.firstOrNull()
    }

    fun open(context: Context, url: String) {
        val pm = context.packageManager
        val view = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // The default browser, asked with a neutral URL this app never claims.
        val probe = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")).addCategory(Intent.CATEGORY_BROWSABLE)
        val defaultBrowser = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        val candidates = pm.queryIntentActivities(view, 0).map { it.activityInfo.packageName }.distinct()
        val target = pickBrowser(defaultBrowser, candidates, context.packageName)
        if (target == null) {
            MatronDebug.breadcrumb("BrowserHandoff: no browser for a tracker link")
            return
        }
        try {
            context.startActivity(view.setPackage(target))
        } catch (e: ActivityNotFoundException) {
            MatronDebug.breadcrumb("BrowserHandoff: $e")
        }
    }
}
