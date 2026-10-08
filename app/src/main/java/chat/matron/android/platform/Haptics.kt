package chat.matron.android.platform

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/// Small, restrained foreground vibrations for meaningful moments. Pure Kotlin
/// so the view models that fire these can be unit-tested against a fake; only
/// [SystemHaptics] touches the platform vibrator.
interface Haptics {
    /// QR link succeeded — a light two-beat pop.
    fun celebrate()

    /// Genuine link failure — one short buzz. Never fired on a deliberate decline.
    fun error()

    /// Agent turn completed — one soft tick.
    fun tick()

    companion object {
        /// No-op default so a VM constructed without haptics (existing call
        /// sites, tests) does nothing rather than crashing.
        val None: Haptics = object : Haptics {
            override fun celebrate() {}
            override fun error() {}
            override fun tick() {}
        }
    }
}

/// Platform [Haptics] over the system vibrator. Every call is a no-op when the
/// device has no vibrator; the OS haptic setting is already honoured by the
/// vibrator itself. API 31+ uses crisp `Composition` primitives; API 26–30 uses
/// `createWaveform`/`createOneShot` shaped to the same feel. Effect amplitudes
/// are hand-tuned on device — the values below are the agreed starting point.
class SystemHaptics(context: Context) : Haptics {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private val hasVibrator: Boolean = vibrator?.hasVibrator() == true

    override fun celebrate() {
        val v = vibrator ?: return
        if (!hasVibrator) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.8f, 60)
                    .compose(),
            )
        } else {
            v.vibrate(
                VibrationEffect.createWaveform(
                    longArrayOf(0, 25, 60, 40),
                    intArrayOf(0, 90, 0, 160),
                    -1, // -1 = do not repeat
                ),
            )
        }
    }

    override fun error() {
        val v = vibrator ?: return
        if (!hasVibrator) return
        v.vibrate(VibrationEffect.createOneShot(60, 180))
    }

    override fun tick() {
        val v = vibrator ?: return
        if (!hasVibrator) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                    .compose(),
            )
        } else {
            v.vibrate(VibrationEffect.createOneShot(20, 60))
        }
    }
}
