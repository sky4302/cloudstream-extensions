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

        // If user navigated into a batch or lengkap page, resolve parent anime page
        if (url.contains("/batch/") || url.contains("/lengkap/") || document.select("div.episodelist ul li").isEmpty()) {
            val parentAnimeUrl = document.selectFirst(
                "div.totalepisode a[href*='/anime/'], " +
                "div.totalepisode a, " +
                "a:contains(Download Episode), " +
                "a:contains(Klik Disini), " +
                "div.cukder a[href*='/anime/']"
            )?.attr("href")
            if (!parentAnimeUrl.isNullOrBlank() && fixUrl(parentAnimeUrl) != url) {
                return load(fixUrl(parentAnimeUrl))
            }
        }

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
            if (!epHref.contains("/episode/") && !epHref.contains("/special/") && !epHref.contains("/ova/") && !epHref.contains("/sp/")) return@forEach

            val epName = a.text().trim()
            val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""episode-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?:ova|special|sp)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: Regex("""(?:ova|special|sp)-(\d+)""", RegexOption.IGNORE_CASE).find(epHref)
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: if (epName.contains("ova", true) || epHref.contains("ova", true) ||
                       epName.contains("special", true) || epHref.contains("special", true)) 0 else null

            episodes.add(
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = epNum
                    this.posterUrl = poster
                }
            )
        }

        // Fallback for batch/lengkap listings if no episodes found in episodelist
        if (episodes.isEmpty()) {
            val batchOrLengkapLink = document.selectFirst(
                "div.episodelist a[href*='/lengkap/'], " +
                "div.episodelist a[href*='/batch/']"
            )?.attr("href")
            if (!batchOrLengkapLink.isNullOrBlank() && fixUrl(batchOrLengkapLink) != url) {
                val batchDoc = app.get(fixUrl(batchOrLengkapLink), timeout = 30L).document
                val parentAnime = batchDoc.selectFirst(
                    "div.totalepisode a[href*='/anime/'], " +
                    "div.totalepisode a, " +
                    "a:contains(Download Episode), " +
                    "a:contains(Klik Disini)"
                )?.attr("href")
                if (!parentAnime.isNullOrBlank() && fixUrl(parentAnime) != url) {
                    return load(fixUrl(parentAnime))
                }
            }
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

        val linkCallback: (ExtractorLink) -> Unit = { link ->
            linksFound = true
            callback(link)
        }

        // 1. Direct embeds in page
        document.select("div.responsive-embed-stream iframe, div#pembed iframe, iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.startsWith("about:") && !src.contains("ads", true)) {
                resolveOtakuStream(src, data, subtitleCallback, linkCallback)
            }
        }

        // 2. Resolve mirrorstream options via AJAX
        val streamActionMatch = Regex("""nonce[^}]+action:\s*["']([a-f0-9]{32})["']""").find(html)
        val nonceActionMatch = Regex("""data:\s*\{\s*action:\s*["']([a-f0-9]{32})["']\s*\}""").find(html)

        val streamAction = streamActionMatch?.groupValues?.get(1)
            ?: Regex("""action:\s*["']([a-f0-9]{32})["']""").find(html)?.groupValues?.get(1)
        val nonceAction = nonceActionMatch?.groupValues?.get(1)
            ?: Regex("""action:\s*["']([a-f0-9]{32})["']""").findAll(html).map { it.groupValues[1] }.distinct().toList().getOrNull(1)

        val inlineNonce = Regex("""nonce["']\s*:\s*["']([a-f0-9]{10,12})["']""").find(html)
            ?.groupValues?.getOrNull(1)

        if (!nonceAction.isNullOrBlank() && !streamAction.isNullOrBlank()) {
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
                                resolveOtakuStream(iframeSrc, data, subtitleCallback, linkCallback)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Mirror resolution failed: ${e.message}")
                    }
                }
            } else {
                Log.w(TAG, "Nonce is blank — mirror stream resolution skipped")
            }
        } else {
            Log.w(TAG, "Could not extract AJAX actions from page — mirror stream resolution skipped")
        }

        // 3. Resolve download mirrors (Hxfile, Filedon, Mega, etc.)
        document.select("div.download ul li a[href*='desustream']").amap { a ->
            val redirectLink = a.attr("href")
            try {
                val realUrl = app.get(redirectLink, referer = data, timeout = 15L).url
                if (realUrl.isNotBlank() && !realUrl.contains("desustream", true)) {
                    resolveOtakuStream(realUrl, data, subtitleCallback, linkCallback)
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
                        resolveOtakuStream(fixUrl(src), data, subtitleCallback, linkCallback)
                    } catch (e: Exception) {
                        Log.w(TAG, "Fallback iframe extraction failed for $src: ${e.message}")
                    }
                }
            }
        }

        return linksFound
    }

    private suspend fun resolveOtakuStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url

        if (fixed.contains("desustream") || fixed.contains("desudrive")) {
            try {
                val embedText = app.get(
                    fixed,
                    referer = referer,
                    headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"),
                    timeout = 20L
                ).text

                val doc = Jsoup.parse(embedText)

                // 1. Direct HTML5 video / source extraction (e.g. PlayDesu, OnDesu, OnDesuHD with googlevideo/blogger mp4)
                val sourceTag = doc.selectFirst("video source[src], source[src], video[src]")
                val html5VideoUrl = sourceTag?.attr("src")?.ifBlank { null }
                    ?: Regex("""<source[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedText)?.groupValues?.get(1)
                    ?: Regex("""<video[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(embedText)?.groupValues?.get(1)

                if (!html5VideoUrl.isNullOrBlank()) {
                    val streamUrl = html5VideoUrl.replace("&amp;", "&")
                    val titleText = doc.selectFirst("title")?.text().orEmpty()
                    val quality = when {
                        streamUrl.contains("itag=37") || titleText.contains("1080p", true) || streamUrl.contains("1080p", true) -> Qualities.P1080.value
                        streamUrl.contains("itag=22") || titleText.contains("720p", true) || streamUrl.contains("720p", true) -> Qualities.P720.value
                        titleText.contains("480p", true) || streamUrl.contains("480p", true) -> Qualities.P480.value
                        streamUrl.contains("itag=18") || titleText.contains("360p", true) || streamUrl.contains("360p", true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val qualityName = when (quality) {
                        Qualities.P1080.value -> "1080p"
                        Qualities.P720.value -> "720p"
                        Qualities.P480.value -> "480p"
                        Qualities.P360.value -> "360p"
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

                // 2. Google Drive / JWPlayer JSON streams (e.g. otakudesu('{"file":"https://www.googleapis.com/..."}'))
                val gdriveMatch = Regex("""["']file["']\s*:\s*["'](https?://(?:www\.)?googleapis\.com/drive/[^"']+)["']""").find(embedText)?.groupValues?.getOrNull(1)
                    ?: Regex("""otakudesu\s*\(\s*['"]\{.*?"file"\s*:\s*"([^'"]+)".*?\}['"]\s*\)""").find(embedText)?.groupValues?.getOrNull(1)
                    ?: Regex("""["']file["']\s*:\s*["'](https?://[^"']+)["']""").find(embedText)?.groupValues?.getOrNull(1)

                if (!gdriveMatch.isNullOrBlank() && (gdriveMatch.contains("googleapis.com", true) || gdriveMatch.contains("drive.google.com", true))) {
                    val streamUrl = gdriveMatch.replace("&amp;", "&")
                    callback.invoke(
                        newExtractorLink("Otakudesu", "DesuDrive", streamUrl, INFER_TYPE) {
                            this.referer = "https://desustream.net/"
                            this.headers = mapOf(
                                "Referer" to "https://desustream.net/",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }

                // 3. Lazy-load player / videoURL pattern (e.g. cdn.odcloud.net)
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

                // 4. Archive.org / direct MP4 playerjs
                val fileMatch = Regex("""file\s*:\s*["'](https?://[^"']+\.mp4[^"']*)["']""").find(embedText)
                if (fileMatch != null) {
                    val streamUrl = fileMatch.groupValues[1]
                    callback.invoke(
                        newExtractorLink("Otakudesu", "DesuStream Archive", streamUrl, INFER_TYPE) {
                            this.referer = fixed
                            this.headers = mapOf(
                                "Referer" to fixed,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        }
                    )
                }

                // 5. Blogger video iframe
                val bloggerMatch = Regex("""src=["'](https?://www\.blogger\.com/video\.g\?[^"']+)["']""").find(embedText)
                if (bloggerMatch != null) {
                    loadExtractor(bloggerMatch.groupValues[1], fixed, subtitleCallback, callback)
                }

                // 6. Secondary wrapper iframes inside player (e.g. yuplod.php embedding yourupload, mp4upload, etc.)
                doc.select("iframe").forEach { innerIframe ->
                    val innerSrc = innerIframe.attr("src").ifBlank { innerIframe.attr("data-src") }
                    if (innerSrc.isNotBlank() && innerSrc != fixed && !innerSrc.startsWith("about:") && !innerSrc.contains("ads", true)) {
                        val fixedInner = fixUrl(innerSrc)
                        if (fixedInner.contains("desustream") || fixedInner.contains("desudrive")) {
                            resolveOtakuStream(fixedInner, fixed, subtitleCallback, callback)
                        } else {
                            loadExtractor(fixedInner, fixed, subtitleCallback, callback)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "DesuStream resolution failed for $fixed: ${e.message}")
            }
        } else if (fixed.contains("odvidhide.com")) {
            // Forward to Vidhide extractor by domain normalization
            loadExtractor(fixed.replace("odvidhide.com", "vidhidepro.com"), referer, subtitleCallback, callback)
        } else if (fixed.contains("filedon.co")) {
            try {
                val fileText = app.get(fixed, referer = referer).text
                val streamUrl = Regex("""["']stream_url["']\s*:\s*["'](https?://[^"']+)["']""").find(fileText)?.groupValues?.get(1)
                    ?: Regex("""["']url["']\s*:\s*["'](https?:\\?/\\?/[^"']+\.mp4[^"']*)["']""").find(fileText)?.groupValues?.get(1)?.replace("\\/", "/")
                    ?: Regex("""https?://[^"'\s<>]+\.r2\.cloudflarestorage\.com/[^"'\s<>]+""").find(fileText)?.value

                if (!streamUrl.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink("Filedon", "Filedon", streamUrl.replace("&amp;", "&"), INFER_TYPE) {
                            this.referer = fixed
                        }
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "Filedon extraction failed: ${e.message}")
            }
        } else {
            loadExtractor(fixed, referer, subtitleCallback, callback)
        }
    }
}
