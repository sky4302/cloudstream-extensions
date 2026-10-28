package com.hexated

import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*

class PencuriMovie : MainAPI() {

    override var mainUrl = "https://ww44.pencurimovie.baby"
    override var name = "PencuriMovie"
    override val hasMainPage = true
    override var lang = "ms"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

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
        val path = if (page == 1) request.data else "${request.data}/page/$page"
        val document = app.get("$mainUrl/$path", timeout = 30L).document
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

    private fun Element.toSearchResult(): SearchResponse? {
        val a = this.selectFirst("a") ?: return null
        val title = a.attr("oldtitle").substringBefore("(").trim()
            .ifEmpty { this.selectFirst("h2")?.text()?.substringBefore("(")?.trim() }
            .orEmpty()
        if (title.isBlank()) return null
        val href = fixUrl(a.attr("href"))

        val img = this.selectFirst("img")
        val rawPoster = img?.getImageAttr()
        val posterUrl = fixUrlNull(rawPoster)

        val quality = this.selectFirst("span.mli-quality, div.jtip-quality")?.text()?.trim()?.replace("-", "").orEmpty()
        val epsCount = this.selectFirst("span.mli-eps i")?.text()?.trim()?.toIntOrNull()
        val isSeries = epsCount != null || selectFirst("span.mli-eps") != null || href.contains("/series/")

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
        return document.select("div.ml-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, timeout = 30L).document

        val title = document.selectFirst("div.mvic-desc h3")?.text()?.substringBefore("(")?.trim()
            ?: document.selectFirst("h1.entry-title")?.text()?.substringBefore("(")?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("(")?.trim()
            ?: "Unknown"
        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
        val description = document.selectFirst("div.desc p.f-desc, div.mvic-desc div.desc")?.text()?.trim()
        val isSeries = url.contains("/series/") || document.select("div.tvseason").isNotEmpty()
        val trailer = document.selectFirst("meta[itemprop=embedUrl]")?.attr("content")
        val genre = document.select("div.mvic-info p:contains(Genre) a").map { it.text().trim() }
        val rating = document.selectFirst("span.imdb-r[itemprop=ratingValue]")?.text()?.toDoubleOrNull()
        val duration = document.selectFirst("span[itemprop=duration]")?.text()?.replace(Regex("\\D"), "")?.toIntOrNull()
        val actors = document.select("div.mvic-info p:contains(Actors) a").map { it.text().trim() }
        val year = document.selectFirst("div.mvic-info p:contains(Release) a")?.text()?.trim()?.toIntOrNull()
        val recommendation = document.select("div.mlw-related div.ml-item").mapNotNull { it.toSearchResult() }

        return if (isSeries) {
            val episodes = mutableListOf<Episode>()
            document.select("div.tvseason").forEach { info ->
                val season = info.selectFirst("strong")?.text()?.substringAfter("Season")?.trim()?.toIntOrNull() ?: 1
                info.select("div.les-content a").forEach { epTag ->
                    val epName = epTag.text().substringAfter("-").trim()
                    val href = fixUrl(epTag.attr("href"))
                    val rawEp = epTag.text().substringAfter("Episode").substringBefore("-").trim().toIntOrNull()
                    episodes.add(
                        newEpisode(href) {
                            this.episode = rawEp
                            this.name = epName.ifBlank { "Episode $rawEp" }
                            this.season = season
                            this.posterUrl = poster
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                if (!trailer.isNullOrBlank()) addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
                this.duration = duration ?: 0
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genre
                this.year = year
                if (!trailer.isNullOrBlank()) addTrailer(trailer)
                addActors(actors)
                this.recommendations = recommendation
                this.duration = duration ?: 0
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
        document.select("div.movieplay iframe, div.embed-container iframe").forEach {
            val href = it.attr("data-src").ifBlank { it.attr("src") }
            if (href.isNotBlank() && !href.contains("youtube.com", true) && !href.contains("youtu.be", true) && !href.startsWith("about:")) {
                val finalUrl = followRedirect(if (href.startsWith("//")) "https:$href" else href)
                loadExtractor(finalUrl, data, subtitleCallback, callback)
            }
        }
        return true
    }

    private suspend fun followRedirect(url: String): String {
        return try {
            val resp = app.get(url, allowRedirects = false)
            val loc = resp.headers["Location"]
            if (!loc.isNullOrBlank()) {
                return if (loc.startsWith("http")) loc else url + loc
            }
            val doc = resp.document
            val meta = doc.selectFirst("meta[http-equiv~=(?i)refresh]")?.attr("content")
            if (!meta.isNullOrBlank()) {
                val u = meta.substringAfter("URL=", "").trim()
                if (u.isNotEmpty()) return if (u.startsWith("http")) u else url + u
            }
            url
        } catch (_: Exception) {
            url
        }
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
