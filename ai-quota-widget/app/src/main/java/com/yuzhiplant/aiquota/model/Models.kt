package com.yuzhiplant.aiquota.model

import org.json.JSONArray
import org.json.JSONObject

/** 單一額度項目，例如「Claude・本週所有模型 42%」。percent 為 null 代表只有數值、沒有上限。 */
data class QuotaItem(
    val label: String,
    val percent: Double?,
    val detail: String,
    /** false：只在 App 內顯示，桌面小工具略過（節省空間） */
    val showInWidget: Boolean = true,
    /** 小工具用的精簡說明，例如「$10.41 / $20」「64 MB / 8 GB」；空白時不顯示 */
    val shortDetail: String = "",
    /** 額度重置時間（epoch ms），0 表示沒有；畫面顯示時才換算成「3 小時後重置」 */
    val resetAt: Long = 0,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("label", label)
        .put("percent", percent ?: JSONObject.NULL)
        .put("detail", detail)
        .put("showInWidget", showInWidget)
        .put("shortDetail", shortDetail)
        .put("resetAt", resetAt)

    companion object {
        fun fromJson(o: JSONObject) = QuotaItem(
            label = o.optString("label"),
            percent = if (o.isNull("percent")) null else o.optDouble("percent"),
            detail = o.optString("detail"),
            showInWidget = o.optBoolean("showInWidget", true),
            shortDetail = o.optString("shortDetail"),
            resetAt = o.optLong("resetAt"),
        )
    }
}

/** 一個來源（服務）一次抓取的結果。 */
data class ProviderResult(
    val id: String,
    val name: String,
    val items: List<QuotaItem>,
    val error: String?,
    val updatedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("items", JSONArray().apply { items.forEach { put(it.toJson()) } })
        .put("error", error ?: JSONObject.NULL)
        .put("updatedAt", updatedAt)

    companion object {
        fun fromJson(o: JSONObject): ProviderResult {
            val arr = o.optJSONArray("items") ?: JSONArray()
            return ProviderResult(
                id = o.optString("id"),
                name = o.optString("name"),
                items = (0 until arr.length()).map { QuotaItem.fromJson(arr.getJSONObject(it)) },
                error = if (o.isNull("error")) null else o.optString("error"),
                updatedAt = o.optLong("updatedAt"),
            )
        }
    }
}

/** 使用者自訂的 JSON API 來源（任何有「查詢用量」API 的工具都能接）。 */
data class CustomSource(
    val id: String,
    val name: String,
    val url: String,
    /** 每行一個「Header-Name: value」 */
    val headers: String,
    /** 數值欄位路徑，例如 data.credits 或 items[0].used */
    val valuePath: String,
    /** 上限欄位路徑（選填） */
    val limitPath: String,
    /** 直接回傳百分比的欄位路徑（選填，有填就優先使用） */
    val percentPath: String,
    val unit: String,
    /** true：valuePath 取到的是「剩餘量」；false：是「已使用量」 */
    val valueIsRemaining: Boolean,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("url", url)
        .put("headers", headers)
        .put("valuePath", valuePath)
        .put("limitPath", limitPath)
        .put("percentPath", percentPath)
        .put("unit", unit)
        .put("valueIsRemaining", valueIsRemaining)

    companion object {
        fun fromJson(o: JSONObject) = CustomSource(
            id = o.optString("id"),
            name = o.optString("name"),
            url = o.optString("url"),
            headers = o.optString("headers"),
            valuePath = o.optString("valuePath"),
            limitPath = o.optString("limitPath"),
            percentPath = o.optString("percentPath"),
            unit = o.optString("unit"),
            valueIsRemaining = o.optBoolean("valueIsRemaining"),
        )
    }
}
