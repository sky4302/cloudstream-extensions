package com.hexated

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.regex.PatternSyntaxException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class Kuronime : MainAPI() {
    override var mainUrl = "https://kuronime.sbs"
    override var name = "Kuronime"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie, TvType.OVA)

    companion object {
        private const val CRYPTO_KEY = "3&!Z0M,VIZ;dZW=="
        private const val ANIMEKU_API = "https://animeku.org/api/v9/sources"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Episode Terbaru",
        "$mainUrl/ongoing-anime/page/" to "Anime Ongoing",
        "$mainUrl/popular-anime/page/" to "Paling Populer",
        "$mainUrl/movies/page/" to "Anime Movies"
    )

    private fun episodeToAnimeUrl(url: String): String {
        val path = url.trimEnd('/').substringAfterLast("/")
        val withoutPrefix = if (path.startsWith("nonton-")) path.removePrefix("nonton-") else path
        val slug = try {
            Regex("-episode-\\d+.*$", RegexOption.IGNORE_CASE).replace(withoutPrefix, "")
        } catch (e: PatternSyntaxException) {
            val idx = withoutPrefix.indexOf("-episode-", ignoreCase = true)
            if (idx != -1) withoutPrefix.substring(0, idx) else withoutPrefix
        }
        return "$mainUrl/anime/$slug/"
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document

        val items = document.select("article.bsu, div.listupd article.bs, article.bs").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsux, div.bsx") ?: article
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = bsux.selectFirst("div.bsuxtt h2, div.tt h2, .title, h2")?.text()?.trim()
                ?: return@mapNotNull null

            val poster = a.selectFirst("div.limit img[itemprop=image]")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("img")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim().orEmpty()
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }

        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.bs, article.bsu").mapNotNull { article ->
            val bsux = article.selectFirst("div.bsx, div.bsux") ?: article
            val a = bsux.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null

            val title = a.attr("title").trim().ifBlank { null }
                ?: bsux.selectFirst("div.bsuxtt h2, div.tt h2, h2")?.text()?.trim()?.ifBlank { null }
                ?: return@mapNotNull null

            val poster = a.selectFirst("img[itemprop=image]")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("img:not([src*=controls-play])")?.let { it.attr("src").ifBlank { it.attr("data-src") } }
                ?: a.selectFirst("div.limit img")?.attr("src")?.ifBlank { null }

            val epText = a.selectFirst("div.bt div.ep")?.text()?.trim().orEmpty()
            val epNum = Regex("(\\d+)").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

            val animeUrl = if (href.contains("/anime/")) href else episodeToAnimeUrl(href)

            newAnimeSearchResponse(title, animeUrl, TvType.Anime) {
                this.posterUrl = poster
                addSub(epNum)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeUrl = if (url.contains("/anime/")) url else episodeToAnimeUrl(url)
        val document = app.get(animeUrl).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("h1")?.text()?.trim()
            ?: throw ErrorLoadingException("Title not found")

        val cleanTitle = title
            .replace(Regex("\\s*Subtitle Indonesia.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s*Sub\\s*Indo.*", RegexOption.IGNORE_CASE), "")
            .trim()

        val poster = document.selectFirst("div.ime img, div.thumb img")?.let { img ->
            img.attr("data-src").ifBlank { null } ?: img.attr("src").ifBlank { null }
        } ?: document.selectFirst("meta[property=og:image]")?.attr("content")?.ifBlank { null }

        val description = document.selectFirst("div.synp p, div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")

        val genres = document.select("div.genxed a, a[href*=/genres/]")
            .map { it.text().trim() }
            .filter { it.isNotBlank() }

        val episodes = document.select("div.bixbox ul li, div.bxcl ul li").mapNotNull { li ->
            val a = li.selectFirst("a[href]") ?: return@mapNotNull null
            val href = a.attr("href").ifBlank { null } ?: return@mapNotNull null
            val epText = a.text().trim().ifBlank { null } ?: return@mapNotNull null
            if (!href.contains("episode", true) && !href.contains("nonton", true) && !href.contains("movie", true)) {
                return@mapNotNull null
            }
            val epNum = Regex("(\\d+)").find(
                epText.replace(Regex("Episode\\s*", RegexOption.IGNORE_CASE), "")
            )?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("episode-(\\d+)", RegexOption.IGNORE_CASE).find(href)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
            newEpisode(fixUrl(href)) {
                this.name = "Episode ${epNum ?: epText}"
                this.episode = epNum
                this.posterUrl = poster
            }
        }.distinctBy { it.data }.sortedBy { it.episode }

        return newAnimeLoadResponse(cleanTitle, animeUrl, TvType.Anime) {
            engName = cleanTitle
            posterUrl = poster
            backgroundPosterUrl = poster
            plot = description
            this.tags = genres.distinct()
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val pageHtml = app.get(data).text

        val apiId = Regex("""_0xa100d42aa\s*=\s*["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
        if (!apiId.isNullOrBlank()) {
            try {
                val postJson = JSONObject().put("id", apiId).toString()
                val apiResponse = app.post(
                    ANIMEKU_API,
                    requestBody = postJson.toRequestBody("application/json; charset=utf-8".toMediaType()),
                    headers = mapOf(
                        "Content-Type" to "application/json",
                        "Origin" to mainUrl,
                        "Referer" to "$data/",
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text

                val rootJson = JSONObject(apiResponse)
                if (rootJson.optInt("status", 0) == 200) {
                    // 1. Process direct HLS src
                    val srcEncrypted = rootJson.optString("src")
                    if (srcEncrypted.isNotBlank()) {
                        try {
                            val srcRawJson = String(Base64.decode(srcEncrypted, Base64.DEFAULT), Charsets.UTF_8)
                            val srcDecrypted = decryptCryptoJsAes(srcRawJson, CRYPTO_KEY)
                            if (!srcDecrypted.isNullOrBlank()) {
                                val srcObj = JSONObject(srcDecrypted)
                                val m3u8Url = srcObj.optString("src")
                                if (m3u8Url.isNotBlank() && m3u8Url.contains(".m3u8")) {
                                    M3u8Helper.generateM3u8(
                                        name,
                                        m3u8Url,
                                        "https://animeku.org/",
                                        headers = mapOf("Origin" to "https://animeku.org")
                                    ).forEach(callback)
                                }
                            }
                        } catch (_: Exception) {}
                    }

                    // 2. Process mirrors
                    val mirrorEncrypted = rootJson.optString("mirror")
                    if (mirrorEncrypted.isNotBlank()) {
                        try {
                            val mirrorRawJson = String(Base64.decode(mirrorEncrypted, Base64.DEFAULT), Charsets.UTF_8)
                            val mirrorDecrypted = decryptCryptoJsAes(mirrorRawJson, CRYPTO_KEY)
                            if (!mirrorDecrypted.isNullOrBlank()) {
                                val mirrorJson = JSONObject(mirrorDecrypted)

                                // Parse embed mirrors
                                val embedObj = mirrorJson.optJSONObject("embed")
                                if (embedObj != null) {
                                    val keys = embedObj.keys()
                                    while (keys.hasNext()) {
                                        val qualityKey = keys.next()
                                        val qualityMap = embedObj.optJSONObject(qualityKey) ?: continue
                                        val serviceKeys = qualityMap.keys()
                                        while (serviceKeys.hasNext()) {
                                            val service = serviceKeys.next()
                                            val embedUrl = qualityMap.optString(service)
                                            if (embedUrl.isNotBlank() && embedUrl != "null" && embedUrl.startsWith("http")) {
                                                loadExtractor(embedUrl, data, subtitleCallback, callback)
                                            }
                                        }
                                    }
                                }

                                // Parse direct download mirrors
                                val downloadObj = mirrorJson.optJSONObject("download")
                                if (downloadObj != null) {
                                    val keys = downloadObj.keys()
                                    while (keys.hasNext()) {
                                        val qualityKey = keys.next()
                                        val qualityMap = downloadObj.optJSONObject(qualityKey) ?: continue
                                        val serviceKeys = qualityMap.keys()
                                        while (serviceKeys.hasNext()) {
                                            val service = serviceKeys.next()
                                            val dlUrl = qualityMap.optString(service)
                                            if (dlUrl.isNotBlank() && dlUrl != "null" && dlUrl.startsWith("http")) {
                                                loadExtractor(dlUrl, data, subtitleCallback, callback)
                                            }
                                        }
                                    }
                                }
                            }
                        } catch (_: Exception) {}
                    }
                }
            } catch (_: Exception) {}
        }

        // 3. Fallback to page iframes and download links
        val doc = app.get(data).document
        val iframes = doc.select("iframe[src], iframe[data-src]").mapNotNull {
            val s = it.attr("src").ifBlank { it.attr("data-src") }
            if (s.isNotBlank() && !s.startsWith("about:") && !s.contains("youtube.com")) s else null
        }
        for (iframe in iframes) {
            val fullUrl = if (iframe.startsWith("//")) "https:$iframe" else iframe
            loadExtractor(fullUrl, data, subtitleCallback, callback)
        }

        doc.select("a[href]").forEach { a ->
            val href = a.attr("href").ifBlank { null } ?: return@forEach
            when {
                href.contains("krakenfiles.com") -> {
                    try {
                        val kfDoc = app.get(href).document
                        val videoUrl = kfDoc.selectFirst("source[src*=krakencloud], source[type=video/mp4]")
                            ?.attr("src")?.ifBlank { null }
                        if (videoUrl != null) {
                            callback(newExtractorLink("KrakenFiles", "Download", videoUrl) {
                                this.quality = Qualities.Unknown.value
                                this.referer = href
                            })
                        }
                    } catch (_: Exception) {}
                }
                href.contains("pixeldrain.com") -> {
                    val pdId = Regex("pixeldrain\\.com/u/(\\w+)").find(href)?.groupValues?.getOrNull(1)
                    if (pdId != null) {
                        callback(newExtractorLink("PixelDrain", "Download", "https://pixeldrain.com/api/file/$pdId") {
                            this.quality = Qualities.Unknown.value
                            this.referer = href
                        })
                    }
                }
            }
        }

        return true
    }

    private fun decryptCryptoJsAes(cipherJsonStr: String, passphrase: String): String? {
        return try {
            val json = JSONObject(cipherJsonStr)
            val ctBase64 = json.getString("ct")
            val ivHex = json.getString("iv")
            val saltHex = json.getString("s")

            val salt = hexToBytes(saltHex)
            val iv = hexToBytes(ivHex)
            val ct = Base64.decode(ctBase64, Base64.DEFAULT)

            // OpenSSL EVP_BytesToKey equivalent for CryptoJS (MD5, 1 iteration, 32-byte key)
            val passBytes = passphrase.toByteArray(Charsets.UTF_8)
            val md = MessageDigest.getInstance("MD5")
            var currentHash = ByteArray(0)
            val keyBytes = ByteArray(32)
            var keyPos = 0

            while (keyPos < 32) {
                md.reset()
                md.update(currentHash)
                md.update(passBytes)
                md.update(salt)
                currentHash = md.digest()
                for (b in currentHash) {
                    if (keyPos < 32) {
                        keyBytes[keyPos++] = b
                    }
                }
            }

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), IvParameterSpec(iv))
            val decryptedBytes = cipher.doFinal(ct)
            String(decryptedBytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val result = ByteArray(hex.length / 2)
        for (i in result.indices) {
            result[i] = hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        return result
    }
}
