package chat.matron.android.platform

import android.content.Intent
import androidx.compose.ui.platform.UriHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackerLinkHandoffTest {
    @Test
    fun onlyAViewOfAWebLinkIsAnIncomingLink() {
        val url = "https://journal.example.com/u/alice/4"
        assertEquals(url, incomingViewLink(Intent.ACTION_VIEW, url))
        assertEquals("HTTP://localhost/u/alice/4", incomingViewLink(Intent.ACTION_VIEW, "HTTP://localhost/u/alice/4"))
        assertNull("the launcher", incomingViewLink(Intent.ACTION_MAIN, null))
        assertNull(incomingViewLink(Intent.ACTION_MAIN, url))
        assertNull(incomingViewLink(Intent.ACTION_VIEW, null))
        assertNull(incomingViewLink(Intent.ACTION_VIEW, "matron://item/4"))
        assertNull(incomingViewLink(Intent.ACTION_VIEW, "content://x/y"))
    }

    @Test
    fun aRecreatedActivityResumesOnlyTheLinkStillWaiting() {
        val url = "https://journal.example.com/u/alice/4"
        assertEquals("a fresh launch reads its intent", url, launchLink(restored = false, saved = null, action = Intent.ACTION_VIEW, data = url))
        assertEquals(
            "a link still waiting survives the recreate",
            url,
            launchLink(restored = true, saved = url, action = Intent.ACTION_MAIN, data = null),
        )
        assertNull(
            "the launching intent of a restored activity was taken already",
            launchLink(restored = true, saved = null, action = Intent.ACTION_VIEW, data = url),
        )
    }

    @Test
    fun trackerLinksStayInAppAndEverythingElseGoesToThePlatform() {
        val platform = mutableListOf<String>()
        val inApp = mutableListOf<String>()
        val handler = TrackerLinkUriHandler(
            platform = object : UriHandler { override fun openUri(uri: String) { platform += uri } },
            isTrackerLink = { it.contains("/u/") },
            openTrackerLink = { inApp += it },
        )
        handler.openUri("https://journal.example.com/u/alice/4")
        handler.openUri("https://example.com/docs")
        assertEquals(listOf("https://journal.example.com/u/alice/4"), inApp)
        assertEquals(listOf("https://example.com/docs"), platform)
    }

    @Test
    fun theBrowserIsNeverThisApp() {
        val own = "chat.matron.android"
        assertEquals("org.browser", BrowserHandoff.pickBrowser("org.browser", listOf(own, "org.browser", "org.other"), own))
        assertEquals("no default picked: the first other viewer", "org.other", BrowserHandoff.pickBrowser("android", listOf(own, "org.other"), own))
        assertEquals("org.other", BrowserHandoff.pickBrowser(own, listOf(own, "org.other"), own))
        assertEquals("a default that cannot view this link is passed over", "org.other", BrowserHandoff.pickBrowser("org.browser", listOf("org.other"), own))
        assertNull(BrowserHandoff.pickBrowser(null, listOf(own), own))
        assertNull(BrowserHandoff.pickBrowser(null, emptyList(), own))
    }
}
