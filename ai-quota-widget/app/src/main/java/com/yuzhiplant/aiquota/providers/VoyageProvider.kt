package com.yuzhiplant.aiquota.providers

import android.util.Base64
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Voyage AI 本月花費。Voyage 本身沒有用量 API，但它已併入 MongoDB Atlas，
 * 帳單走 Atlas，所以透過 Atlas Admin API 讀取：
 *  1. Cost Explorer，只篩 AI Model APIs 相關服務（精準，但非同步、可能要等幾秒）
 *  2. 讀不到時退回「本月未結帳單」總額（組織內若還有其他 Atlas 服務會一起算進去）
 * 驗證用 Atlas 服務帳號（Organization Billing Viewer 權限即可）。
 */
object VoyageProvider {
    const val ID = "voyage"
    private const val NAME = "Voyage AI 花費"
    private const val BASE = "https://cloud.mongodb.com"
    private const val ACCEPT = "application/vnd.atlas.2023-01-01+json"
    private val AI_SERVICES = listOf("AI Model APIs", "Automated Embedding", "Native Reranking")

    fun fetch(settings: Settings): ProviderResult? {
        val id = settings.voyageClientId
        val secret = settings.voyageClientSecret
        val org = settings.voyageOrgId
        if (id.isEmpty() || secret.isEmpty() || org.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            val headers = mapOf("Authorization" to "Bearer ${token(id, secret)}", "Accept" to ACCEPT)
            val (spent, note) = costExplorer(org, headers)?.let { it to "" }
                ?: (pendingInvoice(org, headers) to "（Atlas 全部服務）")
            val items = MonthlyCost.items(spent, settings.voyageMonthlyBudget)
            val labelled = if (note.isEmpty()) items else items.map {
                if (it.label == "本月花費") it.copy(label = "本月花費$note") else it
            }
            ProviderResult(ID, NAME, labelled, null, now)
        } catch (e: Http.HttpException) {
            val msg = when (e.code) {
                400, 401 -> "服務帳號 ID / Secret 無效"
                403 -> "服務帳號沒有帳單讀取權限（需 Organization Billing Viewer）"
                404 -> "找不到這個 Atlas 組織 ID"
                else -> "連線錯誤（HTTP ${e.code}）"
            }
            ProviderResult(ID, NAME, emptyList(), msg, now, authError = e.code in listOf(400, 401, 403))
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /** OAuth2 client credentials 換取 access token（有效 1 小時，每次更新都重新取得）。 */
    private fun token(clientId: String, secret: String): String {
        val basic = Base64.encodeToString("$clientId:$secret".toByteArray(), Base64.NO_WRAP)
        val body = Http.post(
            "$BASE/api/oauth/token",
            mapOf("Authorization" to "Basic $basic", "Accept" to "application/json"),
            "grant_type=client_credentials",
            "application/x-www-form-urlencoded",
        )
        return JSONObject(body).getString("access_token")
    }

    /** 本月 AI 服務花費（美元）；查詢未完成或失敗回傳 null。 */
    private fun costExplorer(org: String, headers: Map<String, String>): Double? = try {
        val monthStart = LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1)
        val req = JSONObject()
            .put("startDate", monthStart.toString())
            .put("endDate", monthStart.plusMonths(1).toString())
            .put("organizations", JSONArray().put(org))
            .put("services", JSONArray(AI_SERVICES))
            .put("groupBy", "services")
        val created = JSONObject(Http.post("$BASE/api/atlas/v2/orgs/$org/billing/costExplorer/usage", headers, req.toString()))
        val queryToken = created.getString("token")
        var result: Double? = null
        for (attempt in 0 until 6) {
            if (attempt > 0) Thread.sleep(2_000)
            val body = try {
                Http.get("$BASE/api/atlas/v2/orgs/$org/billing/costExplorer/usage/$queryToken", headers)
            } catch (e: Http.HttpException) {
                if (e.code == 102) continue else throw e
            }
            if (body.isBlank()) continue
            result = sumUsageAmount(JSONObject(body))
            break
        }
        result
    } catch (e: Http.HttpException) {
        if (e.code == 401) throw e
        null
    } catch (e: Exception) {
        null
    }

    /** 結果欄位未列在官方規格，保守做法：加總所有名為 usageAmount 的數值。 */
    private fun sumUsageAmount(v: Any?): Double = when (v) {
        is JSONObject -> v.keys().asSequence().sumOf { k ->
            val child = v.opt(k)
            if (k.equals("usageAmount", ignoreCase = true)) {
                (child as? Number)?.toDouble() ?: child?.toString()?.toDoubleOrNull() ?: 0.0
            } else {
                sumUsageAmount(child)
            }
        }
        is JSONArray -> (0 until v.length()).sumOf { sumUsageAmount(v.opt(it)) }
        else -> 0.0
    }

    /** 本月未結帳單小計（美元）。 */
    private fun pendingInvoice(org: String, headers: Map<String, String>): Double {
        val json = JSONObject(Http.get("$BASE/api/atlas/v2/orgs/$org/invoices/pending", headers))
        // 規格為分頁格式 { results: [...] }，保險起見也接受直接回傳單張帳單
        val invoice = json.optJSONArray("results")?.optJSONObject(0) ?: json
        return invoice.optLong("subtotalCents", 0L) / 100.0
    }
}
