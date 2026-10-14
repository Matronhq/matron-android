package chat.matron.android.models

import java.text.Normalizer
import java.util.Locale

/** The Apple/web Projects filter's mission rules, applied to the Missions list. */
class MissionsFilter(query: String) {
    private val terms = fold(query).split(whitespace).filter(String::isNotEmpty)

    val isActive: Boolean get() = terms.isNotEmpty()

    /** Every word must occur in the title, short name or #number, in any order. */
    fun matches(mission: Mission): Boolean {
        if (!isActive) return true
        val texts = listOf(mission.title, mission.name.orEmpty(), "#${mission.num}").map(::fold)
        return terms.all { term -> texts.any { term in it } }
    }

    fun apply(missions: List<Mission>): List<Mission> = if (isActive) missions.filter(::matches) else missions

    companion object {
        private val accents = Regex("\\p{M}+")
        private val whitespace = Regex("[\\s\\p{Z}]+")

        private fun fold(text: String): String =
            accents.replace(Normalizer.normalize(text, Normalizer.Form.NFD), "").lowercase(Locale.ROOT)
    }
}
