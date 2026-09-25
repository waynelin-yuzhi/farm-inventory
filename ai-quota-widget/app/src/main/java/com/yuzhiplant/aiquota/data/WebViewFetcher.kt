package com.yuzhiplant.aiquota.data

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONTokener
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 用隱藏的 WebView（真正的 Chrome 核心）開啟網址並讀回頁面文字。
 * claude.ai 前面有 Cloudflare，一般 HTTP 連線可能被當成機器人擋下；
 * WebView 會自動通過 Cloudflare 的 JavaScript 驗證，驗證完就能拿到 JSON。
 */
object WebViewFetcher {
    private const val TIMEOUT_MS = 30_000L
    private const val POLL_MS = 1_000L

    /** 回傳頁面內容（預期是 JSON 文字）；逾時丟出例外。 */
    suspend fun fetch(context: Context, url: String, cookieDomain: String, cookie: String): String =
        withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine<String> { cont ->
                val main = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var done = false

                fun finish(result: Result<String>) {
                    if (done) return
                    done = true
                    main.removeCallbacksAndMessages(null)
                    webView?.let {
                        it.stopLoading()
                        it.destroy()
                    }
                    webView = null
                    result.fold({ if (cont.isActive) cont.resume(it) }, { if (cont.isActive) cont.resumeWithException(it) })
                }

                lateinit var poll: Runnable
                poll = Runnable {
                    val wv = webView ?: return@Runnable
                    wv.evaluateJavascript("(function(){return document.body ? document.body.innerText : ''})()") { raw ->
                        val text = try {
                            JSONTokener(raw ?: "null").nextValue() as? String ?: ""
                        } catch (e: Exception) {
                            ""
                        }.trim()
                        // Cloudflare 驗證頁是 HTML 文字，驗證完才會變成 JSON
                        if (text.startsWith("{") || text.startsWith("[")) {
                            finish(Result.success(text))
                        } else if (!done) {
                            main.postDelayed(poll, POLL_MS)
                        }
                    }
                }

                main.post {
                    try {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        cm.setCookie(cookieDomain, "$cookie; path=/; secure")
                        cm.flush()
                        @SuppressLint("SetJavaScriptEnabled")
                        val wv = WebView(context.applicationContext)
                        wv.settings.javaScriptEnabled = true
                        wv.settings.domStorageEnabled = true
                        // 拿掉 WebView 標記，讓網站看到的是一般 Chrome
                        wv.settings.userAgentString = wv.settings.userAgentString.replace("; wv", "")
                        cm.setAcceptThirdPartyCookies(wv, true)
                        wv.webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, url: String?) {
                                main.removeCallbacks(poll)
                                main.postDelayed(poll, 300)
                            }
                        }
                        webView = wv
                        wv.loadUrl(url)
                    } catch (e: Exception) {
                        finish(Result.failure(e))
                    }
                }

                cont.invokeOnCancellation { main.post { finish(Result.failure(it ?: RuntimeException("cancelled"))) } }
            }
        } ?: throw IOException("網頁驗證逾時，可能被 Cloudflare 擋下")
}
