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
import com.yuzhiplant.aiquota.data.SectionOrder
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.data.WidgetVisibility
import com.yuzhiplant.aiquota.data.usageLevel
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
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
        private const val MAX_ROWS = 14
        private const val MAX_CARDS = 7

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
                val sections = widgetSections(results, WidgetVisibility.load(context))
                if (Settings(context).widgetStyle == "A") {
                    var rows = 0
                    for ((index, section) in sections.withIndex()) {
                        if (rows >= MAX_ROWS) break
                        val header = RemoteViews(pkg, R.layout.widget_header_row)
                        header.setTextViewText(R.id.header_text, section.title)
                        header.setViewVisibility(R.id.header_divider, if (index == 0) View.GONE else View.VISIBLE)
                        root.addView(R.id.widget_rows, header)
                        rows++
                        for (row in section.rows) {
                            if (rows >= MAX_ROWS) break
                            root.addView(R.id.widget_rows, rowView(context, pkg, row))
                            rows++
                        }
                    }
                } else {
                    sections.take(MAX_CARDS).forEach { root.addView(R.id.widget_rows, cardView(context, pkg, it)) }
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

        /** 小工具上的一列（已整理好要顯示的文字） */
        /**
         * 小工具上的一列（已整理好要顯示的文字）。
         * amount = 金額／容量（例如「$10.41 / $20」）；hint = 補充（預估月底、重置時間）。
         */
        private data class WidgetRow(
            val label: String,
            val percent: Double?,
            val value: String,
            val detail: String,
            val amount: String = "",
            val hint: String = "",
        )

        private data class Section(val title: String, val rows: List<WidgetRow>)

        /**
         * 把抓取結果整理成小工具的區塊：
         * - 每個來源一個區塊，只放「上小工具」的項目
         * - 讀取失敗只顯示一行「⚠ 讀取失敗・點開查看」，不塞長錯誤訊息
         * - Supabase 多個專案合成一個區塊：用量 ≥ 50% 才列出，其餘收成一行摘要
         */
        private fun widgetSections(results: List<ProviderResult>, vis: Map<String, Boolean>): List<Section> {
            val sections = mutableListOf<Section>()
            val supabase = results.filter { it.id.startsWith("supabase") }
            var supabaseAdded = false
            for (r in results) {
                if (!WidgetVisibility.groupVisible(vis, SectionOrder.groupKey(r.id))) continue
                if (r.id.startsWith("supabase")) {
                    if (!supabaseAdded) {
                        sections += supabaseSection(supabase, vis)
                        supabaseAdded = true
                    }
                    continue
                }
                val rows = if (r.error != null) {
                    listOf(WidgetRow("⚠ 讀取失敗", null, "點開查看", ""))
                } else {
                    r.items.filter { WidgetVisibility.itemVisible(vis, r.id, it) }.map { toRow(it, it.label) }
                }
                if (rows.isNotEmpty()) sections += Section(r.name, rows)
            }
            return sections
        }

        private fun supabaseSection(results: List<ProviderResult>, vis: Map<String, Boolean>): Section {
            val rows = mutableListOf<WidgetRow>()
            var ok = 0
            var paused = 0
            var failed = 0
            for (r in results) {
                val project = r.name.removePrefix("Supabase・")
                when {
                    r.error != null -> failed++
                    r.items.any { it.label == "專案狀態" } -> paused++
                    else -> if (!r.id.startsWith("supabase_org_")) ok++
                }
                // 預設只有用量偏高才佔一列；使用者可在「排序與顯示」自行開關
                r.items.filter { it.label != "專案狀態" && WidgetVisibility.itemVisible(vis, r.id, it) }.forEach {
                    rows += toRow(it, if (r.id.startsWith("supabase_org_")) it.label else "$project・${it.label}")
                }
            }
            val summary = buildList {
                if (ok > 0) add("$ok 個正常")
                if (paused > 0) add("$paused 個已暫停")
                if (failed > 0) add("$failed 個讀取失敗")
            }.joinToString("・")
            rows.add(0, WidgetRow("專案", null, summary.ifEmpty { "—" }, ""))
            return Section("Supabase", rows)
        }

        private fun toRow(item: QuotaItem, label: String): WidgetRow {
            val detail = listOf(item.shortDetail, Format.resetRelative(item.resetAt, short = true))
                .filter { it.isNotEmpty() }
                .joinToString("・")
            val hint = item.hint.ifEmpty { Format.resetRelative(item.resetAt, short = true) }
            return if (item.percent != null) {
                WidgetRow(label, item.percent, Format.percent(item.percent), detail, item.shortDetail, hint)
            } else {
                WidgetRow(label, null, item.shortDetail.ifEmpty { item.detail }, "")
            }
        }

        private fun rowView(context: Context, pkg: String, row: WidgetRow): RemoteViews {
            val v = RemoteViews(pkg, R.layout.widget_row)
            v.setTextViewText(R.id.row_label, row.label)
            v.setTextViewText(R.id.row_value, row.value)
            val percent = row.percent
            if (percent == null) {
                v.setViewVisibility(R.id.row_bar_line, View.GONE)
                return v
            }
            val level = usageLevel(percent)
            val bars = intArrayOf(R.id.row_bar_ok, R.id.row_bar_warn, R.id.row_bar_bad)
            bars.forEachIndexed { i, id -> v.setViewVisibility(id, if (i == level) View.VISIBLE else View.GONE) }
            v.setProgressBar(bars[level], 100, percent.coerceIn(0.0, 100.0).toInt(), false)
            // 偏高時數字直接變色，一眼看出要注意
            if (level == 1) v.setTextColor(R.id.row_value, context.getColor(R.color.level_warn))
            if (level == 2) v.setTextColor(R.id.row_value, context.getColor(R.color.level_bad))
            v.setTextViewText(R.id.row_detail, row.detail)
            v.setViewVisibility(R.id.row_detail, if (row.detail.isEmpty()) View.GONE else View.VISIBLE)
            return v
        }

        /**
         * 方案 B：一個來源一張卡片。
         * - 只有一個百分比項目 → 大數字（金額或百分比）＋ 進度條 ＋ 補充
         * - 兩個以上 → 兩兩並排的小格
         * - 沒有百分比的項目（摘要、警示）→ 單行
         */
        private fun cardView(context: Context, pkg: String, section: Section): RemoteViews {
            val card = RemoteViews(pkg, R.layout.widget_section)
            card.setTextViewText(R.id.section_title, section.title.uppercase())
            val gauges = section.rows.filter { it.percent != null }
            if (gauges.size == 1) {
                card.addView(R.id.section_body, heroView(context, pkg, gauges[0]))
            } else {
                gauges.chunked(2).forEach { card.addView(R.id.section_body, pairView(context, pkg, it)) }
            }
            section.rows.filter { it.percent == null }.forEach { row ->
                val v = RemoteViews(pkg, R.layout.widget_simple_row)
                v.setTextViewText(R.id.simple_label, row.label)
                v.setTextViewText(R.id.simple_value, row.value)
                if (row.label.startsWith("⚠")) v.setTextColor(R.id.simple_value, context.getColor(R.color.level_bad))
                card.addView(R.id.section_body, v)
            }
            return card
        }

        private fun heroView(context: Context, pkg: String, row: WidgetRow): RemoteViews {
            val v = RemoteViews(pkg, R.layout.widget_hero)
            val percent = row.percent ?: 0.0
            // 有金額／容量就把它當大數字（$10.41），後面接「/ $20」；沒有就直接放大百分比
            val parts = row.amount.split(" / ", limit = 2)
            if (row.amount.isNotEmpty() && parts.size == 2) {
                v.setTextViewText(R.id.hero_big, parts[0])
                v.setTextViewText(R.id.hero_sub, "/ ${parts[1]}　${row.label}")
                v.setTextViewText(R.id.hero_pct, row.value)
            } else {
                v.setTextViewText(R.id.hero_big, row.value)
                v.setTextViewText(R.id.hero_sub, row.label)
                v.setViewVisibility(R.id.hero_pct, View.GONE)
            }
            tintValue(context, v, R.id.hero_pct, percent)
            tintValue(context, v, R.id.hero_big, if (parts.size == 2) 0.0 else percent)
            setBar(v, intArrayOf(R.id.hero_bar_ok, R.id.hero_bar_warn, R.id.hero_bar_bad), percent)
            v.setTextViewText(R.id.hero_detail, row.hint)
            v.setViewVisibility(R.id.hero_detail, if (row.hint.isEmpty()) View.GONE else View.VISIBLE)
            return v
        }

        private fun pairView(context: Context, pkg: String, rows: List<WidgetRow>): RemoteViews {
            val v = RemoteViews(pkg, R.layout.widget_pair)
            val cells = listOf(
                arrayOf(R.id.cell1, R.id.cell1_pct, R.id.cell1_label, R.id.cell1_detail, R.id.cell1_bar_ok, R.id.cell1_bar_warn, R.id.cell1_bar_bad),
                arrayOf(R.id.cell2, R.id.cell2_pct, R.id.cell2_label, R.id.cell2_detail, R.id.cell2_bar_ok, R.id.cell2_bar_warn, R.id.cell2_bar_bad),
            )
            cells.forEachIndexed { i, ids ->
                val row = rows.getOrNull(i)
                if (row == null) {
                    v.setViewVisibility(ids[0], View.INVISIBLE)
                    return@forEachIndexed
                }
                val percent = row.percent ?: 0.0
                v.setTextViewText(ids[1], row.value)
                tintValue(context, v, ids[1], percent)
                v.setTextViewText(ids[2], row.label)
                val detail = row.hint.ifEmpty { row.amount }
                v.setTextViewText(ids[3], detail)
                v.setViewVisibility(ids[3], if (detail.isEmpty()) View.GONE else View.VISIBLE)
                setBar(v, intArrayOf(ids[4], ids[5], ids[6]), percent)
            }
            return v
        }

        private fun setBar(v: RemoteViews, bars: IntArray, percent: Double) {
            val level = usageLevel(percent)
            bars.forEachIndexed { i, id -> v.setViewVisibility(id, if (i == level) View.VISIBLE else View.GONE) }
            v.setProgressBar(bars[level], 100, percent.coerceIn(0.0, 100.0).toInt(), false)
        }

        private fun tintValue(context: Context, v: RemoteViews, id: Int, percent: Double) {
            when (usageLevel(percent)) {
                1 -> v.setTextColor(id, context.getColor(R.color.level_warn))
                2 -> v.setTextColor(id, context.getColor(R.color.level_bad))
            }
        }
    }
}
