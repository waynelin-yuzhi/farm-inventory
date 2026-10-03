package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.model.CustomSource
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/** 通用來源：呼叫任一 JSON API，依使用者指定的欄位路徑取出用量與上限。 */
object CustomJsonProvider {

    fun fetch(src: CustomSource): ProviderResult {
        val now = System.currentTimeMillis()
        val id = "custom_${src.id}"
        return try {
            val root = JSONTokener(Http.get(src.url.trim(), parseHeaders(src.headers))).nextValue()
            val unit = src.unit.trim().let { if (it.isEmpty()) "" else " $it" }

            val item = if (src.percentPath.isNotBlank()) {
                val p = number(resolve(root, src.percentPath)) ?: error("找不到百分比欄位 ${src.percentPath}")
                val used = if (src.valueIsRemaining) 100 - p else p
                QuotaItem("用量", used, "")
            } else {
                val value = number(resolve(root, src.valuePath)) ?: error("找不到數值欄位 ${src.valuePath}")
                val limit = if (src.limitPath.isBlank()) null else number(resolve(root, src.limitPath))
                when {
                    limit != null && limit > 0 && src.valueIsRemaining -> QuotaItem(
                        "用量", (limit - value) / limit * 100,
                        "剩 ${Format.number(value)} / ${Format.number(limit)}$unit",
                    )
                    limit != null && limit > 0 -> QuotaItem(
                        "用量", value / limit * 100,
                        "${Format.number(value)} / ${Format.number(limit)}$unit",
                    )
                    src.valueIsRemaining -> QuotaItem("剩餘", null, "${Format.number(value)}$unit")
                    else -> QuotaItem("已使用", null, "${Format.number(value)}$unit")
                }
            }
            ProviderResult(id, src.name, listOf(item), null, now)
        } catch (e: Http.HttpException) {
            val auth = e.code == 401 || e.code == 403
            ProviderResult(id, src.name, emptyList(), if (auth) "金鑰無效或沒有權限（HTTP ${e.code}）" else "連線錯誤（HTTP ${e.code}）", now, authError = auth)
        } catch (e: Exception) {
            ProviderResult(id, src.name, emptyList(), e.message ?: e.javaClass.simpleName, now)
        }
    }

    private fun parseHeaders(raw: String): Map<String, String> = raw.lines()
        .mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }
        .toMap() + ("Accept" to "application/json")

    /** 支援 a.b.c、a[0].b、a.0.b 形式的路徑。 */
    fun resolve(root: Any?, path: String): Any? {
        var cur: Any? = root
        for (m in Regex("""[^.\[\]]+|\[\d+]""").findAll(path.trim())) {
            val t = m.value
            val c = cur
            cur = when {
                t.startsWith("[") -> (c as? JSONArray)?.opt(t.trim('[', ']').toInt())
                c is JSONObject -> c.opt(t)
                c is JSONArray -> t.toIntOrNull()?.let { c.opt(it) }
                else -> null
            }
            if (cur == null || cur == JSONObject.NULL) return null
        }
        return cur
    }

    private fun number(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }
}
