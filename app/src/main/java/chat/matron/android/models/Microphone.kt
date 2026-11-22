package chat.matron.android.models

/// An audio input a voice note can record from: the phone's own microphone,
/// a wired or USB headset, a Bluetooth headset. [id] is stable across
/// reconnects (Android's own device ids are handed out afresh on every
/// connection), so a remembered pick finds the same headset again.
data class Microphone(val id: String, val name: String)
