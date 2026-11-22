package chat.matron.android.platform

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import chat.matron.android.models.Microphone

/// The inputs a voice note can record from, read from
/// `AudioManager.getDevices(GET_DEVICES_INPUTS)`, and the way back from a
/// remembered [Microphone.id] to the connected device.
///
/// Phones expose several built-in microphones (bottom, back, front); they are
/// one choice here, "Phone microphone", and the system picks among them.
/// Inputs that aren't a microphone the user holds (the telephony line, FM
/// tuner, remote submix, echo reference) are left out.
class MicrophoneCatalog(context: Context) {
    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /// Connected inputs, the phone's own microphone first. Empty below
    /// Android 9, where a recorder can't be pointed at an input.
    fun list(): List<Microphone> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return emptyList()
        val devices = inputs()
        return devices.mapNotNull(::microphone).distinct().sortedBy { if (it.id == BUILT_IN_ID) 0 else 1 }
    }

    /// The connected input for [id], or `null` when it isn't connected (the
    /// note then records from the system's choice).
    fun device(id: String?): AudioDeviceInfo? {
        if (id == null) return null
        return inputs().firstOrNull { microphone(it)?.id == id }
    }

    private fun inputs(): List<AudioDeviceInfo> =
        runCatching { audioManager?.getDevices(AudioManager.GET_DEVICES_INPUTS)?.toList() }.getOrNull().orEmpty()

    internal enum class Kind { BUILT_IN, WIRED, USB, BLUETOOTH }

    companion object {
        const val BUILT_IN_ID = "builtin"

        /// The pickable microphone for [device], or `null` for an input the
        /// user can't hold up to their mouth.
        fun microphone(device: AudioDeviceInfo): Microphone? {
            val kind = kindOf(device.type) ?: return null
            if (kind == Kind.BUILT_IN) return Microphone(BUILT_IN_ID, "Phone microphone")
            val product = device.productName?.toString()?.trim().orEmpty()
            return Microphone(
                id = stableID(device.type, device.address, product),
                name = displayName(kind, product),
            )
        }

        internal fun kindOf(type: Int): Kind? = when (type) {
            AudioDeviceInfo.TYPE_BUILTIN_MIC -> Kind.BUILT_IN
            AudioDeviceInfo.TYPE_WIRED_HEADSET -> Kind.WIRED
            AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_ACCESSORY -> Kind.USB
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET -> Kind.BLUETOOTH
            else -> null
        }

        /// Type plus address (a Bluetooth MAC, a USB card/device path), or
        /// the product name when there is no address: unlike
        /// `AudioDeviceInfo.id`, the same headset gets the same key on every
        /// connection.
        internal fun stableID(type: Int, address: String?, product: String): String =
            "$type:${address?.takeIf { it.isNotBlank() } ?: product}"

        /// The product name the device reports (a headset's model), falling
        /// back to what kind of input it is. The emulator and some phones
        /// report the phone's own model name for wired inputs, which says
        /// nothing, so a wired headset is always "Wired headset".
        internal fun displayName(kind: Kind, product: String): String = when (kind) {
            Kind.BUILT_IN -> "Phone microphone"
            Kind.WIRED -> "Wired headset"
            Kind.USB -> product.ifBlank { "USB microphone" }
            Kind.BLUETOOTH -> product.ifBlank { "Bluetooth headset" }
        }

        /// Bluetooth inputs only reach a recorder over a call-audio link that
        /// has to be switched on first (SCO, or LE Audio's equivalent).
        fun needsCommunicationLink(device: AudioDeviceInfo): Boolean = kindOf(device.type) == Kind.BLUETOOTH

        // AudioDeviceInfo.TYPE_BLE_HEADSET (API 31), inlined so the list
        // compiles against older constants the same way.
        private const val TYPE_BLE_HEADSET = 26
    }
}
