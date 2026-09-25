package com.yuzhiplant.aiquota.providers

import android.content.Context
import com.yuzhiplant.aiquota.data.BudgetAlerts
import com.yuzhiplant.aiquota.data.ResultCache
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.widget.QuotaWidgetProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

object QuotaRepository {

    /**
     * 平行抓取所有已設定的來源，存快取並刷新桌面小工具。
     * 顯示順序即重要順序：API 花費 → Claude App 模型用量 → Voyage 花費 → Supabase → 自訂。
     */
    suspend fun refreshAll(context: Context): List<ProviderResult> = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val results = coroutineScope {
            val api = async { ClaudeApiProvider.fetch(settings) }
            val subscription = async { ClaudeSubscriptionProvider.fetch(context, settings) }
            val voyage = async { VoyageProvider.fetch(settings) }
            val supabase = async { SupabaseProvider.fetch(settings) }
            val custom = settings.customSources.map { src -> async { CustomJsonProvider.fetch(src) } }
            listOfNotNull(api.await(), subscription.await(), voyage.await()) +
                supabase.await() + custom.awaitAll()
        }
        ResultCache.save(context, results)
        BudgetAlerts.check(context, results)
        QuotaWidgetProvider.updateAll(context)
        results
    }
}
