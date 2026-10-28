package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI

class LayarKacaProvider : MainAPI() {
    override var mainUrl = "https://tv12.lk21official.cc"
    override var name = "LayarKaca"
    override val hasMainPage = true
    override var lang = "id"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    private val seriesUrl = "https://tv1.layanfilem.my"

    override val mainPage = mainPageOf(
        "$mainUrl/populer/page/" to "Film Terpopuler",
        "$mainUrl/rating/page/" to "Film Berdasarkan IMDb Rating",
        "$mainUrl/most-commented/page/" to "Film Dengan Komentar Terbanyak",
        "$mainUrl/latest/page/" to "Film Upload Terbaru",
        "$seriesUrl/latest-series/page/" to "Series Terbaru",
        "$seriesUrl/series/asian/page/" to "Film Asian Terbaru"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "${request.data}$page"
        val doc = appGet(url).document
        val items = doc.select("article").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url.trimEnd('/') }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = selectFirst("h3, h1, .grid-title, .entry-title") ?: return null
        val title = titleEl.ownText().ifBlank { titleEl.text() }.trim()
        if (title.isBlank()) return null

        val a = selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null

        val img = selectFirst("img")
        val rawPoster = img?.attr("src")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
        val posterUrl = fixUrlNull(rawPoster)

        val isSeries = href.contains("/series/") || selectFirst("span.episode, .episode") != null
        val quality = selectFirst("div.quality, span.quality")?.text()?.trim().orEmpty()

        return if (isSeries) {
            val epText = selectFirst("span.episode strong, span.episode, .episode")?.text().orEmpty()
            val epNum = Regex("""\d+""").find(epText)?.value?.toIntOrNull()

            newAnimeSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (epNum != null) addSub(epNum)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()

        // 1. Try search API
        try {
            val apiRes = appGet(
                "https://gudangvape.com/search.php?s=$query",
                referer = mainUrl
            ).text.trim()

            if (apiRes.startsWith("{")) {
                val json = JSONObject(apiRes)
                val data = json.optJSONArray("data")
                if (data != null) {
                    for (i in 0 until data.length()) {
                        val item = data.getJSONObject(i)
                        val title = item.optString("title")
                        val slug = item.optString("slug")
                        val type = item.optString("type")
                        val posterUrl = "https://static-jpg.lk21.party/wp-content/uploads/" + item.optString("poster")

                        if (type == "series") {
                            results.add(
                                newTvSeriesSearchResponse(title, "$seriesUrl/$slug", TvType.TvSeries) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        } else {
                            results.add(
                                newMovieSearchResponse(title, "$mainUrl/$slug", TvType.Movie) {
                                    this.posterUrl = posterUrl
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback to HTML search
        if (results.isEmpty()) {
            try {
                val searchDoc = appGet("$mainUrl/?s=$query").document
                val searchItems = searchDoc.select("article").mapNotNull {
                    it.toSearchResult()
                }
                results.addAll(searchItems)
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url.trimEnd('/') }
    }

    override suspend fun load(url: String): LoadResponse {
        val properUrl = getProperLink(url)
        val doc = appGet(properUrl).document

        val title = doc.selectFirst("div.movie-info h1, h1.entry-title, h1")?.text()?.trim()
            ?: properUrl.substringBeforeLast("/").substringAfterLast("/")

        val poster = fixUrlNull(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst("div.poster img, figure img")?.attr("src")
        )

        val tags = doc.select("div.tag-list span, .genre a, a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }
        val description = doc.selectFirst("div.meta-info, div.entry-content, p.desc, div.synopsis p")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
        val year = Regex("""\b(19\d\d|20\d\d)\b""").find(doc.select("div.movie-info, h1, .release").text())?.value?.toIntOrNull()
        val rating = doc.selectFirst("div.info-tag strong, span.rating, .imdb-rating, span[itemprop='ratingValue']")?.text()?.trim()
        val trailer = doc.selectFirst("ul.action-left > li a[href*='youtube.com'], a.trailer[href*='youtube.com']")?.attr("href")

        val recommendations = doc.select("#slider article, .slider article, #related-posts article, .related article, ul.slider li article")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url.trimEnd('/') }

        val isSeries = properUrl.contains(seriesUrl) || doc.selectFirst("#season-data, div.episode-list, div.serial-wrapper") != null

        if (isSeries) {
            val episodes = mutableListOf<Episode>()

            val seasonDataScript = doc.selectFirst("script#season-data")?.data()
            if (!seasonDataScript.isNullOrBlank()) {
                try {
                    val root = JSONObject(seasonDataScript)
                    root.keys().forEach { seasonKey ->
                        val seasonArr = root.optJSONArray(seasonKey)
                        if (seasonArr != null) {
                            for (epIdx in 0 until seasonArr.length()) {
                                val epObj = seasonArr.getJSONObject(epIdx)
                                val epSlug = epObj.optString("slug")
                                val epNo = epObj.optInt("episode_no")
                                val sNo = epObj.optInt("s", 1)
                                val epHref = fixUrl("$mainUrl/$epSlug")
                                episodes.add(
                                    newEpisode(epHref) {
                                        this.name = "Episode $epNo"
                                        this.episode = epNo
                                        this.season = sNo
                                        this.posterUrl = poster
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            if (episodes.isEmpty()) {
                doc.select("div.episode-list > a, ul.episode-list > li a, div.serial-wrapper a").forEach { link ->
                    val epHref = fixUrlNull(link.attr("href")) ?: return@forEach
                    val epText = link.text().filter { it.isDigit() }
                    val epNo = epText.toIntOrNull() ?: 1
                    val season = Regex("""season-(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1
                    episodes.add(
                        newEpisode(epHref) {
                            this.name = "Episode $epNo"
                            this.episode = epNo
                            this.season = season
                            this.posterUrl = poster
                        }
                    )
                }
            }

            return newTvSeriesLoadResponse(title, properUrl, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (rating != null) addScore(rating, 10)
                addTrailer(trailer)
            }
        } else {
            return newMovieLoadResponse(title, properUrl, TvType.Movie, properUrl) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (rating != null) addScore(rating, 10)
                addTrailer(trailer)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = appGet(data).document
        val playerUrls = mutableListOf<String>()

        // 1. Collect player option links
        doc.select("ul#player-list > li a, select#player-select option, div.player-options a, ul#loadProviders > li a, ul.nav-tabs > li a, .player-list a, div#player-list a, div.server-list a, ul.server-list > li a").forEach { el ->
            val raw = el.attr("href").ifBlank { el.attr("data-url") }.ifBlank { el.attr("data-src") }.ifBlank { el.attr("value") }
            val fixed = fixUrlNull(raw)
            if (!fixed.isNullOrBlank() && !fixed.startsWith("javascript:") && !fixed.startsWith("#") && !fixed.startsWith("about:")) {
                playerUrls.add(fixed)
            }
        }

        // 2. Collect embedded iframes
        doc.select("div.embed-container iframe, iframe#main-player, div.embed iframe, .embed iframe, #player iframe, #content-embed iframe, iframe[src*='stream'], iframe[src*='api'], iframe[src*='embed'], iframe[src*='videonode'], iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            val fixed = fixUrlNull(src)
            if (!fixed.isNullOrBlank() && !fixed.startsWith("about:") && !fixed.startsWith("javascript:")) {
                playerUrls.add(fixed)
            }
        }

        // 3. Scan scripts for stream/embed URLs
        val scriptRegex = Regex("""(?:src|file|link)\s*[:=]\s*["'](https?://[^"']+)["']""")
        doc.select("script:not([src])").forEach { script ->
            scriptRegex.findAll(script.data()).forEach { match ->
                val found = match.groupValues[1]
                if (found.contains("embed") || found.contains("stream") || found.contains("player") ||
                    found.contains("api.php") || found.contains(".m3u8") || found.contains("videonode")) {
                    playerUrls.add(found)
                }
            }
        }

        val distinct = playerUrls.distinct()
        distinct.forEach { url ->
            try {
                resolveStream(url, data, subtitleCallback, callback)
            } catch (_: Exception) {}
        }

        return true
    }

    private suspend fun resolveStream(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = fixUrl(url)
        when {
            fixed.contains(".m3u8") -> {
                M3u8Helper.generateM3u8(name, fixed, referer).forEach(callback)
            }
            fixed.contains("videonode.de") -> {
                VideoNode().getUrl(fixed, referer, subtitleCallback, callback)
            }
            fixed.contains("hownetwork") -> {
                Hownetwork().getUrl(fixed, referer, subtitleCallback, callback)
            }
            fixed.contains("abyss") || fixed.contains("msmbot.club") || fixed.contains("hydrax") -> {
                LKAbyass().getUrl(fixed, referer, subtitleCallback, callback)
            }
            else -> {
                // If it's an embed page, check if it contains an inner iframe
                val innerIframe = try { getIframe(fixed) } catch (_: Exception) { null }
                if (!innerIframe.isNullOrBlank() && innerIframe != fixed) {
                    resolveStream(innerIframe, fixed, subtitleCallback, callback)
                } else {
                    loadExtractor(fixed, referer, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun getIframe(url: String): String {
        return try {
            val doc = appGet(url, referer = getBaseUrl(url)).document
            val iframe = doc.selectFirst("div.embed-container iframe, div.embed iframe, .embed iframe, #player iframe, #content-embed iframe, iframe[src*='stream'], iframe[src*='api'], iframe[src*='embed'], iframe")
            val src = iframe?.attr("src")?.ifBlank { iframe.attr("data-src") }
            if (!src.isNullOrBlank() && !src.startsWith("about:")) fixUrl(src) else ""
        } catch (_: Exception) {
            ""
        }
    }

    private suspend fun getProperLink(url: String): String {
        if (url.startsWith(seriesUrl)) return url
        return try {
            val res = appGet(url)
            val doc = res.document
            if (doc.select("title").text().contains("Nontondrama", ignoreCase = true)) {
                val openNow = doc.selectFirst("a#openNow")?.attr("href")
                if (!openNow.isNullOrBlank()) return fixUrl(openNow)
                val link = doc.selectFirst("div.links a")?.attr("href")
                if (!link.isNullOrBlank()) return fixUrl(link)
            }
            res.url
        } catch (_: Exception) {
            url
        }
    }

    private suspend fun appGet(url: String, referer: String? = null) = app.get(
        url,
        headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "en-US,en;q=0.9"
        ),
        referer = referer
    )

    private fun getBaseUrl(url: String?): String {
        if (url.isNullOrBlank()) return mainUrl
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
    }
}
