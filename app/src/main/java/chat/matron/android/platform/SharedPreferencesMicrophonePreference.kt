package chat.matron.android.platform

import android.content.Context
import chat.matron.android.viewmodels.MicrophonePreference

/// Keeps the voice-note microphone pick in this device's own preferences:
/// the headsets connected to a phone are that phone's, so the pick is not
/// synced to the account. A pick whose headset isn't connected simply falls
/// back to the system's choice.
class SharedPreferencesMicrophonePreference(context: Context) : MicrophonePreference {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): String? = prefs.getString(KEY, null)

    override fun save(id: String?) {
        prefs.edit().apply { if (id == null) remove(KEY) else putString(KEY, id) }.apply()
    }

    private companion object {
        const val FILE = "voice-notes"
        const val KEY = "preferred-microphone"
    }
}
