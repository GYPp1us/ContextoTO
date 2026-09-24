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

data class ReadingPlace(val articleId: String, val item: Int, val offset: Int)
data class Provider(val name: String, val baseUrl: String, val model: String, val key: String) {
    val official: Boolean get() = name == "DeepSeek 官方"
}

class UserStore(context: Context) : SQLiteOpenHelper(context, "contextoto.db", null, 1) {
    override fun onConfigure(db: SQLiteDatabase) {
        db.enableWriteAheadLogging()
        db.setForeignKeyConstraintsEnabled(true)
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE analysis(cache_key TEXT PRIMARY KEY, kind TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE lookup_event(article_id TEXT NOT NULL, kind TEXT NOT NULL, item_id TEXT NOT NULL, created_at INTEGER NOT NULL, PRIMARY KEY(article_id,kind,item_id))")
        db.execSQL("CREATE TABLE reading_place(singleton INTEGER PRIMARY KEY CHECK(singleton=1), article_id TEXT NOT NULL, item_index INTEGER NOT NULL, item_offset INTEGER NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun getAnalysis(key: String): String? = readableDatabase.rawQuery("SELECT payload FROM analysis WHERE cache_key=?", arrayOf(key)).use {
        if (it.moveToFirst()) it.getString(0) else null
    }
    fun putAnalysis(key: String, kind: String, payload: String) {
        val values = ContentValues().apply {
            put("cache_key", key); put("kind", kind); put("payload", payload); put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("analysis", null, values, SQLiteDatabase.CONFLICT_REPLACE)
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
}

class SecureSettings(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val alias = "contextoto_provider_key_v1"
    var dark: Boolean
        get() = prefs.getBoolean("dark", true)
        set(value) { prefs.edit().putBoolean("dark", value).apply() }
    var markQueriedWords: Boolean
        get() = prefs.getBoolean("mark_queried_words", false)
        set(value) { prefs.edit().putBoolean("mark_queried_words", value).apply() }
    var silentInference: Boolean
        get() = prefs.getBoolean("silent_inference", false)
        set(value) { prefs.edit().putBoolean("silent_inference", value).apply() }
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
    fun provider(): Provider {
        val name = providerName
        val key = prefs.getString("key_${name}", null)?.let(::decrypt).orEmpty()
        return when (name) {
            "Command Code GOAT" -> Provider(name, "https://api.commandcode.ai/provider/v1", "deepseek/deepseek-v4.1-flash", key)
            "自定义" -> Provider(name, customBaseUrl.trimEnd('/'), customModel, key)
            else -> Provider("DeepSeek 官方", "https://api.deepseek.com", "deepseek-flash", key)
        }
    }
}
