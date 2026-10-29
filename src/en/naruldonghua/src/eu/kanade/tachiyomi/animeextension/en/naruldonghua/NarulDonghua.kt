package eu.kanade.tachiyomi.animeextension.en.naruldonghua

import android.content.SharedPreferences
import android.util.Base64
import androidx.preference.PreferenceScreen
import aniyomi.lib.dailymotionextractor.DailymotionExtractor
import aniyomi.lib.playlistutils.PlaylistUtils
import aniyomi.lib.rumbleextractor.RumbleExtractor
import aniyomi.lib.streamwishextractor.StreamWishExtractor
import eu.kanade.tachiyomi.animesource.model.Hoster
import eu.kanade.tachiyomi.animesource.model.SEpisode
import eu.kanade.tachiyomi.animesource.model.Video
import eu.kanade.tachiyomi.multisrc.animestream.AnimeStream
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.awaitSuccess
import keiyoushi.annotation.Source
import keiyoushi.network.get
import keiyoushi.utils.addListPreference
import keiyoushi.utils.addSetPreference
import keiyoushi.utils.delegate
import keiyoushi.utils.useAsJsoup
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Response
import org.jsoup.Jsoup

@Source
abstract class NarulDonghua : AnimeStream() {

    // ============================== Preferences ==============================

    override val prefQualityValues = listOf("1080p", "720p", "480p", "360p", "240p", "144p")

    private val SharedPreferences.prefServer
        by preferences.delegate(PREF_SERVER_KEY, PREF_SERVER_DEFAULT)

    private val SharedPreferences.enabledHosters
        by preferences.delegate(PREF_HOSTER_KEY, PREF_HOSTER_DEFAULT)

    override fun setupPreferenceScreen(screen: PreferenceScreen) {
        super.setupPreferenceScreen(screen)

        screen.addListPreference(
            key = PREF_SERVER_KEY,
            title = "Preferred Server",
            entries = PREF_SERVER_ENTRIES,
            entryValues = PREF_SERVER_VALUES,
            default = PREF_SERVER_DEFAULT,
            summary = "%s",
        )

        screen.addSetPreference(
            key = PREF_HOSTER_KEY,
            title = "Enable/Disable Hosts",
            summary = "",
            entries = INTERNAL_HOSTER_NAMES,
            entryValues = PREF_HOSTER_ENTRY_VALUES,
            default = PREF_HOSTER_DEFAULT,
        )
    }

    private companion object {
        private const val PREF_SERVER_KEY = "preferred_server"
        private val PREF_SERVER_ENTRIES = listOf("Dailymotion", "Rumble", "StreamWish")
        private val PREF_SERVER_VALUES = listOf("Dailymotion", "Rumble", "StreamWish")
        private const val PREF_SERVER_DEFAULT = "Dailymotion"

        private const val PREF_HOSTER_KEY = "naruldonghua_hoster_selection"
        private val INTERNAL_HOSTER_NAMES = listOf("Dailymotion", "Rumble", "StreamWish")
        private val PREF_HOSTER_ENTRY_VALUES = INTERNAL_HOSTER_NAMES.map { it.lowercase() }
        private val PREF_HOSTER_DEFAULT = PREF_HOSTER_ENTRY_VALUES.toSet()
    }

    // ============================== Episodes ==============================

    override fun getEpisodeNumber(epNum: String): Float = epNum.replace("[4K]", "", ignoreCase = true)
        .trim()
        .substringBefore(" ")
        .substringBefore("(")
        .toFloatOrNull() ?: 0F

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
            it.hosterName.contains("ADS", ignoreCase = true) ||
                it.hosterName.equals("Countdown", ignoreCase = true)
        }
    }

    private val dailymotionExtractor by lazy { DailymotionExtractor(client, headers) }
    private val rumbleExtractor by lazy { RumbleExtractor(client, headers) }
    private val streamwishExtractor by lazy { StreamWishExtractor(client, headers) }
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
        if (url.isBlank()) return emptyList()

        val prefix = "${hoster.hosterName} - "

        return when {
            preferences.enabledHosters.contains("dailymotion") &&
                ("dailymotion.com" in url || "geo.dailymotion.com" in url) -> {
                val videoId = url.toHttpUrlOrNull()?.run {
                    queryParameter("video") ?: pathSegments.lastOrNull()
                } ?: ""
                val normalizedUrl = if (videoId.isNotEmpty()) {
                    "https://geo.dailymotion.com/player/x9mpt.html?video=$videoId"
                } else {
                    url
                }
                dailymotionExtractor.videosFromUrl(normalizedUrl, prefix)
            }
            preferences.enabledHosters.contains("rumble") && "rumble.com" in url -> {
                rumbleExtractor.videosFromUrl(url, prefix)
            }
            preferences.enabledHosters.contains("streamwish") &&
                ("embedrise.com" in url || "streamwish" in url) -> {
                streamwishExtractor.videosFromUrl(url, prefix = prefix)
            }
            url.endsWith(".m3u8") -> {
                playlistUtils.extractFromHls(url, videoNameGen = { "$prefix$it" })
            }
            url.endsWith(".mp4") -> {
                listOf(Video(videoUrl = url, videoTitle = hoster.hosterName))
            }
            else -> emptyList()
        }
    }

    // ============================= Utilities ==============================

    override fun List<Video>.sortVideos(): List<Video> {
        val quality = preferences.videoSortPref
        val server = preferences.prefServer

        return sortedWith(
            compareBy(
                { it.videoTitle.contains(server, ignoreCase = true) },
                { it.videoTitle.contains(quality, ignoreCase = true) },
                { Regex("""(\d+)p""").find(it.videoTitle)?.groupValues?.get(1)?.toIntOrNull() ?: 0 },
            ),
        ).reversed()
    }
}
