package com.yuzhiplant.aiquota.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.CustomSource
import com.yuzhiplant.aiquota.work.RefreshScheduler
import java.util.UUID

class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: Settings
    private lateinit var sessionKey: TextInputEditText
    private lateinit var adminKey: TextInputEditText
    private lateinit var budget: TextInputEditText
    private lateinit var interval: TextInputEditText
    private lateinit var voyageId: TextInputEditText
    private lateinit var voyageSecret: TextInputEditText
    private lateinit var voyageOrg: TextInputEditText
    private lateinit var voyageBudget: TextInputEditText
    private lateinit var supabaseToken: TextInputEditText
    private lateinit var supabaseRefs: TextInputEditText
    private lateinit var customList: LinearLayout
    private val customSources = mutableListOf<CustomSource>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)

        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        sessionKey = findViewById(R.id.input_session_key)
        adminKey = findViewById(R.id.input_admin_key)
        budget = findViewById(R.id.input_budget)
        interval = findViewById(R.id.input_interval)
        customList = findViewById(R.id.custom_list)
        voyageId = findViewById(R.id.input_voyage_id)
        voyageSecret = findViewById(R.id.input_voyage_secret)
        voyageOrg = findViewById(R.id.input_voyage_org)
        voyageBudget = findViewById(R.id.input_voyage_budget)
        supabaseToken = findViewById(R.id.input_supabase_token)
        supabaseRefs = findViewById(R.id.input_supabase_refs)

        sessionKey.setText(settings.claudeSessionKey)
        adminKey.setText(settings.anthropicAdminKey)
        settings.apiMonthlyBudget.takeIf { it > 0 }?.let { budget.setText(trimNumber(it)) }
        interval.setText(settings.refreshMinutes.toString())
        voyageId.setText(settings.voyageClientId)
        voyageSecret.setText(settings.voyageClientSecret)
        voyageOrg.setText(settings.voyageOrgId)
        settings.voyageMonthlyBudget.takeIf { it > 0 }?.let { voyageBudget.setText(trimNumber(it)) }
        supabaseToken.setText(settings.supabaseToken)
        supabaseRefs.setText(settings.supabaseProjectRefs)
        customSources += settings.customSources
        renderCustom()

        findViewById<MaterialButton>(R.id.btn_add_custom).setOnClickListener { editCustom(null) }
        findViewById<MaterialButton>(R.id.btn_add_cloudconvert).setOnClickListener {
            editCustom(
                CustomSource(
                    id = "",
                    name = "CloudConvert",
                    url = "https://api.cloudconvert.com/v2/users/me",
                    headers = "Authorization: Bearer 貼上你的 API key",
                    valuePath = "data.credits",
                    limitPath = "",
                    percentPath = "",
                    unit = "點",
                    valueIsRemaining = true,
                ),
            )
        }
        findViewById<MaterialButton>(R.id.btn_save).setOnClickListener { save() }
    }

    private fun save() {
        val newSession = sessionKey.text?.toString()?.trim().orEmpty()
        if (Settings.cleanSessionKey(newSession) != settings.claudeSessionKey) settings.claudeOrgId = ""
        settings.claudeSessionKey = newSession
        settings.anthropicAdminKey = adminKey.text?.toString().orEmpty()
        settings.apiMonthlyBudget = budget.text?.toString()?.toDoubleOrNull() ?: 0.0
        settings.voyageClientId = voyageId.text?.toString().orEmpty()
        settings.voyageClientSecret = voyageSecret.text?.toString().orEmpty()
        settings.voyageOrgId = voyageOrg.text?.toString().orEmpty()
        settings.voyageMonthlyBudget = voyageBudget.text?.toString()?.toDoubleOrNull() ?: 0.0
        settings.supabaseToken = supabaseToken.text?.toString().orEmpty()
        settings.supabaseProjectRefs = supabaseRefs.text?.toString().orEmpty()
        val oldInterval = settings.refreshMinutes
        settings.refreshMinutes = interval.text?.toString()?.toIntOrNull() ?: 30
        settings.customSources = customSources.toList()
        if (settings.refreshMinutes != oldInterval) RefreshScheduler.ensurePeriodic(this, replace = true)
        Toast.makeText(this, "已儲存", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun renderCustom() {
        settings.customSources = customSources.toList()
        customList.removeAllViews()
        if (customSources.isEmpty()) {
            val tv = TextView(this)
            tv.text = "尚未新增"
            tv.setPadding(0, 8, 0, 8)
            customList.addView(tv)
            return
        }
        for (src in customSources) {
            val btn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle)
            btn.text = "✎  ${src.name}"
            btn.isAllCaps = false
            btn.textAlignment = android.view.View.TEXT_ALIGNMENT_VIEW_START
            btn.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            btn.setOnClickListener { editCustom(src) }
            customList.addView(btn)
        }
    }

    /** 新增或編輯自訂來源；src.id 為空代表新增。 */
    private fun editCustom(src: CustomSource?) {
        val view = LayoutInflater.from(this).inflate(R.layout.dialog_custom_source, null)
        val name = view.findViewById<TextInputEditText>(R.id.cs_name)
        val url = view.findViewById<TextInputEditText>(R.id.cs_url)
        val headers = view.findViewById<TextInputEditText>(R.id.cs_headers)
        val valuePath = view.findViewById<TextInputEditText>(R.id.cs_value_path)
        val limitPath = view.findViewById<TextInputEditText>(R.id.cs_limit_path)
        val percentPath = view.findViewById<TextInputEditText>(R.id.cs_percent_path)
        val unit = view.findViewById<TextInputEditText>(R.id.cs_unit)
        val remaining = view.findViewById<CheckBox>(R.id.cs_remaining)

        src?.let {
            name.setText(it.name)
            url.setText(it.url)
            headers.setText(it.headers)
            valuePath.setText(it.valuePath)
            limitPath.setText(it.limitPath)
            percentPath.setText(it.percentPath)
            unit.setText(it.unit)
            remaining.isChecked = it.valueIsRemaining
        }
        val existing = src != null && src.id.isNotEmpty()

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(if (existing) "編輯來源" else "新增來源")
            .setView(view)
            .setNegativeButton("取消", null)
            .setPositiveButton("確定") { _, _ ->
                val updated = CustomSource(
                    id = if (existing) src!!.id else UUID.randomUUID().toString().take(8),
                    name = name.text?.toString()?.trim().orEmpty().ifEmpty { "未命名" },
                    url = url.text?.toString()?.trim().orEmpty(),
                    headers = headers.text?.toString().orEmpty(),
                    valuePath = valuePath.text?.toString()?.trim().orEmpty(),
                    limitPath = limitPath.text?.toString()?.trim().orEmpty(),
                    percentPath = percentPath.text?.toString()?.trim().orEmpty(),
                    unit = unit.text?.toString()?.trim().orEmpty(),
                    valueIsRemaining = remaining.isChecked,
                )
                if (updated.url.isEmpty() || (updated.valuePath.isEmpty() && updated.percentPath.isEmpty())) {
                    Toast.makeText(this, "網址與欄位路徑至少要填", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                val idx = customSources.indexOfFirst { it.id == updated.id }
                if (idx >= 0) customSources[idx] = updated else customSources += updated
                renderCustom()
            }
        if (existing) {
            builder.setNeutralButton("刪除") { _, _ ->
                customSources.removeAll { it.id == src!!.id }
                renderCustom()
            }
        }
        builder.show()
    }

    private fun trimNumber(v: Double): String =
        if (v % 1.0 == 0.0) v.toLong().toString() else v.toString()
}
