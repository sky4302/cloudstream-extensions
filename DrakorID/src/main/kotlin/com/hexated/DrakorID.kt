package com.hexated

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addScore
import com.lagradost.cloudstream3.utils.AppUtils
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.nio.charset.StandardCharsets

class DrakorID : MainAPI() {
    override var mainUrl = "https://drakorid.co"
    override var name = "DrakorID"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/list/%d" to "Terbaru",
        "$mainUrl/kategori/drama-korea/%d" to "Drama Korea",
        "$mainUrl/kategori/film-korea/%d" to "Film Korea",
        "$mainUrl/kategori/drama-china/%d" to "Drama China",
        "$mainUrl/kategori/drama-jepang/%d" to "Drama Jepang",
        "$mainUrl/kategori/drama-thailand/%d" to "Drama Thailand",
        "$mainUrl/kategori/variety-show/%d" to "Variety Show",
        "$mainUrl/kategori/romance/%d" to "Romance",
        "$mainUrl/kategori/action/%d" to "Action",
        "$mainUrl/kategori/comedy/%d" to "Comedy"
    )

    private fun fixPosterUrl(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val fixed = fixUrlNull(url) ?: return null

        val convertRegex = Regex("""/convert/([A-Za-z0-9_-]+)/\d+x\d+""")
        val match = convertRegex.find(fixed)
        if (match != null) {
            try {
                var rawToken = match.groupValues[1]
                val padding = when {
                    rawToken.endsWith("--") -> {
                        rawToken = rawToken.removeSuffix("--")
                        "=="
                    }
                    rawToken.endsWith("-") -> {
                        rawToken = rawToken.removeSuffix("-")
                        "="
                    }
                    else -> ""
                }
                var b64 = rawToken.replace('-', '+').replace('_', '/') + padding
                while (b64.length % 4 != 0) {
                    b64 += "="
                }
                val decoded = String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
                if ((decoded.startsWith("http://") || decoded.startsWith("https://")) &&
                    (decoded.endsWith(".jpg", ignoreCase = true) || decoded.endsWith(".jpeg", ignoreCase = true) ||
                     decoded.endsWith(".png", ignoreCase = true) || decoded.endsWith(".webp", ignoreCase = true))) {
                    return decoded
                }
            } catch (_: Exception) {}
            return fixed.replace(Regex("""/\d+x\d+/"""), "/800x800/")
        }

        if (fixed.contains("/180x200/")) {
            return fixed.replace("/180x200/", "/800x800/")
        }
        if (fixed.contains("/200x200/")) {
            return fixed.replace("/200x200/", "/800x800/")
        }
        return fixed
    }

    private fun cleanTitle(raw: String): String {
        return raw
            .replace(Regex("""\s*\(\d{4}\)"""), "")
            .replace(Regex("""(?i)\s*sub\s*indo.*"""), "")
            .replace(Regex("""(?i)\s*-\s*drakor.*"""), "")
            .replace(Regex("""\.\.$"""), "")
            .trim()
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val link = this.selectFirst("a.movie-list-card__media, h5.movie-list-card__title a, a[href*='/nonton/']")?.attr("href")
            ?: return null
        val properUrl = fixUrl(link)

        val rawTitle = this.selectFirst(".movie-list-card__title")?.attr("data-original-title")?.trim()
            ?.ifBlank { null }
            ?: this.selectFirst(".movie-list-card__img")?.attr("alt")?.trim()?.ifBlank { null }
            ?: this.selectFirst(".movie-list-card__title a, .movie-list-card__title, h5")?.text()?.trim()
            ?: return null

        val rawPoster = this.selectFirst(".movie-list-card__img, img")?.let { img ->
            img.attr("src").ifBlank { img.attr("data-src") }
        }
        val poster = fixPosterUrl(rawPoster)

        val yearMatch = Regex("""\((\d{4})\)""").find(rawTitle)
        val year = yearMatch?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = cleanTitle(rawTitle)

        val isMovie = properUrl.contains("/film-") || this.selectFirst(".movie-list-card__title")?.text()?.contains("Movie", ignoreCase = true) == true

        val tvType = if (isMovie) TvType.Movie else TvType.AsianDrama
        return if (isMovie) {
            newMovieSearchResponse(title, properUrl, tvType) {
                this.posterUrl = poster
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, properUrl, tvType) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (request.data.contains("%d")) request.data.format(page) else request.data
        val document = app.get(url, timeout = 25L, headers = mapOf("Referer" to "$mainUrl/")).document
        val items = document.select("article.movie-list-card, div.movie-list-card").mapNotNull {
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

    override suspend fun quickSearch(query: String): List<SearchResponse>? = search(query)

    override suspend fun search(query: String): List<SearchResponse>? {
        val url = "$mainUrl/cari.html?q=${query.trim().replace(" ", "+")}"
        val document = app.get(url, timeout = 25L, headers = mapOf("Referer" to "$mainUrl/")).document
        return document.select("article.movie-list-card, div.movie-list-card").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val properUrl = fixUrl(url)
        val document = app.get(properUrl, timeout = 25L, headers = mapOf("Referer" to "$mainUrl/")).document

        val rawTitle = document.selectFirst("meta[property='og:title']")?.attr("content")?.trim()
            ?: Regex("""var\s+mTitle\s*=\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1)
            ?: document.selectFirst("h1.movie-detail-title, .movie-detail-title, h1")?.text()?.trim()
            ?: "Unknown"

        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val title = cleanTitle(rawTitle)

        val rawPoster = document.selectFirst("meta[property='og:image']")?.attr("content")
            ?: document.selectFirst(".movie-detail-poster img, .movie-detail-poster, img")?.attr("src")
        val poster = fixPosterUrl(rawPoster)

        val description = document.selectFirst("meta[property='og:description']")?.attr("content")?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()
            ?: document.selectFirst(".movie-detail-desc, .movie-detail-synopsis, #synopsis, .synopsis")?.text()?.trim()

        val html = document.html()
        val mTipe = Regex("""var\s+mTipe\s*=\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull()
        val slug = Regex("""var\s+link\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)
            ?: properUrl.removeSuffix("/").substringAfterLast("/")

        val epElements = document.select("#modalPilihEpisode .episode-picker-grid button.episode-pick-item, button.episode-pick-item")
        val episodes = ArrayList<Episode>()

        if (epElements.isNotEmpty()) {
            for (btn in epElements) {
                val epNum = btn.attr("data-episode").toIntOrNull()
                    ?: Regex("""\d+""").find(btn.text())?.value?.toIntOrNull()
                    ?: continue
                val epLabel = btn.attr("data-ep-label").ifBlank { btn.text() }.trim()
                val epData = EpisodeData(slug = slug, episode = epNum).toJson()
                episodes.add(
                    newEpisode(epData) {
                        this.name = if (epLabel.isNotBlank()) epLabel else "Episode $epNum"
                        this.episode = epNum
                        this.posterUrl = poster
                    }
                )
            }
        }

        val isMovie = mTipe == 1 || properUrl.contains("/film-") || episodes.isEmpty()

        val recommendations = document.select(".related-movies article.movie-list-card, .sidebar article.movie-list-card, article.movie-list-card").mapNotNull {
            if (it.selectFirst("a")?.attr("href")?.contains(slug) == true) null
            else it.toSearchResult()
        }.distinctBy { it.url }

        val tags = document.select("a[href*='/kategori/'], .movie-detail-genres a, .genres a")
            .map { it.text().trim() }
            .filter { it.isNotBlank() && !it.matches(Regex("""\d{4}""")) }
            .distinct()

        val rating = document.selectFirst(".movie-detail-rating, .movie-list-card__rating")?.text()?.trim()?.toDoubleOrNull()

        return if (isMovie) {
            val movieData = EpisodeData(slug = slug, episode = 1).toJson()
            newMovieLoadResponse(title, properUrl, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                if (rating != null) addScore(rating.toString(), 10)
            }
        } else {
            newTvSeriesLoadResponse(title, properUrl, TvType.AsianDrama, episodes.sortedBy { it.episode }) {
                this.posterUrl = poster
                this.backgroundPosterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.recommendations = recommendations
                if (rating != null) addScore(rating.toString(), 10)
            }
        }
    }

    companion object {
        private var sessionCookies: MutableMap<String, String> = mutableMapOf()
    }

    private suspend fun refreshSession(): Map<String, String> {
        return try {
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
                "Referer" to "$mainUrl/"
            )
            val regPage = app.get("$mainUrl/register", headers = headers, timeout = 20L)
            val csrf = Regex("""name="csrf"\s+value="([^"]+)"""").find(regPage.text)?.groupValues?.get(1) ?: ""

            val newCookies = mutableMapOf<String, String>()
            newCookies.putAll(regPage.cookies)

            val randId = (100000..999999).random()
            val randEmail = "cs_guest_${System.currentTimeMillis()}_$randId@gmail.com"

            val regRes = app.post(
                "$mainUrl/register",
                data = mapOf(
                    "nama" to "User $randId",
                    "email" to randEmail,
                    "password" to "P@ss_$randId",
                    "csrf" to csrf
                ),
                cookies = newCookies,
                headers = mapOf(
                    "Referer" to "$mainUrl/register",
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                ),
                timeout = 20L
            )

            newCookies.putAll(regRes.cookies)
            sessionCookies = newCookies
            newCookies
        } catch (e: Exception) {
            Log.e(name, "Error refreshing session: ${e.message}")
            sessionCookies
        }
    }

    private suspend fun getSession(forceRefresh: Boolean = false): Map<String, String> {
        if (forceRefresh || sessionCookies.isEmpty()) {
            return refreshSession()
        }
        return sessionCookies
    }

    private suspend fun fetchPage(url: String, isRetry: Boolean = false): String {
        val cookies = getSession(forceRefresh = isRetry)
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
            "Referer" to "$mainUrl/"
        )
        return try {
            val res = app.get(url, headers = headers, cookies = cookies, timeout = 25L)
            val html = res.text
            val isBlocked = html.contains("belum login", ignoreCase = true) ||
                            html.contains("batas maksimal", ignoreCase = true) ||
                            html.contains("Opps, Ada Kesalahan", ignoreCase = true)
            if (isBlocked && !isRetry) {
                fetchPage(url, isRetry = true)
            } else {
                html
            }
        } catch (e: Exception) {
            if (!isRetry) fetchPage(url, isRetry = true) else ""
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (slug, episode) = try {
            val epData = AppUtils.parseJson<EpisodeData>(data)
            epData.slug to epData.episode
        } catch (_: Exception) {
            val s = if (data.contains("/")) data.removeSuffix("/").substringAfterLast("/") else data
            s to 1
        }

        var foundLinks = false

        val watchUrls = listOf(
            "$mainUrl/watch-lite/$slug/$episode",
            "$mainUrl/watch-fast/$slug/$episode",
            "$mainUrl/watch-max/$slug/$episode"
        )

        for (watchUrl in watchUrls) {
            try {
                val watchHtml = fetchPage(watchUrl)
                val iframeMatches = Regex("""<iframe\b[^>]*src=["']([^"']+)["']""", RegexOption.IGNORE_CASE).findAll(watchHtml)
                for (m in iframeMatches) {
                    val iframeSrc = m.groupValues[1]
                    val vParam = Regex("""[?&]v=([^&]+)""").find(iframeSrc)?.groupValues?.get(1) ?: continue
                    val decodedStream = try {
                        var b64 = vParam.replace('-', '+').replace('_', '/')
                        while (b64.length % 4 != 0) b64 += "="
                        String(Base64.decode(b64, Base64.DEFAULT), StandardCharsets.UTF_8).trim()
                    } catch (_: Exception) {
                        null
                    }

                    if (!decodedStream.isNullOrBlank() && (decodedStream.startsWith("http://") || decodedStream.startsWith("https://"))) {
                        val serverName = if (watchUrl.contains("fast")) "Fast" else if (watchUrl.contains("max")) "Max" else "Lite"
                        if (decodedStream.contains(".m3u8")) {
                            M3u8Helper.generateM3u8(
                                name,
                                decodedStream,
                                "$mainUrl/"
                            ).forEach { link ->
                                callback.invoke(link)
                                foundLinks = true
                            }
                        } else {
                            callback.invoke(
                                newExtractorLink(
                                    name,
                                    "$name HLS $serverName",
                                    decodedStream,
                                    INFER_TYPE
                                ) {
                                    this.referer = "$mainUrl/"
                                }
                            )
                            foundLinks = true
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(name, "Error extracting $watchUrl: ${e.message}")
            }
        }

        val dlUrls = listOf(
            "$mainUrl/download-lite/$slug/$episode",
            "$mainUrl/download-fast/$slug/$episode"
        )

        for (dlUrl in dlUrls) {
            try {
                val dlHtml = fetchPage(dlUrl)
                val dlRegex = Regex("""<a\b[^>]*href=["'](https?://[^"']+\.mp4[^"']*)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
                for (m in dlRegex.findAll(dlHtml)) {
                    val mp4Url = m.groupValues[1]
                    val label = m.groupValues[2].replace(Regex("""<[^>]+>"""), "").trim()
                    val quality = when {
                        label.contains("1080p", ignoreCase = true) || mp4Url.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
                        label.contains("720p", ignoreCase = true) || mp4Url.contains("720p", ignoreCase = true) -> Qualities.P720.value
                        label.contains("480p", ignoreCase = true) || mp4Url.contains("480p", ignoreCase = true) -> Qualities.P480.value
                        label.contains("360p", ignoreCase = true) || mp4Url.contains("360p", ignoreCase = true) -> Qualities.P360.value
                        else -> Qualities.Unknown.value
                    }
                    val qualityName = when {
                        label.contains("1080p", ignoreCase = true) || mp4Url.contains("1080p", ignoreCase = true) -> "1080p"
                        label.contains("720p", ignoreCase = true) || mp4Url.contains("720p", ignoreCase = true) -> "720p"
                        label.contains("480p", ignoreCase = true) || mp4Url.contains("480p", ignoreCase = true) -> "480p"
                        label.contains("360p", ignoreCase = true) || mp4Url.contains("360p", ignoreCase = true) -> "360p"
                        else -> "MP4"
                    }
                    val serverName = if (dlUrl.contains("fast")) "Fast" else "Lite"

                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name Direct $serverName $qualityName",
                            mp4Url,
                            INFER_TYPE
                        ) {
                            this.referer = "$mainUrl/"
                            this.quality = quality
                        }
                    )
                    foundLinks = true
                }
            } catch (e: Exception) {
                Log.e(name, "Error extracting $dlUrl: ${e.message}")
            }
        }

        return foundLinks
    }
}

data class EpisodeData(
    @JsonProperty("slug") val slug: String,
    @JsonProperty("episode") val episode: Int
)
