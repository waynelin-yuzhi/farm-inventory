package com.yuzhiplant.aiquota.data

import android.content.Context
import com.yuzhiplant.aiquota.model.ProviderResult
import org.json.JSONArray

/** 最近一次抓取結果；App 畫面與桌面小工具都從這裡讀。 */
object ResultCache {
    private const val FILE = "result_cache"
    private const val KEY = "results"

    fun save(context: Context, results: List<ProviderResult>) {
        val arr = JSONArray().apply { results.forEach { put(it.toJson()) } }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun load(context: Context): List<ProviderResult> {
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            SectionOrder.apply(context, (0 until arr.length()).map { ProviderResult.fromJson(arr.getJSONObject(it)) })
        } catch (e: Exception) {
            emptyList()
        }
    }
}
