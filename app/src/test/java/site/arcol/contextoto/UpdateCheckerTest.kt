package site.arcol.contextoto

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {
    @Test fun comparesSemanticVersions() {
        assertTrue(compareVersions("v0.2.0", "0.1.11") > 0)
        assertEquals(0, compareVersions("v0.2", "0.2.0"))
        assertTrue(compareVersions("0.1.9", "0.2.0") < 0)
    }

    @Test fun updateAvailability() {
        assertTrue(UpdateInfo("0.1.1", "v0.2.0", "https://example.com").available)
        assertFalse(UpdateInfo("0.2.0", "v0.2.0", "https://example.com").available)
    }
}
