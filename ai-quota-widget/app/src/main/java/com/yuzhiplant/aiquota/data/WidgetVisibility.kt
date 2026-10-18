package com.yuzhiplant.aiquota.data

import android.content.Context
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject

/**
 * 小工具要顯示哪些平台、哪些額度（只影響桌面小工具，App 內一律全部顯示）。
 * 沒設定過的項目沿用預設：一般額度顯示；內部代號額度、今日花費等細項隱藏；
 * Supabase 各專案的項目預設「用量 ≥ 50% 才顯示」。
 */
object WidgetVisibility {
    private const val PREFS = "widget_visibility"
    private const val KEY = "map"

    fun load(context: Context): Map<String, Boolean> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyMap()
        return try {
            val o = JSONObject(raw)
            o.keys().asSequence().associateWith { o.optBoolean(it, true) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    fun set(context: Context, key: String, visible: Boolean) {
        val map = load(context).toMutableMap()
        map[key] = visible
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun groupKey(groupKey: String) = "g:$groupKey"

    fun itemKey(resultId: String, label: String) = "i:$resultId|$label"

    fun groupVisible(map: Map<String, Boolean>, groupKey: String): Boolean = map[groupKey(groupKey)] ?: true

    /** 使用者明確設定過就照設定；否則回傳 null，由呼叫端套預設規則。 */
    fun itemOverride(map: Map<String, Boolean>, resultId: String, item: QuotaItem): Boolean? =
        map[itemKey(resultId, item.label)]

    /** 一般來源的預設：照 provider 給的 showInWidget；Supabase 專案項目預設看用量。 */
    fun defaultVisible(resultId: String, item: QuotaItem): Boolean =
        if (resultId.startsWith("supabase")) (item.percent ?: 0.0) >= 50 else item.showInWidget

    fun itemVisible(map: Map<String, Boolean>, resultId: String, item: QuotaItem): Boolean =
        itemOverride(map, resultId, item) ?: defaultVisible(resultId, item)
}
