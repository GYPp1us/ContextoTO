package site.arcol.contextoto

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat

/** Empty host for offline, isolated layout tests; excluded from release APKs. */
class LayoutTestActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }
}
