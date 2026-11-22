package chat.matron.android.journal

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The `/notify` route's shapes (journal notification settings): what may
// push to the user's devices. The mode, its event switches and the
// per-conversation overrides are synced per user and sent live as
// `{kind:"notify", settings}`; the per-device level belongs to one device
// and rides only [NotifyView]. Pure, so tests pin the shapes without a
// server. Port of matron-apple's `NotifySettings.swift`.

data class NotifySettings(
    val mode: Mode,
    val events: Map<Event, Boolean>,
    /// Coordinator mode with no Coordinator set acts as "Every session".
    val hasCoordinator: Boolean,
    /// Latest change first, as the journal lists them.
    val convos: List<ConvoOverride>,
) {
    enum class Mode(val wire: String) {
        COORDINATOR("coordinator"),
        ALL("all"),
        CUSTOM("custom");

        companion object {
            fun fromWire(raw: String?): Mode? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// The event switches, in the order the settings screen lists them.
    enum class Event(val wire: String) {
        PROMPTS("prompts"),
        QUESTIONS("questions"),
        /// Things filed for the user to read, with a Seen button.
        NOTICES("notices"),
        COORDINATOR_DONE("coordinator_done"),
        OTHER_DONE("other_done"),
        STOPPED("stopped"),
        ROOMS("rooms"),
        ACTIVITY("activity"),
    }

    /// One conversation's own level; `null` on an override means "follow
    /// the mode".
    enum class ConvoLevel(val wire: String) {
        ALL("all"),
        NEEDS_ME("needs_me"),
        NONE("none");

        companion object {
            fun fromWire(raw: String?): ConvoLevel? = entries.firstOrNull { it.wire == raw }
        }
    }

    /// One conversation's row in `convos`: a level, a mute, or both.
    data class ConvoOverride(
        val convoID: String,
        val level: ConvoLevel? = null,
        /// Epoch milliseconds.
        val muteUntil: Long? = null,
    ) {
        /// The mute only counts while it runs; the journal lists a lapsed
        /// one until the next read, and so does this copy.
        fun isMuted(nowMs: Long): Boolean = muteUntil != null && muteUntil > nowMs

        /// Still an override at [nowMs]: a level, or a mute that has not run out.
        fun isActive(nowMs: Long): Boolean = level != null || isMuted(nowMs)
    }

    /// Whether one switch is on. `prompts` is always on: an unanswered
    /// prompt blocks an agent.
    fun isOn(event: Event): Boolean = event == Event.PROMPTS || events[event] == true

    /// The override for one conversation, as stored (lapsed mutes included).
    fun override(convoID: String): ConvoOverride? = convos.firstOrNull { it.convoID == convoID }

    companion object {
        /// The switches a preset fixes; `null` for [Mode.CUSTOM], which
        /// keeps its own. Mirrors the journal's presets.
        fun preset(mode: Mode): Map<Event, Boolean>? = when (mode) {
            Mode.COORDINATOR -> Event.entries.associateWith {
                it == Event.PROMPTS || it == Event.QUESTIONS || it == Event.NOTICES
            }
            Mode.ALL -> Event.entries.associateWith { it != Event.ROOMS && it != Event.ACTIVITY }
            Mode.CUSTOM -> null
        }

        /// The `settings` object of a `notify` frame, or the synced part of
        /// a `GET`/`PUT /notify` answer. `null` when the mode is missing or
        /// unknown. An event this build does not know is ignored; a missing
        /// one reads as off, except `prompts` (always on) and `notices`
        /// (on in every preset, and absent from a journal predating it).
        fun decode(obj: JsonObject?): NotifySettings? {
            if (obj == null) return null
            val mode = Mode.fromWire(obj.stringOrNull("mode")) ?: return null
            val raw = obj.objectOrNull("events")
            val events = Event.entries.associateWith { event ->
                event == Event.PROMPTS || (raw?.boolOrNull(event.wire) ?: (event == Event.NOTICES))
            }
            val convos = obj.arrayOrNull("convos").orEmpty().mapNotNull { element ->
                val row = element as? JsonObject ?: return@mapNotNull null
                val id = row.stringOrNull("convo_id")?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ConvoOverride(
                    convoID = id,
                    level = ConvoLevel.fromWire(row.stringOrNull("level")),
                    muteUntil = row.longOrNull("mute_until"),
                )
            }
            return NotifySettings(
                mode = mode,
                events = events,
                hasCoordinator = obj.boolOrNull("has_coordinator") ?: false,
                convos = convos,
            )
        }
    }
}

/// What this device may push, on top of everything above: all of it, only
/// what needs the user (prompts, questions and things to read), or nothing.
enum class NotifyDeviceLevel(val wire: String) {
    ALL("all"),
    NEEDS_ME("needs_me"),
    OFF("off");

    companion object {
        fun fromWire(raw: String?): NotifyDeviceLevel? = entries.firstOrNull { it.wire == raw }
    }
}

/// The whole `GET /notify` answer: the synced settings plus this device's
/// own level.
data class NotifyView(val settings: NotifySettings, val deviceLevel: NotifyDeviceLevel) {
    companion object {
        fun decode(obj: JsonObject?): NotifyView? {
            val settings = NotifySettings.decode(obj) ?: return null
            val level = NotifyDeviceLevel.fromWire(obj!!.stringOrNull("device_level")) ?: NotifyDeviceLevel.ALL
            return NotifyView(settings, level)
        }
    }
}

/// One `PUT /notify`. Each case is one change the user makes, so a failed
/// write rolls back exactly that change.
sealed interface NotifyChange {
    data class SetMode(val mode: NotifySettings.Mode) : NotifyChange
    /// Switching an event while on a preset moves the user to custom,
    /// starting from that preset.
    data class SetEvent(val event: NotifySettings.Event, val on: Boolean) : NotifyChange
    data class SetDeviceLevel(val level: NotifyDeviceLevel) : NotifyChange
    /// `null` = follow the mode. Leaves any mute as it is.
    data class ConvoLevel(val convoID: String, val level: NotifySettings.ConvoLevel?) : NotifyChange
    /// `null` = unmute. Leaves the level as it is.
    data class ConvoMute(val convoID: String, val untilMs: Long?) : NotifyChange
    /// Drops the conversation's override entirely: level and mute.
    data class ClearConvo(val convoID: String) : NotifyChange

    /// The `PUT /notify` body. Explicit `null`s clear a level or a mute; an
    /// absent key leaves it as it is.
    fun body(): JsonObject = when (this) {
        is SetMode -> buildJsonObject { put("mode", mode.wire) }
        is SetEvent -> buildJsonObject { put("events", buildJsonObject { put(event.wire, on) }) }
        is SetDeviceLevel -> buildJsonObject { put("device_level", level.wire) }
        is ConvoLevel -> convo(convoID, "level" to (level?.let { JsonPrimitive(it.wire) } ?: JsonNull))
        is ConvoMute -> convo(convoID, "mute_until" to (untilMs?.let { JsonPrimitive(it) } ?: JsonNull))
        is ClearConvo -> convo(convoID, "level" to JsonNull, "mute_until" to JsonNull)
    }

    /// The change as the journal will apply it, for the copy shown while
    /// the `PUT` is in flight.
    fun appliedTo(view: NotifyView): NotifyView {
        val s = view.settings
        return when (this) {
            // Custom keeps whatever switches were showing.
            is SetMode -> view.copy(settings = s.copy(mode = mode, events = NotifySettings.preset(mode) ?: s.events))
            is SetEvent -> view.copy(
                settings = s.copy(
                    mode = NotifySettings.Mode.CUSTOM,
                    events = s.events + (event to (event == NotifySettings.Event.PROMPTS || on)),
                ),
            )
            is SetDeviceLevel -> view.copy(deviceLevel = level)
            is ConvoLevel -> view.copy(settings = s.copy(convos = updating(s.convos, convoID) { it.copy(level = level) }))
            is ConvoMute -> view.copy(settings = s.copy(convos = updating(s.convos, convoID) { it.copy(muteUntil = untilMs) }))
            is ClearConvo -> view.copy(settings = s.copy(convos = s.convos.filterNot { it.convoID == convoID }))
        }
    }

    private companion object {
        fun convo(convoID: String, vararg fields: Pair<String, kotlinx.serialization.json.JsonElement>): JsonObject =
            buildJsonObject {
                put("convo", JsonObject(mapOf("convo_id" to JsonPrimitive(convoID)) + fields))
            }

        /// Edits one conversation's row (a changed row goes first, as the
        /// journal lists the latest change first); a row left with neither a
        /// level nor a mute is dropped, as the journal deletes it.
        fun updating(
            convos: List<NotifySettings.ConvoOverride>,
            convoID: String,
            edit: (NotifySettings.ConvoOverride) -> NotifySettings.ConvoOverride,
        ): List<NotifySettings.ConvoOverride> {
            val row = edit(convos.firstOrNull { it.convoID == convoID } ?: NotifySettings.ConvoOverride(convoID))
            val rest = convos.filterNot { it.convoID == convoID }
            return if (row.level != null || row.muteUntil != null) listOf(row) + rest else rest
        }
    }
}

/// The mute choices a conversation's Notifications menu offers.
enum class NotifyMuteDuration {
    ONE_HOUR,
    EIGHT_HOURS,
    UNTIL_TOMORROW_MORNING;

    /// When a mute started at [nowMs] ends. "Tomorrow" is the next calendar
    /// day in [zone] (the device's time zone), at 08:00.
    fun endMs(nowMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long = when (this) {
        ONE_HOUR -> nowMs + 60 * 60 * 1000L
        EIGHT_HOURS -> nowMs + 8 * 60 * 60 * 1000L
        UNTIL_TOMORROW_MORNING -> Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate().plusDays(1)
            .atTime(LocalTime.of(8, 0)).atZone(zone).toInstant().toEpochMilli()
    }
}

/// `GET` / `PUT /notify`. A journal predating the route 404s the GET
/// ([JournalApiError.NotFound]), which the screen reports. A `PUT` naming a
/// conversation the user does not own 404s too. [JournalApi] implements it;
/// tests fake it.
interface NotifySettingsProviding {
    suspend fun notifySettings(): NotifyView
    /// Returns the whole view as the journal stored it.
    suspend fun updateNotify(change: NotifyChange): NotifyView
}
