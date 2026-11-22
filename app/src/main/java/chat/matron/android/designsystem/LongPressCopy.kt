package chat.matron.android.designsystem

import android.content.Context
import android.os.Build
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString

/// The long-press Copy menu a whole message offers: a long-press on the
/// surface opens a one-item menu, and Copy puts [text] on the clipboard and
/// confirms it with "Copied". Shared by the chat bubble and the item
/// thread's cards, so a whole message and a whole comment copy the same way.
///
/// Use: put [modifier] on the surface that takes the long-press, and draw
/// [Menu] inside a box anchored to that surface. With no text there is no
/// gesture and no menu.
@Stable
internal class LongPressCopy(
    private val text: String?,
    private val clipboard: ClipboardManager,
    private val context: Context,
    private val onLongPress: () -> Unit,
) {
    var menuOpen by mutableStateOf(false)
        internal set

    /// Opens the menu on a long-press. pointerInput, not combinedClickable:
    /// a plain tap on a message has no action, so it should get no ripple.
    val modifier: Modifier
        get() = if (text == null) {
            Modifier
        } else {
            Modifier.pointerInput(text) {
                detectTapGestures(onLongPress = {
                    onLongPress()
                    menuOpen = true
                })
            }
        }

    /// Puts the text on the clipboard and confirms it.
    fun copy() {
        val copied = text ?: return
        clipboard.setText(AnnotatedString(copied))
        if (copyNeedsOwnConfirmation()) Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
    }

    @Composable
    fun Menu() {
        if (text == null) return
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text("Copy") },
                onClick = {
                    menuOpen = false
                    copy()
                },
            )
        }
    }
}

@Composable
internal fun rememberLongPressCopy(text: String?): LongPressCopy {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    return remember(text, clipboard, context, haptics) {
        LongPressCopy(text, clipboard, context) { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
    }
}

/// Whether a copy shows its own "Copied" toast. From Android 13 the system
/// confirms every clipboard write itself (with a preview of what was
/// copied), and the platform guidance is not to show a second one.
internal fun copyNeedsOwnConfirmation(sdk: Int = Build.VERSION.SDK_INT): Boolean =
    sdk < Build.VERSION_CODES.TIRAMISU
