package com.yuzhiplant.aiquota.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {

    class HttpException(val code: Int, val body: String) :
        IOException("HTTP $code ${body.take(160)}")

    /** 同步 GET，請在 Dispatchers.IO 呼叫。 */
    fun get(url: String, headers: Map<String, String>): String = request("GET", url, headers, null)

    /** 同步 POST，請在 Dispatchers.IO 呼叫。預設送 JSON。 */
    fun post(
        url: String,
        headers: Map<String, String>,
        body: String,
        contentType: String = "application/json",
    ): String = request("POST", url, headers + ("Content-Type" to contentType), body)

    private fun request(method: String, url: String, headers: Map<String, String>, body: String?): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.requestMethod = method
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        if (body != null) {
            conn.doOutput = true
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, text)
            return text
        } finally {
            conn.disconnect()
        }
    }
}
