package chat.matron.android.viewmodels

/// One box's usage numbers, as the Boxes screen weighs them.
data class BoxAllowanceReading(val boxID: Long, val accountEmail: String?, val lines: List<LimitLine>)

/// The login with the most allowance to spend per day before it resets.
data class AllowanceSpare(
    /// Null for boxes that report no login.
    val accountEmail: String?,
    /// Every box on this login, in reading order.
    val boxIDs: List<Long>,
    val percentLeft: Int,
    val percentPerDay: Int,
    /// Epoch ms.
    val resetsAt: Long,
)

/// Whether a box can be reached, and how.
enum class BoxConnectionState(val title: String) {
    Online("Online"),
    /// Disconnected, and the journal can start it again.
    Asleep("Asleep"),
    /// Disconnected, with no way to start it from here.
    Offline("Offline"),
}

/// The Boxes screen's two highlights and its captions, off the views so they
/// unit-test. Port of matron-apple's `BoxAllowances`.
object BoxAllowances {
    /// The bridge's stable id for the all-models weekly meter.
    const val ALL_MODELS_LINE_ID = "week_all"
    /// Where a usage percentage turns red everywhere else in the app.
    const val NEAR_LIMIT_PERCENT = 80
    private const val DAY_MS = 86_400_000.0

    /// Whether the box's weekly meter is near its limit. An expired line
    /// predates its reset, so it no longer says anything about today.
    fun isNearLimit(lines: List<LimitLine>, nowMs: Long = System.currentTimeMillis()): Boolean {
        val line = lines.firstOrNull { it.id == ALL_MODELS_LINE_ID } ?: return false
        return line.percent >= NEAR_LIMIT_PERCENT && !BoxCapacity.hasReset(line.resetsAt, nowMs)
    }

    /// The login with the highest percent left per day until its reset.
    ///
    /// Boxes sharing a login spend one allowance, so they count once, at
    /// their highest reading and earliest reset. A reset under a day away
    /// counts as one full day: otherwise a sliver left with an hour to go
    /// would outrank a login with most of its week ahead of it.
    fun mostSpare(readings: List<BoxAllowanceReading>, nowMs: Long = System.currentTimeMillis()): AllowanceSpare? {
        data class Group(val email: String?, val boxIDs: MutableList<Long>, var percent: Int, var resetsAt: Long)

        val groups = linkedMapOf<String, Group>()
        for (reading in readings) {
            val line = reading.lines.firstOrNull { it.id == ALL_MODELS_LINE_ID } ?: continue
            val resetsAt = line.resetsAt ?: continue
            if (resetsAt <= nowMs) continue
            val key = reading.accountEmail?.let { "login:$it" } ?: "box:${reading.boxID}"
            val group = groups[key]
            if (group != null) {
                group.boxIDs += reading.boxID
                group.percent = maxOf(group.percent, line.percent)
                group.resetsAt = minOf(group.resetsAt, resetsAt)
            } else {
                groups[key] = Group(reading.accountEmail, mutableListOf(reading.boxID), line.percent, resetsAt)
            }
        }
        var best: AllowanceSpare? = null
        var bestRate = 0.0
        for (group in groups.values) {
            val left = maxOf(0, 100 - group.percent)
            val days = maxOf(1.0, (group.resetsAt - nowMs) / DAY_MS)
            val rate = left / days
            val beatsBest = rate > bestRate || (rate == bestRate && best != null && group.resetsAt < best.resetsAt)
            if (rate <= 0 || !beatsBest) continue
            bestRate = rate
            best = AllowanceSpare(
                accountEmail = group.email,
                boxIDs = group.boxIDs.toList(),
                percentLeft = left,
                percentPerDay = Math.round(rate).toInt(),
                resetsAt = group.resetsAt,
            )
        }
        return best
    }

    /// "4 min ago", "2 h ago", "3 d ago", or "just now" under a minute.
    fun ageText(sinceMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val seconds = (nowMs - sinceMs) / 1000
        return when {
            seconds < 60 -> "just now"
            seconds < 3_600 -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / 3_600} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }

    /// "Claude · Opus · high" for a box's default agent, model and effort.
    /// A bare alias is capitalised; a full model id is left as it is.
    fun defaultsText(agent: String, model: String?, effort: String?): String {
        val agentName = when (agent) {
            AgentOption.CODEX -> "Codex"
            AgentOption.CLAUDE -> "Claude"
            else -> agent
        }
        val modelName = if (!model.isNullOrEmpty()) {
            if (model.all { it.isLetter() }) model.replaceFirstChar { it.uppercase() } else model
        } else if (agent == AgentOption.CODEX) {
            "Codex's own model"
        } else {
            "Claude Code's own model"
        }
        return (listOf(agentName, modelName) + listOfNotNull(effort?.takeIf { it.isNotEmpty() })).joinToString(" · ")
    }
}
