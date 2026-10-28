package com.hexated

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale

class PencuriMovieSubMalay : MainAPI() {
    override var mainUrl = "https://pencurimoviesubmalay26.site"
    override var name = "PencuriMovie Sub Malay"
    override val hasMainPage = true
    override var lang = "ms"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "trending" to "Trending",
        "movies" to "Latest Movies",
        "tvshows" to "TV Shows",
        "group_movie/malaysia" to "Malaysia Movies",
        "group_movie/indonesia" to "Indonesia Movies",
        "group_movie/malaysub" to "Malay Subtitle",
        "group_movie/malaydub" to "Malay Dubbed",
        "group_movie/south-korea" to "Korea Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val path = if (page == 1) "${request.data}/" else "${request.data}/page/$page/"
        val document = app.get("$mainUrl/$path", timeout = 30L).document
        val items = document.select("div.item-box, div.display-item, div.module-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

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
        val a = this.selectFirst("a[href]") ?: return null
        val href = fixUrl(a.attr("href"))
        val rawTitle = (a.attr("title").ifBlank { this.selectFirst("h3")?.text() } ?: "").substringBefore("(")
            .trim()
        val title = if (rawTitle.isNotBlank()) rawTitle else return null
        val img = this.selectFirst("img") ?: a.selectFirst("img")
        val rawPoster = img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }
        val posterUrl = fixUrlNull(rawPoster)
        val quality = this.selectFirst("span.item-quality, span.mli-quality")?.text()?.trim().orEmpty()
        val isSeries = href.contains("/tvshows/") || a.attr("data-ptype") == "tvshows" || this.attr("data-ptype") == "tvshows"

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (quality.isNotBlank()) addQuality(quality)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search/$query/"
        val document = try {
            app.get(searchUrl, timeout = 30L).document
        } catch (_: Exception) {
            app.get("$mainUrl/?s=$query", timeout = 30L).document
        }
        return document.select("div.item-box, div.display-item, div.module-item").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document
        val isSeries = url.contains("/tvshows/") || document.select(".seasons-list, .episodes-list, div.content-episodes").isNotEmpty()

        val rawTitle = document.selectFirst("div.details-title h3, h1.entry-title, h1.movie-title, h1")?.text()?.trim().orEmpty()
        val title = if (isSeries) rawTitle else rawTitle.substringBeforeLast("(").trim().ifBlank { rawTitle }

        val poster = fixUrlNull(
            document.selectFirst("div.content-poster img, div.splash-bg img")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val bgposter = fixUrlNull(document.selectFirst("meta[property='og:image']")?.attr("content")?.replace("w780", "original"))

        val description = document.selectFirst("div.details-desc p, div.desc p, div.entry-content p")?.text()?.trim()
            ?: document.selectFirst("meta[property='og:description']")?.attr("content")
        val tags = document.select("div.details-genre a, div.genres a, a[href*='/genre/']").map { it.text().trim() }.filter { it.isNotBlank() }
        val actors = document.select("div.details-info p:contains(Stars) a, div.cast a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = extractYear(document.selectFirst("div.details-info p:contains(Year), .release")?.text() ?: rawTitle)
        val rating = Regex("""(\d+(\.\d+)?)""").find(document.selectFirst("span.data-imdb, .data-imdb")?.text().orEmpty())
            ?.groupValues?.firstOrNull()?.toDoubleOrNull()
        val trailerId = document.selectFirst("span.data-trailer[data-tid], a.btn-trailer[data-tid]")?.attr("data-tid")?.trim()
        val trailerUrl = trailerId?.takeIf { it.isNotBlank() }?.let { "https://www.youtube.com/watch?v=$it" }
            ?: document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")

        val recommendations = document.select("div.item-box, div.module-item, div.display-item")
            .mapNotNull { it.toSearchResult() }
            .filter { it.url != url }
            .distinctBy { it.url }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()

            // Try Phisher episode list selector first
            val episodeElements = document.select("div.content-episodes ul.episodes-list li")
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { li ->
                    li.toEpisode()?.let { episodes.add(it) }
                }
            } else {
                // Season list fallback
                val seasonElements = document.select("ul.seasons-list li a")
                if (seasonElements.isNotEmpty()) {
                    seasonElements.forEach { seasonTag ->
                        val seasonNum = seasonTag.attr("data-snum").toIntOrNull() ?: 1
                        document.select("ul#season-listep-$seasonNum li a").forEach { link ->
                            val epHref = fixUrl(link.attr("href"))
                            if (epHref.isBlank()) return@forEach
                            val epNum = link.selectFirst(".ep-num")?.text()?.trim()?.toIntOrNull()
                                ?: Regex("""ep(\d+)""").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()
                            val epName = link.selectFirst(".ep-title")?.text()?.trim() ?: "Episode ${epNum ?: ""}".trim()
                            episodes.add(
                                newEpisode(epHref) {
                                    this.name = epName
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = poster
                                }
                            )
                        }
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = bgposter
                this.plot = description
                this.tags = tags
                this.year = year
                this.recommendations = recommendations
                if (rating != null) addScore(rating.toString(), 10)
                addActors(actors)
                addTrailer(trailerUrl)
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

        // Check playeroptionsul (both direct li and zetaflix_player_option)
        document.select("#playeroptionsul > li, #playeroptionsul li").forEach { li ->
            val post = li.attr("data-post").trim()
            val nume = li.attr("data-nume").trim()
            val type = li.attr("data-type").trim()
            if (post.isNotBlank() && nume.isNotBlank() && type.isNotBlank() && nume != "fake") {
                try {
                    val rawText = app.post(
                        "$mainUrl/wp-admin/admin-ajax.php",
                        data = mapOf(
                            "action" to "zeta_player_ajax",
                            "post" to post,
                            "nume" to nume,
                            "type" to type
                        ),
                        referer = data,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    ).text

                    if (rawText.isNotBlank()) {
                        val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(rawText)?.groupValues?.get(1)
                            ?: Regex(""""embed_url"\s*:\s*"([^"]+)"""").find(rawText)?.groupValues?.get(1)?.replace("""\/""", "/")
                            ?: Jsoup.parse(rawText).selectFirst("iframe")?.attr("src")
                            ?: if (rawText.startsWith("http")) rawText else null

                        if (!iframeSrc.isNullOrBlank() && !iframeSrc.startsWith("about:")) {
                            resolveStreamLink(iframeSrc, data, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Direct iframes fallback
        document.select("iframe.metaframe, div.pframe iframe, .movieplay iframe, .playcontent iframe").forEach { iframe ->
            val src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank() && !src.contains("youtube.com", true) && !src.contains("youtu.be", true) && !src.startsWith("about:")) {
                resolveStreamLink(src, data, subtitleCallback, callback)
            }
        }

        return true
    }

    private suspend fun resolveStreamLink(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fixed = if (url.startsWith("//")) "https:$url" else url
        if (fixed.contains("/#")) {
            VidStack().getUrl(fixed, "", subtitleCallback, callback)
        } else if (fixed.contains("abyss") || fixed.contains("msmbot.club") || fixed.contains("short.icu")) {
            AbyssExtractor().getUrl(fixed, referer, subtitleCallback, callback)
            loadExtractor(fixed, referer, subtitleCallback, callback)
        } else {
            loadExtractor(fixed, referer, subtitleCallback, callback)
        }
    }

    private fun Element.toEpisode(): Episode? {
        val anchor = selectFirst("a[href]") ?: return null
        val href = fixUrl(anchor.attr("href"))
        val epNum = selectFirst("span.ep-num")?.text()?.trim()?.toIntOrNull()
        val title = selectFirst("span.ep-title")?.text()?.trim()
        val poster = selectFirst("span.ep-thumb img")?.attr("src")?.let { fixUrlNull(it) }
        val rawDate = selectFirst("span.ep-date")?.text()
        val date = rawDate?.replace(".", "")?.let {
            runCatching {
                SimpleDateFormat("MMM dd, yyyy", Locale.ENGLISH).parse(it)
            }.getOrNull()
        }
        val classSeason = classNames()
            .firstNotNullOfOrNull { Regex("""ep-(\d+)-\d+""").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
        val parentSeason = parent()?.id()?.substringAfter("season-listep-", "")?.toIntOrNull()
        val season = classSeason ?: parentSeason

        return newEpisode(href) {
            this.name = title
            this.posterUrl = poster
            this.episode = epNum
            this.season = season
            addDate(date)
        }
    }

    private fun extractYear(text: String?): Int? {
        return Regex("""(19|20)\d{2}""").find(text.orEmpty())?.value?.toIntOrNull()
    }

    private data class ZetaPlayerResponse(
        @param:JsonProperty("embed_url") val embedUrl: String? = null,
        @param:JsonProperty("type") val type: String? = null,
        @param:JsonProperty("msg") val msg: String? = null
    )
}
