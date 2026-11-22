package chat.matron.android.features.projects

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import chat.matron.android.designsystem.MatronRed
import chat.matron.android.viewmodels.ProjectActionsFormat
import chat.matron.android.viewmodels.ProjectPageState
import chat.matron.android.viewmodels.ProjectPageViewModel
import kotlinx.coroutines.launch

/// Which dialog the "⋯" menu has open.
sealed interface ProjectActionDialog {
    data object AddMission : ProjectActionDialog
    data object Rename : ProjectActionDialog
    data object MergePick : ProjectActionDialog
    data class MergeConfirm(val intoID: String, val intoTitle: String) : ProjectActionDialog
    data object Close : ProjectActionDialog
}

/// A project page's "⋯" beside its title: "Add a mission" (open missions
/// in no project), "Ask the Coordinator about this", "Rename…", "Merge
/// into…" (the other open projects) and "Close project…". The writes are
/// dimmed on a project that is not open and until the lists have loaded; a
/// merge and a close are confirmed first. Ported from matron-apple's
/// `ProjectActionsMenu`.
@Composable
fun ProjectActionsMenu(state: ProjectPageState, viewModel: ProjectPageViewModel, initialDialog: ProjectActionDialog? = null, initiallyOpen: Boolean = false) {
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(initiallyOpen) }
    var dialog by remember { mutableStateOf(initialDialog) }
    val available = state.actions
    val title = state.page?.project?.title ?: ""

    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("projects.summary.more")) {
            Icon(Icons.Filled.MoreHoriz, contentDescription = "More")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(ProjectActionsFormat.ADD_MISSION) }, enabled = available.addMission,
                onClick = { open = false; dialog = ProjectActionDialog.AddMission },
            )
            if (viewModel.canAsk) {
                DropdownMenuItem(
                    text = { Text(if (state.asked) ProjectActionsFormat.ASKED else ProjectActionsFormat.ASK) }, enabled = !state.asked,
                    onClick = { open = false; scope.launch { viewModel.ask() } },
                )
            }
            HorizontalDivider()
            DropdownMenuItem(
                text = { Text(ProjectActionsFormat.RENAME) }, enabled = available.rename,
                onClick = { open = false; dialog = ProjectActionDialog.Rename },
            )
            DropdownMenuItem(
                text = { Text(ProjectActionsFormat.MERGE_INTO) }, enabled = available.merge,
                onClick = { open = false; dialog = ProjectActionDialog.MergePick },
            )
            DropdownMenuItem(
                text = { Text(ProjectActionsFormat.CLOSE, color = if (available.close) MatronRed else MatronRed.copy(alpha = 0.38f)) }, enabled = available.close,
                onClick = { open = false; dialog = ProjectActionDialog.Close },
            )
        }
    }

    val dismiss = { dialog = null }
    when (val d = dialog) {
        ProjectActionDialog.AddMission -> PickDialog(
            title = ProjectActionsFormat.ADD_MISSION,
            rows = state.unfiled.map { it.id to it.label },
            onPick = { id -> dialog = null; scope.launch { viewModel.addMission(id) } },
            onDismiss = dismiss,
        )
        ProjectActionDialog.Rename -> RenameDialog(title, onRename = { name -> dialog = null; scope.launch { viewModel.rename(name) } }, onDismiss = dismiss)
        ProjectActionDialog.MergePick -> PickDialog(
            title = ProjectActionsFormat.MERGE_INTO.removeSuffix("…"),
            rows = state.mergeTargets.map { it.id to it.title },
            onPick = { id -> dialog = ProjectActionDialog.MergeConfirm(id, state.mergeTargets.firstOrNull { it.id == id }?.title ?: "") },
            onDismiss = dismiss,
        )
        is ProjectActionDialog.MergeConfirm -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(ProjectActionsFormat.mergeConfirmation(title, d.intoTitle)) },
            confirmButton = { TextButton(onClick = { dialog = null; scope.launch { viewModel.merge(d.intoID) } }) { Text("Merge") } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        ProjectActionDialog.Close -> AlertDialog(
            onDismissRequest = dismiss,
            text = { Text(ProjectActionsFormat.closeConfirmation(title)) },
            confirmButton = { TextButton(onClick = { dialog = null; scope.launch { viewModel.close() } }) { Text("Close project", color = MatronRed) } },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
        )
        null -> Unit
    }
}

@Composable
private fun PickDialog(title: String, rows: List<Pair<String, String>>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                rows.forEach { (id, label) ->
                    Text(
                        label, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().clickable { onPick(id) }.padding(vertical = 12.dp).testTag("projects.pick.$id"),
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RenameDialog(current: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename project") },
        text = { OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onRename(name) }, enabled = name.isNotBlank() && name.trim() != current) { Text("Rename") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
