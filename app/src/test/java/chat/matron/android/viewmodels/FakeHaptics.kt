package chat.matron.android.viewmodels

import chat.matron.android.platform.Haptics

/// Records haptic calls so trigger edges can be asserted in unit tests.
class FakeHaptics : Haptics {
    var celebrateCount = 0
    var errorCount = 0
    var tickCount = 0
    override fun celebrate() { celebrateCount++ }
    override fun error() { errorCount++ }
    override fun tick() { tickCount++ }
}
