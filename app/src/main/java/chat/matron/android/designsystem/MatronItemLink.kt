package chat.matron.android.designsystem

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import java.net.URI
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/// Tracker-item deep links (tracker item #115; port of matron-apple's
/// `MatronItemLink.swift`, #208).
///
/// Agents reference tracker items in ordinary prose as standard markdown
/// links — `[#65](matron://item/65)` — so they render as a normal link in
/// every client and cost nothing when the reader's app doesn't understand
/// them. The `matron` scheme is deliberately NOT registered with the OS
/// (`matron://link` / `matron://rlink` are pasted or scanned, never opened),
/// so a tap must be resolved **in-app**: handing `matron://…` to the platform
/// URI handler throws `ActivityNotFoundException` and shows nothing.
///
/// This object is the single source of truth for that decision, shared by
/// [MarkdownText]'s click handler and the item detail's link chips so the
/// paths cannot drift apart.
object MatronItemLink {

    /// What a tapped link in a message body should do.
    sealed interface Action {
        /// A well-formed `matron://item/<n>` — open tracker item `n` in-app.
        data class OpenTrackerItem(val num: Int) : Action

        /// Hand to the OS (the pre-existing default for http(s) and for any
        /// scheme we have no opinion about).
        data class System(val url: String) : Action

        /// Consume silently — matrix-internal URLs, and any `matron://` URL
        /// that is not a canonical item link.
        data object Swallow : Action
    }

    /// The item number in `matron://item/<positive integer>`, or `null` for
    /// anything else.
    ///
    /// Strict by design — this parser decides whether a URL a remote agent
    /// wrote gets in-app navigation. Only the canonical form is accepted:
    /// the path must be exactly `/` followed by ASCII digits greater than
    /// zero, with no query, fragment, user info or port. Scheme and host
    /// compare case-insensitively (RFC 3986); everything else must match
    /// exactly. The RAW path is inspected (never the decoded one), so a
    /// trailing slash, an empty segment and percent-encoded digits are all
    /// rejected rather than quietly accepted as `#65`.
    fun itemNumber(url: String): Int? {
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        if (!uri.scheme.equals("matron", ignoreCase = true)) return null
        if (!uri.host.equals("item", ignoreCase = true)) return null
        if (uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null || uri.port != -1) return null
        val path = uri.rawPath ?: return null
        if (!path.startsWith("/")) return null
        val digits = path.substring(1)
        // `toIntOrNull` alone would accept "+65" / "-5"; require plain ASCII
        // digits (and let the conversion reject an overflowing run of them).
        // This also rejects any second path segment: a "/" is not a digit.
        if (digits.isEmpty() || !digits.all { it in '0'..'9' }) return null
        val number = digits.toIntOrNull() ?: return null
        return if (number > 0) number else null
    }

    /// Message-body link policy. `matron://item/<n>` first; then the
    /// pre-existing scheme policy, unchanged apart from `matron` itself.
    fun action(url: String): Action {
        itemNumber(url)?.let { return Action.OpenTrackerItem(it) }
        return when (scheme(url)) {
            // A `matron` URL we don't understand — a malformed item link, or
            // a pairing `matron://link` / `matron://rlink` that got
            // linkified. The scheme is registered with NOTHING, so handing
            // it to the OS does nothing useful: swallow instead.
            "matron" -> Action.Swallow
            // Swallowed until permalink / content-URI handling lands.
            "matrix", "mxc" -> Action.Swallow
            else -> Action.System(url)
        }
    }

    /// A URL trimmed to what is safe to write into a log or breadcrumb:
    /// `scheme://host` plus the path, never the query or the fragment. A
    /// swallowed link is very often a linkified pairing URI whose secret
    /// rides in the QUERY (`matron://rlink?…&k=<offer key>`,
    /// `matron://link?…&code=XXXX-XXXX`). The path is kept because it is
    /// the diagnostic part (`/item/65`); for an opaque URL with no host —
    /// where the "path" IS the payload, as in `mailto:` — only the scheme
    /// survives.
    fun redactedForLog(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull() ?: return "(unparseable URL)"
        val scheme = uri.scheme ?: return "(unparseable URL)"
        val host = uri.host
        if (host.isNullOrEmpty()) return "$scheme:…"
        return "$scheme://$host${uri.rawPath ?: ""}"
    }

    private fun scheme(url: String): String? =
        runCatching { URI(url).scheme }.getOrNull()?.lowercase()
            ?: url.substringBefore(':', "").takeIf { it.isNotEmpty() && !it.contains('/') }?.lowercase()
}

/// Routes a tapped message-body link (port of the Swift `MarkdownText.handle`):
/// an item link goes to [openItem] when a host installed one and is swallowed
/// otherwise (the scheme is unregistered — never hand it to the OS); matrix
/// and non-item `matron://` URLs are swallowed; everything else goes to
/// [openExternally]. Pure, so the policy is pinned without rendering.
fun handleMessageLink(url: String, openItem: ((Int) -> Unit)?, openExternally: (String) -> Unit) {
    when (val action = MatronItemLink.action(url)) {
        is MatronItemLink.Action.OpenTrackerItem -> openItem?.invoke(action.num)
        MatronItemLink.Action.Swallow -> Unit
        is MatronItemLink.Action.System -> openExternally(action.url)
    }
}

/// Opens a tracker item by NUMBER, in whatever surface the host provides
/// (the Android analogue of the Swift `\.openTrackerItem` environment
/// action). `null` — the default — means no host is installed, and item
/// links are swallowed rather than handed to the OS. Hosts install it through
/// [TrackerItemLinkHost], never by providing the local directly.
val LocalOpenTrackerItem = compositionLocalOf<((Int) -> Unit)?> { null }

/// The link opener every message-body surface should use for links that do
/// NOT go through [MarkdownText] (the item detail's link chips): the same
/// policy as the markdown click handler, so no `matron://` URL ever reaches
/// the platform URI handler.
@Composable
fun rememberMessageLinkOpener(): (String) -> Unit {
    val openItem by rememberUpdatedState(LocalOpenTrackerItem.current)
    val uriHandler = LocalUriHandler.current
    return remember(uriHandler) {
        { url -> handleMessageLink(url, openItem) { external -> runCatching { uriHandler.openUri(external) } } }
    }
}

/// What a resolved `matron://item/<n>` tap should do to the host it was
/// tapped in. The host's `resolve` produces one of these and
/// [TrackerItemLinkHost] applies it — so navigation, the alert and the
/// "nothing to do" case all pass through the SAME staleness check
/// ([TrackerItemLinkTapGate]).
sealed interface TrackerItemLinkOutcome {
    /// The number resolved to a local item — navigate to it.
    data class Open(val itemID: String) : TrackerItemLinkOutcome

    /// It didn't, and this is what to tell the user in the tracker alert.
    data class Explain(val message: String) : TrackerItemLinkOutcome

    /// The host couldn't even try (no session yet), or the tap is a no-op
    /// such as a link to the item already on screen. Say nothing, change
    /// nothing.
    data object Ignore : TrackerItemLinkOutcome
}

/// Serialises tracker-link taps so only the LATEST one can act.
///
/// Every tap starts an independent async resolve, and resolves do not finish
/// in the order they were started: a miss suspends inside a full
/// `refresh(All)` while a tap made a moment later hits the local store and
/// returns at once. Without this gate the slow first tap would come back
/// afterwards and either navigate to the OLDER item — silently undoing the
/// navigation the user just watched happen — or overwrite the alert with a
/// message about a number they have moved on from.
///
/// The rule is "last tap wins", enforced at the point of EFFECT rather than
/// at the point of start: a superseded resolve is cancelled and, whether or
/// not it notices, its outcome is dropped. Two taps on the same number are
/// two different taps, so a double tap on `#65` still resolves to one
/// navigation.
class TrackerItemLinkTapGate(private val scope: CoroutineScope) {
    private var inFlight: Job? = null

    /// The tap allowed to act. Bumped synchronously in [begin], so it is
    /// already the newer tap's id by the time an older resolve returns.
    private var currentTap = 0L

    /// Resolves a tap on item [num], superseding whatever tap was still
    /// resolving, and applies the outcome only if no newer tap arrived
    /// meanwhile. [resolve] is cancelled on supersession, but the gate does
    /// not rely on it honouring that: a store read and a network refresh
    /// both run to completion regardless, which is why the check is on the
    /// way out and not on the way in.
    fun begin(num: Int, resolve: suspend (Int) -> TrackerItemLinkOutcome, apply: (TrackerItemLinkOutcome) -> Unit) {
        supersede()
        val tap = currentTap
        inFlight = scope.launch {
            val outcome = resolve(num)
            if (currentTap != tap) return@launch
            inFlight = null
            apply(outcome)
        }
    }

    /// A navigation that needs no resolving — an inline item card, which
    /// already knows its item id — still counts as a tap: it supersedes any
    /// link resolve still in flight and applies at once. Without this a
    /// miss-path resolve still refreshing when the card was tapped could
    /// finish afterwards and push the OLDER item on top of the one the user
    /// just opened (Bugbot on matron-android #78).
    fun openDirectly(itemID: String, apply: (TrackerItemLinkOutcome) -> Unit) {
        supersede()
        apply(TrackerItemLinkOutcome.Open(itemID))
    }

    /// Cancels whatever tap was still resolving and makes the next tap the
    /// current one.
    private fun supersede() {
        inFlight?.cancel()
        inFlight = null
        currentTap++
    }
}

/// Installs a surface as the host for `matron://item/<n>` links: the
/// [LocalOpenTrackerItem] action every rendered body reads, the tap →
/// [resolve] hop, the staleness gate, and the "Tracker" alert the resolver's
/// miss paths surface. [content] receives the host's own item opener for
/// navigations that need no resolving (an inline item card's tap): it goes
/// through the same gate, so it supersedes a link resolve still in flight
/// instead of racing it. The alert is the whole point of the miss path: the
/// host stays exactly where it was, so without it an unknown number would be
/// a dead tap.
///
/// Apply this ONCE, on the host container — not per child. Nesting is
/// meaningful only where a genuinely different container takes over (an
/// item detail pushed over a chat): the innermost install wins for
/// everything it contains, which is exactly the "push where the link was
/// tapped" behaviour.
@Composable
fun TrackerItemLinkHost(
    resolve: suspend (Int) -> TrackerItemLinkOutcome,
    open: (String) -> Unit,
    content: @Composable (openItem: (String) -> Unit) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gate = remember(scope) { TrackerItemLinkTapGate(scope) }
    val latestResolve by rememberUpdatedState(resolve)
    val latestOpen by rememberUpdatedState(open)
    var alert by remember { mutableStateOf<String?>(null) }
    // One closure instance for the host's lifetime: every rendered body
    // reads this local, so a fresh lambda per recomposition would
    // invalidate the whole timeline.
    val apply: (TrackerItemLinkOutcome) -> Unit = remember(gate) {
        { outcome ->
            when (outcome) {
                is TrackerItemLinkOutcome.Open -> latestOpen(outcome.itemID)
                is TrackerItemLinkOutcome.Explain -> alert = outcome.message
                TrackerItemLinkOutcome.Ignore -> Unit
            }
        }
    }
    val action: (Int) -> Unit = remember(gate) { { num -> gate.begin(num, { latestResolve(it) }, apply) } }
    val openItem: (String) -> Unit = remember(gate) { { id -> gate.openDirectly(id, apply) } }
    CompositionLocalProvider(LocalOpenTrackerItem provides action) { content(openItem) }
    alert?.let { message ->
        // Same chrome as every other tracker error (`ItemsPanelViewModel.error`).
        AlertDialog(
            onDismissRequest = { alert = null },
            title = { Text("Tracker") },
            text = { Text(message) },
            confirmButton = { TextButton(onClick = { alert = null }) { Text("OK") } },
        )
    }
}
