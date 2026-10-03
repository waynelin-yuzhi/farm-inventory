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
import com.yuzhiplant.aiquota.ui.SettingsActivity

/**
 * 金鑰失效提醒：某平台因金鑰／憑證問題讀取失敗時推播一次；
 * 同一個平台恢復正常前不會重複通知（網路錯誤不通知）。
 */
object CredentialAlerts {
    private const val CHANNEL = "credential_alerts"
    private const val PREFS = "credential_alerts"
    private const val KEY = "notified"

    /** 各平台失效時要換的東西，讓通知一看就知道要做什麼 */
    private fun whatToRenew(groupKey: String, name: String): String = when (groupKey) {
        "claude_subscription" -> "Claude App 的 sessionKey 已失效"
        "claude_api" -> "Claude API 的 Admin key 失效"
        "supabase" -> "Supabase Access token 失效"
        "line" -> "LINE Channel access token 失效"
        "google_drive" -> "Google Drive 小程式網址失效"
        "voyage" -> "Voyage（Atlas）服務帳號失效"
        else -> "$name 的金鑰失效"
    }

    fun check(context: Context, results: List<ProviderResult>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val notified = prefs.getStringSet(KEY, emptySet())!!.toMutableSet()
        val failing = results.filter { it.authError }.groupBy { SectionOrder.groupKey(it.id) }
        val healthy = results.map { SectionOrder.groupKey(it.id) }.toSet() - failing.keys

        // 恢復正常的平台，下次失效要能再通知
        notified.removeAll(healthy)

        for ((group, rs) in failing) {
            if (group in notified) continue
            notified += group
            val r = rs.first()
            notify(context, group.hashCode(), whatToRenew(group, r.name), "${r.error ?: "讀取失敗"}・點一下前往設定更新")
        }
        prefs.edit().putStringSet(KEY, notified).apply()
    }

    private fun notify(context: Context, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "金鑰失效提醒", NotificationManager.IMPORTANCE_HIGH),
        )
        val open = PendingIntent.getActivity(
            context, 3, Intent(context, SettingsActivity::class.java),
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
