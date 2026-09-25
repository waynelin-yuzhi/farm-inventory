package com.yuzhiplant.aiquota.providers

import com.yuzhiplant.aiquota.data.Format
import com.yuzhiplant.aiquota.data.Http
import com.yuzhiplant.aiquota.data.Settings
import com.yuzhiplant.aiquota.model.ProviderResult
import com.yuzhiplant.aiquota.model.QuotaItem
import org.json.JSONObject

/**
 * Google Drive 儲存空間。Google 帳號的用量需要登入授權才讀得到，
 * 所以由使用者自己部署一支極小的 Apps Script 網頁應用程式（見 GAS_SCRIPT），
 * App 只讀它回傳的 { used, limit }（bytes）。
 */
object DriveProvider {
    const val ID = "google_drive"
    private const val NAME = "Google Drive"

    /** 給使用者貼到 script.google.com 的程式碼 */
    const val GAS_SCRIPT = """function doGet() {
  var used = DriveApp.getStorageUsed();
  var limit = DriveApp.getStorageLimit();
  return ContentService.createTextOutput(JSON.stringify({ used: used, limit: limit }))
    .setMimeType(ContentService.MimeType.JSON);
}"""

    fun fetch(settings: Settings): ProviderResult? {
        val url = settings.driveUsageUrl
        if (url.isEmpty()) return null
        val now = System.currentTimeMillis()
        return try {
            val body = Http.get(url, mapOf("Accept" to "application/json"))
            if (!body.trimStart().startsWith("{")) {
                return ProviderResult(ID, NAME, emptyList(), "網址回傳的不是資料，請確認部署時「存取權」選「所有人」", now)
            }
            val json = JSONObject(body)
            val used = json.optDouble("used", Double.NaN)
            val limit = json.optDouble("limit", Double.NaN)
            if (used.isNaN()) return ProviderResult(ID, NAME, emptyList(), "回傳內容沒有 used 欄位", now)
            val item = if (!limit.isNaN() && limit > 0) {
                QuotaItem(
                    "儲存空間",
                    used / limit * 100,
                    "${Format.bytes(used)} / ${Format.bytes(limit)}・剩 ${Format.bytes((limit - used).coerceAtLeast(0.0))}",
                )
            } else {
                QuotaItem("儲存空間", null, "${Format.bytes(used)}（無上限）")
            }
            ProviderResult(ID, NAME, listOf(item), null, now)
        } catch (e: Http.HttpException) {
            ProviderResult(ID, NAME, emptyList(), "連線錯誤（HTTP ${e.code}），請確認網址是否正確", now)
        } catch (e: Exception) {
            ProviderResult(ID, NAME, emptyList(), "讀取失敗：${e.message ?: e.javaClass.simpleName}", now)
        }
    }
}
