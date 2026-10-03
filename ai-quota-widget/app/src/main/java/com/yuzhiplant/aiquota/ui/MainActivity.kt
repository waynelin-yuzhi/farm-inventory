package com.yuzhiplant.aiquota.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.BudgetAlerts
import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.ResultCache
import com.yuzhiplant.aiquota.data.UpdateChecker
import com.yuzhiplant.aiquota.data.usageLevel
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import com.yuzhiplant.aiquota.providers.QuotaRepository
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var swipe: SwipeRefreshLayout
    private lateinit var container: LinearLayout
    private lateinit var empty: View

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        toolbar.setOnMenuItemClickListener {
            when (it.itemId) {
                R.id.action_settings -> {
                    startActivity(Intent(this, SettingsActivity::class.java))
                    true
                }
                R.id.action_reorder -> {
                    startActivity(Intent(this, ReorderActivity::class.java))
                    true
                }
                else -> false
            }
        }

        swipe = findViewById(R.id.swipe)
        container = findViewById(R.id.cards)
        empty = findViewById(R.id.empty)
        findViewById<MaterialButton>(R.id.empty_setup).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        swipe.setOnRefreshListener { refresh() }

        // 預算提醒需要通知權限（Android 13+）
        BudgetAlerts.ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        render(ResultCache.load(this))
        refresh()
        // 從「有新版本」通知點進來時一定檢查；平常最多每小時檢查一次
        val fromNotification = intent.getBooleanExtra(UpdateChecker.EXTRA_CHECK_UPDATE, false)
        if (fromNotification) intent.removeExtra(UpdateChecker.EXTRA_CHECK_UPDATE)
        UpdateUi.check(this, manual = fromNotification)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    private fun refresh() {
        swipe.isRefreshing = true
        lifecycleScope.launch {
            val results = QuotaRepository.refreshAll(this@MainActivity)
            render(results)
            swipe.isRefreshing = false
        }
    }

    private fun render(results: List<ProviderResult>) {
        container.removeAllViews()
        empty.visibility = if (results.isEmpty()) View.VISIBLE else View.GONE
        if (results.isEmpty()) return
        val inflater = LayoutInflater.from(this)

        container.addView(attentionCard(inflater, results))

        for (r in results) {
            val card = inflater.inflate(R.layout.item_provider, container, false)
            card.findViewById<TextView>(R.id.provider_name).text = r.name
            card.findViewById<TextView>(R.id.provider_updated).text = "更新 ${Format.updatedAt(r.updatedAt)}"
            val err = card.findViewById<TextView>(R.id.provider_error)
            err.visibility = if (r.error != null) View.VISIBLE else View.GONE
            err.text = r.error ?: ""

            val rows = card.findViewById<LinearLayout>(R.id.provider_rows)
            for (item in r.items) addRow(inflater, rows, item, item.label)
            container.addView(card)
        }
    }

    /**
     * 最上方的摘要卡：把 80% 以上、預估超支、讀取失敗的項目集中列出，
     * 打開 App 第一眼就知道有沒有事要處理。
     */
    private fun attentionCard(inflater: LayoutInflater, results: List<ProviderResult>): View {
        val card = inflater.inflate(R.layout.item_provider, container, false)
        val rows = card.findViewById<LinearLayout>(R.id.provider_rows)
        var count = 0
        for (r in results) {
            if (r.error != null) {
                addRow(inflater, rows, QuotaItem("讀取失敗", null, "往下查看原因"), "${r.name}・讀取失敗", forceWarn = true)
                count++
                continue
            }
            for (item in r.items) {
                val hot = (item.percent ?: 0.0) >= 80 || item.label.startsWith("⚠")
                if (!hot) continue
                addRow(inflater, rows, item, "${r.name}・${item.label.removePrefix("⚠ ")}", forceWarn = item.percent == null)
                count++
            }
        }
        val title = card.findViewById<TextView>(R.id.provider_name)
        val sub = card.findViewById<TextView>(R.id.provider_updated)
        if (count == 0) {
            title.text = "✓ 一切正常"
            title.setTextColor(ContextCompat.getColor(this, R.color.level_ok))
            sub.text = "所有額度都在 80% 以下"
        } else {
            title.text = "需要注意"
            title.setTextColor(ContextCompat.getColor(this, R.color.level_bad))
            sub.text = "$count 項"
        }
        return card
    }

    private fun addRow(inflater: LayoutInflater, parent: LinearLayout, item: QuotaItem, label: String, forceWarn: Boolean = false) {
        val row = inflater.inflate(R.layout.item_quota_row, parent, false)
        row.findViewById<TextView>(R.id.quota_label).text = label
        val value = row.findViewById<TextView>(R.id.quota_value)
        val bar = row.findViewById<LinearProgressIndicator>(R.id.quota_bar)
        val detail = row.findViewById<TextView>(R.id.quota_detail)
        val p = item.percent
        if (p != null) {
            val level = usageLevel(p)
            value.text = Format.percent(p)
            if (level > 0) value.setTextColor(ContextCompat.getColor(this, levelColor(level)))
            bar.visibility = View.VISIBLE
            bar.max = 100
            bar.progress = p.coerceIn(0.0, 100.0).toInt()
            bar.setIndicatorColor(ContextCompat.getColor(this, levelColor(level)))
            val text = listOf(item.detail, Format.resetRelative(item.resetAt))
                .filter { it.isNotEmpty() }
                .joinToString("・")
            detail.text = text
            detail.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
        } else {
            value.text = item.detail
            if (forceWarn) value.setTextColor(ContextCompat.getColor(this, R.color.level_bad))
            bar.visibility = View.GONE
            detail.visibility = View.GONE
        }
        parent.addView(row)
    }

    private fun levelColor(level: Int) = when (level) {
        2 -> R.color.level_bad
        1 -> R.color.level_warn
        else -> R.color.level_ok
    }
}
