package com.hexated

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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

    override val mainPage = mainPageOf(
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Currently+Airing&type=" to "Ongoing Anime",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=Finished+Airing&type=" to "Complete Anime",
        "$mainUrl/anime/?mode=&sort=series_popularity&status=&type=" to "Paling Populer",
        "$mainUrl/anime/?mode=&sort=series_skor&status=&type=" to "Rating Tertinggi",
        "$mainUrl/anime/?mode=&sort=series_tahun_newest&status=&type=Movie" to "Anime Movie",
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            val pageUrl = if (page <= 1) {
                request.data
            } else {
                if (request.data.contains("?")) {
                    request.data.replaceFirst("?", "page/$page/?")
                } else {
                    "${request.data.removeSuffix("/")}/page/$page/"
                }
            }

            val res = NontonAnimeIDCloudflare.safeGet(pageUrl)
            updateMainUrl(res.url)
            val document = res.document

            val home = document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }

            val hasNext = home.isNotEmpty() && document.selectFirst(".pagination .next, a.next, .pagination-arrow.-right") != null

            newHomePageResponse(request.name, home, hasNext = hasNext)
        } catch (e: Throwable) {
            Log.w(TAG, "getMainPage error for ${request.name}: ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
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
        return try {
            val link = "$mainUrl/?s=${URLEncoder.encode(query, "UTF-8")}"
            val res = NontonAnimeIDCloudflare.safeGet(link)
            updateMainUrl(res.url)
            val document = res.document

            document.select("article.animeseries, .animeseries, a.as-anime-card, .result > ul > li").mapNotNull {
                it.toSearchResult()
            }.distinctBy { it.url }
        } catch (e: Throwable) {
            Log.e(TAG, "search error for $query: ${e.message}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val initialRes = NontonAnimeIDCloudflare.safeGet(url)
            updateMainUrl(initialRes.url)

            val targetUrl = if (url.contains("/anime/")) {
                url
            } else {
                initialRes.document.selectFirst("div.nvs.nvsc a, .all-episode a, .nvs a")?.attr("href") ?: url
            }

            val res = if (targetUrl == url) initialRes else NontonAnimeIDCloudflare.safeGet(targetUrl)
            val document = res.document

            val rawTitle = document.selectFirst("h1.entry-title.cs, h1.entry-title, .entry-title")?.text()?.trim()
                ?: return null

            val title = rawTitle
                .replace(Regex("^Nonton\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+Sub\\s+Indo$", RegexOption.IGNORE_CASE), "")
                .trim()

            val poster = document.selectFirst(".anime-card__sidebar img, .poster > img, .thumb > img")?.getImageAttr()
            val tags = document.select(".anime-card__genres .genre-tag, .tagline > a, .genxed a, .genre-info a").map { it.text().trim() }

            val year = Regex("(19|20)\\d{2}").find(
                document.select(".details-list li:contains(Aired), .info-item.season, .bottomtitle, .spe span:contains(Rilis)").text()
            )?.groupValues?.getOrNull(0)?.toIntOrNull()

            val statusText = document.select(".details-list li:contains(Status), .spe span:contains(Status), .info-item.status").text()
            val status = getStatus(statusText)

            val typeText = document.select(".details-list li:contains(Type), .spe span:contains(Type), .info-item.type").text()
            val tvType = getType(typeText)

            val description = document.selectFirst(".entry-content[itemprop=description] p, .sinopsis, .entry-content p, .deskripsi")?.text()?.trim()

            val episodes = mutableListOf<Episode>()

            // Scrape static episode list
            document.select(".maintable tr, ul.episodelst li, .episodelst li, .single-episode, .episodelist li").forEach { epElement ->
                val a = epElement.selectFirst("a") ?: return@forEach
                val epHref = fixUrlNull(a.attr("href")) ?: return@forEach
                val epTitle = a.text().trim().ifBlank { epElement.text().trim() }
                val epNum = Regex("(?i)(?:episode|ep)\\s*(\\d+)").find(epTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()

                episodes.add(
                    newEpisode(epHref) {
                        this.name = epTitle
                        this.episode = epNum
                    }
                )
            }

            // Handle AJAX pagination for large episode lists if present
            val ajaxUrl = "$mainUrl/wp-admin/admin-ajax.php"
            val postId = document.selectFirst("input#post_id, [data-post-id]")?.attr("value")
                ?: document.selectFirst("[data-post-id]")?.attr("data-post-id")

            if (!postId.isNullOrBlank()) {
                var currentPage = 2
                var hasMore = true
                while (hasMore && currentPage <= 20) {
                    try {
                        val ajaxRes = NontonAnimeIDCloudflare.safePost(
                            url = ajaxUrl,
                            data = mapOf(
                                "action" to "load_more_eps",
                                "post_id" to postId,
                                "page" to currentPage.toString()
                            ),
                            referer = targetUrl,
                            headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                        ).text

                        if (ajaxRes.isBlank() || ajaxRes == "0" || ajaxRes == "-1") {
                            hasMore = false
                        } else {
                            val ajaxDoc = Jsoup.parse(ajaxRes)
                            val ajaxItems = ajaxDoc.select("tr, li")
                            if (ajaxItems.isEmpty()) {
                                hasMore = false
                            } else {
                                ajaxItems.forEach { item ->
                                    val a = item.selectFirst("a") ?: return@forEach
                                    val href = fixUrlNull(a.attr("href")) ?: return@forEach
                                    val name = a.text().trim().ifBlank { item.text().trim() }
                                    val num = Regex("(?i)(?:episode|ep)\\s*(\\d+)").find(name)?.groupValues?.getOrNull(1)?.toIntOrNull()
                                    episodes.add(
                                        newEpisode(href) {
                                            this.name = name
                                            this.episode = num
                                        }
                                    )
                                }
                                currentPage++
                            }
                        }
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error fetching episode page $currentPage: ${e.message}")
                        hasMore = false
                    }
                }
            }

            val sortedEpisodes = episodes.distinctBy { it.data }.reversed()

            newAnimeLoadResponse(title, targetUrl, tvType) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = tags
                this.showStatus = status
                addEpisodes(DubStatus.Subbed, sortedEpisodes)
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
            val res = NontonAnimeIDCloudflare.safeGet(data)
            updateMainUrl(res.url)
            val document = res.document
            val iframeLinks = linkedSetOf<String>()

            fun normalizeUrl(value: String?): String? {
                val raw = value
                    ?.trim()
                    ?.replace("\\/", "/")
                    ?.replace("&amp;", "&")
                    ?.takeIf { it.isNotBlank() }
                    ?: return null
                return when {
                    raw.startsWith("about:") -> null
                    raw.startsWith("http://") || raw.startsWith("https://") -> raw
                    else -> fixUrl(raw)
                }
            }

            fun collectIframes(doc: Document) {
                doc.select("iframe").forEach { iframe ->
                    normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })?.let { iframeLinks.add(it) }
                }
            }

            collectIframes(document)

            // Parse AJAX player config
            val ajaxConfigScript = document.selectFirst("script#ajax_video-js-extra, script:containsData(player_ajax)")
                ?.html()
                .orEmpty()
                .ifBlank {
                    document.selectFirst("script#ajax_video-js-extra")
                        ?.attr("src")
                        ?.substringAfter("base64,", "")
                        ?.takeIf { it.isNotBlank() }
                        ?.let { encoded ->
                            try {
                                String(Base64.decode(encoded, Base64.DEFAULT))
                            } catch (_: Throwable) {
                                ""
                            }
                        }
                        .orEmpty()
                }

            val ajaxUrl = Regex("\"url\"\\s*:\\s*\"([^\"]+)\"")
                .find(ajaxConfigScript)
                ?.groupValues
                ?.getOrNull(1)
                ?.replace("\\/", "/")
                ?.let { fixUrl(it) }
                ?: "$mainUrl/wp-admin/admin-ajax.php"

            val nonce = Regex("\"nonce\"\\s*:\\s*\"([^\"]+)\"")
                .find(ajaxConfigScript)
                ?.groupValues
                ?.getOrNull(1)

            if (!nonce.isNullOrBlank()) {
                document.select(".serverplayer[data-post][data-type][data-nume], .container1 > ul > li[data-post][data-type][data-nume], ul#serverul li[data-post][data-type][data-nume]")
                    .forEach { serverItem ->
                        val dataPost = serverItem.attr("data-post")
                        val dataNume = serverItem.attr("data-nume")
                        val serverName = serverItem.attr("data-type").lowercase()
                        if (dataPost.isBlank() || dataNume.isBlank() || serverName.isBlank()) return@forEach

                        try {
                            val response = NontonAnimeIDCloudflare.safePost(
                                url = ajaxUrl,
                                data = mapOf(
                                    "action" to "player_ajax",
                                    "nonce" to nonce,
                                    "serverName" to serverName,
                                    "nume" to dataNume,
                                    "post" to dataPost,
                                ),
                                referer = data,
                                headers = mapOf(
                                    "X-Requested-With" to "XMLHttpRequest",
                                    "Origin" to mainUrl,
                                )
                            ).text
                            collectIframes(Jsoup.parse(response))
                        } catch (e: Throwable) {
                            Log.w(TAG, "Error fetching player AJAX for server $serverName: ${e.message}")
                        }
                    }
            }

            // Resolve collected iframes
            iframeLinks.toList().amap { link ->
                val nestedLink = if (link.contains("/video-frame/") || link.contains("/video-embed/")) {
                    try {
                        val frameRes = NontonAnimeIDCloudflare.safeGet(link, referer = data)
                        val iframe = frameRes.document.selectFirst("iframe")
                        if (iframe != null) {
                            normalizeUrl(iframe.attr("data-src").ifEmpty { iframe.attr("src") })
                        } else null
                    } catch (_: Throwable) {
                        null
                    }
                } else {
                    null
                }

                val finalUrl = nestedLink ?: link
                loadExtractor(finalUrl, "$mainUrl/", subtitleCallback, callback)
            }

            // Also check downloadable / direct links
            document.select(".listlink a, .download a").amap { a ->
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

    private fun Element.getImageAttr(): String? {
        return when {
            this.hasAttr("data-src") -> this.attr("abs:data-src")
            this.hasAttr("data-lazy-src") -> this.attr("abs:data-lazy-src")
            this.hasAttr("srcset") -> this.attr("abs:srcset").substringBefore(" ")
            else -> this.attr("abs:src")
        }
    }
}
