package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject

/**
 * LINE 官方帳號本月訊息用量（官方 Messaging API）。
 * 額度用完就推播不出去，客戶會收不到養護報告與通知。
 */
object LineProvider {
    const val ID = "line"
    private const val NAME = "LINE 官方帳號"
    private const val BASE = "https://api.line.me/v2/bot/message"

    fun fetch(settings: Settings): ProviderResult? {
        val token = settings.lineChannelToken
        if (token.isEmpty()) return null
        val now = System.currentTimeMillis()
        val headers = mapOf("Authorization" to "Bearer $token")
        return try {
            val quota = JSONObject(Http.get("$BASE/quota", headers))
            val used = JSONObject(Http.get("$BASE/quota/consumption", headers)).optLong("totalUsage")
            val item = if (quota.optString("type") == "limited" && quota.optLong("value") > 0) {
                val limit = quota.optLong("value")
                QuotaItem(
                    "本月訊息",
                    used * 100.0 / limit,
                    "${Format.number(used.toDouble())} / ${Format.number(limit.toDouble())} 則・剩 ${Format.number((limit - used).coerceAtLeast(0).toDouble())} 則",
                )
            } else {
                QuotaItem("本月訊息", null, "${Format.number(used.toDouble())} 則（方案無上限）")
            }
            ProviderResult(ID, NAME, listOf(item), null, now)
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 401) "Channel access token 無效" else "連線錯誤（HTTP ${e.code}）"
            ProviderResult(ID, NAME, emptyList(), msg, now)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }
}
