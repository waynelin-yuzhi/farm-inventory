package com.yuzhiplant.aiquota.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.yuzhiplant.aiquota.data.UpdateChecker
import kotlinx.coroutines.launch

/** 更新流程的畫面部分：詢問 → 下載（進度條）→ 交給系統安裝。 */
object UpdateUi {

    /** manual=true 時（設定頁按鈕）沒有新版也會提示「已是最新版」。 */
    fun check(activity: AppCompatActivity, manual: Boolean) {
        activity.lifecycleScope.launch {
            val info = try {
                if (manual) UpdateChecker.check(activity) else UpdateChecker.checkThrottled(activity, 60 * 60 * 1000L)
            } catch (e: Exception) {
                if (manual) Toast.makeText(activity, "檢查更新失敗：${e.message}", Toast.LENGTH_LONG).show()
                return@launch
            }
            if (info == null) {
                if (manual) Toast.makeText(activity, "已是最新版", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (activity.isFinishing) return@launch
            val message = buildString {
                append("目前版本 ${UpdateChecker.currentVersionName(activity)} → 新版 ${info.versionName}")
                if (info.notes.isNotEmpty()) append("\n\n").append(info.notes)
            }
            MaterialAlertDialogBuilder(activity)
                .setTitle("有新版本")
                .setMessage(message)
                .setNegativeButton("稍後", null)
                .setPositiveButton("立即更新") { _, _ -> startUpdate(activity) }
                .show()
        }
    }

    private fun startUpdate(activity: AppCompatActivity) {
        if (!UpdateChecker.canInstall(activity)) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("需要允許安裝")
                .setMessage("第一次更新需要允許「AI 額度」安裝應用程式。\n\n按「前往設定」→ 打開「允許來自這個來源的應用程式」→ 返回後再按一次更新。")
                .setNegativeButton("取消", null)
                .setPositiveButton("前往設定") { _, _ ->
                    activity.startActivity(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")),
                    )
                }
                .show()
            return
        }

        val bar = LinearProgressIndicator(activity).apply {
            max = 100
            isIndeterminate = true
            setPadding(64, 32, 64, 0)
        }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("下載新版中…")
            .setView(bar)
            .setCancelable(false)
            .show()

        activity.lifecycleScope.launch {
            try {
                val apk = UpdateChecker.download(activity) { pct ->
                    bar.isIndeterminate = false
                    bar.progress = pct
                }
                dialog.dismiss()
                activity.startActivity(UpdateChecker.installIntent(activity, apk))
            } catch (e: Exception) {
                dialog.dismiss()
                Toast.makeText(activity, "下載失敗：${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
