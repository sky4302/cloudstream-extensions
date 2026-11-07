package com.hexated

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.CloudStreamApp.Companion.setKey
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.app
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object NontonAnimeIDCloudflare {
    private const val TAG = "NontonAnimeIDCF"

    fun getSavedClearance(): String? {
        return getKey<String>(NontonAnimeIDSettings.KEY_CF_CLEARANCE)?.takeIf { it.isNotBlank() }
    }

    fun getSavedUserAgent(): String? {
        return getKey<String>(NontonAnimeIDSettings.KEY_CF_USER_AGENT)?.takeIf { it.isNotBlank() }
    }

    fun clearSavedClearance() {
        setKey(NontonAnimeIDSettings.KEY_CF_CLEARANCE, "")
    }

    fun isChallenge(response: NiceResponse): Boolean {
        if (response.code in listOf(403, 503)) return true
        val title = response.document.title().lowercase()
        if (title.contains("just a moment") || title.contains("cloudflare")) return true
        if (response.headers["cf-mitigated"] == "challenge") return true
        return false
    }

    /**
     * Executes GET request with Cloudflare clearance cookie & UA injection.
     * If challenge is met, attempts silent background resolution.
     */
    suspend fun safeGet(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowRetry: Boolean = true
    ): NiceResponse {
        val reqHeaders = headers.toMutableMap()
        getSavedUserAgent()?.let { reqHeaders["User-Agent"] = it }

        val cookies = mutableMapOf<String, String>()
        getSavedClearance()?.let { cookies["cf_clearance"] = it }

        val response = try {
            app.get(
                url = url,
                headers = reqHeaders,
                referer = referer,
                cookies = cookies
            )
        } catch (e: Exception) {
            Log.e(TAG, "Request failed: ${e.message}")
            throw e
        }

        if (isChallenge(response) && allowRetry) {
            Log.w(TAG, "Cloudflare challenge detected on $url. Attempting background WebView solve...")
            val solved = resolveBackground(url)
            if (solved) {
                Log.i(TAG, "Background solve successful! Retrying request...")
                return safeGet(url, referer, headers, allowRetry = false)
            } else {
                Log.w(TAG, "Background solve failed or requires manual click.")
                throw ErrorLoadingException(
                    "Cloudflare Turnstile terdeteksi! Silakan buka Pengaturan Plugin NontonAnimeID -> 'Verifikasi Cloudflare' untuk verifikasi sekali saja."
                )
            }
        }

        return response
    }

    /**
     * Executes POST request with Cloudflare clearance cookie & UA injection.
     */
    suspend fun safePost(
        url: String,
        data: Map<String, String>,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowRetry: Boolean = true
    ): NiceResponse {
        val reqHeaders = headers.toMutableMap()
        getSavedUserAgent()?.let { reqHeaders["User-Agent"] = it }

        val cookies = mutableMapOf<String, String>()
        getSavedClearance()?.let { cookies["cf_clearance"] = it }

        val response = app.post(
            url = url,
            data = data,
            headers = reqHeaders,
            referer = referer,
            cookies = cookies
        )

        if (isChallenge(response) && allowRetry) {
            Log.w(TAG, "Cloudflare challenge detected on POST $url. Attempting background WebView solve...")
            val solved = resolveBackground(url)
            if (solved) {
                return safePost(url, data, referer, headers, allowRetry = false)
            } else {
                throw ErrorLoadingException(
                    "Cloudflare Turnstile terdeteksi! Buka Pengaturan Plugin NontonAnimeID -> 'Verifikasi Cloudflare'."
                )
            }
        }

        return response
    }

    /**
     * Attempts background silent bypass using Android's WebView.
     */
    @SuppressLint("SetJavaScriptEnabled")
    suspend fun resolveBackground(targetUrl: String): Boolean = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        var solved = false
        val mainHandler = Handler(Looper.getMainLooper())

        mainHandler.post {
            try {
                val context = com.lagradost.cloudstream3.CloudStreamApp.context
                if (context == null) {
                    Log.e(TAG, "Application context is null, cannot spawn WebView")
                    latch.countDown()
                    return@post
                }

                val webView = WebView(context)
                val cookieManager = CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)

                with(webView.settings) {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    useWideViewPort = true
                    loadWithOverviewMode = true
                }

                webView.webViewClient = object : WebViewClient() {
                    private fun checkCookie(url: String?) {
                        if (url == null) return
                        val currentCookies = cookieManager.getCookie(url) ?: return
                        if (currentCookies.contains("cf_clearance")) {
                            val clearance = Regex("""cf_clearance=([^;]+)""").find(currentCookies)?.groupValues?.getOrNull(1)
                            if (!clearance.isNullOrBlank()) {
                                setKey(NontonAnimeIDSettings.KEY_CF_CLEARANCE, clearance)
                                setKey(NontonAnimeIDSettings.KEY_CF_USER_AGENT, webView.settings.userAgentString)
                                solved = true
                                Log.i(TAG, "Extracted cf_clearance: $clearance")
                                webView.stopLoading()
                                webView.destroy()
                                latch.countDown()
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        checkCookie(url)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        checkCookie(request?.url?.toString())
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(targetUrl)
            } catch (e: Exception) {
                Log.e(TAG, "Error in background WebView: ${e.message}")
                latch.countDown()
            }
        }

        // Wait up to 15 seconds for silent resolution
        latch.await(15, TimeUnit.SECONDS)
        return@withContext solved
    }

    /**
     * Displays an interactive WebView dialog to allow user to solve Turnstile checkbox.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showInteractiveDialog(
        ctx: Context,
        targetUrl: String,
        onSolved: (() -> Unit)? = null
    ) {
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 16, 16, 16)
        }

        val statusText = TextView(ctx).apply {
            text = "Memuat proteksi Cloudflare... Jika muncul kotak centang 'Verify you are human', silakan klik."
            textSize = 14f
            setPadding(0, 0, 0, 16)
        }
        container.addView(statusText)

        val progressBar = ProgressBar(ctx).apply {
            isIndeterminate = true
        }
        container.addView(progressBar)

        val webView = WebView(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                800
            )
        }
        container.addView(webView)

        val dialog = AlertDialog.Builder(ctx)
            .setTitle("Verifikasi Cloudflare Turnstile")
            .setView(container)
            .setNegativeButton("Tutup") { d, _ ->
                webView.stopLoading()
                webView.destroy()
                d.dismiss()
            }
            .create()

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            private fun checkCookie(url: String?) {
                if (url == null) return
                val currentCookies = cookieManager.getCookie(url) ?: return
                if (currentCookies.contains("cf_clearance")) {
                    val clearance = Regex("""cf_clearance=([^;]+)""").find(currentCookies)?.groupValues?.getOrNull(1)
                    if (!clearance.isNullOrBlank()) {
                        setKey(NontonAnimeIDSettings.KEY_CF_CLEARANCE, clearance)
                        setKey(NontonAnimeIDSettings.KEY_CF_USER_AGENT, webView.settings.userAgentString)
                        Toast.makeText(ctx, "Verifikasi Cloudflare Berhasil!", Toast.LENGTH_LONG).show()
                        webView.stopLoading()
                        webView.destroy()
                        dialog.dismiss()
                        onSolved?.invoke()
                    }
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                progressBar.visibility = android.view.View.GONE
                checkCookie(url)
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                checkCookie(request?.url?.toString())
                return super.shouldInterceptRequest(view, request)
            }
        }

        dialog.show()
        webView.loadUrl(targetUrl)
    }
}
