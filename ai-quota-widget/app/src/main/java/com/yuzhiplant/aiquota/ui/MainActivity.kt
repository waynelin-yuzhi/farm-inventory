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
import com.yuzhiplant.aiquota.data.usageLevel
import com.yuzhiplant.aiquota.model.ProviderResult
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
            if (it.itemId == R.id.action_settings) {
                startActivity(Intent(this, SettingsActivity::class.java))
                true
            } else {
                false
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
        val inflater = LayoutInflater.from(this)
        for (r in results) {
            val card = inflater.inflate(R.layout.item_provider, container, false)
            card.findViewById<TextView>(R.id.provider_name).text = r.name
            card.findViewById<TextView>(R.id.provider_updated).text = "更新 ${Format.updatedAt(r.updatedAt)}"
            val err = card.findViewById<TextView>(R.id.provider_error)
            err.visibility = if (r.error != null) View.VISIBLE else View.GONE
            err.text = r.error ?: ""

            val rows = card.findViewById<LinearLayout>(R.id.provider_rows)
            for (item in r.items) {
                val row = inflater.inflate(R.layout.item_quota_row, rows, false)
                row.findViewById<TextView>(R.id.quota_label).text = item.label
                val value = row.findViewById<TextView>(R.id.quota_value)
                val bar = row.findViewById<LinearProgressIndicator>(R.id.quota_bar)
                val detail = row.findViewById<TextView>(R.id.quota_detail)
                val p = item.percent
                if (p != null) {
                    value.text = Format.percent(p)
                    bar.visibility = View.VISIBLE
                    bar.max = 100
                    bar.progress = p.coerceIn(0.0, 100.0).toInt()
                    bar.setIndicatorColor(ContextCompat.getColor(this, levelColor(usageLevel(p))))
                    detail.text = item.detail
                    detail.visibility = if (item.detail.isEmpty()) View.GONE else View.VISIBLE
                } else {
                    value.text = item.detail
                    bar.visibility = View.GONE
                    detail.visibility = View.GONE
                }
                rows.addView(row)
            }
            container.addView(card)
        }
    }

    private fun levelColor(level: Int) = when (level) {
        2 -> R.color.level_bad
        1 -> R.color.level_warn
        else -> R.color.level_ok
    }
}
