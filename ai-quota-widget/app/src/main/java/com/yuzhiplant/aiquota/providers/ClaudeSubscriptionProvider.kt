package com.yuzhiplant.aiquota.providers

import android.content.Context
import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.data.WebViewFetcher
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Claude 訂閱方案（Pro / Max）用量：5 小時時段、本週各模型（含 Fable）使用百分比。
 *
 * 讀的是 claude.ai 網頁版「設定 → 用量」背後的同一支接口，以瀏覽器的 sessionKey cookie 驗證。
 * 這不是官方公開 API，未來格式可能改變，所以欄位採「凡是帶 utilization 的物件都顯示」的寬鬆解析。
 */
object ClaudeSubscriptionProvider {
    const val ID = "claude_subscription"
    private const val NAME = "Claude App 用量"
    private const val BASE = "https://claude.ai/api"
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Mobile Safari/537.36"

    private val labels = mapOf(
        "five_hour" to "目前時段（5 小時）",
        "seven_day" to "本週・所有模型",
        "seven_day_opus" to "本週・Opus",
        "seven_day_sonnet" to "本週・Sonnet",
        "seven_day_fable" to "本週・Fable",
        "seven_day_oauth_apps" to "本週・外部應用",
        "extra_usage" to "額外用量（本月）",
    )
    private val order = listOf("five_hour", "seven_day")

    /** claude.ai 回傳的錯誤（sessionKey 無效等），和連線層錯誤分開處理 */
    private class AuthException(message: String) : Exception(message)

    suspend fun fetch(context: Context, settings: Settings): ProviderResult? {
        val key = settings.claudeSessionKey
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            var org = settings.claudeOrgId
            if (org.isEmpty()) {
                org = pickOrg(JSONArray(get(context, "$BASE/organizations", key)))
                settings.claudeOrgId = org
            }
            val body = try {
                get(context, "$BASE/organizations/$org/usage", key)
            } catch (e: AuthException) {
                // 組織可能變了，下次重新偵測
                settings.claudeOrgId = ""
                throw e
            }
            val items = parseUsage(JSONObject(body))
            if (items.isEmpty()) {
                ProviderResult(ID, NAME, emptyList(), "回傳內容沒有用量欄位，可能是免費帳號或格式已變更", now)
            } else {
                ProviderResult(ID, NAME, items, null, now)
            }
        } catch (e: AuthException) {
            ProviderResult(ID, NAME, emptyList(), "sessionKey 無效或已過期，請重新取得後貼上（${e.message}）", now, authError = true)
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 429) "查詢太頻繁，稍後再試" else "連線錯誤（HTTP ${e.code}）"
            ProviderResult(ID, NAME, emptyList(), msg, now)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /**
     * 先用一般 HTTP 連線；被 Cloudflare 擋下（403 且回傳 HTML）時，改用隱藏 WebView 取得。
     * 回傳內容若是 claude.ai 的錯誤 JSON，丟出 AuthException。
     */
    private suspend fun get(context: Context, url: String, key: String): String {
        val headers = mapOf(
            "Cookie" to "sessionKey=$key",
            "User-Agent" to UA,
            "Accept" to "application/json",
            "Referer" to "https://claude.ai/settings/usage",
            "Origin" to "https://claude.ai",
        )
        val body = try {
            withContext(Dispatchers.IO) { Http.get(url, headers) }
        } catch (e: Http.HttpException) {
            val looksJson = e.body.trimStart().let { it.startsWith("{") || it.startsWith("[") }
            when {
                e.code == 429 -> throw e
                looksJson -> throw AuthException(errorMessage(e.body) ?: "HTTP ${e.code}")
                e.code == 401 || e.code == 403 || e.code == 503 ->
                    WebViewFetcher.fetch(context, url, "https://claude.ai", "sessionKey=$key")
                else -> throw e
            }
        }
        errorMessage(body)?.let { throw AuthException(it) }
        return body
    }

    /** claude.ai 錯誤格式：{"type":"error","error":{"type":"...","message":"..."}} */
    private fun errorMessage(body: String): String? = try {
        val o = JSONTokener(body).nextValue() as? JSONObject
        if (o != null && o.optString("type") == "error") {
            o.optJSONObject("error")?.optString("type")?.takeIf { it.isNotEmpty() } ?: "error"
        } else {
            null
        }
    } catch (e: Exception) {
        null
    }

    /** 帳號可能同時屬於多個組織，優先挑有 Pro/Max 權限的那個。 */
    private fun pickOrg(arr: JSONArray): String {
        if (arr.length() == 0) throw IllegalStateException("找不到任何組織")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val caps = o.optJSONArray("capabilities")?.toString() ?: ""
            if (caps.contains("claude_max") || caps.contains("claude_pro")) return o.getString("uuid")
        }
        return arr.getJSONObject(0).getString("uuid")
    }

    private fun parseUsage(json: JSONObject): List<QuotaItem> {
        val keys = json.keys().asSequence().toList()
            .sortedWith(compareBy<String>({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it }))
        val items = mutableListOf<QuotaItem>()
        for (key in keys) {
            val o = json.optJSONObject(key) ?: continue
            if (o.isNull("utilization")) continue
            val util = o.optDouble("utilization", Double.NaN)
            if (util.isNaN()) continue
            var money = ""
            if (o.has("used_credits") && o.has("monthly_limit") && !o.isNull("monthly_limit")) {
                money = "${Format.usdShort(o.optDouble("used_credits") / 100)} / ${Format.usdShort(o.optDouble("monthly_limit") / 100)}"
            }
            // 認得的額度才上小工具；內部代號（例如 iguana_necktie）只在 App 內列出
            val known = key in labels || key.startsWith("seven_day_")
            items += QuotaItem(
                label = labelFor(key),
                percent = util,
                detail = money,
                showInWidget = known,
                shortDetail = money,
                resetAt = Format.parseIso(o.optString("resets_at")),
            )
        }
        return items
    }

    private fun labelFor(key: String): String = labels[key]
        ?: if (key.startsWith("seven_day_")) {
            "本週・" + key.removePrefix("seven_day_").replace('_', ' ')
                .replaceFirstChar { it.uppercase() }
        } else {
            "其他額度・" + key.replace('_', ' ')
        }
}
