package com.yuzhiplant.aiquota.data

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object Http {

    class HttpException(val code: Int, val body: String) :
        IOException("HTTP $code ${body.take(160)}")

    /** 同步 GET，請在 Dispatchers.IO 呼叫。 */
    fun get(url: String, headers: Map<String, String>): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.requestMethod = "GET"
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) throw HttpException(code, body)
            return body
        } finally {
            conn.disconnect()
        }
    }
}
