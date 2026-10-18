package com.yuzhiplant.aiquota.data

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
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import com.yuzhiplant.aiquota.R
import com.yuzhiplant.aiquota.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * 線上更新：讀 GitHub Release 上的 version.json，比對版本號；
 * 有新版就下載 APK 並交給系統安裝程式（簽章固定，可直接覆蓋安裝）。
 */
object UpdateChecker {
    private const val BASE =
        "https://github.com/waynelin-yuzhi/farm-inventory/releases/download/ai-quota-latest"
    private const val PREFS = "app_update"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified_version"
    const val CHANNEL = "app_update"
    const val EXTRA_CHECK_UPDATE = "check_update"

    data class Info(val versionCode: Long, val versionName: String, val notes: String)

    fun currentVersionCode(context: Context): Long =
        PackageInfoCompat.getLongVersionCode(context.packageManager.getPackageInfo(context.packageName, 0))

    fun currentVersionName(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""

    /** 有比目前新的版本就回傳，否則 null。網路錯誤會丟出例外。 */
    suspend fun check(context: Context): Info? = withContext(Dispatchers.IO) {
        // 加時間參數避免拿到快取的舊檔
        val json = JSONObject(Http.get("$BASE/version.json?t=${System.currentTimeMillis()}", emptyMap()))
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        val info = Info(
            versionCode = json.optLong("versionCode"),
            versionName = json.optString("versionName"),
            notes = json.optString("notes").trim(),
        )
        if (info.versionCode > currentVersionCode(context)) info else null
    }

    /** 距上次檢查超過 minIntervalMs 才檢查，避免每次開 App 都連網。 */
    suspend fun checkThrottled(context: Context, minIntervalMs: Long): Info? {
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getLong(KEY_LAST_CHECK, 0)
        if (System.currentTimeMillis() - last < minIntervalMs) return null
        return try {
            check(context)
        } catch (e: Exception) {
            null
        }
    }

    /** 下載 APK 到 App 快取資料夾，onProgress 回報 0–100。 */
    suspend fun download(context: Context, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        val file = File(dir, "ai-quota-widget.apk")
        val conn = URL("$BASE/ai-quota-widget.apk?t=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        try {
            if (conn.responseCode !in 200..299) throw Http.HttpException(conn.responseCode, "")
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        if (total > 0) {
                            val pct = (read * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                withContext(Dispatchers.Main) { onProgress(pct) }
                            }
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
        file
    }

    /** 是否已允許本 App 安裝其他 App（Android 8+ 需使用者在設定中開啟）。 */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun installIntent(context: Context, apk: File): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apk)
        return Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 背景更新時順便檢查（每 12 小時一次），同一個新版本只通知一次。 */
    suspend fun notifyIfAvailable(context: Context) {
        val info = checkThrottled(context, 12 * 60 * 60 * 1000L) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getLong(KEY_NOTIFIED, 0) >= info.versionCode) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) return
        prefs.edit().putLong(KEY_NOTIFIED, info.versionCode).apply()

        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "App 更新", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 2,
            Intent(context, MainActivity::class.java).putExtra(EXTRA_CHECK_UPDATE, true),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_budget)
            .setContentTitle("AI 額度有新版本 ${info.versionName}")
            .setContentText("點一下更新")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(9001, n)
    }
}
