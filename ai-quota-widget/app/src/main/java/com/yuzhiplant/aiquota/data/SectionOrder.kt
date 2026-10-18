package com.yuzhiplant.aiquota.data

import android.content.Context
import com.yuzhiplant.aiquota.model.ProviderResult
import org.json.JSONArray

/**
 * 使用者自訂的區塊順序（App 與小工具共用）。
 * 以「群組」為單位排序：Supabase 的多個專案視為同一組，其他來源一個來源一組。
 */
object SectionOrder {
    private const val PREFS = "section_order"
    private const val KEY = "order"

    fun groupKey(resultId: String): String = if (resultId.startsWith("supabase")) "supabase" else resultId

    fun groupName(result: ProviderResult): String =
        if (result.id.startsWith("supabase")) "Supabase" else result.name

    fun load(context: Context): List<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun save(context: Context, keys: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY, JSONArray(keys).toString()).apply()
    }

    /** 依自訂順序排列；還沒排過的來源維持原本順序、排在最後。 */
    fun apply(context: Context, results: List<ProviderResult>): List<ProviderResult> {
        val order = load(context)
        if (order.isEmpty()) return results
        return results.withIndex()
            .sortedWith(compareBy<IndexedValue<ProviderResult>>({ order.indexOf(groupKey(it.value.id)).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.index }))
            .map { it.value }
    }

    /** 排序畫面用：目前有哪些群組（依現行順序、去重）。 */
    fun groups(context: Context, results: List<ProviderResult>): List<Pair<String, String>> =
        apply(context, results)
            .map { groupKey(it.id) to groupName(it) }
            .distinctBy { it.first }
}
