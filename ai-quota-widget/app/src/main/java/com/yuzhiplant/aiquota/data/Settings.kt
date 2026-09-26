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
        set(v) = prefs.edit().putString(KEY_SESSION, cleanSessionKey(v)).apply()

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

    /** MongoDB Atlas 服務帳號（Voyage AI 帳單用）：Client ID / Secret / 組織 ID */
    var voyageClientId: String
        get() = prefs.getString(KEY_VOYAGE_ID, "") ?: ""
        set(v) = prefs.edit().putString(KEY_VOYAGE_ID, v.trim()).apply()

    var voyageClientSecret: String
        get() = prefs.getString(KEY_VOYAGE_SECRET, "") ?: ""
        set(v) = prefs.edit().putString(KEY_VOYAGE_SECRET, v.trim()).apply()

    var voyageOrgId: String
        get() = prefs.getString(KEY_VOYAGE_ORG, "") ?: ""
        set(v) = prefs.edit().putString(KEY_VOYAGE_ORG, v.trim()).apply()

    /** Voyage AI 每月預算（美元），0 表示未設定 */
    var voyageMonthlyBudget: Double
        get() = prefs.getString(KEY_VOYAGE_BUDGET, "0")?.toDoubleOrNull() ?: 0.0
        set(v) = prefs.edit().putString(KEY_VOYAGE_BUDGET, v.toString()).apply()

    /** LINE 官方帳號 Channel access token（長效） */
    var lineChannelToken: String
        get() = prefs.getString(KEY_LINE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LINE_TOKEN, v.filterNot { it.isWhitespace() }).apply()

    /** 自行部署的 Apps Script 網址，回傳 Google Drive 用量 */
    var driveUsageUrl: String
        get() = prefs.getString(KEY_DRIVE_URL, "") ?: ""
        set(v) = prefs.edit().putString(KEY_DRIVE_URL, v.trim()).apply()

    /** Supabase Personal Access Token（sbp_ 開頭） */
    var supabaseToken: String
        get() = prefs.getString(KEY_SUPABASE_TOKEN, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_TOKEN, v.trim()).apply()

    /** 只看特定專案（逗號分隔的 project ref）；空白 = 全部專案 */
    var supabaseProjectRefs: String
        get() = prefs.getString(KEY_SUPABASE_REFS, "") ?: ""
        set(v) = prefs.edit().putString(KEY_SUPABASE_REFS, v.trim()).apply()

    /** 小工具樣式："A" 精簡列表、"B" 大數字卡片 */
    var widgetStyle: String
        get() = prefs.getString(KEY_WIDGET_STYLE, "B") ?: "B"
        set(v) = prefs.edit().putString(KEY_WIDGET_STYLE, v).apply()

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
        /** 貼上時常多帶「sessionKey=」、引號、分號或換行，一律清掉 */
        fun cleanSessionKey(raw: String): String {
            var v = raw.trim().trim('"', '\'', ';').trim()
            if (v.startsWith("sessionKey=", ignoreCase = true)) v = v.substringAfter('=')
            return v.filterNot { it.isWhitespace() }.trim('"', '\'', ';')
        }

        private const val FILE = "secure_settings"
        private const val KEY_SESSION = "claude_session_key"
        private const val KEY_ORG = "claude_org_id"
        private const val KEY_ADMIN = "anthropic_admin_key"
        private const val KEY_BUDGET = "api_monthly_budget"
        private const val KEY_VOYAGE_ID = "voyage_client_id"
        private const val KEY_VOYAGE_SECRET = "voyage_client_secret"
        private const val KEY_VOYAGE_ORG = "voyage_org_id"
        private const val KEY_VOYAGE_BUDGET = "voyage_monthly_budget"
        private const val KEY_LINE_TOKEN = "line_channel_token"
        private const val KEY_DRIVE_URL = "drive_usage_url"
        private const val KEY_SUPABASE_TOKEN = "supabase_token"
        private const val KEY_SUPABASE_REFS = "supabase_refs"
        private const val KEY_WIDGET_STYLE = "widget_style"
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
