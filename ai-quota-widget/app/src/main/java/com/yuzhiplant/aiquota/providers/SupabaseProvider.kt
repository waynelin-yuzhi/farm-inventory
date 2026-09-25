package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * Supabase：透過官方 Management API（Personal Access Token）。
 * 每個專案一張卡：資料庫大小、檔案儲存量（對比方案額度）、暫停狀態。
 * 另外嘗試讀取組織本月用量（流量 / MAU / Edge Function 次數），
 * 那支是 Supabase 後台自用接口，讀不到就略過、不顯示錯誤。
 */
object SupabaseProvider {
    private const val BASE = "https://api.supabase.com"
    private const val MB = 1_000_000.0
    private const val GB = 1_000_000_000.0

    /** 方案 → (資料庫上限, 檔案儲存上限)，單位 bytes */
    private val planLimits = mapOf(
        "free" to (500 * MB to 1 * GB),
        "pro" to (8 * GB to 100 * GB),
        "team" to (8 * GB to 100 * GB),
    )

    private val orgMetrics = linkedMapOf(
        "EGRESS" to "本月流量",
        "MONTHLY_ACTIVE_USERS" to "本月活躍用戶",
        "FUNCTION_INVOCATIONS" to "Edge Function 次數",
    )

    fun fetch(settings: Settings): List<ProviderResult> {
        val token = settings.supabaseToken
        if (token.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val headers = mapOf("Authorization" to "Bearer $token", "Accept" to "application/json")

        val projects = try {
            JSONArray(Http.get("$BASE/v1/projects", headers))
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 401) "Access token 無效" else "連線錯誤（HTTP ${e.code}）"
            return listOf(ProviderResult("supabase", "Supabase", emptyList(), msg, now))
        } catch (e: Exception) {
            return listOf(ProviderResult("supabase", "Supabase", emptyList(), "讀取失敗：${e.message}", now))
        }

        val wanted = settings.supabaseProjectRefs.split(',', ' ', '\n')
            .map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val planCache = mutableMapOf<String, String>()
        val results = mutableListOf<ProviderResult>()
        val orgSlugs = linkedSetOf<String>()

        for (i in 0 until projects.length()) {
            val p = projects.getJSONObject(i)
            val ref = p.optString("ref")
            if (wanted.isNotEmpty() && ref !in wanted) continue
            val status = p.optString("status")
            if (status == "REMOVED") continue
            val slug = p.optString("organization_slug")
            if (slug.isNotEmpty()) orgSlugs += slug
            val plan = planCache.getOrPut(slug) { orgPlan(slug, headers) }
            results += projectResult(p, plan, headers, now)
        }
        if (results.isEmpty()) {
            results += ProviderResult("supabase", "Supabase", emptyList(), "找不到符合的專案", now)
        }
        for (slug in orgSlugs) orgUsage(slug, headers, now)?.let { results += it }
        return results
    }

    private fun projectResult(p: JSONObject, plan: String, headers: Map<String, String>, now: Long): ProviderResult {
        val ref = p.optString("ref")
        val id = "supabase_$ref"
        val name = "Supabase・${p.optString("name", ref)}"
        val status = p.optString("status")
        if (status != "ACTIVE_HEALTHY") {
            val label = when (status) {
                "INACTIVE", "PAUSING" -> "已暫停"
                "COMING_UP", "RESTORING", "RESTARTING", "UPGRADING", "RESIZING" -> "啟動／維護中"
                else -> "狀態異常"
            }
            return ProviderResult(id, name, listOf(QuotaItem("專案狀態", null, "$label（$status）")), null, now)
        }

        return try {
            val row = sizes(ref, headers)
            val (dbLimit, storageLimit) = planLimits[plan] ?: planLimits.getValue("free")
            val items = mutableListOf<QuotaItem>()
            row.optDouble("db_bytes").takeIf { !it.isNaN() }?.let {
                items += QuotaItem(
                    "資料庫大小", it / dbLimit * 100, "${Format.bytes(it)} / ${Format.bytes(dbLimit)}",
                    shortDetail = "${Format.bytesShort(it)} / ${Format.bytesShort(dbLimit)}",
                )
            }
            row.optDouble("storage_bytes").takeIf { !it.isNaN() }?.let {
                items += QuotaItem(
                    "檔案儲存", it / storageLimit * 100, "${Format.bytes(it)} / ${Format.bytes(storageLimit)}",
                    shortDetail = "${Format.bytesShort(it)} / ${Format.bytesShort(storageLimit)}",
                )
            }
            ProviderResult(id, name, items, if (items.isEmpty()) "查詢結果沒有大小欄位" else null, now)
        } catch (e: Http.HttpException) {
            val msg = if (e.code == 403) "Token 沒有讀取資料庫的權限" else "查詢失敗（HTTP ${e.code}）"
            ProviderResult(id, name, emptyList(), msg, now)
        } catch (e: Exception) {
            ProviderResult(id, name, emptyList(), "查詢失敗：${e.message}", now)
        }
    }

    private const val SIZE_SQL =
        "select pg_database_size(current_database())::bigint as db_bytes, " +
            "(select coalesce(sum((metadata->>'size')::bigint), 0) from storage.objects)::bigint as storage_bytes"

    /** 先走唯讀查詢端點；token 權限不足時改走一般查詢端點（read_only 模式）。 */
    private fun sizes(ref: String, headers: Map<String, String>): JSONObject {
        val body = try {
            Http.post("$BASE/v1/projects/$ref/database/query/read-only", headers, JSONObject().put("query", SIZE_SQL).toString())
        } catch (e: Http.HttpException) {
            if (e.code != 403 && e.code != 404) throw e
            Http.post(
                "$BASE/v1/projects/$ref/database/query", headers,
                JSONObject().put("query", SIZE_SQL).put("read_only", true).toString(),
            )
        }
        return firstRow(JSONTokener(body).nextValue()) ?: throw IllegalStateException("查詢沒有回傳資料")
    }

    private fun firstRow(v: Any?): JSONObject? = when (v) {
        is JSONArray -> if (v.length() > 0) v.optJSONObject(0) else null
        is JSONObject -> firstRow(v.opt("result") ?: v.opt("rows") ?: v.opt("data")) ?: v.takeIf { it.has("db_bytes") }
        else -> null
    }

    private fun orgPlan(slug: String, headers: Map<String, String>): String = try {
        JSONObject(Http.get("$BASE/v1/organizations/$slug", headers)).optString("plan", "free")
    } catch (e: Exception) {
        "free"
    }

    /** 組織本月用量：非公開接口，任何錯誤都回傳 null（不顯示這張卡）。 */
    private fun orgUsage(slug: String, headers: Map<String, String>, now: Long): ProviderResult? = try {
        val json = JSONObject(Http.get("$BASE/platform/organizations/$slug/usage", headers))
        val usages = json.optJSONArray("usages") ?: JSONArray()
        val items = mutableListOf<QuotaItem>()
        for ((metric, label) in orgMetrics) {
            val u = (0 until usages.length()).map { usages.getJSONObject(it) }
                .firstOrNull { it.optString("metric") == metric } ?: continue
            val used = u.optDouble("usage", Double.NaN)
            if (used.isNaN()) continue
            val free = u.optDouble("pricing_free_units", Double.NaN)
                .takeIf { !it.isNaN() && it > 0 } ?: u.optDouble("available_in_plan", Double.NaN)
            items += if (!free.isNaN() && free > 0 && !u.optBoolean("unlimited")) {
                QuotaItem(label, used / free * 100, "${Format.number(used)} / ${Format.number(free)}")
            } else {
                QuotaItem(label, null, Format.number(used))
            }
        }
        if (items.isEmpty()) null else ProviderResult("supabase_org_$slug", "Supabase 本月用量", items, null, now)
    } catch (e: Exception) {
        null
    }
}
