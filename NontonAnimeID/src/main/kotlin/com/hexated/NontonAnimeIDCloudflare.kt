package com.hexated

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Context
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
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

object NontonAnimeIDCloudflare {
    private const val TAG = "NontonAnimeIDCF"

    fun getSavedClearance(): String? {
        return try {
            getKey<String>(NontonAnimeIDSettings.KEY_CF_CLEARANCE)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun getSavedUserAgent(): String? {
        return try {
            getKey<String>(NontonAnimeIDSettings.KEY_CF_USER_AGENT)?.takeIf { it.isNotBlank() }
        } catch (_: Throwable) {
            null
        }
    }

    fun setSavedClearance(cookie: String) {
        try {
            setKey(NontonAnimeIDSettings.KEY_CF_CLEARANCE, cookie.trim())
        } catch (_: Throwable) {}
    }

    fun setSavedUserAgent(ua: String) {
        try {
            setKey(NontonAnimeIDSettings.KEY_CF_USER_AGENT, ua.trim())
        } catch (_: Throwable) {}
    }

    fun clearSavedClearance() {
        try {
            setKey(NontonAnimeIDSettings.KEY_CF_CLEARANCE, "")
        } catch (_: Throwable) {}
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
     */
    suspend fun safeGet(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap()
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

        if (isChallenge(response)) {
            Log.w(TAG, "Cloudflare challenge detected on $url")
            throw ErrorLoadingException(
                "Proteksi Cloudflare aktif. Buka Pengaturan Plugin NontonAnimeID -> 'Verifikasi Cloudflare (Turnstile)'."
            )
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
        headers: Map<String, String> = emptyMap()
    ): NiceResponse {
        val reqHeaders = headers.toMutableMap()
        getSavedUserAgent()?.let { reqHeaders["User-Agent"] = it }

        val cookies = mutableMapOf<String, String>()
        getSavedClearance()?.let { cookies["cf_clearance"] = it }

        val response = try {
            app.post(
                url = url,
                data = data,
                headers = reqHeaders,
                referer = referer,
                cookies = cookies
            )
        } catch (e: Exception) {
            Log.e(TAG, "POST request failed: ${e.message}")
            throw e
        }

        if (isChallenge(response)) {
            Log.w(TAG, "Cloudflare challenge detected on POST $url")
            throw ErrorLoadingException(
                "Proteksi Cloudflare aktif. Buka Pengaturan Plugin NontonAnimeID -> 'Verifikasi Cloudflare'."
            )
        }

        return response
    }

    /**
     * Displays an interactive WebView dialog in the Activity context to solve Turnstile.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun showInteractiveDialog(
        ctx: Context,
        targetUrl: String,
        onSolved: (() -> Unit)? = null
    ) {
        try {
            val container = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(16, 16, 16, 16)
            }

            val statusText = TextView(ctx).apply {
                text = "Memuat halaman... Silakan klik 'Verify you are human' jika muncul."
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
                    850
                )
            }
            container.addView(webView)

            val dialog = AlertDialog.Builder(ctx)
                .setTitle("Verifikasi Cloudflare")
                .setView(container)
                .setNegativeButton("Tutup") { d, _ ->
                    try {
                        webView.stopLoading()
                        (webView.parent as? ViewGroup)?.removeView(webView)
                        webView.destroy()
                    } catch (_: Throwable) {}
                    d.dismiss()
                }
                .create()

            val cookieManager = CookieManager.getInstance()
            try {
                cookieManager.setAcceptCookie(true)
            } catch (_: Throwable) {}

            with(webView.settings) {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                useWideViewPort = true
                loadWithOverviewMode = true
            }

            webView.webViewClient = object : WebViewClient() {
                private var handled = false

                private fun checkCookie(url: String?) {
                    if (handled || url == null) return
                    try {
                        val currentCookies = cookieManager.getCookie(url) ?: return
                        if (currentCookies.contains("cf_clearance")) {
                            val clearance = Regex("""cf_clearance=([^;]+)""").find(currentCookies)?.groupValues?.getOrNull(1)
                            if (!clearance.isNullOrBlank()) {
                                handled = true
                                setSavedClearance(clearance)
                                setSavedUserAgent(webView.settings.userAgentString)
                                Toast.makeText(ctx, "Verifikasi Cloudflare Berhasil!", Toast.LENGTH_LONG).show()
                                webView.stopLoading()
                                (webView.parent as? ViewGroup)?.removeView(webView)
                                webView.destroy()
                                dialog.dismiss()
                                onSolved?.invoke()
                            }
                        }
                    } catch (_: Throwable) {}
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    progressBar.visibility = android.view.View.GONE
                    checkCookie(url)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    checkCookie(request?.url?.toString())
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                    try {
                        (view?.parent as? ViewGroup)?.removeView(view)
                        view?.destroy()
                    } catch (_: Throwable) {}
                    return true
                }
            }

            dialog.show()
            webView.loadUrl(targetUrl)
        } catch (e: Throwable) {
            Toast.makeText(ctx, "Gagal membuka WebView: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }
}
