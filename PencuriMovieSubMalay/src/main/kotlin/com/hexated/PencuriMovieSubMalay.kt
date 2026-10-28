package com.hexated

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class PencuriMovieSubMalay : MainAPI() {

    override var mainUrl = "https://pencurimoviesubmalay26.site"
    override var name = "PencuriMovie Sub Malay"
    override val hasMainPage = true
    override var lang = "ms"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "tvshows" to "TV Shows",
        "group_movie/malaysub" to "Malay Subtitle",
        "group_movie/malaydub" to "Malay Dubbed",
        "group_movie/malaysia" to "Malaysia Movies",
        "group_movie/indonesia" to "Indonesia Movies",
        "group_movie/china" to "China Movies",
        "group_movie/south-korea" to "Korea Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) "${request.data}/" else "${request.data}/page/$page/"
        val document = app.get("$mainUrl/$path", timeout = 30L).document
        val home = document.select("div.display-item, div.module-item").mapNotNull { it.toSearchResult() }

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
        val title = a.attr("title").substringBefore("(").trim()
            .ifEmpty { this.selectFirst("div.item-desc-title h3")?.text()?.substringBefore("(")?.trim() }
            .orEmpty()
        if (title.isBlank()) return null

        val img = this.selectFirst("img")
        val rawPoster = img?.getImageAttr()
        val posterUrl = fixUrlNull(rawPoster)

        val quality = this.selectFirst("span.item-quality")?.text()?.trim()?.replace("?", "")?.trim().orEmpty()
        val isSeries = href.contains("/tvshows/") || a.attr("data-ptype") == "tvshows" || this.attr("data-ptype") == "tvshows"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 30L).document
        return document.select("div.display-item, div.module-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document

        val title = document.selectFirst("h1.entry-title, h1.movie-title, h1")?.text()?.substringBefore("(")?.trim()
            ?: document.selectFirst("div.item-desc-title h3, div.data h1")?.text()?.substringBefore("(")?.trim()
            ?: document.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore("(")?.trim()
            ?: document.selectFirst("title")?.text()?.substringBefore("(")?.substringBefore("-")?.trim()
            ?: "Unknown"

        val poster = document.selectFirst("div.splash-bg img")?.attr("src")
            ?: document.selectFirst("img.thumb")?.getImageAttr()
            ?: document.selectFirst("meta[property='og:image']")?.attr("content")

        val description = document.selectFirst("div.desc p, div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")

        val year = Regex("""\b(19\d{2}|20\d{2})\b""").find(url + " " + title)?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?: document.selectFirst(".release, .item-desc-hl")?.text()?.let {
                Regex("""\b(19\d{2}|20\d{2})\b""").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
            }

        val rating = document.selectFirst(".data-imdb, .item-desc-hl")?.text()?.let {
            Regex("""(\d+(?:\.\d+)?)""").find(it)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        }

        val genres = document.select("div.genres a, div.tagline a, a[href*='/genre/']").map { it.text().trim() }
            .filter { it.isNotBlank() }

        val isSeries = url.contains("/tvshows/") || document.select(".seasons-list, .episodes-list").isNotEmpty()

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            val seasonElements = document.select("ul.seasons-list li a")

            if (seasonElements.isNotEmpty()) {
                seasonElements.forEach { seasonTag ->
                    val seasonNum = seasonTag.attr("data-snum").toIntOrNull() ?: 1
                    document.select("ul#season-listep-$seasonNum li a").forEach { link ->
                        val epHref = fixUrl(link?.attr("href").orEmpty())
                        if (epHref.isBlank()) return@forEach

                        val epNum = link?.selectFirst(".ep-num")?.text()?.trim()?.toIntOrNull()
                            ?: Regex("""ep(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()

                        val epName = link?.selectFirst(".ep-title")?.text()?.trim()
                            ?: "Episode ${epNum ?: ""}".trim()

                        val epThumb = link?.selectFirst(".ep-thumb img")?.attr("src")

                        episodes.add(
                            newEpisode(epHref) {
                                this.name = epName
                                this.season = seasonNum
                                this.episode = epNum
                                this.posterUrl = epThumb ?: poster
                            }
                        )
                    }
                }
            } else {
                document.select("ul.episodes-list li a").forEach { link ->
                    val epHref = fixUrl(link.attr("href"))
                    if (epHref.isBlank()) return@forEach

                    val epNum = link.selectFirst(".ep-num")?.text()?.trim()?.toIntOrNull()
                        ?: Regex("""ep(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    val epName = link.selectFirst(".ep-title")?.text()?.trim()
                        ?: "Episode ${epNum ?: ""}".trim()

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epName
                            this.episode = epNum
                            this.posterUrl = poster
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genres
                this.year = year
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document

        val playerOptions = document.select("#playeroptionsul li.zetaflix_player_option")
        playerOptions.forEach { opt ->
            val post = opt.attr("data-post")
            val nume = opt.attr("data-nume")
            val type = opt.attr("data-type")
            if (post.isNotBlank() && nume.isNotBlank() && type.isNotBlank() && nume != "fake") {
                try {
                    val ajaxResp = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        headers = mapOf(
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to data,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                        ),
                        data = mapOf(
                            "action" to "zeta_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        )
                    ).text
                    if (ajaxResp.isNotBlank()) {
                        val json = JSONObject(ajaxResp)
                        val embedHtml = json.optString("embed_url", "")
                        val iframeSrc = Regex("""src=["']([^"']+)["']""").find(embedHtml)?.groupValues?.getOrNull(1)
                            ?: if (embedHtml.startsWith("http")) embedHtml else null

                        if (!iframeSrc.isNullOrBlank()) {
                            val cleanSrc = iframeSrc.replace("\\/", "/")
                            val fixedSrc = if (cleanSrc.startsWith("//")) "https:$cleanSrc" else cleanSrc
                            if (fixedSrc.contains("abyss") || fixedSrc.contains("msmbot.club") || fixedSrc.contains("short.icu")) {
                                AbyssExtractor().getUrl(fixedSrc, data, subtitleCallback, callback)
                            }
                            loadExtractor(fixedSrc, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Direct iframes fallback
        document.select("iframe.metaframe, div.pframe iframe, iframe#trailer-view, .movieplay iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("youtube.com", true) && !src.contains("youtu.be", true) && !src.startsWith("about:")) {
                val fixed = if (src.startsWith("//")) "https:$src" else src
                if (fixed.contains("abyss") || fixed.contains("msmbot.club") || fixed.contains("short.icu")) {
                    AbyssExtractor().getUrl(fixed, data, subtitleCallback, callback)
                }
                loadExtractor(fixed, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-original") -> this.attr("abs:data-original")
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
