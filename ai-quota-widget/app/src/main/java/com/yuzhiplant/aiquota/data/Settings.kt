package com.yuzhiplant.aiquota.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.yuzhiplant.aiquota.model.CustomSource
import org.json.JSONArray

/** 所有設定與金鑰，存在加密的 SharedPreferences（Android Keystore 保護）。 */
class Settings(context: Context) {

    private val prefs: SharedPreferences = open(context.applicationContext)

    var claudeSessionKey: String
        get() = prefs.getString(KEY_SESSION, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SESSION, v.trim()).apply()

    /** 自動偵測後快取的 claude.ai 組織 ID；sessionKey 變更時清空。 */
    var claudeOrgId: String
        get() = prefs.getString(KEY_ORG, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ORG, v).apply()

    var anthropicAdminKey: String
        get() = prefs.getString(KEY_ADMIN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_ADMIN, v.trim()).apply()

    /** Claude API 每月預算（美元），0 表示未設定 */
    var apiMonthlyBudget: Double
        get() = prefs.getString(KEY_BUDGET, "0")?.toDoubleOrNull() ?: 0.0
        set(v) = prefs.edit().putString(KEY_BUDGET, v.toString()).apply()

    /** Supabase Personal Access Token（sbp_ 開頭） */
    var supabaseToken: String
        get() = prefs.getString(KEY_SUPABASE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_TOKEN, v.trim()).apply()

    /** 只看特定專案（逗號分隔的 project ref）；空白 = 全部專案 */
    var supabaseProjectRefs: String
        get() = prefs.getString(KEY_SUPABASE_REFS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_REFS, v.trim()).apply()

    var refreshMinutes: Int
        get() = prefs.getInt(KEY_INTERVAL, 30)
        set(v) = prefs.edit().putInt(KEY_INTERVAL, v.coerceAtLeast(15)).apply()

    var customSources: List<CustomSource>
        get() {
            val raw = prefs.getString(KEY_CUSTOM, "[]") ?: "[]"
            return try {
                val arr = JSONArray(raw)
                (0 until arr.length()).map { CustomSource.fromJson(arr.getJSONObject(it)) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(list) {
            val arr = JSONArray().apply { list.forEach { put(it.toJson()) } }
            prefs.edit().putString(KEY_CUSTOM, arr.toString()).apply()
        }

    companion object {
        private const val FILE = "secure_settings"
        private const val KEY_SESSION = "claude_session_key"
        private const val KEY_ORG = "claude_org_id"
        private const val KEY_ADMIN = "anthropic_admin_key"
        private const val KEY_BUDGET = "api_monthly_budget"
        private const val KEY_SUPABASE_TOKEN = "supabase_token"
        private const val KEY_SUPABASE_REFS = "supabase_refs"
        private const val KEY_INTERVAL = "refresh_minutes"
        private const val KEY_CUSTOM = "custom_sources"

        private fun open(context: Context): SharedPreferences = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // 少數機型 Keystore 異常時退回一般儲存，至少 App 能用
            context.getSharedPreferences("${FILE}_plain", Context.MODE_PRIVATE)
        }
    }
}
