package com.hexated

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.net.URLEncoder

class Otakudesu : MainAPI() {
    override var mainUrl = "https://otakudesu.blog"
    override var name = "Otakudesu"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val TAG = "Otakudesu"
    }

    override val mainPage = mainPageOf(
        "ongoing-anime/" to "Anime Ongoing",
        "complete-anime/" to "Anime Completed"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page <= 1) request.data else "${request.data}page/$page/"
        val document = app.get("$mainUrl/$path", timeout = 30L).document
        val home = document.select("div.venz ul li, div.rseries div.rapi ul li").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val title = this.selectFirst("h2.jdlflm, h2, .jdlarea h2")?.text()?.trim()
            ?: a.text().trim()
        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(
            this.selectFirst("img")?.attr("src")
                ?: this.selectFirst("img")?.attr("data-src")
        )

        val epText = this.selectFirst("div.epz, div.epztipe")?.text()?.trim().orEmpty()
        val epNum = Regex("""(\d+)""").find(epText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            if (epNum != null) addSub(epNum)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/?s=$encodedQuery&post_type=anime", timeout = 30L).document
        return document.select("ul.chivsrc li").mapNotNull { li ->
            val a = li.selectFirst("h2 a") ?: li.selectFirst("a") ?: return@mapNotNull null
            val href = fixUrl(a.attr("href"))
            val title = a.text().trim()
            if (title.isBlank()) return@mapNotNull null

            val poster = fixUrlNull(li.selectFirst("img")?.attr("src"))
            val rating = li.selectFirst("div.set:contains(Rating)")?.text()
                ?.substringAfter(":")?.trim()?.toDoubleOrNull()

            newAnimeSearchResponse(title, href, TvType.Anime) {
                this.posterUrl = poster
                if (rating != null) this.score = Score.from10(rating)
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document

        val title = document.selectFirst("div.infozingle p:contains(Judul) span")?.text()?.trim()
            ?: document.selectFirst("div.jdlarea h1, h1.entry-title")?.text()?.trim().orEmpty()

        val poster = fixUrlNull(
            document.selectFirst("div.fotoanime img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )

        val synopsis = document.select("div.sinopc p").joinToString("\n\n") { it.text().trim() }
            .ifBlank { document.selectFirst("meta[property='og:description']")?.attr("content") }

        val genres = document.select("div.infozingle p:contains(Genre) a").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val rating = document.selectFirst("div.infozingle p:contains(Skor) span")?.text()?.trim()

        val isMovie = url.contains("/anime-movie/") || genres.any { it.contains("Movie", true) }

        val episodes = mutableListOf<Episode>()
        document.select("div.episodelist ul li").forEach { li ->
            val a = li.selectFirst("span a[href]") ?: li.selectFirst("a[href]") ?: return@forEach
            val epHref = fixUrl(a.attr("href"))
            if (!epHref.contains("/episode/")) return@forEach

            val epName = a.text().trim()
            val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()

            episodes.add(
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
        }

        return if (isMovie && episodes.isEmpty()) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = synopsis
                this.tags = genres
                if (rating != null) addScore(rating, 10)
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.reversed().distinctBy { it.data }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = synopsis
                this.tags = genres
                if (rating != null) addScore(rating, 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, timeout = 30L).document
        val html = document.html()
        var linksFound = false

        // 1. Direct embeds in page
        document.select("div.responsive-embed-stream iframe, div#pembed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("ads", true)) {
                resolveOtakuStream(src, data, subtitleCallback, callback)
                linksFound = true
            }
        }

        // 2. Resolve mirrorstream options via AJAX
        // Strategy A: Extract action hashes from inline JS
        val actionsA = Regex("""action:\s*["']([a-f0-9]{32})["']""").findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        // Strategy B: Extract from localized script vars / wp data attributes
        val actionsB = Regex("""["']action["']\s*:\s*["']([a-f0-9]{20,40})["']""").findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        val allActions = (actionsA + actionsB).distinct()

        // Try to extract nonce directly from inline scripts
        val inlineNonce = Regex("""nonce["']\s*:\s*["']([a-f0-9]{10,12})["']""").find(html)
            ?.groupValues?.getOrNull(1)

        val nonceAction = allActions.getOrNull(1) ?: allActions.getOrNull(0)
        val streamAction = allActions.getOrNull(0)

        if (nonceAction != null && streamAction != null) {
            val nonce = inlineNonce ?: try {
                val nonceRes = app.post(
                    "$mainUrl/wp-admin/admin-ajax.php",
                    data = mapOf("action" to nonceAction),
                    referer = data,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                ).text
                JSONObject(nonceRes).optString("data", "")
            } catch (e: Exception) {
                Log.w(TAG, "Nonce fetch failed for action=$nonceAction: ${e.message}")
                ""
            }

            if (nonce.isNotBlank()) {
                // Broadened selector: try multiple known mirror container patterns
                document.select(
                    "div.mirrorstream ul li a[data-content], " +
                    "div.mirror ul li a[data-content], " +
                    "div.server ul li a[data-content]"
                ).amap { a ->
                    val b64 = a.attr("data-content")
                    try {
                        val rawJson = String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                        val json = JSONObject(rawJson)
                        val id = json.opt("id")?.toString() ?: ""
                        val i = json.opt("i")?.toString() ?: ""
                        val q = json.optString("q", "")

                        val res = app.post(
                            "$mainUrl/wp-admin/admin-ajax.php",
                            data = mapOf(
                                "action" to streamAction,
                                "nonce" to nonce,
                                "id" to id,
                                "i" to i,
                                "q" to q
                            ),
                            referer = data,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).text

                        val embedB64 = JSONObject(res).optString("data", "")
                        if (embedB64.isNotBlank()) {
                            val decodedHtml = String(Base64.decode(embedB64, Base64.DEFAULT), Charsets.UTF_8)
                            val iframeSrc = Jsoup.parse(decodedHtml).selectFirst("iframe")?.attr("src")
                            if (!iframeSrc.isNullOrBlank()) {
                                resolveOtakuStream(iframeSrc, data, subtitleCallback, callback)
                                linksFound = true
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Mirror resolution failed for data-content: ${e.message}")
                    }
                }
            } else {
                Log.w(TAG, "Nonce is blank — mirror stream resolution skipped")
            }
        } else {
            Log.w(TAG, "Could not extract AJAX actions from page — mirror stream resolution skipped (found ${allActions.size} actions)")
        }

        // 3. Resolve download mirrors (Hxfile, KrakenFiles, Mega, etc.)
        // Broadened selector: match any desustream redirect domain variant
        document.select("div.download ul li a[href*='desustream']").amap { a ->
            val redirectLink = a.attr("href")
            try {
                val realUrl = app.get(redirectLink, referer = data, timeout = 15L).url
                if (realUrl.isNotBlank() && !realUrl.contains("desustream", true)) {
                    loadExtractor(realUrl, data, subtitleCallback, callback)
                    linksFound = true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Download mirror redirect failed for $redirectLink: ${e.message}")
            }
        }

        // 4. Final fallback: extract any video-related iframe from page
        if (!linksFound) {
            Log.w(TAG, "No links found from strategies 1-3, trying fallback iframe extraction")
            document.select("iframe[src*='player'], iframe[src*='embed'], iframe[src*='stream']").forEach { iframe ->
                val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
                if (src.isNotBlank() && !src.startsWith("about:")) {
                    try {
                        loadExtractor(fixUrl(src), data, subtitleCallback, callback)
                        linksFound = true
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback iframe extraction failed for $src: ${e.message}")
                    }
                }
            }
        }

        return true
    }

    private suspend fun resolveOtakuStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url

        // Broadened check: match any desustream TLD variant
        if (fixed.contains("desustream")) {
            try {
                val embedText = app.get(
                    fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"),
                    timeout = 20L
                ).text

                // 1. Lazy-load player / videoURL pattern (e.g. cdn.odcloud.net)
                val videoUrlMatch = Regex("""videoURL\s*=\s*["'](https?://[^"']+)["']""").find(embedText)
                    ?: Regex("""["'](https?://cdn\.odcloud\.net/[^"'s]+\.mp4[^"']*)["']""").find(embedText)

                if (videoUrlMatch != null) {
                    val streamUrl = videoUrlMatch.groupValues[1]
                    val quality = when {
                        streamUrl.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                        streamUrl.contains("720p", ignoreCase = true) -> Qualities.P720.value
                        streamUrl.contains("480p", ignoreCase = true) -> Qualities.P480.value
                        streamUrl.contains("360p", ignoreCase = true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val qualityName = when {
                        streamUrl.contains("1080p", ignoreCase = true) -> "1080p"
                        streamUrl.contains("720p", ignoreCase = true) -> "720p"
                        streamUrl.contains("480p", ignoreCase = true) -> "480p"
                        streamUrl.contains("360p", ignoreCase = true) -> "360p"
                        else -> "MP4"
                    }

                    callback.invoke(
                        newExtractorLink("Otakudesu", "DesuStream $qualityName", streamUrl, INFER_TYPE) {
                            this.referer = "https://desustream.net/"
                            this.headers = mapOf(
                                "Referer" to "https://desustream.net/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                            this.quality = quality
                        }
                    )
                }

                // 2. Check for Blogger video iframe
                val bloggerMatch = Regex("""src=["'](https?://www\.blogger\.com/video\.g\?[^"']+)["']""").find(embedText)
                if (bloggerMatch != null) {
                    loadExtractor(bloggerMatch.groupValues[1], fixed, subtitleCallback, callback)
                }

                // 3. Check for Archive.org / direct MP4 playerjs
                val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(embedText)
                if (fileMatch != null) {
                    val streamUrl = fileMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink("Otakudesu", "DesuStream", streamUrl, INFER_TYPE) {
                            this.referer = fixed
                            this.headers = mapOf(
                                "Referer" to fixed,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }

                // 4. Check for secondary iframe inside player
                val innerIframe = Jsoup.parse(embedText).selectFirst("iframe#myIframe, iframe")?.attr("src")
                if (!innerIframe.isNullOrBlank() && innerIframe != fixed) {
                    loadExtractor(innerIframe, fixed, subtitleCallback, callback)
                }
            } catch (e: Exception) {
                Log.w(TAG, "DesuStream resolution failed for $fixed: ${e.message}")
            }
        } else {
            loadExtractor(fixed, referer, subtitleCallback, callback)
        }
    }
}
