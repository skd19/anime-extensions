package eu.kanade.tachiyomi.animeextension.en.anime4i

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.okruextractor.OkruExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.addSwitchPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class Anime4i : AnimeStream() {

    // ============================== Preferences ==============================

    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p", "144p")

    private val SharedPreferences.ignorePreview
        by preferences.delegate(IGNORE_PREVIEW_KEY, IGNORE_PREVIEW_DEFAULT)

    private val SharedPreferences.enabledHosters
        by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "",
            entries = INTERNAL_HOSTER_NAMES,
            entryValues = PREF_HOSTER_ENTRY_VALUES,
            default = PREF_HOSTER_DEFAULT,
        )

        screen.addSwitchPreference(
            key = IGNORE_PREVIEW_KEY,
            title = "Skip preview episodes",
            summary = "Filter out unreleased or preview episodes that have only countdown timers",
            default = IGNORE_PREVIEW_DEFAULT,
        )
    }

    private companion object {
        private const val PREF_HOSTER_KEY = "anime4i_hoster_selection"
        private val INTERNAL_HOSTER_NAMES = listOf("Dailymotion", "Ok.ru")
        private val PREF_HOSTER_ENTRY_VALUES = listOf("dailymotion", "ok.ru")
        private val PREF_HOSTER_DEFAULT = PREF_HOSTER_ENTRY_VALUES.toSet()

        private const val IGNORE_PREVIEW_KEY = "anime4i_ignore_preview"
        private const val IGNORE_PREVIEW_DEFAULT = true
    }

    // ============================== Episodes ==============================

    override fun getEpisodeNumber(epNum: String): Float = epNum.replace("[4K]", "", ignoreCase = true)
        .trim()
        .substringBefore(" ")
        .toFloatOrNull() ?: 0F

    override fun episodeListParse(response: Response): List<SEpisode> {
        var episodeList = super.episodeListParse(response)
        if (preferences.ignorePreview && episodeList.size > 2) {
            val sorted = episodeList.sortedByDescending { it.episode_number }
            if (sorted[0].date_upload > 0L && sorted[1].date_upload > 0L && sorted[0].date_upload < sorted[1].date_upload) {
                episodeList = sorted.drop(1)
            }
        }
        return episodeList
    }

    // ============================ Video Links =============================

    override fun videoListSelector() = "select.mirror > option[data-index]"

    override suspend fun getHosterList(episode: SEpisode): List<Hoster> = client.get(baseUrl + episode.url)
        .use(::hosterListParse)

    override fun hosterListParse(response: Response): List<Hoster> {
        val document = response.useAsJsoup()
        val episodeUrl = response.request.url.toString()
        val mirrors = document.select(videoListSelector())

        val hosters = if (mirrors.isNotEmpty()) {
            mirrors.mapNotNull { option ->
                val name = option.text().trim()
                val data = option.attr("value")
                if (data.isNotBlank()) {
                    Hoster(
                        hosterUrl = episodeUrl,
                        hosterName = name,
                        internalData = data,
                    )
                } else {
                    null
                }
            }
        } else {
            val pembed = document.selectFirst("#pembed")
            val defaultEmbed = pembed?.attr("data-default-embed")
            if (!defaultEmbed.isNullOrBlank()) {
                listOf(
                    Hoster(
                        hosterUrl = episodeUrl,
                        hosterName = "Default",
                        internalData = defaultEmbed,
                    ),
                )
            } else {
                emptyList()
            }
        }

        return hosters.filterNot {
            it.hosterName.equals("Countdown", ignoreCase = true)
        }
    }

    private val okruExtractor by lazy { OkruExtractor(client) }
    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val playlistUtils by lazy { PlaylistUtils(client, headers) }

    override suspend fun getHosterUrl(encodedData: String): String = runCatching {
        val doc = if (encodedData.toHttpUrlOrNull() == null) {
            val decoded = String(Base64.decode(encodedData, Base64.DEFAULT))
            Jsoup.parseBodyFragment(decoded, baseUrl)
        } else {
            client.newCall(GET(encodedData, headers)).awaitSuccess().useAsJsoup()
        }

        doc.selectFirst("iframe[src~=.]")?.let { iframe ->
            val src = iframe.attr("src")
            when {
                src.startsWith("http") -> src
                src.startsWith("//") -> "https:$src"
                else -> iframe.absUrl("src").ifEmpty { src }
            }
        } ?: doc.selectFirst("meta[content~=.][itemprop=embedUrl]")?.attr("content") ?: ""
    }.getOrDefault("")

    override suspend fun getVideoList(hoster: Hoster): List<Video> {
        val url = getHosterUrl(hoster.internalData)

        if (url.isBlank() || url.contains("animetimer.com")) {
            return emptyList()
        }

        val prefix = "${hoster.hosterName} - "

        return when {
            preferences.enabledHosters.contains("dailymotion") &&
                ("dailymotion.com" in url || "geo.dailymotion.com" in url) -> {
                dailymotionExtractor.videosFromUrl(url, prefix)
            }
            preferences.enabledHosters.contains("ok.ru") && "ok.ru" in url -> {
                okruExtractor.videosFromUrl(url, prefix)
            }
            url.endsWith(".m3u8") -> {
                playlistUtils.extractFromHls(url, videoNameGen = { "$prefix$it" })
            }
            url.endsWith(".mp4") -> {
                listOf(
                    Video(
                        videoUrl = url,
                        videoTitle = hoster.hosterName,
                    ),
                )
            }
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.videoSortPref
        return sortedWith(
            compareBy(
                { it.videoTitle.contains(quality, ignoreCase = true) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
