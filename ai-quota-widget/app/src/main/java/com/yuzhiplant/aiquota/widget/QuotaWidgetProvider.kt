package com.yuzhiplant.aiquota.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.ResultCache
import com.yuzhiplant.aiquota.data.usageLevel
import com.yuzhiplant.aiquota.ui.MainActivity
import com.yuzhiplant.aiquota.work.RefreshScheduler

class QuotaWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        ids.forEach { manager.updateAppWidget(it, buildViews(context, refreshing = false)) }
        RefreshScheduler.ensurePeriodic(context)
    }

    override fun onEnabled(context: Context) {
        RefreshScheduler.refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) {
            updateAll(context, refreshing = true)
            RefreshScheduler.refreshNow(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "com.yuzhiplant.aiquota.action.REFRESH"
        private const val MAX_ROWS = 9

        fun updateAll(context: Context, refreshing: Boolean = false) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, QuotaWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, refreshing)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun buildViews(context: Context, refreshing: Boolean): RemoteViews {
            val pkg = context.packageName
            val root = RemoteViews(pkg, R.layout.widget_quota)
            val results = ResultCache.load(context)

            val updated = results.maxOfOrNull { it.updatedAt } ?: 0L
            root.setTextViewText(
                R.id.widget_updated,
                if (refreshing) "更新中…" else Format.updatedAt(updated),
            )

            root.removeAllViews(R.id.widget_rows)
            if (results.isEmpty()) {
                root.setViewVisibility(R.id.widget_empty, View.VISIBLE)
            } else {
                root.setViewVisibility(R.id.widget_empty, View.GONE)
                var rows = 0
                for (r in results) {
                    if (rows >= MAX_ROWS) break
                    val header = RemoteViews(pkg, R.layout.widget_header_row)
                    header.setTextViewText(R.id.header_text, r.name)
                    root.addView(R.id.widget_rows, header)
                    rows++

                    if (r.error != null) {
                        root.addView(R.id.widget_rows, row(pkg, "⚠ 讀取失敗", null, r.error))
                        rows++
                        continue
                    }
                    for (item in r.items) {
                        if (rows >= MAX_ROWS) break
                        root.addView(R.id.widget_rows, row(pkg, item.label, item.percent, item.detail))
                        rows++
                    }
                }
            }

            val openApp = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            root.setOnClickPendingIntent(R.id.widget_root, openApp)

            val refresh = PendingIntent.getBroadcast(
                context, 1,
                Intent(context, QuotaWidgetProvider::class.java).setAction(ACTION_REFRESH),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            root.setOnClickPendingIntent(R.id.widget_refresh, refresh)
            return root
        }

        private fun row(pkg: String, label: String, percent: Double?, detail: String): RemoteViews {
            val v = RemoteViews(pkg, R.layout.widget_row)
            v.setTextViewText(R.id.row_label, label)
            val bars = intArrayOf(R.id.row_bar_ok, R.id.row_bar_warn, R.id.row_bar_bad)
            if (percent != null) {
                v.setTextViewText(R.id.row_value, Format.percent(percent))
                val level = usageLevel(percent)
                bars.forEachIndexed { i, id ->
                    v.setViewVisibility(id, if (i == level) View.VISIBLE else View.GONE)
                }
                v.setProgressBar(bars[level], 100, percent.coerceIn(0.0, 100.0).toInt(), false)
                v.setViewVisibility(R.id.row_bar_frame, View.VISIBLE)
                v.setTextViewText(R.id.row_detail, detail)
                v.setViewVisibility(R.id.row_detail, if (detail.isEmpty()) View.GONE else View.VISIBLE)
            } else {
                // 沒有上限的項目：數值直接放右側
                v.setTextViewText(R.id.row_value, detail)
                v.setViewVisibility(R.id.row_bar_frame, View.GONE)
                v.setViewVisibility(R.id.row_detail, View.GONE)
            }
            return v
        }
    }
}
