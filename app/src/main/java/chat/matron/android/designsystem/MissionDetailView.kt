package chat.matron.android.designsystem

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.models.Milestone
import chat.matron.android.models.Mission
import chat.matron.android.models.MissionConversation
import chat.matron.android.models.MissionState
import chat.matron.android.models.SessionTagInputs
import chat.matron.android.models.TrackerItem

/// One milestone as the page draws it: the record, plus the `A:bc` tag of
/// the conversation it was posted in — a mission spans several sessions, so
/// each row says which one it came from. `null` when this device has no
/// cached row for that conversation: the row then renders with no tag,
/// never a placeholder.
data class MilestoneRow(val milestone: Milestone, val sessionTag: SessionTagInputs?) {
    val id: String get() = milestone.id
}

/// Everything the mission page renders, mapped by the host from
/// `MissionDetailViewModel` (the Swift `MissionDetailView.Model`).
data class MissionDetailModel(
    val mission: Mission?,
    val milestones: List<MilestoneRow>,
    val openItems: List<TrackerItem>,
    val conversations: List<MissionConversation>,
    val showOnlyUserInput: Boolean,
    val closeSummary: String,
    val isBusy: Boolean,
) {
    companion object {
        /// The ONE mapping from the view model's published values into this
        /// model, so every host draws the same page.
        fun from(
            mission: Mission?, milestones: List<Milestone>, sessionTags: Map<String, SessionTagInputs>,
            openItems: List<TrackerItem>, conversations: List<MissionConversation>,
            showOnlyUserInput: Boolean, closeSummary: String, isBusy: Boolean,
        ) = MissionDetailModel(
            mission = mission, milestones = milestones.map { MilestoneRow(it, sessionTags[it.convoID]) },
            openItems = openItems, conversations = conversations, showOnlyUserInput = showOnlyUserInput,
            closeSummary = closeSummary, isBusy = isBusy,
        )
    }
}

/// The close confirmation's title — what the user actually sees. Pure so
/// the "close confirmation counts" requirement is pinned without a view.
fun missionCloseConfirmationTitle(openItems: Int): String =
    if (openItems == 0) "Close this mission?" else "Close with $openItems item${if (openItems == 1) "" else "s"} still open?"

/// The orange "Closed over N open items." line under a closed summary, or
/// `null` when the close was clean.
fun missionClosedOverText(mission: Mission): String? =
    mission.closedOverOpenItems.takeIf { it > 0 }?.let { "Closed over $it open item${if (it == 1) "" else "s"}." }

/// The empty-milestones copy, which follows the filter.
fun missionMilestonesEmptyText(showOnlyUserInput: Boolean): String =
    if (showOnlyUserInput) "No milestones from you yet." else "No milestones yet."

/// A milestone row's TalkBack announcement: kind, number, title and the
/// conversation tag spelled out as box NAMES, never the visual run's
/// single-letter glyphs (apple #216, `SessionTagText.plainLabel`).
fun milestoneRowAccessibilityLabel(row: MilestoneRow): String =
    "${MissionGlyph.label(row.milestone.kind)} ${row.milestone.num}, ${row.milestone.title}" +
        (row.sessionTag?.let { SessionTagText.plainLabel(it.boxName, it.sessionShort, it.roomBoxNames) }?.let { ", $it" } ?: "") +
        ". Opens the conversation at this point"

/// One mission page (spec: Apps → Missions tab → Page): header, milestones
/// newest first with a "My inputs only" toggle, open items, and the
/// conversations the mission owns, plus the user's close control. Leaf
/// view; the host owns the view model. Ported from matron-apple's
/// `MissionDetailView`.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissionDetailView(
    model: MissionDetailModel,
    onToggleUserInputOnly: (Boolean) -> Unit,
    /// The milestone's conversation and its anchor seq — the host opens the
    /// transcript there.
    onOpenMilestone: (Milestone) -> Unit,
    onOpenItem: (String) -> Unit,
    onOpenConversation: (String) -> Unit,
    onEditCloseSummary: (String) -> Unit,
    onClose: () -> Unit,
    /// Retries the detail fetch: the "Try again" action in the
    /// `mission == null` placeholder, and pull-to-refresh everywhere.
    onRefresh: () -> Unit,
    isRefreshing: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var showingClose by remember { mutableStateOf(false) }
    val mission = model.mission
    PullToRefreshBox(isRefreshing = isRefreshing, onRefresh = onRefresh, modifier = modifier.fillMaxSize()) {
        if (mission == null) {
            MissionUnavailable(onRefresh)
            return@PullToRefreshBox
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") { MissionHeader(mission) }
            item(key = "milestones-header") {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Milestones", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Text("My inputs only", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(8.dp))
                    Switch(
                        checked = model.showOnlyUserInput, onCheckedChange = onToggleUserInputOnly,
                        modifier = Modifier.semantics { contentDescription = "My inputs only" },
                    )
                }
                HorizontalDivider()
            }
            if (model.milestones.isEmpty()) {
                item(key = "milestones-empty") {
                    Text(
                        missionMilestonesEmptyText(model.showOnlyUserInput),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            } else {
                items(model.milestones, key = { "ml:${it.id}" }) { row ->
                    Column(Modifier.fillMaxWidth()) {
                        MilestoneRowView(row, Modifier.clickable { onOpenMilestone(row.milestone) }.padding(horizontal = 16.dp, vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            }
            if (model.openItems.isNotEmpty()) {
                item(key = "items-header") { PageSectionHeader("Open items") }
                items(model.openItems, key = { "it:${it.id}" }) { item ->
                    Column(Modifier.fillMaxWidth()) {
                        ItemRow(item, modifier = Modifier.clickable { onOpenItem(item.id) }.padding(horizontal = 16.dp, vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            }
            if (model.conversations.isNotEmpty()) {
                item(key = "convos-header") { PageSectionHeader("Conversations") }
                items(model.conversations, key = { "cv:${it.id}" }) { convo ->
                    Column(Modifier.fillMaxWidth()) {
                        ConversationRow(convo, Modifier.clickable { onOpenConversation(convo.id) }.padding(horizontal = 16.dp, vertical = 10.dp))
                        HorizontalDivider()
                    }
                }
            }
            if (mission.state == MissionState.OPEN) {
                item(key = "close-header") { PageSectionHeader("Close this mission") }
                item(key = "close") {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = model.closeSummary, onValueChange = onEditCloseSummary,
                            label = { Text("How it went") }, minLines = 2, maxLines = 6,
                            modifier = Modifier.fillMaxWidth().semantics { contentDescription = "Closing summary" },
                        )
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            if (model.isBusy) CircularProgressIndicator(Modifier.size(20.dp))
                            Spacer(Modifier.weight(1f))
                            Button(
                                onClick = { showingClose = true },
                                enabled = !model.isBusy && model.closeSummary.isNotBlank(),
                            ) { Text("Close mission") }
                        }
                    }
                }
            }
        }
    }
    if (showingClose && mission != null) {
        AlertDialog(
            onDismissRequest = { showingClose = false },
            title = { Text(missionCloseConfirmationTitle(model.openItems.size)) },
            text = { Text("The items stay open and keep their mission. The close is recorded on it.") },
            confirmButton = { TextButton(onClick = { showingClose = false; onClose() }) { Text("Close mission") } },
            dismissButton = { TextButton(onClick = { showingClose = false }) { Text("Keep it open") } },
        )
    }
}

@Composable
private fun PageSectionHeader(title: String) {
    Text(
        title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp, bottom = 8.dp),
    )
    HorizontalDivider()
}

@Composable
private fun MissionHeader(mission: Mission) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
            Text("#${mission.num}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 3.dp))
            Text(mission.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(
                MissionGlyph.label(mission.state), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
                color = MissionGlyph.tint(mission.state), modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (mission.body.isNotEmpty()) MarkdownText(mission.body, textStyle = MaterialTheme.typography.bodyMedium)
        val summary = mission.closeSummary
        if (!summary.isNullOrEmpty()) {
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text("Closed", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MarkdownText(summary, textStyle = MaterialTheme.typography.bodyMedium)
            missionClosedOverText(mission)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MatronOrange) }
        }
    }
    HorizontalDivider()
}

/// One milestone: kind glyph, `#num`, title, body preview, then the age and
/// the conversation's `A:bc` tag (room form first, single-box form second —
/// the same fallback order chat headers and list rows use).
@Composable
private fun MilestoneRowView(row: MilestoneRow, modifier: Modifier = Modifier) {
    val milestone = row.milestone
    Row(
        modifier = modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = milestoneRowAccessibilityLabel(row) },
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(MissionGlyph.icon(milestone.kind), contentDescription = null, tint = MissionGlyph.tint(milestone.kind), modifier = Modifier.padding(top = 4.dp).size(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val secondary = MaterialTheme.colorScheme.onSurfaceVariant
            val tertiary = secondary.copy(alpha = 0.75f)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.Top) {
                Text("#${milestone.num}", style = MaterialTheme.typography.labelSmall, color = secondary, modifier = Modifier.padding(top = 3.dp))
                Text(milestone.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (milestone.body.isNotEmpty()) {
                Text(milestone.body.replace("\n", " "), style = MaterialTheme.typography.bodyMedium, color = secondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                RelativeMinuteTimeView(milestone.createdAt, style = MaterialTheme.typography.labelSmall.copy(color = tertiary))
                val tag = row.sessionTag
                if (tag != null) {
                    val darkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
                    val run = SessionTagText.room(tag.roomBoxShorts, tag.roomBoxNames, tag.sessionShort, darkTheme, secondary)
                        ?: SessionTagText.run(tag.boxLetter, tag.boxName, tag.sessionShort, darkTheme, secondary)
                    // No cached conversation ⇒ no tag ⇒ nothing rendered, no
                    // empty gap. Not restyled with a color: that would flatten
                    // the per-run box hue.
                    if (run != null) Text(run, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ConversationRow(convo: MissionConversation, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        convo.box?.let { BoxChip(it) }
        Text(convo.title.ifEmpty { convo.id }, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(convo.state, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f))
    }
}

/// The `mission == null` leaf: not cached yet, or a refresh just failed.
/// A retry action either way — the view model has no way to tell "still
/// syncing" from "the last attempt failed" apart from `error` (surfaced
/// separately, via the host's alert), so this placeholder always offers a
/// way to try again rather than dead-ending.
@Composable
private fun MissionUnavailable(onRefresh: () -> Unit) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.SportsScore, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Mission not on this device yet", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            Text("It will appear once this device syncs it.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            OutlinedButton(onClick = onRefresh) { Text("Try again") }
        }
    }
}
