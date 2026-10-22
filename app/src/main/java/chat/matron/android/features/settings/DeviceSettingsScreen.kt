package chat.matron.android.features.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import chat.matron.android.designsystem.AppearancePicker
import chat.matron.android.designsystem.MatronAppearance
import chat.matron.android.designsystem.StorageSettingsRows
import chat.matron.android.models.UserSession
import chat.matron.android.viewmodels.AppLockController
import chat.matron.android.viewmodels.AppLockTimeout
import chat.matron.android.viewmodels.DevicesProviding
import chat.matron.android.viewmodels.CoordinatorConsentSettingViewModel
import chat.matron.android.viewmodels.NoticesSettingViewModel
import chat.matron.android.viewmodels.TwoFactorSettingViewModel
import chat.matron.android.viewmodels.AutoArchiveSettingViewModel
import chat.matron.android.viewmodels.AccountEmailViewModel
import chat.matron.android.viewmodels.ChangePasswordViewModel
import kotlinx.coroutines.launch

/**
 * Settings → Device. Ports Features/Settings/DeviceSettingsView.swift: a
 * read-only account summary (userID, deviceID, homeserver host), a Manage
 * Devices link, the appearance picker, the Storage diagnostics section
 * (apple #212), and the Privacy section hosting the app lock.
 * Verification/recovery-key sections were Matrix-SDK-only and are absent
 * from the journal stack.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun DeviceSettingsScreen(
    session: UserSession,
    devicesApi: DevicesProviding?,
    appearance: MatronAppearance,
    onAppearanceChange: (MatronAppearance) -> Unit,
    onManageDevices: () -> Unit,
    onLinkDevice: (() -> Unit)? = null,
    /// Settings → Agent Chats. `null` in hosts without the surface (previews).
    onAgentChats: (() -> Unit)? = null,
    /// `null` in hosts that have no lock (and in previews); the Privacy section
    /// then doesn't render at all.
    appLock: AppLockController? = null,
    /// Loads the Storage section's model — on demand only: two file stats and
    /// two `COUNT(*)`s when the user opens this screen, never on the launch
    /// path. `null` in hosts without a store (previews) hides the section.
    loadStorage: (suspend () -> StorageSettingsRows.Model)? = null,
    /// Settings → Coordinator (apple #197). `null` in hosts without the
    /// app shell (previews); the section then doesn't render.
    coordinator: CoordinatorSettingRowModel? = null,
    /// Settings → Pinned chats. `null` hides the row (a journal without
    /// pins, previews).
    onPinnedChats: (() -> Unit)? = null,
    /// Settings → For you's journal-held "notices" switch. `null` in hosts
    /// without a session (previews); the section also stays hidden until the
    /// journal answers, and for good on a journal without `/settings`.
    notices: NoticesSettingViewModel? = null,
    /// Settings → Archive's "Archive idle chats" choice. `null` in hosts
    /// without a session; hidden too on a journal without auto-archive.
    autoArchive: AutoArchiveSettingViewModel? = null,
    /// Settings → Security's two-factor status. `null` in hosts without a
    /// session; hidden too on a journal without two-factor.
    twoFactor: TwoFactorSettingViewModel? = null,
    /// Settings → Security's "Change password". `null` in hosts without a
    /// session (previews).
    changePassword: ChangePasswordViewModel? = null,
    /// Settings → Security's "Email address" for password resets. `null` in
    /// hosts without a session; hidden too on a journal without it.
    accountEmail: AccountEmailViewModel? = null,
    onBack: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Device") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Done") } },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            SettingsSection("Account") {
                LabeledRow("User ID", session.userID)
                LabeledRow("Device ID", session.deviceID)
                LabeledRow("Server", hostOf(session.homeserverURL))
            }

            if (twoFactor != null || changePassword != null || accountEmail != null) {
                SecuritySection(twoFactor, changePassword, accountEmail)
            }

            if (devicesApi != null) {
                SettingsSection("Devices") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onManageDevices)
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Manage Devices", modifier = Modifier.weight(1f))
                        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                    }
                    if (onLinkDevice != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onLinkDevice)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Link a Device", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                    if (onAgentChats != null) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(onClick = onAgentChats)
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("Agent Chats", modifier = Modifier.weight(1f))
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        }
                    }
                }
            }

            if (coordinator != null) CoordinatorSection(coordinator)

            if (onPinnedChats != null) PinnedChatsSection(onPinnedChats)

            if (notices != null) NoticesSection(notices)

            if (autoArchive != null) AutoArchiveSection(autoArchive)

            SettingsSection("Appearance") {
                AppearancePicker(selected = appearance, onSelect = onAppearanceChange)
            }

            if (loadStorage != null) StorageSection(loadStorage)

            if (appLock != null) AppLockSection(appLock)
        }
    }
}

/**
 * Privacy → app lock. Renders only when the device can actually authenticate:
 * offering a toggle that would immediately fail is worse than offering nothing,
 * and it is the same rule the lock itself stands down on.
 *
 * The toggle writes through [AppLockController.setEnabled], which authenticates
 * BEFORE enabling — so flipping it on raises the system prompt and the switch
 * only moves once that succeeds. The local `switchOn` mirror exists so the
 * switch tracks the controller rather than the user's optimistic tap.
 */
@Composable
private fun AppLockSection(appLock: AppLockController) {
    // Read once per composition: a settings screen is not on screen while the
    // screen lock is being reconfigured, so re-probing on recomposition buys
    // nothing over a stable read.
    val methodName = remember(appLock) { appLock.methodName } ?: return

    val enabled by appLock.isEnabled.collectAsStateWithLifecycle()
    val timeout by appLock.timeout.collectAsStateWithLifecycle()
    val authenticating by appLock.isAuthenticating.collectAsStateWithLifecycle()
    val error by appLock.authError.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    SettingsSection("Privacy") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Require unlock", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Lock Matron with $methodName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = enabled,
                enabled = !authenticating,
                onCheckedChange = { wanted -> scope.launch { appLock.setEnabled(wanted) } },
            )
        }

        if (error != null) {
            Text(
                error!!,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }

        if (enabled) {
            Text(
                "Lock",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                AppLockTimeout.entries.forEach { option ->
                    FilterChip(
                        selected = option == timeout,
                        onClick = { appLock.setTimeout(option) },
                        label = { Text(option.shortTitle) },
                    )
                }
            }
            Text(
                timeout.title.replaceFirstChar { it.lowercase() }
                    .let { "Locks $it in the background." },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Settings → For you: whether agents file what the user should read as
 * notice items. Rendered only once the journal has answered `GET /settings`
 * — never for a journal that 404s it. The switch follows the view model,
 * which moves it optimistically and puts it back if the save fails.
 */
@Composable
private fun NoticesSection(model: NoticesSettingViewModel) {
    DisposableEffect(model) {
        model.start()
        onDispose { model.stop() }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val saving by model.isSaving.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val loaded = state as? NoticesSettingViewModel.State.Loaded ?: return

    SettingsSection("For you") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(NoticesSettingViewModel.TITLE, style = MaterialTheme.typography.bodyMedium)
                Text(
                    NoticesSettingViewModel.HELP,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = loaded.notices,
                enabled = !saving,
                onCheckedChange = { wanted -> scope.launch { model.setNotices(wanted) } },
            )
        }
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
    }
}

/**
 * Settings → Security: the email address for password resets, Change
 * password, and whether two-factor sign-in is on.
 * The two-factor rows are read-only — turning it on or off and new recovery
 * codes live in Matron Web, and the section says so. They stay hidden until
 * `GET /2fa` answers, and for good on a journal that 404s it; the section
 * itself then shows only for Change password.
 */
@Composable
private fun SecuritySection(
    twoFactor: TwoFactorSettingViewModel?,
    changePassword: ChangePasswordViewModel?,
    accountEmail: AccountEmailViewModel?,
) {
    if (twoFactor != null) LaunchedEffect(twoFactor) { twoFactor.load() }
    val twoFactorState = twoFactor?.state?.collectAsStateWithLifecycle()?.value
    val showTwoFactor = twoFactorState != null && twoFactorState != TwoFactorSettingViewModel.State.Loading &&
        twoFactorState != TwoFactorSettingViewModel.State.Unsupported
    if (!showTwoFactor && changePassword == null && accountEmail == null) return

    SettingsSection("Security") {
        if (accountEmail != null) AccountEmailRows(accountEmail)
        if (changePassword != null) ChangePasswordRows(changePassword)
        if (showTwoFactor) TwoFactorRows(twoFactorState!!)
    }
}

@Composable
private fun TwoFactorRows(state: TwoFactorSettingViewModel.State) {
    when (val s = state) {
        is TwoFactorSettingViewModel.State.Loaded -> {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(TwoFactorSettingViewModel.TITLE, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        TwoFactorSettingViewModel.detailText(s.status),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    TwoFactorSettingViewModel.valueText(s.status),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (s.status.enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        is TwoFactorSettingViewModel.State.Failed -> Text(
            s.message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        else -> Unit
    }
    Text(
        TwoFactorSettingViewModel.MANAGE,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp),
    )
}

/**
 * Settings › Archive: the journal's "Archive idle chats" window, a menu of
 * Off / 14 / 30 / 90 days. Moves at once and goes back if the save fails.
 */
@Composable
private fun AutoArchiveSection(model: AutoArchiveSettingViewModel) {
    DisposableEffect(model) {
        model.start()
        onDispose { model.stop() }
    }
    val state by model.state.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val loaded = state as? AutoArchiveSettingViewModel.State.Loaded ?: return
    var menuOpen by remember { mutableStateOf(false) }

    SettingsSection("Archive") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(AutoArchiveSettingViewModel.TITLE, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Box {
                TextButton(onClick = { menuOpen = true }, modifier = Modifier.testTag(AUTO_ARCHIVE_BUTTON_TAG)) {
                    Text(AutoArchiveSettingViewModel.label(loaded.days))
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    val choices = (AutoArchiveSettingViewModel.CHOICES + loaded.days).distinct().sorted()
                    for (days in choices) {
                        DropdownMenuItem(
                            text = { Text(AutoArchiveSettingViewModel.label(days)) },
                            onClick = {
                                menuOpen = false
                                scope.launch { model.setDays(days) }
                            },
                        )
                    }
                }
            }
        }
        Text(
            AutoArchiveSettingViewModel.HELP,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        error?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
    }
}

internal const val AUTO_ARCHIVE_BUTTON_TAG = "auto-archive-days"

/**
 * Settings › Storage: store sizes on disk, row counts, this launch's
 * timings and the last maintenance pass. The read starts as soon as the
 * screen composes regardless of scroll position; `null` renders as a
 * spinner until it returns.
 */
@Composable
private fun StorageSection(loadStorage: suspend () -> StorageSettingsRows.Model) {
    var storage by remember { mutableStateOf<StorageSettingsRows.Model?>(null) }
    // One read per entry to the screen, never per recomposition: the host
    // hands over a fresh lambda every composition, so keying the effect on
    // it re-ran the two file stats and two `COUNT(*)`s on every appearance,
    // app-lock or coordinator change (Bugbot, #73). The newest lambda is
    // still the one the effect calls.
    val latestLoad by rememberUpdatedState(loadStorage)
    LaunchedEffect(Unit) { storage = latestLoad() }
    SettingsSection("Storage") { StorageSettingsRows(storage) }
}

/**
 * Settings → Coordinator (app shell, spec §5b): the current coordinator
 * chat's title with Change and Clear, or a Choose button when none is set.
 * The shell supplies the model (it owns the setting, the chooser sheet and
 * the live chat list the title comes from) so this stays a dumb renderer.
 */
data class CoordinatorSettingRowModel(
    /// The chosen chat's title (its id when untitled), or `null` when unset.
    val title: String?,
    val onChoose: () -> Unit,
    val onClear: () -> Unit,
    /// The journal's consent-approval switch, under the picker. Null in
    /// hosts without one.
    val consent: CoordinatorConsentSettingViewModel? = null,
)

@Composable
internal fun CoordinatorSection(model: CoordinatorSettingRowModel) {
    SettingsSection("Coordinator") {
        if (model.title != null) {
            LabeledRow("Conversation", model.title)
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TextButton(onClick = model.onChoose) { Text("Change…") }
                TextButton(onClick = model.onClear) { Text("Clear", color = MaterialTheme.colorScheme.error) }
            }
        } else {
            Text(
                "No coordinator conversation yet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
            TextButton(onClick = model.onChoose) { Text("Choose…") }
        }
        model.consent?.let { CoordinatorConsentSwitch(it) }
    }
}

/**
 * The journal's consent-approval switch, under the Coordinator picker: on,
 * the Coordinator may answer chat and spawn cards for the user (each card
 * then says so, with its reason). Hidden until the journal answers, and for
 * a journal without the switch.
 */
@Composable
private fun CoordinatorConsentSwitch(model: CoordinatorConsentSettingViewModel) {
    DisposableEffect(model) {
        model.start()
        onDispose { model.stop() }
    }
    val enabled by model.enabled.collectAsStateWithLifecycle()
    val saving by model.isSaving.collectAsStateWithLifecycle()
    val error by model.error.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val on = enabled ?: return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(CoordinatorConsentSettingViewModel.TITLE, style = MaterialTheme.typography.bodyMedium)
            Text(
                CoordinatorConsentSettingViewModel.HELP,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = on,
            enabled = !saving,
            onCheckedChange = { wanted -> scope.launch { model.setEnabled(wanted) } },
        )
    }
    error?.let {
        Text(
            it,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun PinnedChatsSection(onOpen: () -> Unit) {
    SettingsSection("Pinned chats") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Pinned chats", modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
        }
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        HorizontalDivider()
        content()
    }
}

@Composable
private fun LabeledRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Extracts the host from a homeserver URL string, falling back to the whole value. */
private fun hostOf(url: String): String =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotEmpty() } ?: url
