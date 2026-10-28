package com.hexated

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class PencuriMovie : MainAPI() {
    override var mainUrl = "https://ww44.pencurimovie.baby"
    override var name = "PencuriMovie"
    override val hasMainPage = true
    override var lang = "ms"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "movies" to "Latest Movies",
        "series" to "TV Series",
        "most-rating" to "Most Rating Movies",
        "top-imdb" to "Top IMDB Movies",
        "country/malaysia" to "Malaysia Movies",
        "country/indonesia" to "Indonesia Movies",
        "country/india" to "India Movies",
        "country/japan" to "Japan Movies",
        "country/thailand" to "Thailand Movies",
        "country/china" to "China Movies",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("$mainUrl/${request.data}/page/$page", timeout = 50L).document
        val home = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse {
        val a = this.selectFirst("a")
        val title = (a?.attr("oldtitle")?.takeIf { it.isNotBlank() } ?: a?.attr("title") ?: this.text()).substringBefore("(")
        val href = fixUrl(a?.attr("href").orEmpty())
        val img = a?.selectFirst("img") ?: this.selectFirst("img")
        val rawPoster = img?.attr("data-original")?.ifBlank { null }
            ?: img?.attr("data-src")?.ifBlank { null }
            ?: img?.attr("src")?.ifBlank { null }
        val posterUrl = fixUrlNull(rawPoster)
        val quality = getQualityFromString(this.select("span.mli-quality").text())
        val isSeries = href.contains("/series/") || href.contains("/tv/")
        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query", timeout = 50L).document
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 50L).document
        val title = document.selectFirst("div.mvic-desc h3")?.text()?.trim().orEmpty().substringBefore("(")
        val poster = fixUrlNull(
            document.selectFirst("div.mvic-thumb img")?.let { img ->
                img.attr("data-original").ifBlank { null }
                    ?: img.attr("data-src").ifBlank { null }
                    ?: img.attr("src").ifBlank { null }
            } ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val description = document.selectFirst("div.desc p.f-desc")?.text()?.trim()
        val isSeries = url.contains("series")
        val trailer = document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
        val genre = document.select("div.mvic-info p:contains(Genre) a").map { it.text().trim() }
        val actors = document.select("div.mvic-info p:contains(Actors) a").map { it.text().trim() }
        val year = document.select("div.mvic-info p:contains(Release) a").text().toIntOrNull()
        val recommendation = document.select("div.ml-item").mapNotNull { it.toSearchResult() }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tvseason").amap { info ->
                val season = info.selectFirst("strong")?.text()?.substringAfter("Season")?.trim()?.toIntOrNull()
                info.select("div.les-content a").forEach { link ->
                    val epName = link.text().substringAfter("-").trim()
                    val href = link.attr("href")
                    val rawEpisode = link.text().substringAfter("Episode").substringBefore("-").trim().toIntOrNull()
                    episodes.add(
                        newEpisode(href) {
                            this.episode = rawEpisode
                            this.name = epName
                            this.season = season
                            this.posterUrl = poster
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
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
        document.select("div.movieplay iframe, div.playcontent iframe").forEach {
            val href = it.attr("data-src").ifBlank { it.attr("src") }
            if (href.isNotBlank() && !href.contains("youtube.com", true) && !href.contains("youtu.be", true) && !href.startsWith("about:")) {
                loadExtractor(href, subtitleCallback, callback)
            }
        }
        return true
    }
}
