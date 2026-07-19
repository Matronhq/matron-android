# Compact-Context Header Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show a one-tap "compact this conversation" banner at the top of a chat once its context passes 200,000 tokens.

**Architecture:** A pure design-system banner (`CompactContextBanner`) renders a coloured strip from a token count; a pure predicate (`shouldShowCompactHeader`) decides when it appears; `ChatScreen` reads `ChatViewModel.sessionStatus`, gates on the predicate, and on tap sends a bare `/compact` through a new narrow `ComposerViewModel.sendCommand` seam that reuses the existing send path.

**Tech Stack:** Kotlin, Jetpack Compose, kotlinx.coroutines, JUnit 4.

## Global Constraints

- Scope is `matron-android` only. No journal/relay/protocol changes.
- Trigger is an **absolute** threshold: show when `SessionStatus.Context.tokens > 200_000`, independent of `window`. Null context → do not show.
- Threshold is a single named constant `COMPACT_HEADER_TOKEN_THRESHOLD = 200_000`, living beside the banner composable.
- Banner copy: `"Large conversation (<n> tokens) · Tap to compact"` where `<n>` is `UsageMetersFormat.compactTokens(tokens)`. Reuse the existing formatter; do not hand-roll number formatting.
- TalkBack content description uses `UsageMetersFormat.spokenTokens(tokens)`: `"Large conversation, <spoken> tokens, tap to compact"`.
- On tap: send a **bare** `/compact` (no arguments, no confirmation dialog, no pre-fill) through the existing composer/timeline send path.
- The banner is a pure `@Composable` taking `tokens: Int` and `onCompact: () -> Unit`; it always renders and holds no view-model dependency. The show/hide decision lives entirely in the caller.
- Structurally mirror `ConnectionStatusBanner`: `Row`, `.fillMaxWidth()`, coloured background, `.padding(horizontal = 12.dp, vertical = 8.dp)`, single-line text with `TextOverflow.Ellipsis`.
- Build/test command: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest`.
- Commits end with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

---

### Task 1: `ComposerViewModel.sendCommand` send seam

**Files:**
- Modify: `app/src/main/java/chat/matron/android/viewmodels/ComposerViewModel.kt`
- Test: `app/src/test/java/chat/matron/android/viewmodels/ComposerViewModelTest.kt`

**Interfaces:**
- Consumes: `TimelineService.sendText(String)` (existing); `FakeTimelineService.sentText: List<String>` and `FakeTimelineService.nextSendError: Throwable?` in tests (existing).
- Produces: `suspend fun ComposerViewModel.sendCommand(text: String)` — sends `text` verbatim through the timeline, records `sendError` on failure. Consumed by `ChatScreen` (Task 4).

- [ ] **Step 1: Write the failing tests**

Add to `ComposerViewModelTest.kt` (the `makeVM` helper and `FakeTimelineService` already exist in this file's imports):

```kotlin
    @Test
    fun sendCommand_sendsTextVerbatimThroughTimeline() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.sendCommand("/compact")
        assertEquals(listOf("/compact"), fake.sentText)
        assertNull(vm.sendError.value)
    }

    @Test
    fun sendCommand_recordsSendError_whenServiceThrows() = runBlocking {
        val fake = FakeTimelineService()
        fake.nextSendError = RuntimeException("boom")
        val vm = makeVM(timeline = fake)
        vm.sendCommand("/compact")
        assertEquals("boom", vm.sendError.value)
    }

    @Test
    fun sendCommand_doesNotTouchComposerInput() = runBlocking {
        val fake = FakeTimelineService()
        val vm = makeVM(timeline = fake)
        vm.input = "half-typed draft"
        vm.sendCommand("/compact")
        assertEquals("half-typed draft", vm.input)
        assertEquals(listOf("/compact"), fake.sentText)
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.ComposerViewModelTest"`
Expected: FAIL — `sendCommand` is unresolved (compile error).

- [ ] **Step 3: Write minimal implementation**

Add this method to `ComposerViewModel`, immediately after `send()` (after its closing brace near line 222):

```kotlin
    /// Sends [text] as a plain message through the same timeline path as [send],
    /// bypassing the composer input and attachment tray. Used by one-tap
    /// affordances such as the compact-context header. Records [sendError] on
    /// failure; never mutates [input] or the staged attachments.
    suspend fun sendCommand(text: String) {
        try {
            timeline.sendText(text)
            _sendError.value = null
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            _sendError.value = error.message ?: error.toString()
        }
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --tests "chat.matron.android.viewmodels.ComposerViewModelTest"`
Expected: PASS (all three new tests plus the existing ones).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/viewmodels/ComposerViewModel.kt app/src/test/java/chat/matron/android/viewmodels/ComposerViewModelTest.kt
git commit -m "feat: ComposerViewModel.sendCommand one-tap send seam

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Threshold predicate + constant

**Files:**
- Create: `app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt`
- Test: `app/src/test/java/chat/matron/android/designsystem/CompactContextBannerTest.kt`

**Interfaces:**
- Consumes: `chat.matron.android.models.SessionStatus.Context` (existing: `data class Context(val tokens: Int, val window: Int, val pct: Int)`).
- Produces: `const val COMPACT_HEADER_TOKEN_THRESHOLD = 200_000` and `fun shouldShowCompactHeader(context: SessionStatus.Context?): Boolean`. Consumed by `ChatScreen` (Task 4). The composable is added to the same file in Task 3.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/chat/matron/android/designsystem/CompactContextBannerTest.kt`:

```kotlin
package chat.matron.android.designsystem

import chat.matron.android.models.SessionStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactContextBannerTest {
    private fun context(tokens: Int) = SessionStatus.Context(tokens = tokens, window = 1_000_000, pct = 0)

    @Test
    fun shouldShowCompactHeader_isFalse_whenContextIsNull() {
        assertFalse(shouldShowCompactHeader(null))
    }

    @Test
    fun shouldShowCompactHeader_isFalse_atExactlyThreshold() {
        assertFalse(shouldShowCompactHeader(context(COMPACT_HEADER_TOKEN_THRESHOLD)))
    }

    @Test
    fun shouldShowCompactHeader_isTrue_oneTokenOverThreshold() {
        assertTrue(shouldShowCompactHeader(context(COMPACT_HEADER_TOKEN_THRESHOLD + 1)))
    }

    @Test
    fun shouldShowCompactHeader_ignoresWindowSize() {
        // A huge window does not suppress the header — the trigger is absolute.
        val ctx = SessionStatus.Context(tokens = 250_000, window = 1_000_000, pct = 25)
        assertTrue(shouldShowCompactHeader(ctx))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --tests "chat.matron.android.designsystem.CompactContextBannerTest"`
Expected: FAIL — `shouldShowCompactHeader` and `COMPACT_HEADER_TOKEN_THRESHOLD` are unresolved.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt`:

```kotlin
package chat.matron.android.designsystem

import chat.matron.android.models.SessionStatus

/// Absolute context size (in tokens) past which the compact-context header
/// appears. Absolute, not a fraction of the model's window: the concern is
/// cost/latency/recall at large sizes, which a 1M-window model shares.
const val COMPACT_HEADER_TOKEN_THRESHOLD = 200_000

/// Whether the compact-context header should show for [context]. Null (no status
/// yet) and exactly-at-threshold do not show; strictly above does.
fun shouldShowCompactHeader(context: SessionStatus.Context?): Boolean =
    context != null && context.tokens > COMPACT_HEADER_TOKEN_THRESHOLD
```

- [ ] **Step 4: Run test to verify it passes**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest --tests "chat.matron.android.designsystem.CompactContextBannerTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt app/src/test/java/chat/matron/android/designsystem/CompactContextBannerTest.kt
git commit -m "feat: compact-context header threshold predicate

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: `CompactContextBanner` composable

**Files:**
- Modify: `app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt`

**Interfaces:**
- Consumes: `UsageMetersFormat.compactTokens(Int)` and `UsageMetersFormat.spokenTokens(Int)` (existing, same package).
- Produces: `@Composable fun CompactContextBanner(tokens: Int, onCompact: () -> Unit, modifier: Modifier = Modifier)`. Consumed by `ChatScreen` (Task 4).

No unit test: this is a presentation-only composable with no branching logic (the show/hide predicate is already tested in Task 2 and the formatters already have coverage in `UsageMetersFormatTest`). This mirrors how `ConnectionStatusBanner` is covered.

- [ ] **Step 1: Add the composable**

Append to `app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt` (add the imports at the top of the file, below the existing `import chat.matron.android.models.SessionStatus`):

```kotlin
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Compress
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
```

```kotlin
/// Tappable strip pinned at the top of a large conversation nudging the user to
/// compact it. Always renders the coloured strip for [tokens]; the show/hide
/// decision belongs to the caller (see [shouldShowCompactHeader]). Structurally
/// mirrors [ConnectionStatusBanner]. Tapping calls [onCompact], which sends a
/// bare `/compact`.
@Composable
fun CompactContextBanner(
    tokens: Int,
    onCompact: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val spoken = "Large conversation, ${UsageMetersFormat.spokenTokens(tokens)} tokens, tap to compact"
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClick = onCompact)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = spoken },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Compress,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            "Large conversation (${UsageMetersFormat.compactTokens(tokens)} tokens) · Tap to compact",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL. (If `Icons.Filled.Compress` is unresolved, replace with `Icons.Filled.UnfoldLess` — both ship in `androidx.compose.material:material-icons-core`; pick whichever resolves and keep the import in sync.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/chat/matron/android/designsystem/CompactContextBanner.kt
git commit -m "feat: CompactContextBanner composable

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Wire the banner into `ChatScreen`

**Files:**
- Modify: `app/src/main/java/chat/matron/android/features/chat/ChatScreen.kt`

**Interfaces:**
- Consumes: `ChatViewModel.sessionStatus: StateFlow<SessionStatus?>` (existing); `shouldShowCompactHeader` + `CompactContextBanner` (Tasks 2–3); `ComposerViewModel.sendCommand` (Task 1).
- Produces: nothing downstream.

No unit test: this is UI composition. The predicate and send seam it wires are already unit-tested in Tasks 1–2; the visual result is verified by hand, consistent with `ConnectionStatusBanner`.

- [ ] **Step 1: Add state collection + a send scope in `ChatScreen`**

In `ChatScreen` (the `@Composable fun ChatScreen(...)`), alongside the other `collectAsStateWithLifecycle()` calls near line 80–83, add:

```kotlin
    val sessionStatus by chatVM.sessionStatus.collectAsStateWithLifecycle()
```

And, alongside the other `remember`/scope declarations near line 85–88, add a coroutine scope for the tap (import `androidx.compose.runtime.rememberCoroutineScope` and `kotlinx.coroutines.launch` — `launch` is already imported):

```kotlin
    val compactScope = rememberCoroutineScope()
```

- [ ] **Step 2: Render the banner in the top `Column`**

In the top `Column(modifier = Modifier.fillMaxSize())` (near line 116), insert the banner directly above `RunningSubagentStrip(...)` so it sits in the same band as the connection/subagent strips, above the timeline:

```kotlin
                if (shouldShowCompactHeader(sessionStatus?.context)) {
                    CompactContextBanner(
                        tokens = sessionStatus!!.context!!.tokens,
                        onCompact = { compactScope.launch { composerVM.sendCommand("/compact") } },
                    )
                }
                RunningSubagentStrip(runningChildren = runningChildren, onOpenChild = onOpenChild)
```

Add the imports at the top of `ChatScreen.kt`:

```kotlin
import chat.matron.android.designsystem.CompactContextBanner
import chat.matron.android.designsystem.shouldShowCompactHeader
```

- [ ] **Step 3: Verify it compiles**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Run the full unit-test suite**

Run: `JAVA_HOME=/opt/homebrew/opt/openjdk@17 ./gradlew testDebugUnitTest`
Expected: PASS (no regressions).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/chat/matron/android/features/chat/ChatScreen.kt
git commit -m "feat: show compact-context header in ChatScreen over 200k tokens

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review Notes

- **Spec coverage:** trigger (Task 2 predicate, absolute 200k, null-safe, window-ignoring), placement (Task 4, top `Column` band above the timeline), copy + TalkBack (Task 3, reusing `compactTokens`/`spokenTokens`), one-tap bare `/compact` (Task 1 seam + Task 4 wiring, no dialog/pre-fill), self-hiding (Task 4 re-reads live `sessionStatus`, so a post-compact lower count drops below threshold and the banner disappears). All covered.
- **Type consistency:** `shouldShowCompactHeader(SessionStatus.Context?)`, `CompactContextBanner(tokens: Int, onCompact: () -> Unit, modifier)`, `sendCommand(text: String)` used identically across tasks.
- **YAGNI:** no dismissibility, no percentage triggers, no custom-instruction UI — all listed out-of-scope in the spec and omitted here.
