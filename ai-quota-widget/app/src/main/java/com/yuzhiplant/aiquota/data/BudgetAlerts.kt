package com.yuzhiplant.aiquota.data

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.providers.ClaudeApiProvider
import com.yuzhiplant.aiquota.providers.DriveProvider
import com.yuzhiplant.aiquota.providers.LineProvider
import com.yuzhiplant.aiquota.providers.VoyageProvider
import com.yuzhiplant.aiquota.ui.MainActivity
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 用量提醒：本月花費、LINE 訊息、Drive 空間達 80%、100% 時各推播一次（每月重置）。
 * 只針對有花錢的來源（Claude API、Voyage AI）。
 */
object BudgetAlerts {
    const val CHANNEL = "budget_alerts"
    private const val PREFS = "budget_alerts"
    /** 來源 ID → 要監看的項目名稱開頭 */
    private val WATCHED = mapOf(
        ClaudeApiProvider.ID to "本月花費",
        VoyageProvider.ID to "本月花費",
        LineProvider.ID to "本月訊息",
        DriveProvider.ID to "儲存空間",
    )
    private val THRESHOLDS = listOf(100, 80)

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "預算提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    fun check(context: Context, results: List<ProviderResult>) {
        val month = LocalDate.now(ZoneOffset.UTC).toString().take(7)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        for (r in results) {
            val prefix = WATCHED[r.id] ?: continue
            if (r.error != null) continue
            val item = r.items.firstOrNull { it.label.startsWith(prefix) } ?: continue
            val pct = item.percent ?: continue
            val key = "${r.id}_$month"
            val notified = prefs.getInt(key, 0)
            val hit = THRESHOLDS.firstOrNull { pct >= it } ?: continue
            if (hit <= notified) continue
            prefs.edit().putInt(key, hit).apply()
            val title = when {
                prefix == "本月花費" && hit >= 100 -> "${r.name} 本月已超過預算"
                prefix == "本月花費" -> "${r.name} 本月已用 ${Format.percent(pct)} 預算"
                hit >= 100 -> "${r.name} ${prefix}已用完"
                else -> "${r.name} ${prefix}已用 ${Format.percent(pct)}"
            }
            notify(context, r.id.hashCode(), title, item.detail)
        }
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, n)
    }
}
