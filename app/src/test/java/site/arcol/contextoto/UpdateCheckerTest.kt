package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

class UpdateCheckerTest {
    @Test fun comparesSemanticVersions() {
        assertTrue(compareVersions("v0.2.0", "0.1.11") > 0)
        assertEquals(0, compareVersions("v0.2", "0.2.0"))
        assertTrue(compareVersions("0.1.9", "0.2.0") < 0)
        assertTrue(compareVersions("v1.0.0", "1.0.0-rc1") > 0)
        assertTrue(compareVersions("v1.0.0-rc2", "1.0.0-rc1") > 0)
        assertTrue(compareVersions("1.0.0-rc1", "0.4.1") > 0)
    }

    @Test fun updateAvailability() {
        assertTrue(UpdateInfo("0.1.1", "v0.2.0", "https://example.com").available)
        assertFalse(UpdateInfo("0.2.0", "v0.2.0", "https://example.com").available)
    }

    @Test fun downloadFractionIsBounded() {
        assertEquals(0f, DownloadProgress(DownloadPhase.CONNECTING).fraction)
        assertEquals(.5f, DownloadProgress(DownloadPhase.RECEIVING, 5, 10).fraction)
        assertEquals(1f, DownloadProgress(DownloadPhase.RECEIVING, 20, 10).fraction)
    }

    @Test fun prereleaseCanBeSelectedForInAppUpdates() {
        fun release(tag: String) = JSONObject().put("tag_name", tag)
            .put("html_url", "https://github.com/GYPp1us/ContextoTO/releases/tag/v$tag")
            .put("assets", JSONArray().put(JSONObject().put("name", "app-release.apk")
                .put("browser_download_url", "https://github.com/GYPp1us/ContextoTO/releases/download/v$tag/app-release.apk")
                .put("digest", "sha256:${"a".repeat(64)}")))
        val releases = JSONArray().put(release("0.4.1")).put(release("1.0.0-rc1"))
        val selected = selectRelease(releases, "0.4.1")
        assertEquals("1.0.0-rc1", selected.latest)
        assertTrue(selected.available)
    }
}
