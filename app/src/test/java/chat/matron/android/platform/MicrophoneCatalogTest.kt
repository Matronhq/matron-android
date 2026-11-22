package chat.matron.android.platform

import android.media.AudioDeviceInfo
import chat.matron.android.designsystem.microphoneMenuSelection
import chat.matron.android.models.Microphone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicrophoneCatalogTest {
    @Test
    fun kinds_keepHeldInputsAndDropTheRest() {
        assertEquals(MicrophoneCatalog.Kind.BUILT_IN, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(MicrophoneCatalog.Kind.WIRED, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(MicrophoneCatalog.Kind.USB, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(MicrophoneCatalog.Kind.USB, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_USB_DEVICE))
        assertEquals(MicrophoneCatalog.Kind.BLUETOOTH, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(MicrophoneCatalog.Kind.BLUETOOTH, MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertNull(MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_TELEPHONY))
        assertNull(MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_REMOTE_SUBMIX))
        assertNull(MicrophoneCatalog.kindOf(AudioDeviceInfo.TYPE_FM_TUNER))
    }

    @Test
    fun stableID_usesTheAddressAndFallsBackToTheProduct() {
        assertEquals("7:00:11:22:33:44:55", MicrophoneCatalog.stableID(7, "00:11:22:33:44:55", "Headset A"))
        assertEquals("22:USB headset B", MicrophoneCatalog.stableID(22, "", "USB headset B"))
        assertEquals("22:USB headset B", MicrophoneCatalog.stableID(22, null, "USB headset B"))
    }

    @Test
    fun displayName_prefersTheProductExceptForWiredInputs() {
        assertEquals("Headset A", MicrophoneCatalog.displayName(MicrophoneCatalog.Kind.BLUETOOTH, "Headset A"))
        assertEquals("Bluetooth headset", MicrophoneCatalog.displayName(MicrophoneCatalog.Kind.BLUETOOTH, ""))
        assertEquals("USB microphone", MicrophoneCatalog.displayName(MicrophoneCatalog.Kind.USB, ""))
        assertEquals("Wired headset", MicrophoneCatalog.displayName(MicrophoneCatalog.Kind.WIRED, "Phone model X"))
        assertEquals("Phone microphone", MicrophoneCatalog.displayName(MicrophoneCatalog.Kind.BUILT_IN, "Phone model X"))
    }

    @Test
    fun menuSelection_isThePickOrTheMicrophoneInUse() {
        val phone = Microphone("builtin", "Phone microphone")
        assertEquals("builtin", microphoneMenuSelection(phone, null))
        assertEquals("7:x", microphoneMenuSelection(phone, "7:x"))
        assertNull(microphoneMenuSelection(null, null))
    }

    @Test
    fun bluetooth_isOfferedFromAndroid12Only() {
        assertEquals(false, MicrophoneCatalog.offered(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 30))
        assertEquals(true, MicrophoneCatalog.offered(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, 31))
        assertEquals(true, MicrophoneCatalog.offered(AudioDeviceInfo.TYPE_USB_HEADSET, 28))
        assertEquals(true, MicrophoneCatalog.offered(AudioDeviceInfo.TYPE_BUILTIN_MIC, 28))
    }
}
