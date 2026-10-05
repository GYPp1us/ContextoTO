package site.arcol.contextoto

import android.app.Application
import androidx.lifecycle.AndroidViewModel

class ContextoApplication : Application() {
    val content by lazy { Content(this).also { LearningStore(store, it).refreshContent() } }
    val store by lazy { UserStore(this) }
    val settings by lazy { SecureSettings(this) }
    val engine by lazy {
        settings.rememberCacheProvider(settings.provider())
        AnalysisEngine(content, store, legacyProviders = settings::cacheProviders)
    }
}

class ReaderViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = application as ContextoApplication
    val content = runtime.content
    val store = runtime.store
    val settings = runtime.settings
    val engine = runtime.engine
}
