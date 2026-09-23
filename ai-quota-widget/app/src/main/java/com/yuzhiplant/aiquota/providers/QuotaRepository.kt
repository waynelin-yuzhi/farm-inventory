package com.yuzhiplant.aiquota.providers

import android.content.Context
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

    /** 平行抓取所有已設定的來源，存快取並刷新桌面小工具。 */
    suspend fun refreshAll(context: Context): List<ProviderResult> = withContext(Dispatchers.IO) {
        val settings = Settings(context)
        val results = coroutineScope {
            val jobs = listOf(
                async { ClaudeSubscriptionProvider.fetch(settings) },
                async { ClaudeApiProvider.fetch(settings) },
            ) + settings.customSources.map { src -> async { CustomJsonProvider.fetch(src) } }
            jobs.awaitAll().filterNotNull()
        }
        ResultCache.save(context, results)
        QuotaWidgetProvider.updateAll(context)
        results
    }
}
