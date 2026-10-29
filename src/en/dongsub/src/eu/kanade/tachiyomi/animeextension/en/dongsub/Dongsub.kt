package eu.kanade.tachiyomi.animeextension.en.dongsub

import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.youtubeextractor.YoutubeExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilterList
import eu.kanade.tachiyomi.animesource.model.AnimesPage
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SAnime
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.animesource.online.AnimeHttpSource
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.util.asJsoup
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.network.rateLimit
import keiyoushi.utils.addListPreference
import keiyoushi.utils.bodyString
import keiyoushi.utils.getPreferencesLazy
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.OffsetDateTime

@Source
abstract class Dongsub :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override val client = network.client.newBuilder()
        .rateLimit(3) { it.host == "www.dongsub.net" || it.host == "dongsub.net" }
        .build()

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "https://www.dongsub.net/")

    private val preferences by getPreferencesLazy()

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val youtubeExtractor by lazy { YoutubeExtractor(client, headers) }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )
    }

    // ============================== Popular Anime ==============================

    override fun popularAnimeRequest(page: Int): Request {
        val startIndex = ((page - 1) * PAGE_SIZE) + 1
        val url = "$baseUrl/feeds/posts/default/-/Series?alt=json&start-index=$startIndex&max-results=$PAGE_SIZE"
        return GET(url, headers)
    }

    override fun popularAnimeParse(response: Response): AnimesPage = parseFeed(response)

    // ============================== Latest Updates ==============================

    override fun latestUpdatesRequest(page: Int): Request {
        val startIndex = ((page - 1) * PAGE_SIZE) + 1
        val url = "$baseUrl/feeds/posts/default/-/Episode?alt=json&start-index=$startIndex&max-results=$PAGE_SIZE"
        return GET(url, headers)
    }

    override fun latestUpdatesParse(response: Response): AnimesPage = parseFeed(response)

    // ============================== Search Anime ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request = throw UnsupportedOperationException()

    override fun searchAnimeParse(response: Response): AnimesPage = throw UnsupportedOperationException()

    private var allSeriesCache: List<Pair<SAnime, SeriesSearchMeta>>? = null
    private var cacheTime: Long = 0L

    private suspend fun getAllSeries(): List<Pair<SAnime, SeriesSearchMeta>> {
        val now = System.currentTimeMillis()
        if (allSeriesCache != null && (now - cacheTime) < CACHE_TIMEOUT) {
            return allSeriesCache!!
        }
        val url = "$baseUrl/feeds/posts/default/-/Series?alt=json&max-results=500"
        val response = client.get(url, headers)
        val json = response.bodyString()
        val root = JSONObject(json)
        val feed = root.optJSONObject("feed") ?: return emptyList()
        val entries = feed.optJSONArray("entry") ?: JSONArray()
        val series = buildList(entries.length()) {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val anime = parseEntryToAnime(entry) ?: continue
                val rawTitle = entry.optJSONObject("title")?.optString("$" + "t").orEmpty()
                val content = entry.optJSONObject("content")?.optString("$" + "t").orEmpty()
                val categories = buildList {
                    val catArray = entry.optJSONArray("category")
                    if (catArray != null) {
                        for (j in 0 until catArray.length()) {
                            val term = catArray.getJSONObject(j).optString("term")
                            if (term.isNotBlank()) add(term.lowercase())
                        }
                    }
                }
                val meta = SeriesSearchMeta(
                    titleLower = rawTitle.lowercase(),
                    contentLower = content.lowercase(),
                    categoriesLower = categories,
                )
                add(anime to meta)
            }
        }
        allSeriesCache = series
        cacheTime = now
        return series
    }

    override suspend fun getSearchAnime(page: Int, query: String, filters: AnimeFilterList): AnimesPage {
        val seriesList = getAllSeries()
        val cleanQuery = query.trim().lowercase()

        val filtered = if (cleanQuery.isBlank()) {
            seriesList.map { it.first }
        } else {
            val terms = cleanQuery.split(Regex("""\s+""")).filter { it.isNotBlank() }
            seriesList.filter { (_, meta) ->
                terms.all { term ->
                    meta.titleLower.contains(term) ||
                        meta.contentLower.contains(term) ||
                        meta.categoriesLower.any { it.contains(term) }
                }
            }.map { it.first }
        }

        val startIndex = (page - 1) * PAGE_SIZE
        if (startIndex >= filtered.size) {
            return AnimesPage(emptyList(), false)
        }
        val endIndex = (startIndex + PAGE_SIZE).coerceAtMost(filtered.size)
        val pageItems = filtered.subList(startIndex, endIndex)
        val hasNextPage = endIndex < filtered.size

        return AnimesPage(pageItems, hasNextPage)
    }

    // ============================== Feed Parser ==============================

    private fun parseFeed(response: Response): AnimesPage {
        val json = response.bodyString()
        val root = JSONObject(json)
        val feed = root.optJSONObject("feed") ?: return AnimesPage(emptyList(), false)
        val entries = feed.optJSONArray("entry") ?: JSONArray()
        val animeList = buildList(entries.length()) {
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                parseEntryToAnime(entry)?.let(::add)
            }
        }

        val total = feed.optJSONObject("openSearch\$totalResults")?.optString("\$t")?.toIntOrNull() ?: animeList.size
        val start = feed.optJSONObject("openSearch\$startIndex")?.optString("\$t")?.toIntOrNull() ?: 1
        val perPage = feed.optJSONObject("openSearch\$itemsPerPage")?.optString("\$t")?.toIntOrNull() ?: animeList.size
        val hasNextPage = (start + perPage - 1) < total

        return AnimesPage(animeList, hasNextPage)
    }

    private fun parseEntryToAnime(entry: JSONObject): SAnime? {
        val alternateLink = extractAlternateLink(entry) ?: return null
        val rawTitle = entry.optJSONObject("title")?.optString("$" + "t")?.trim().orEmpty()
        val cleanTitle = CLEAN_TITLE_REGEX.replace(rawTitle, "").trim().ifEmpty { rawTitle }
        val thumbnail = extractThumbnail(entry)

        return SAnime.create().apply {
            setUrlWithoutDomain(alternateLink)
            title = cleanTitle
            thumbnail_url = thumbnail
        }
    }

    private fun extractAlternateLink(entry: JSONObject): String? {
        val links = entry.optJSONArray("link") ?: return null
        for (i in 0 until links.length()) {
            val link = links.getJSONObject(i)
            if (link.optString("rel") == "alternate") {
                val href = link.optString("href")
                if (href.isNotBlank()) return href
            }
        }
        return null
    }

    private fun extractThumbnail(entry: JSONObject): String? {
        val mediaThumb = entry.optJSONObject("media\$thumbnail")?.optString("url")
        if (!mediaThumb.isNullOrBlank()) {
            return upgradeImageUrl(mediaThumb)
        }
        val content = entry.optJSONObject("content")?.optString("\$t").orEmpty()
        val imgSrc = IMG_REGEX.find(content)?.groupValues?.get(1)
        if (!imgSrc.isNullOrBlank()) {
            return upgradeImageUrl(imgSrc)
        }
        return null
    }

    private fun upgradeImageUrl(url: String): String = url.replace(RESIZE_REGEX, "/s0/")

    // ============================== Anime Details ==============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            val rawTitle = document.selectFirst("h1.entry-judul, h1.post-title, h1.title-stream, h1")?.text()?.trim().orEmpty()
            title = CLEAN_TITLE_REGEX.replace(rawTitle, "").trim().ifEmpty { rawTitle }
            thumbnail_url = document.selectFirst("img.post-thumbnail, div.thumbox img, div.thumbnail img, meta[property=og:image]")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("content") }
            }?.let(::upgradeImageUrl)

            val rawGenres = document.select(".genred a, a[rel=tag]").eachText()
            genre = rawGenres.filterNot { isSystemTag(it) }.distinct().joinToString()

            val descBuilder = StringBuilder()
            val synopsis = document.selectFirst(".descNime p, .sinoposis p, .entry-content p")?.text()?.trim()
            if (!synopsis.isNullOrEmpty()) {
                descBuilder.append(synopsis).append("\n\n")
            }
            val altTitle = document.selectFirst(".alter")?.text()?.trim()
            if (!altTitle.isNullOrEmpty()) {
                descBuilder.append("Alternative: ").append(altTitle).append("\n")
            }
            description = descBuilder.toString().trim()

            val statusText = document.selectFirst(".infonya:has(b:contains(Status)) span")?.text()
                ?: document.selectFirst("span.status")?.text()
                ?: ""
            status = when {
                statusText.contains("ongoing", ignoreCase = true) -> SAnime.ONGOING
                statusText.contains("completed", ignoreCase = true) -> SAnime.COMPLETED
                else -> SAnime.UNKNOWN
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val document = client.get(baseUrl + anime.url, headers).asJsoup()

        // 1. Check for label_episode JavaScript variable on series page
        val scriptContent = document.select("script").html()
        var seriesLabel = LABEL_EPISODE_REGEX.find(scriptContent)?.groupValues?.get(1)?.trim()

        // 2. If not found in script, try extracting series label from tags/categories
        if (seriesLabel.isNullOrBlank()) {
            val tags = document.select("a[rel=tag]").map { it.text().trim() }
            seriesLabel = tags.firstOrNull { !isSystemTag(it) && !isGenreTag(it) }
        }

        // 3. If series label found, query Blogger feed for all episodes
        if (!seriesLabel.isNullOrBlank()) {
            try {
                val feedUrl = "$baseUrl/feeds/posts/default/-/${URLEncoder.encode(seriesLabel, "UTF-8")}?alt=json&max-results=999"
                val response = client.get(feedUrl, headers)
                val json = response.bodyString()
                val root = JSONObject(json)
                val feed = root.optJSONObject("feed")
                val entries = feed?.optJSONArray("entry")
                if (entries != null && entries.length() > 0) {
                    val episodes = mutableListOf<SEpisode>()
                    for (i in 0 until entries.length()) {
                        val entry = entries.getJSONObject(i)
                        val alternateLink = extractAlternateLink(entry) ?: continue
                        val epTitle = entry.optJSONObject("title")?.optString("\$t")?.trim().orEmpty()
                        val published = entry.optJSONObject("published")?.optString("\$t")
                        val dateUpload = published?.let {
                            runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrDefault(0L)
                        } ?: 0L

                        episodes.add(
                            SEpisode.create().apply {
                                setUrlWithoutDomain(alternateLink)
                                name = epTitle
                                episode_number = parseEpisodeNumber(epTitle)
                                date_upload = dateUpload
                            },
                        )
                    }
                    if (episodes.isNotEmpty()) {
                        return episodes.sortedByDescending { it.episode_number }
                    }
                }
            } catch (_: Exception) {
                // Fall back to DOM parsing
            }
        }

        // 4. Fallback: Parse static DOM elements if present
        val domEpisodes = document.select("#episodeNime ul.myUL li a, select#chapterSelect option").mapNotNull { el ->
            val href = if (el.tagName() == "option") el.attr("value") else el.attr("href")
            if (href.isBlank() || !href.contains(".html")) return@mapNotNull null
            val epName = if (el.tagName() == "option") {
                el.text().trim()
            } else {
                el.selectFirst(".chapternum")?.text()?.trim() ?: el.text().trim()
            }
            SEpisode.create().apply {
                setUrlWithoutDomain(href)
                name = epName
                episode_number = parseEpisodeNumber(epName)
            }
        }
        if (domEpisodes.isNotEmpty()) {
            return domEpisodes.sortedByDescending { it.episode_number }
        }

        // 5. Fallback for single episode watch page
        return listOf(
            SEpisode.create().apply {
                setUrlWithoutDomain(anime.url)
                name = anime.title.ifBlank { "Episode 1" }
                episode_number = parseEpisodeNumber(anime.title).takeIf { it > 0F } ?: 1F
            },
        )
    }

    private fun parseEpisodeNumber(name: String): Float = EPISODE_NUMBER_REGEX.find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 0F

    // ============================== Hosters & Videos ==============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val document = client.get(baseUrl + episode.url, headers).asJsoup()
        val hosters = mutableListOf<Hoster>()

        document.select("select.selectServ option").forEach { option ->
            val url = option.attr("value").trim()
            if (url.startsWith("http")) {
                val name = option.text().trim().ifEmpty { "Server ${hosters.size + 1}" }
                hosters.add(Hoster(hosterUrl = url, hosterName = name))
            }
        }

        if (hosters.isEmpty()) {
            document.select("#content iframe, #streamFull iframe, .player-embed iframe").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.startsWith("http")) {
                    hosters.add(Hoster(hosterUrl = src, hosterName = "Server ${hosters.size + 1}"))
                }
            }
        }

        return hosters.distinctBy { it.hosterUrl }
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        return when {
            "dailymotion" in url -> dailymotionExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ")
            "youtube" in url -> youtubeExtractor.videosFromUrl(url, prefix = "${hoster.hosterName} - ")
            else -> emptyList()
        }.sortVideos()
    }

    // ============================== Video Sorting ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality, ignoreCase = true) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    // ============================== Helper Functions & Constants ==============================

    private fun isSystemTag(tag: String): Boolean {
        val lower = tag.trim().lowercase()
        return SYSTEM_TAGS.any { lower == it || lower.startsWith(it) }
    }

    private fun isGenreTag(tag: String): Boolean {
        val lower = tag.trim().lowercase()
        return GENRE_TAGS.contains(lower)
    }

    companion object {
        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES

        private const val PAGE_SIZE = 20
        private const val CACHE_TIMEOUT = 10 * 60 * 1000L

        private val RESIZE_REGEX = Regex("""/(?:s\d+(-c)?|w\d+-h\d+(-c)?)/""")
        private val IMG_REGEX = Regex("""<img[^>]+src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
        private val LABEL_EPISODE_REGEX = Regex("""var\s+label_episode\s*=\s*["']([^"']+)["']""")
        private val EPISODE_NUMBER_REGEX = Regex("""Episode\s+(\d+(?:\.\d+)?)""", RegexOption.IGNORE_CASE)
        private val CLEAN_TITLE_REGEX = Regex("""\s+(?:Episode|Eps\.?)\s+.*""", RegexOption.IGNORE_CASE)

        private val SYSTEM_TAGS = setOf(
            "episode",
            "eps.",
            "hot",
            "sub",
            "su",
            "series",
            "donghua",
            "movie",
        )

        private val GENRE_TAGS = setOf(
            "action",
            "adventure",
            "fantasy",
            "historical",
            "martial arts",
            "romance",
            "comedy",
            "mystery",
            "supernatural",
            "sci-fi",
            "drama",
            "harem",
            "magic",
            "school",
            "shounen",
            "isekai",
            "cultivation",
        )
    }
}

private data class SeriesSearchMeta(
    val titleLower: String,
    val contentLower: String,
    val categoriesLower: List<String>,
)
