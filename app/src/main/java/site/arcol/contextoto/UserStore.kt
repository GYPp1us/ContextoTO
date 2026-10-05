package site.arcol.contextoto

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

data class ReadingPlace(val articleId: String, val item: Int, val offset: Int)
data class Provider(val name: String, val baseUrl: String, val model: String, val key: String,
                    val supportedEfforts: List<String> = emptyList(), val defaultEffort: String = "",
                    val protocol: ApiProtocol = ApiProtocol.CHAT) {
    val official: Boolean get() = name == "DeepSeek 官方"
}
data class AnalysisRecord(val key: String, val kind: String, val payload: JSONObject)
data class BookmarkPlace(val paragraph: Int = 0, val sentenceStart: Int = 0) : Comparable<BookmarkPlace> {
    override fun compareTo(other: BookmarkPlace): Int = compareValuesBy(this, other, { it.paragraph }, { it.sentenceStart })
}

class UserStore(context: Context, databaseName: String = "contextoto.db") : SQLiteOpenHelper(context, databaseName, null, 4) {
    init {
        // Best-effort pre-upgrade recovery copy. SQLiteOpenHelper also runs upgrades in one transaction.
        val source = context.getDatabasePath(databaseName)
        if (source.exists()) runCatching {
            val oldVersion = SQLiteDatabase.openDatabase(source.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
            if (oldVersion in 1..3) listOf("", "-wal").forEach { suffix ->
                val part = java.io.File(source.path + suffix)
                val backup = java.io.File(source.path + ".before-rc4" + suffix)
                if (part.exists() && !backup.exists()) part.copyTo(backup)
            }
        }
    }
    private val learningEpoch = kotlinx.coroutines.flow.MutableStateFlow(0)
    val learningRevision = learningEpoch
    fun bumpLearningRevision() { learningEpoch.value += 1 }
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE analysis(cache_key TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE lookup_event(article_id TEXT NOT NULL, kind TEXT NOT NULL, item_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(article_id,kind,item_id))")
        db.execSQL("CREATE TABLE reading_place(singleton INTEGER PRIMARY KEY CHECK(singleton=1), article_id TEXT NOT NULL, item_index INTEGER NOT NULL, item_offset INTEGER NOT NULL)")
        createBookmarks(db)
        LearningStore.create(db)
    }
    private fun createBookmarks(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS bookmark(article_id TEXT PRIMARY KEY, paragraph_index INTEGER NOT NULL, sentence_start INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) createBookmarks(db)
        if (oldVersion == 2) db.execSQL("ALTER TABLE bookmark ADD COLUMN sentence_start INTEGER NOT NULL DEFAULT 0")
        if (oldVersion < 4) LearningStore.create(db)
    }

    fun getAnalysis(key: String): String? = readableDatabase.rawQuery("SELECT payload FROM analysis WHERE cache_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun analysisTime(key: String): Long = readableDatabase.rawQuery("SELECT created_at FROM analysis WHERE cache_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getLong(0) else 0L
    }
    fun putAnalysis(key: String, kind: String, payload: String) {
        val values = ContentValues().apply {
            put("cache_key", key); put("kind", kind); put("payload", payload); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun mergeAnalyses(records: List<AnalysisRecord>) {
        if (records.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            records.forEach { record ->
                val previous = db.rawQuery("SELECT payload FROM analysis WHERE cache_key=?", arrayOf(record.key)).use {
                    if (it.moveToFirst()) runCatching { JSONObject(it.getString(0)) }.getOrNull() else null
                }
                val values = ContentValues().apply {
                    put("cache_key", record.key); put("kind", record.kind)
                    put("payload", mergeAnalysisPayload(previous, record.payload).toString())
                    put("created_at", System.currentTimeMillis())
                }
                db.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }
    fun recordLookup(articleId: String, kind: String, itemId: String) {
        val values = ContentValues().apply {
            put("article_id", articleId); put("kind", kind); put("item_id", itemId); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("lookup_event", null, values, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun countLookups(articleId: String, kind: String): Int = readableDatabase.rawQuery(
        "SELECT COUNT(*) FROM lookup_event WHERE article_id=? AND kind=?", arrayOf(articleId, kind)
    ).use { if (it.moveToFirst()) it.getInt(0) else 0 }
    fun queriedWords(articleId: String): Set<String> = readableDatabase.rawQuery(
        "SELECT item_id FROM lookup_event WHERE article_id=? AND kind='word'", arrayOf(articleId)
    ).use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    fun getPlace(): ReadingPlace? = readableDatabase.rawQuery(
        "SELECT article_id,item_index,item_offset FROM reading_place WHERE singleton=1", null
    ).use { if (it.moveToFirst()) ReadingPlace(it.getString(0), it.getInt(1), it.getInt(2)) else null }
    fun savePlace(articleId: String, item: Int, offset: Int) {
        val values = ContentValues().apply {
            put("singleton", 1); put("article_id", articleId); put("item_index", item); put("item_offset", offset)
        }
        writableDatabase.insertWithOnConflict("reading_place", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
    fun bookmarkPlace(articleId: String): BookmarkPlace = readableDatabase.rawQuery(
        "SELECT paragraph_index,sentence_start FROM bookmark WHERE article_id=?", arrayOf(articleId)
    ).use { if (it.moveToFirst()) BookmarkPlace(it.getInt(0), it.getInt(1)) else BookmarkPlace() }
    fun bookmark(articleId: String): Int = bookmarkPlace(articleId).paragraph
    fun advanceBookmark(articleId: String, paragraphIndex: Int): Int =
        advanceSentenceBookmark(articleId, BookmarkPlace(paragraphIndex)).paragraph
    fun advanceSentenceBookmark(articleId: String, place: BookmarkPlace): BookmarkPlace {
        if (place.paragraph < 0 || place.sentenceStart < 0) return bookmarkPlace(articleId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val furthest = maxOf(bookmarkPlace(articleId), place)
            val values = ContentValues().apply {
                put("article_id", articleId); put("paragraph_index", furthest.paragraph)
                put("sentence_start", furthest.sentenceStart); put("updated_at", System.currentTimeMillis())
            }
            db.insertWithOnConflict("bookmark", null, values, SQLiteDatabase.CONFLICT_REPLACE)
            db.setTransactionSuccessful()
            return furthest
        } finally { db.endTransaction() }
    }
}

class SecureSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val alias = "contextoto_provider_key_v1"
    var guideStep: Int
        get() = prefs.getInt("guide_step_v1", -1)
        set(value) { prefs.edit().putInt("guide_step_v1", value).apply() }
    var dark: Boolean
        get() = prefs.getBoolean("dark", true)
        set(value) { prefs.edit().putBoolean("dark", value).apply() }
    var markQueriedWords: Boolean
        get() = prefs.getBoolean("mark_queried_words", false)
        set(value) { prefs.edit().putBoolean("mark_queried_words", value).apply() }
    var silentInference: Boolean
        get() = prefs.getBoolean("silent_inference", false)
        set(value) { prefs.edit().putBoolean("silent_inference", value).apply() }
    var customStatusBar: Boolean
        get() = prefs.getBoolean("custom_status_bar", false)
        set(value) { prefs.edit().putBoolean("custom_status_bar", value).apply() }
    var bookmarkVisible: Boolean
        get() = prefs.getBoolean("bookmark_visible", true)
        set(value) { prefs.edit().putBoolean("bookmark_visible", value).apply() }
    var bodySize: Float
        get() = prefs.getFloat("body_size", 20f).coerceIn(16f, 28f)
        set(value) { prefs.edit().putFloat("body_size", value.coerceIn(16f, 28f)).apply() }
    var lineFactor: Float
        get() = prefs.getFloat("line_factor", 1.58f).coerceIn(1.25f, 2.0f)
        set(value) { prefs.edit().putFloat("line_factor", value.coerceIn(1.25f, 2.0f)).apply() }
    var sideMargin: Float
        get() = prefs.getFloat("side_margin", 18f).coerceIn(12f, 44f)
        set(value) { prefs.edit().putFloat("side_margin", value.coerceIn(12f, 44f)).apply() }
    var paragraphGap: Float
        get() = prefs.getFloat("paragraph_gap", 30f).coerceIn(12f, 72f)
        set(value) { prefs.edit().putFloat("paragraph_gap", value.coerceIn(12f, 72f)).apply() }
    var providerName: String
        get() = prefs.getString("provider", "DeepSeek 官方") ?: "DeepSeek 官方"
        set(value) { prefs.edit().putString("provider", value).apply() }
    var customBaseUrl: String
        get() = prefs.getString("custom_url", "") ?: ""
        set(value) { prefs.edit().putString("custom_url", value).apply() }
    var customModel: String
        get() = prefs.getString("custom_model", "") ?: ""
        set(value) { prefs.edit().putString("custom_model", value).apply() }
    var focusUrl: String
        get() = prefs.getString("focus_url", null)?.let(::decrypt).orEmpty()
        set(value) { prefs.edit().putString("focus_url", if (value.isBlank()) "" else encrypt(value.trim())).apply() }
    var focusSubjectId: Int
        get() = prefs.getInt("focus_subject_id", 0)
        set(value) { prefs.edit().putInt("focus_subject_id", value).apply() }
    var focusItemId: Int
        get() = prefs.getInt("focus_item_id", 0)
        set(value) { prefs.edit().putInt("focus_item_id", value).apply() }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        return generator.generateKey()
    }
    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        return Base64.encodeToString(cipher.iv + cipher.doFinal(text.toByteArray()), Base64.NO_WRAP)
    }
    private fun decrypt(value: String): String = runCatching {
        val bytes = Base64.decode(value, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrDefault("")

    fun saveKey(provider: String, key: String) {
        prefs.edit().putString("key_${provider}", encrypt(key.trim())).apply()
    }
    fun savedKey(name: String): String = prefs.getString("key_$name", null)?.let(::decrypt).orEmpty()
    fun modelForProvider(name: String): String = prefs.getString("selected_model_$name", null)
        ?.takeIf { it.isNotBlank() } ?: when (name) {
            "Command Code GOAT" -> "deepseek/deepseek-v4.1-flash"
            "自定义" -> customModel
            else -> "deepseek-flash"
        }
    fun saveModel(name: String, id: String) {
        prefs.edit().putString("selected_model_$name", id.trim()).apply()
        if (name == "自定义") customModel = id.trim()
    }
    fun protocolForProvider(name: String): ApiProtocol = runCatching {
        ApiProtocol.valueOf(prefs.getString("protocol_$name", "CHAT") ?: "CHAT")
    }.getOrDefault(ApiProtocol.CHAT)
    fun saveProtocol(name: String, protocol: ApiProtocol) {
        prefs.edit().putString("protocol_$name", protocol.name).apply()
    }
    fun rememberCacheProvider(provider: Provider) {
        val sources = cacheProviders().filter { it.baseUrl.isNotBlank() && it.model.isNotBlank() }
            .plus(provider).distinctBy { it.baseUrl to it.model }
        prefs.edit().putString("cache_sources", org.json.JSONArray(sources.map {
            JSONObject().put("base_url", it.baseUrl).put("model", it.model)
        }).toString()).apply()
    }
    fun cacheProviders(): List<Provider> {
        val items = runCatching { org.json.JSONArray(prefs.getString("cache_sources", "[]")) }.getOrDefault(org.json.JSONArray())
        val remembered = (0 until items.length()).mapNotNull { index ->
            items.optJSONObject(index)?.let { Provider("", it.optString("base_url"), it.optString("model"), "") }
        }
        return remembered + listOf("DeepSeek 官方", "Command Code GOAT", "自定义").map { name ->
            Provider(name, providerBaseUrl(name, customBaseUrl), modelForProvider(name), "")
        } + listOf(Provider("", "https://api.deepseek.com", "deepseek-flash", ""),
            Provider("", "https://api.commandcode.ai/provider/v1", "deepseek/deepseek-v4.1-flash", ""))
    }
    fun cachedModels(baseUrl: String, key: String): List<ModelOption> = runCatching {
        parseModelCatalog(JSONObject(prefs.getString(modelCatalogKey(baseUrl, key), null) ?: return emptyList()))
    }.getOrDefault(emptyList())
    fun saveModelCatalog(baseUrl: String, key: String, models: List<ModelOption>) {
        prefs.edit().putString(modelCatalogKey(baseUrl, key), modelCatalogJson(models).toString()).apply()
    }
    private fun modelCatalogKey(baseUrl: String, key: String): String =
        "model_catalog_" + Content.sha256("${baseUrl.trim().trimEnd('/')}|$key")
    fun provider(): Provider {
        val name = providerName
        val key = savedKey(name)
        val baseUrl = providerBaseUrl(name, customBaseUrl)
        val model = modelForProvider(name)
        val option = cachedModels(baseUrl, key).firstOrNull { it.id == model }
        return Provider(name, baseUrl, model, key, option?.supportedEfforts.orEmpty(), option?.defaultEffort.orEmpty(),
            protocolForProvider(name))
    }
}
