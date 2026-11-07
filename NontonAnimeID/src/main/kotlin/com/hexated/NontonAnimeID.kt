package com.hexated

import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import com.lagradost.nicehttp.Session
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLEncoder

class NontonAnimeID : MainAPI() {
    override var mainUrl = NontonAnimeIDSettings.DEFAULT_URL
    override var name = "NontonAnimeID"
    override val hasMainPage = true
    override var lang = "id"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val TAG = "NontonAnimeID"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/133.0.0.0 Safari/537.36"

        fun getType(t: String): TvType {
            return when {
                t.contains("TV", true) -> TvType.Anime
                t.contains("Movie", true) -> TvType.AnimeMovie
                else -> TvType.OVA
            }
        }

        fun getStatus(t: String): ShowStatus {
            return when {
                t.contains("Finished", true) || t.contains("Completed", true) -> ShowStatus.Completed
                t.contains("Currently", true) || t.contains("Ongoing", true) -> ShowStatus.Ongoing
                else -> ShowStatus.Completed
            }
        }
    }

    private val fetch: Session by lazy {
        Session(
            app.baseClient.newBuilder()
                .dns(CloudflareDoHDns())
                .build()
        )
    }

    data class LiveSearchItem(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("slug") val slug: String? = null,
        @JsonProperty("poster") val poster: String? = null,
        @JsonProperty("rating") val rating: String? = null,
        @JsonProperty("release_year") val releaseYear: Int? = null,
        @JsonProperty("episodes") val episodes: Int? = null,
        @JsonProperty("type") val type: String? = null
    )

    data class LiveSearchResponse(
        @JsonProperty("data") val data: List<LiveSearchItem>? = null
    )

    data class EpisodeData(
        @JsonProperty("id") val id: Int? = null,
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("video_url") val videoUrl: String? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("download_url1") val downloadUrl1: String? = null,
        @JsonProperty("download_url2") val downloadUrl2: String? = null,
        @JsonProperty("download_url3") val downloadUrl3: String? = null
    )

    override val mainPage = mainPageOf(
        "" to "Latest Releases",
        "popular" to "Popular Anime",
        "movies" to "Anime Movie",
    )

    private fun updateMainUrl(url: String) {
        try {
            val uri = URI(url)
            val newBase = "${uri.scheme}://${uri.host}"
            if (newBase.isNotBlank() && !newBase.contains("localhost")) {
                mainUrl = newBase
            }
        } catch (_: Throwable) {}
    }

    private suspend fun requestDoc(url: String, referer: String? = null): Document {
        return try {
            val headers = mutableMapOf("User-Agent" to USER_AGENT)
            if (referer != null) headers["Referer"] = referer
            val res: NiceResponse = fetch.get(url, headers = headers)
            updateMainUrl(res.url)
            res.document
        } catch (e: Throwable) {
            Log.w(TAG, "fetch.get failed for $url: ${e.message}, trying safeGet fallback")
            val res = NontonAnimeIDCloudflare.safeGet(url, referer = referer)
            updateMainUrl(res.url)
            res.document
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val pageUrl = if (page <= 1) {
                if (request.data.isEmpty()) "$mainUrl/" else "$mainUrl/${request.data}/"
            } else {
                if (request.data.isEmpty()) "$mainUrl/page/$page/" else "$mainUrl/${request.data}/page/$page/"
            }

            val document = requestDoc(pageUrl, referer = "$mainUrl/")

            // Modern nontonanime.id card format
            var home = document.select("a.anime-card-v1, .anime-card-v1").mapNotNull { card ->
                val a = if (card.tagName() == "a") card else card.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = card.selectFirst(".anime-card-title-v1")?.text()?.trim()
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: card.selectFirst("h3, h2")?.text()?.trim()
                    ?: return@mapNotNull null

                val posterUrl = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("img")?.attr("data-src")

                val badgeText = card.selectFirst(".anime-card-badgebar-v1 span:last-child")?.text()?.trim()
                val epNum = badgeText?.substringBefore("/")?.filter { it.isDigit() }?.toIntOrNull()

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = false, subExist = true, epNum)
                }
            }.distinctBy { it.url }

            // Fallback for mirror/legacy HTML format
            if (home.isEmpty()) {
                home = document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li").mapNotNull {
                    it.toLegacySearchResult()
                }.distinctBy { it.url }
            }

            val hasNext = home.isNotEmpty() && document.selectFirst(".pagination .next, a.next, .pagination-arrow.-right, a:contains(Next)") != null

            newHomePageResponse(request.name, home, hasNext = hasNext)
        } catch (e: Throwable) {
            Log.w(TAG, "getMainPage error for ${request.name}: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toLegacySearchResult(): AnimeSearchResponse? {
        val a = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(a.attr("href")) ?: return null

        val title = this.selectFirst("h3.title span, .title span, .as-anime-title, h2, h3")?.text()?.trim()
            ?: this.selectFirst("img")?.attr("alt")?.trim()
            ?: a.attr("title").trim()

        if (title.isBlank()) return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.getImageAttr())

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addDubStatus(dubExist = false, subExist = true)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // Try live-search JSON API first
        try {
            val searchUrl = "$mainUrl/live-search?q=${URLEncoder.encode(query, "UTF-8")}"
            val res = fetch.get(
                searchUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "application/json",
                    "Referer" to "$mainUrl/"
                )
            )

            val json = tryParseJson<LiveSearchResponse>(res.text)
            val items = json?.data
            if (!items.isNullOrEmpty()) {
                return items.mapNotNull { item ->
                    val title = item.title ?: return@mapNotNull null
                    val slug = item.slug ?: return@mapNotNull null
                    val href = "$mainUrl/anime/$slug"
                    val posterUrl = item.poster

                    newAnimeSearchResponse(title, href, TvType.Anime) {
                        this.posterUrl = posterUrl
                        addDubStatus(dubExist = false, subExist = true, item.episodes)
                    }
                }.distinctBy { it.url }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "live-search failed for $query: ${e.message}, trying HTML search")
        }

        // Fallback to HTML search
        return try {
            val link = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
            val document = requestDoc(link, referer = "$mainUrl/")

            val modernCards = document.select("a.anime-card-v1, .anime-card-v1").mapNotNull { card ->
                val a = if (card.tagName() == "a") card else card.selectFirst("a") ?: return@mapNotNull null
                val href = fixUrlNull(a.attr("href")) ?: return@mapNotNull null
                val title = card.selectFirst(".anime-card-title-v1")?.text()?.trim()
                    ?: a.attr("title").trim().takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                val posterUrl = card.selectFirst("img")?.attr("src")?.takeIf { it.isNotBlank() }
                    ?: card.selectFirst("img")?.attr("data-src")

                newAnimeSearchResponse(title, href, TvType.Anime) {
                    this.posterUrl = posterUrl
                    addDubStatus(dubExist = false, subExist = true)
                }
            }

            if (modernCards.isNotEmpty()) {
                modernCards.distinctBy { it.url }
            } else {
                document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li").mapNotNull {
                    it.toLegacySearchResult()
                }.distinctBy { it.url }
            }
        } catch (e: Throwable) {
            Log.e(TAG, "search error for $query: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = requestDoc(url, referer = "$mainUrl/")

            val rawTitle = document.selectFirst("h1.hero-title, h1, h1.entry-title.cs, h1.entry-title")?.text()?.trim()
                ?: return null

            val title = rawTitle
                .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Sub\\s+Indo$", RegexOption.IGNORE_CASE), "")
                .trim()

            val poster = document.selectFirst("img.yore-season-cover")?.attr("src")
                ?: document.selectFirst(".anime-card img, .hero-poster img, .poster > img, .thumb > img")?.let {
                    it.attr("data-src").ifBlank { it.attr("src") }
                }

            val tags = document.select(".hero-genres a, .genre-tag, .tagline a, .genxed a, .anime-card__genres .genre-tag")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }

            val year = Regex("(19|20)\\d{2}").find(
                document.select(".hero-meta, .details-list li:contains(Aired), .info-item.season, .spe span:contains(Rilis)").text()
            )?.groupValues?.getOrNull(0)?.toIntOrNull()

            val statusText = document.select(".hero-meta, .details-list li:contains(Status), .spe span:contains(Status), .info-item.status").text()
            val status = getStatus(statusText)

            val typeText = document.select(".hero-meta, .details-list li:contains(Type), .spe span:contains(Type), .info-item.type").text()
            val tvType = getType(typeText)

            val description = document.selectFirst("div.hero-description, p.hero-short-description, .entry-content[itemprop=description] p, .sinopsis, .entry-content p")
                ?.text()?.trim()

            val episodes = mutableListOf<Episode>()

            // 1. Try modern JSON allEpisodes script
            val scriptHtml = document.html()
            val jsonMatch = Regex("const allEpisodes\\s*=\\s*(\\[.*?\\]);").find(scriptHtml)?.groupValues?.getOrNull(1)
            if (!jsonMatch.isNullOrBlank()) {
                val parsedEpisodes = tryParseJson<List<EpisodeData>>(jsonMatch)
                parsedEpisodes?.forEach { ep ->
                    val dataUrl = ep.videoUrl?.takeIf { it.isNotBlank() }
                        ?: ep.downloadUrl3?.takeIf { it.isNotBlank() }
                        ?: ep.downloadUrl2?.takeIf { it.isNotBlank() }
                        ?: ep.downloadUrl1?.takeIf { it.isNotBlank() }
                        ?: return@forEach

                    val epName = ep.title?.takeIf { it.isNotBlank() } ?: "Episode ${ep.episodeNumber ?: ""}"
                    episodes.add(
                        newEpisode(dataUrl) {
                            this.name = epName
                            this.episode = ep.episodeNumber
                            this.posterUrl = ep.thumbnail
                        }
                    )
                }
            }

            // 2. Fallback to HTML episode list if script was absent or empty
            if (episodes.isEmpty()) {
                document.select(".maintable tr, ul.episodelst li, .episodelst li, .single-episode, .episodelist li, a.episode-card").forEach { epElement ->
                    val a = if (epElement.tagName() == "a") epElement else epElement.selectFirst("a") ?: return@forEach
                    val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                    val epTitle = a.text().trim().ifBlank { epElement.text().trim() }
                    val epNum = Regex("(?i)(?:episode|ep)[- ]*(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
                        ?: Regex("episode-(\\d+)").find(epHref)?.groupValues?.getOrNull(1)?.toIntOrNull()

                    episodes.add(
                        newEpisode(epHref) {
                            this.name = epTitle
                            this.episode = epNum
                        }
                    )
                }
            }

            val finalEpisodes = episodes.distinctBy { it.data }

            newAnimeLoadResponse(title, url, tvType) {
                this.posterUrl = poster
                this.posterHeaders = mapOf("Referer" to "$mainUrl/")
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
                addEpisodes(DubStatus.Subbed, finalEpisodes)
            }
        } catch (e: Throwable) {
            Log.e(TAG, "Error in load for $url: ${e.message}")
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            // Case 1: Base64-encoded stream URL (canonical /stream/v2/ endpoint)
            if (data.contains("/stream/v2/")) {
                var decoded = decodeStreamUrl(data)
                // In some cases, the decoded payload contains another /stream/v2/ wrapper
                if (decoded.contains("/stream/v2/")) {
                    decoded = decodeStreamUrl(decoded)
                }

                val target = if (decoded.startsWith("http://") || decoded.startsWith("https://")) decoded else data

                if (target.contains(".m3u8")) {
                    M3u8Helper.generateM3u8(
                        source = name,
                        streamUrl = target,
                        referer = "$mainUrl/",
                        headers = mapOf("User-Agent" to USER_AGENT)
                    ).forEach(callback)
                    return true
                } else if (target.contains(".mp4")) {
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - Direct MP4",
                            url = target,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                    return true
                } else {
                    return loadExtractor(target, "$mainUrl/", subtitleCallback, callback)
                }
            }

            // Case 2: Direct media link
            if (data.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    source = name,
                    streamUrl = data,
                    referer = "$mainUrl/",
                    headers = mapOf("User-Agent" to USER_AGENT)
                ).forEach(callback)
                return true
            }

            // Case 3: Episode web page containing iframes or video players
            val document = requestDoc(data, referer = "$mainUrl/")
            val iframeLinks = linkedSetOf<String>()

            fun normalizeUrl(value: String?): String? {
                val raw = value?.trim()?.replace("\\/", "/")?.replace("&amp;", "&")?.takeIf { it.isNotBlank() } ?: return null
                return when {
                    raw.startsWith("about:") -> null
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> fixUrl(raw)
                }
            }

            document.select("iframe").forEach { iframe ->
                normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })?.let { iframeLinks.add(it) }
            }

            for (link in iframeLinks) {
                if (link.contains("/stream/v2/")) {
                    val decoded = decodeStreamUrl(link)
                    if (decoded.contains(".m3u8")) {
                        M3u8Helper.generateM3u8(
                            source = name,
                            streamUrl = decoded,
                            referer = "$mainUrl/",
                            headers = mapOf("User-Agent" to USER_AGENT)
                        ).forEach(callback)
                    } else {
                        loadExtractor(decoded, "$mainUrl/", subtitleCallback, callback)
                    }
                } else {
                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                }
            }

            for (a in document.select(".listlink a, .download a")) {
                val href = normalizeUrl(a.attr("href"))
                if (href != null && !href.contains("javascript", true)) {
                    try {
                        loadExtractor(href, "$mainUrl/", subtitleCallback, callback)
                    } catch (_: Throwable) {}
                }
            }

            true
        } catch (e: Throwable) {
            Log.e(TAG, "loadLinks error: ${e.message}")
            false
        }
    }

    private fun decodeStreamUrl(url: String): String {
        return try {
            val base64 = url.substringAfter("/stream/v2/").trimEnd('/')
            String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (_: Throwable) {
            url
        }
    }

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
