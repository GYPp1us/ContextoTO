package site.arcol.contextoto

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StatusDefaultsTest {
    @Test fun immersiveDefaultsOnButPreservesAnExplicitOptOut() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val name = "release-status-defaults-test"
        val context = object : ContextWrapper(base) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences = base.getSharedPreferences(name, mode)
        }
        val prefs = base.getSharedPreferences(name, Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        try {
            assertTrue(SecureSettings(context).customStatusBar)
            SecureSettings(context).customStatusBar = false
            prefs.edit().commit()
            assertFalse(SecureSettings(context).customStatusBar)
            SecureSettings(context).customStatusBar = true
            prefs.edit().commit()
            assertTrue(SecureSettings(context).customStatusBar)
        } finally { prefs.edit().clear().commit() }
    }
}
