package eu.kanade.tachiyomi.animeextension.en.animekhor

import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.doodextractor.DoodExtractor
import aniyomi.lib.mp4uploadextractor.Mp4uploadExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import aniyomi.lib.vidhideextractor.VidHideExtractor
import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.TurboVidExtractor
import eu.kanade.tachiyomi.animeextension.en.animekhor.extractors.VidaraExtractor
import eu.kanade.tachiyomi.animesource.ConfigurableAnimeSource
import eu.kanade.tachiyomi.animesource.model.AnimeFilter
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
import keiyoushi.utils.addListPreference
import keiyoushi.utils.getPreferencesLazy
import keiyoushi.utils.tryParse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Headers
import okhttp3.Request
import okhttp3.Response
import org.jsoup.nodes.Element
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.abs

@Source
abstract class AnimeKhor :
    AnimeHttpSource(),
    ConfigurableAnimeSource {

    override val supportsLatest = true

    override fun headersBuilder(): Headers.Builder = super.headersBuilder()
        .add("Referer", "$baseUrl/")

    private val preferences by getPreferencesLazy()

    private val animeListUrl get() = "$baseUrl/anime"

    private val dateFormatter by lazy {
        SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
    }

    // ============================ Extractors ==============================

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val doodExtractor by lazy { DoodExtractor(client) }
    private val mp4uploadExtractor by lazy { Mp4uploadExtractor(client) }
    private val okruExtractor by lazy { OkruExtractor(client) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val streamWishExtractor by lazy {
        val docHeaders = headers.newBuilder()
            .set("Referer", "$baseUrl/")
            .build()
        StreamWishExtractor(client, docHeaders)
    }
    private val vidHideExtractor by lazy { VidHideExtractor(client, headers) }
    private val vidaraExtractor by lazy { VidaraExtractor(client, headers) }
    private val turboVidExtractor by lazy { TurboVidExtractor(client, headers) }

    // ============================== Popular Anime ==============================

    override suspend fun getPopularAnime(page: Int): AnimesPage {
        fetchFilterList()
        return super.getPopularAnime(page)
    }

    override fun popularAnimeRequest(page: Int): Request = GET("$animeListUrl/?page=$page&order=popular", headers)

    override fun popularAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    // ============================== Latest Updates ==============================

    override suspend fun getLatestUpdates(page: Int): AnimesPage {
        fetchFilterList()
        return super.getLatestUpdates(page)
    }

    override fun latestUpdatesRequest(page: Int): Request = GET("$animeListUrl/?page=$page&order=update", headers)

    override fun latestUpdatesParse(response: Response): AnimesPage = parseAnimeList(response)

    // ============================== Search Anime ==============================

    override fun searchAnimeRequest(page: Int, query: String, filters: AnimeFilterList): Request {
        val params = AnimeKhorFilters.getSearchParameters(filters)
        return if (query.isNotEmpty()) {
            GET("$baseUrl/page/$page/?s=$query", headers)
        } else {
            val multiString = buildString {
                if (params.genres.isNotEmpty()) append(params.genres + "&")
                if (params.seasons.isNotEmpty()) append(params.seasons + "&")
                if (params.studios.isNotEmpty()) append(params.studios + "&")
            }
            GET("$animeListUrl/?page=$page&$multiString&status=${params.status}&type=${params.type}&sub=${params.sub}&order=${params.order}", headers)
        }
    }

    override fun searchAnimeParse(response: Response): AnimesPage = parseAnimeList(response)

    private fun parseAnimeList(response: Response): AnimesPage {
        val document = response.asJsoup()
        val animeList = document.select("div.listupd article a.tip").map { element ->
            SAnime.create().apply {
                setUrlWithoutDomain(element.attr("abs:href"))
                title = element.selectFirst("div.tt, div.ttl")?.ownText().orEmpty()
                thumbnail_url = element.selectFirst("img")?.getImageUrl()
            }
        }
        val hasNextPage = document.selectFirst("div.pagination a.next, div.hpage > a.r") != null
        return AnimesPage(animeList, hasNextPage)
    }

    // ============================== Filters ===============================

    private suspend fun fetchFilterList() {
        if (!AnimeKhorFilters.filterInitialized()) {
            withContext(Dispatchers.IO) {
                runCatching {
                    AnimeKhorFilters.filterElements = client.get(animeListUrl, headers)
                        .asJsoup()
                        .select("span.sec1 > div.filter > ul")
                }
            }
        }
    }

    override fun getFilterList(): AnimeFilterList = if (AnimeKhorFilters.filterInitialized() && AnimeKhorFilters.filterElements.isNotEmpty()) {
        AnimeFilterList(
            AnimeKhorFilters.GenresFilter("Genres"),
            AnimeKhorFilters.SeasonFilter("Seasons"),
            AnimeKhorFilters.StudioFilter("Studios"),
            AnimeFilter.Separator(),
            AnimeKhorFilters.StatusFilter("Status"),
            AnimeKhorFilters.TypeFilter("Type"),
            AnimeKhorFilters.SubFilter("Subtitle"),
            AnimeKhorFilters.OrderFilter("Order"),
        )
    } else {
        AnimeFilterList(AnimeFilter.Header("Press 'Reset' to attempt to show the filters"))
    }

    // =========================== Anime Details ============================

    override fun animeDetailsParse(response: Response): SAnime {
        val document = response.asJsoup()
        return SAnime.create().apply {
            setUrlWithoutDomain(document.location())
            title = document.selectFirst("h1.entry-title")?.text().orEmpty()
            thumbnail_url = document.selectFirst("div.thumb > img, div.limage > img")?.getImageUrl()

            val infos = document.selectFirst("div.info-content, div.right ul.data")
            if (infos != null) {
                genre = infos.select("div.genxed > a, li:contains(Genre:) a").eachText().joinToString()
                status = parseStatus(infos.getInfo("Status"))
                artist = infos.getInfo("Studio")
                author = infos.getInfo("Fansub")

                description = buildString {
                    document.select(".entry-content[itemprop=description], .desc").lastOrNull()?.text()?.let {
                        append("$it\n\n")
                    }
                    document.selectFirst(".alter")?.text()?.takeIf(String::isNotBlank)?.let {
                        append("Alternative name(s): $it\n")
                    }
                    infos.select("div.spe > span, li:has(b)").eachText().forEach {
                        append("$it\n")
                    }
                }.trim()
            }
        }
    }

    // ============================== Episodes ==============================

    override fun episodeListParse(response: Response): List<SEpisode> = throw UnsupportedOperationException()
    override fun hosterListParse(response: Response): List<Hoster> = throw UnsupportedOperationException()
    override fun seasonListParse(response: Response): List<SAnime> = throw UnsupportedOperationException()
    override fun videoListParse(response: Response, hoster: Hoster): List<Video> = throw UnsupportedOperationException()

    override suspend fun getEpisodeList(anime: SAnime): List<SEpisode> {
        val doc = client.get(baseUrl + anime.url, headers).asJsoup()
        val episodes = doc.select("div.eplister > ul > li > a").map { element ->
            SEpisode.create().apply {
                setUrlWithoutDomain(element.attr("href"))
                val epNumStr = element.selectFirst(".epl-num")?.text().orEmpty()
                name = "Episode $epNumStr"
                episode_number = epNumStr.substringBefore(" ").toFloatOrNull() ?: 0F
                element.selectFirst(".epl-sub")?.text()?.let { scanlator = it }
                date_upload = element.selectFirst(".epl-date")?.text().let { dateFormatter.tryParse(it) }
            }
        }

        val firstEp = episodes.firstOrNull() ?: return episodes
        val latestUploadDate = doc.selectFirst("time[itemprop=dateModified]")?.attr("datetime")
            ?.let { SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ENGLISH).tryParse(it) }
            ?: 0L
        if (latestUploadDate > 0L) {
            val diff = abs(latestUploadDate - firstEp.date_upload)
            if (firstEp.date_upload == 0L || diff < 86_400_000L * 2) {
                firstEp.date_upload = latestUploadDate
            }
        }

        return episodes
    }

    // ============================ Hosters & Videos (Lib 16 API) =============================

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> {
        val doc = client.get(baseUrl + episode.url, headers).asJsoup()
        val servers = mutableListOf<Hoster>()

        // 1. Parse select.mirror options (instant in-memory Base64 decoding, matching Python)
        doc.select("select.mirror option[value]").forEach { option ->
            val valStr = option.attr("value").trim()
            val rawName = option.text().trim()
            if (valStr.isEmpty() || rawName.contains("Select Video Server", ignoreCase = true)) {
                return@forEach
            }

            val embedUrl = extractEmbedUrl(valStr) ?: return@forEach
            if (isBrokenEmbed(embedUrl)) return@forEach

            val cleanName = rawName.replace("《", " [").replace("》", "] ").trim()
            servers.add(Hoster(hosterUrl = embedUrl, hosterName = cleanName))
        }

        // 2. Direct iframe fallback if no mirrors found
        if (servers.isEmpty()) {
            doc.select(".player-embed iframe, .video-content iframe, iframe#embed-frame").forEach { iframe ->
                val src = iframe.attr("src").trim()
                if (src.isNotEmpty() && !isBrokenEmbed(src)) {
                    val fullUrl = if (src.startsWith("//")) "https:$src" else src
                    servers.add(Hoster(hosterUrl = fullUrl, hosterName = "Default Player"))
                }
            }
        }

        return servers.distinctBy { it.hosterUrl }.sortHosters()
    }

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = hoster.hosterUrl
        val name = hoster.hosterName
        val prefix = "$name - "

        return when {
            url.contains("dailymotion.com") || url.contains("geo.dailymotion.com") || name.contains("VidPlayer", true) -> {
                val cleanUrl = if (url.contains("dailymotion.com/embed/embed-")) {
                    val id = url.substringAfter("dailymotion.com/embed/embed-").substringBefore(".")
                    "https://www.dailymotion.com/embed/video/$id"
                } else {
                    url
                }
                dailymotionExtractor.videosFromUrl(cleanUrl, prefix = prefix)
            }

            url.contains("ok.ru") || url.contains("odnoklassniki.ru") -> {
                okruExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("rumble.com") -> {
                rumbleExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("vidara") || url.contains("vidvara") || name.contains("DaraPlayer", true) -> {
                vidaraExtractor.videosFromUrl(url, prefix = prefix)
            }

            url.contains("ahvsh.com") || url.contains("vidhide") || name.contains("streamhide", true) -> {
                vidHideExtractor.videosFromUrl(url) { "$prefix$it" }
            }

            url.contains("wish") -> {
                streamWishExtractor.videosFromUrl(url, prefix)
            }

            url.contains("mp4upload") -> {
                mp4uploadExtractor.videosFromUrl(url, headers, prefix = prefix)
            }

            url.contains("dood") || url.contains("do0od") || url.contains("ds2play") -> {
                doodExtractor.videosFromUrl(url, quality = prefix)
            }

            url.contains("turbovid") || url.contains("turboviplay") || url.contains("emturbovid") -> {
                turboVidExtractor.videosFromUrl(url, prefix = prefix)
            }

            else -> emptyList()
        }.sortVideos()
    }

    // ============================== Preferences ==============================

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        screen.addListPreference(
            key = PREF_SERVER_KEY,
            default = PREF_SERVER_DEFAULT,
            title = "Preferred server",
            summary = "%s",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
        )

        screen.addListPreference(
            key = PREF_QUALITY_KEY,
            default = PREF_QUALITY_DEFAULT,
            title = "Preferred quality",
            summary = "%s",
            entries = PREF_QUALITY_ENTRIES,
            entryValues = PREF_QUALITY_VALUES,
        )
    }

    // ============================== Sorting & Helpers ==============================

    override fun List<Hoster>.sortHosters(): List<Hoster> {
        val prefServer = preferences.getString(PREF_SERVER_KEY, PREF_SERVER_DEFAULT) ?: PREF_SERVER_DEFAULT
        return sortedWith(
            compareByDescending { hoster ->
                hoster.hosterName.contains(prefServer, ignoreCase = true) ||
                    hoster.hosterUrl.contains(prefServer, ignoreCase = true)
            },
        )
    }

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.getString(PREF_QUALITY_KEY, PREF_QUALITY_DEFAULT) ?: PREF_QUALITY_DEFAULT
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality, ignoreCase = true) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }

    private fun extractEmbedUrl(value: String): String? {
        if (value.startsWith("http://") || value.startsWith("https://") || value.startsWith("//")) {
            return if (value.startsWith("//")) "https:$value" else value
        }
        return try {
            val decoded = String(Base64.decode(value, Base64.DEFAULT))
            val src = IFRAME_SRC_REGEX.find(decoded)?.groupValues?.get(1)?.trim()
            if (!src.isNullOrBlank()) {
                if (src.startsWith("//")) "https:$src" else src
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun isBrokenEmbed(url: String): Boolean {
        val lower = url.lowercase()
        return (lower.contains("turbovidhls.com/t/") && !lower.contains("/t/video") && !lower.contains(".m3u8")) ||
            lower.contains("a-ads.com") ||
            lower.contains("googleads") ||
            lower.isBlank()
    }

    private fun Element.getInfo(text: String): String? = selectFirst("span:contains($text)")
        ?.run {
            selectFirst("a")?.text() ?: ownText()
        }

    private fun Element.getImageUrl(): String? = when {
        hasAttr("data-src") -> attr("abs:data-src")
        hasAttr("data-lazy-src") -> attr("abs:data-lazy-src")
        hasAttr("srcset") -> attr("abs:srcset").substringBefore(" ")
        else -> attr("abs:src")
    }.substringBefore("?resize")

    private fun parseStatus(statusString: String?): Int = when (statusString?.trim()?.lowercase()) {
        "completed", "completo" -> SAnime.COMPLETED
        "ongoing", "lançamento" -> SAnime.ONGOING
        else -> SAnime.UNKNOWN
    }

    companion object {
        private val IFRAME_SRC_REGEX = Regex("""src=["']([^"']+)["']""", RegexOption.IGNORE_CASE)

        private const val PREF_SERVER_KEY = "preferred_server"
        private const val PREF_SERVER_DEFAULT = "VidPlayer"
        private val PREF_SERVER_ENTRIES = listOf(
            "VidPlayer (Dailymotion)",
            "ok.ru",
            "RumblePlayer",
            "DaraPlayer (Vidara)",
            "StreamWish",
            "Mp4Upload",
            "DoodStream",
            "TurboVid",
        )
        private val PREF_SERVER_VALUES = listOf(
            "VidPlayer",
            "ok.ru",
            "Rumble",
            "DaraPlayer",
            "wish",
            "mp4upload",
            "dood",
            "turbovid",
        )

        private const val PREF_QUALITY_KEY = "preferred_quality"
        private const val PREF_QUALITY_DEFAULT = "720p"
        private val PREF_QUALITY_ENTRIES = listOf("1080p", "720p", "480p", "360p", "240p", "144p")
        private val PREF_QUALITY_VALUES = PREF_QUALITY_ENTRIES
    }
}
