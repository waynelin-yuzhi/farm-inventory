package com.yuzhiplant.aiquota.providers

import android.content.Context
import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.data.WebViewFetcher
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.json.JSONTokener

/**
 * ChatGPT 訂閱的 Codex 用量：5 小時與每週額度 %（chatgpt.com/codex 設定頁背後的接口）。
 * ChatGPT 一般對話的訊息上限沒有任何接口可查，只能看 Codex。
 *
 * 流程：用瀏覽器的 session-token cookie 換 accessToken（/api/auth/session），再查 /backend-api/wham/usage。
 * 非官方接口，格式可能改變，所以解析盡量寬鬆。
 */
object ChatGptProvider {
    const val ID = "chatgpt"
    private const val NAME = "ChatGPT 用量（Codex）"
    private const val ORIGIN = "https://chatgpt.com"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private const val DEBUG_PREFS = "debug"
    private const val KEY_RAW = "chatgpt_usage_raw"

    /** 最近一次的原始回應（只有用量數字，不含金鑰），設定頁可複製給開發者除錯 */
    fun lastRaw(context: Context): String =
        context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).getString(KEY_RAW, "") ?: ""

    private class AuthException(message: String) : Exception(message)

    suspend fun fetch(context: Context, settings: Settings): ProviderResult? {
        val token = settings.chatgptSessionToken
        if (token.isEmpty()) return null
        val now = System.currentTimeMillis()
        val cookie = if (token.contains("session-token")) token else "__Secure-next-auth.session-token=$token"
        return try {
            val session = JSONObject(get(context, "$ORIGIN/api/auth/session", mapOf("Cookie" to cookie), cookie))
            val access = session.optString("accessToken").takeIf { it.isNotEmpty() && it != "null" }
                ?: throw AuthException("登入已失效")
            val headers = mutableMapOf("Authorization" to "Bearer $access")
            session.optJSONObject("account")?.optString("id")?.takeIf { it.isNotEmpty() && it != "null" }
                ?.let { headers["ChatGPT-Account-Id"] = it }
            val body = get(context, "$ORIGIN/backend-api/wham/usage", headers, cookie)
            context.getSharedPreferences(DEBUG_PREFS, Context.MODE_PRIVATE).edit().putString(KEY_RAW, body).apply()
            val items = parse(JSONObject(body))
            if (items.isEmpty()) {
                ProviderResult(ID, NAME, emptyList(), "回傳內容沒有 Codex 用量，可能是免費帳號或格式已變更", now)
            } else {
                ProviderResult(ID, NAME, items, null, now)
            }
        } catch (e: AuthException) {
            ProviderResult(ID, NAME, emptyList(), "session token 無效或已過期，請重新取得後貼上（${e.message}）", now, authError = true)
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 429) "查詢太頻繁，稍後再試" else "連線錯誤（HTTP ${e.code}）"
            ProviderResult(ID, NAME, emptyList(), msg, now)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /** 先用一般 HTTP；被 Cloudflare 擋下（回傳 HTML）時改用隱藏 WebView。 */
    private suspend fun get(context: Context, url: String, extra: Map<String, String>, cookie: String): String {
        val headers = mapOf(
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "$ORIGIN/codex/settings/usage",
        ) + extra
        return try {
            withContext(Dispatchers.IO) { Http.get(url, headers) }
        } catch (e: Http.HttpException) {
            val looksJson = e.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            when {
                e.code == 429 -> throw e
                looksJson && (e.code == 401 || e.code == 403) -> throw AuthException(errorMessage(e.body) ?: "HTTP ${e.code}")
                looksJson -> throw e
                e.code == 401 || e.code == 403 || e.code == 503 ->
                    WebViewFetcher.fetch(context, url, ORIGIN, cookie, extra - "Cookie")
                else -> throw e
            }
        }
    }

    private fun errorMessage(body: String): String? = try {
        val o = JSONTokener(body).nextValue() as? JSONObject
        o?.optJSONObject("error")?.optString("message")?.takeIf { it.isNotBlank() }
            ?: o?.optString("detail")?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun parse(json: JSONObject): List<QuotaItem> {
        val items = mutableListOf<QuotaItem>()
        json.optJSONObject("rate_limit")?.let { rl ->
            windowItem(rl.optJSONObject("primary_window"), "Codex")?.let { items += it }
            windowItem(rl.optJSONObject("secondary_window"), "Codex")?.let { items += it }
        }
        // 其他額外額度（例如特定模型），只在 App 內顯示
        json.optJSONArray("additional_rate_limits")?.let { arr ->
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val name = listOf("limit_name", "metered_feature", "name")
                    .map { o.optString(it) }.firstOrNull { it.isNotEmpty() && it != "null" } ?: "其他"
                val rl = o.optJSONObject("rate_limit") ?: o
                listOf("primary_window", "secondary_window").forEach { k ->
                    windowItem(rl.optJSONObject(k), name)?.let { items += it.copy(showInWidget = false) }
                }
            }
        }
        json.optJSONObject("credits")?.let { c ->
            if (c.optBoolean("has_credits") && !c.optBoolean("unlimited")) {
                val bal = c.optString("balance").toDoubleOrNull()
                if (bal != null) items += QuotaItem("Codex 點數餘額", null, Format.number(bal), showInWidget = false)
            }
        }
        json.optString("plan_type").takeIf { it.isNotEmpty() && it != "null" }?.let {
            items += QuotaItem("方案", null, it.replaceFirstChar { ch -> ch.uppercase() }, showInWidget = false)
        }
        return items
    }

    /** {used_percent, limit_window_seconds, reset_after_seconds, reset_at(秒)} */
    private fun windowItem(w: JSONObject?, prefix: String): QuotaItem? {
        if (w == null || w.isNull("used_percent")) return null
        val pct = w.optDouble("used_percent", Double.NaN).takeIf { !it.isNaN() } ?: return null
        val secs = w.optLong("limit_window_seconds", 0)
        val window = when {
            secs in 1L..(6 * 3600L) -> "5 小時"
            secs in (6 * 24 * 3600L)..(8 * 24 * 3600L) -> "本週"
            secs >= 24 * 3600 -> "${secs / (24 * 3600)} 天"
            secs > 0 -> "${secs / 3600} 小時"
            else -> "額度"
        }
        val resetAt = when {
            w.optLong("reset_at", 0) > 0 -> w.optLong("reset_at") * 1000
            w.optLong("reset_after_seconds", 0) > 0 -> System.currentTimeMillis() + w.optLong("reset_after_seconds") * 1000
            else -> 0L
        }
        return QuotaItem(label = "$prefix・$window", percent = pct, detail = "", resetAt = resetAt)
    }
}
