package chat.matron.android.viewmodels

import chat.matron.android.journal.NumberKind
import chat.matron.android.journal.TrackerLinkURL

/// Where a shareable tracker link (`https://<journal>/u/<user>/<num>`, see
/// [TrackerLinkURL]) opens, whether it was tapped in a message or an item
/// body or arrived from outside the app as an App Link.
///
/// The user's own numbers go through the "Go to number" resolver, so a link
/// and a typed `#N` cannot drift: the local store first, then the journal's
/// `/lookup`, then whatever sync puts the target on this device. What this
/// app has no page for leaves for the browser, where the web client shows
/// it: another person's link (this app has no view of what others share),
/// a project, and a link to another journal than the session's.
///
/// Pure apart from the resolver, so tests drive it with hand-rolled lambdas.
class TrackerLinkRouter(
    private val journalBaseURL: String,
    private val sessionUser: String,
    private val resolver: GoToNumberResolver,
) {
    sealed interface Route {
        /// Navigate in-app.
        data class Open(val target: GoToTarget) : Route

        /// Stay put and tell the user why nothing opened.
        data class Explain(val message: String) : Route

        /// Hand the link to a browser — never back to this app.
        data class Browser(val url: String) : Route
    }

    /// The tracker link [url] names on this session's journal, or `null`
    /// when it is anything else.
    fun link(url: String): TrackerLinkURL? = TrackerLinkURL.parse(url, journalBaseURL)

    suspend fun route(url: String): Route {
        val link = link(url) ?: return Route.Browser(url)
        if (link.user != sessionUser) return Route.Browser(url)
        return when (val found = resolver.find(link.num)) {
            GoToNumberResolver.Found.Missing -> Route.Explain(GoToNumberCopy.nothingNumbered(link.num))
            is GoToNumberResolver.Found.Failed -> Route.Explain(found.message)
            is GoToNumberResolver.Found.Hit ->
                if (found.hit.kind == NumberKind.PROJECT) {
                    Route.Browser(url)
                } else {
                    when (val opened = resolver.open(found.hit)) {
                        is GoToNumberResolver.Opened.Target -> Route.Open(opened.target)
                        is GoToNumberResolver.Opened.Message -> Route.Explain(opened.text)
                    }
                }
        }
    }
}
