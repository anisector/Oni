package com.kerimmkirac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

class Anizium : MainAPI() {
    override var mainUrl = AniziumApi.WEB
    override var name = "Anizium"
    override var lang = "tr"
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val instantLinkLoading = true

    private val mapper = jacksonObjectMapper()

    override val mainPage = listOf(
        MainPageData("Yeni Bölümler", "/page/last-added-episodes?page=%d"),
        MainPageData("Top 100", "/page/top?platform=favorite&page=%d"),
        MainPageData("Aksiyon", "/page/catalog?id=23813&type=genre&page=%d"),
        MainPageData("Macera", "/page/catalog?id=43261&type=genre&page=%d"),
        MainPageData("Komedi", "/page/catalog?id=47450&type=genre&page=%d"),
        MainPageData("Drama", "/page/catalog?id=59624&type=genre&page=%d"),
        MainPageData("Fantastik", "/page/catalog?id=62263&type=genre&page=%d"),
        MainPageData("Romantik", "/page/catalog?id=87910&type=genre&page=%d"),
        MainPageData("Bilim Kurgu", "/page/catalog?id=94032&type=genre&page=%d"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = request.data.replace("%d", page.toString())
        val node = AniziumApi.getJson(path)
            ?: return newHomePageResponse(request.name, emptyList())
        val root = AniziumApi.unwrap(node)
        val items = extractItems(root)
        return newHomePageResponse(
            request.name,
            items.mapNotNull { it.asSearchResponse() },
            hasNext = items.isNotEmpty(),
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = AniziumApi.encode(query)
        val node = AniziumApi.firstJson(
            "/page/search?value=$q&page=1",
            "/search?value=$q&page=1",
            "/search?query=$q&page=1",
        ) ?: return emptyList()
        return extractItems(AniziumApi.unwrap(node)).mapNotNull { it.asSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
            ?: Regex("/anime/([^/?#]+)").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: return null

        val encodedId = AniziumApi.encode(animeId)
        val node = AniziumApi.firstJson(
            "/anime/get?id=$encodedId",
            "/anime/$encodedId",
        ) ?: return null
        val root = AniziumApi.unwrap(node)
        val title = AniziumApi.text(
            root,
            "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: return null
        val poster = AniziumApi.text(
            root,
            "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        )
        val plot = AniziumApi.text(
            root,
            "overview", "overview_short", "overviewShort", "description", "synopsis", "summary",
        )
        val year = AniziumApi.int(root, "year", "release_year", "releaseYear")
        val type = (AniziumApi.text(root, "type", "mediaType", "media_type", "contentType", "content_type") ?: "series").lowercase()
        val tmdb = AniziumApi.text(root, "tmdb_id", "tmdbId", "tmdb") ?: ""

        val episodeList = buildEpisodes(root, animeId, tmdb)

        return if (type.contains("movie") || type == "film") {
            val movieRef = EpisodeRef(animeId, tmdb, 1, 1, null, true)
            newMovieLoadResponse(title, url, TvType.AnimeMovie, mapper.writeValueAsString(movieRef)) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodeList) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    private fun buildEpisodes(root: JsonNode, animeId: String, tmdb: String): List<Episode> {
        val result = mutableListOf<Episode>()
        val seasons = AniziumApi.array(root, "seasons")

        if (seasons.isNotEmpty()) {
            for (seasonNode in seasons) {
                val seasonNo = AniziumApi.int(
                    seasonNode,
                    "number", "season", "seasonNumber", "season_number",
                ) ?: 1
                val episodes = AniziumApi.array(seasonNode, "episodes", "items")
                for (episodeNode in episodes) {
                    addEpisode(result, episodeNode, animeId, tmdb, seasonNo)
                }
            }
        } else {
            for (episodeNode in AniziumApi.array(root, "episodes", "items")) {
                val seasonNo = AniziumApi.int(
                    episodeNode,
                    "season", "seasonNumber", "season_number",
                ) ?: 1
                addEpisode(result, episodeNode, animeId, tmdb, seasonNo)
            }
        }
        return result
    }

    private fun addEpisode(
        target: MutableList<Episode>,
        episodeNode: JsonNode,
        animeId: String,
        tmdb: String,
        seasonNo: Int,
    ) {
        val epNo = AniziumApi.int(
            episodeNode,
            "number", "episode", "episodeNumber", "episode_number",
        ) ?: 0
        val epId = AniziumApi.text(episodeNode, "ID", "id", "episodeId", "episode_id")
        val epTitle = AniziumApi.text(
            episodeNode,
            "name", "title", "episodeTitle", "episode_name",
        ) ?: "Bölüm $epNo"
        val ref = EpisodeRef(animeId, tmdb, seasonNo, epNo, epId, false)
        target.add(newEpisode(mapper.writeValueAsString(ref)) {
            name = epTitle
            episode = epNo
            season = seasonNo
        })
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val ref = runCatching { mapper.readValue(data, EpisodeRef::class.java) }.getOrNull() ?: return false
        val ids = listOfNotNull(ref.animeId, ref.episodeId).distinct()
        val seenLinks = mutableSetOf<String>()
        val seenSubtitles = mutableSetOf<String>()
        var emitted = false

        for (id in ids) {
            val encodedId = AniziumApi.encode(id)
            val paths = listOf(
                // TV client routes.
                "/anime/source?id=$encodedId&season=${ref.season}&episode=${ref.episode}",
                "/anime/source?id=$encodedId&site=main&season=${ref.season}&episode=${ref.episode}",
                "/anime/source?id=$encodedId&site=main&plan=standard&season=${ref.season}&episode=${ref.episode}&server=1",
                "/anime/source?anime_id=$encodedId&season=${ref.season}&episode=${ref.episode}",
                // Android client also exposes bulk-source; keep it strictly as a public fallback.
                "/anime/bulk-source?id=$encodedId&season=${ref.season}&episode=${ref.episode}",
                "/anime/bulk-source?anime_id=$encodedId&season=${ref.season}&episode=${ref.episode}",
            )
            for (path in paths) {
                val sourceNode = AniziumApi.getJson(path) ?: continue
                if (emitApiSources(sourceNode, seenLinks, seenSubtitles, subtitleCallback, callback)) {
                    emitted = true
                }
            }
        }
        return emitted
    }

    private fun extractItems(root: JsonNode): List<JsonNode> {
        val direct = AniziumApi.array(root, "items", "results", "animes", "anime", "episodes", "data", "list", "content")
        if (direct.isNotEmpty()) return direct
        val page = root.get("page")
        val pageData = AniziumApi.array(page, "data", "items", "results", "animes", "anime", "episodes", "list", "content")
        if (pageData.isNotEmpty()) return pageData
        return AniziumApi.findFirstArray(root)
    }

    private fun JsonNode.asSearchResponse(): SearchResponse? {
        val source = listOf("anime", "show", "series", "item").asSequence()
            .mapNotNull { get(it) }
            .firstOrNull { it.isObject } ?: this

        val title = AniziumApi.text(
            source,
            "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: AniziumApi.text(
            this,
            "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: return null

        val id = AniziumApi.text(
            source,
            "ID", "id", "animeId", "anime_id", "slug", "animeSlug", "anime_slug",
        ) ?: AniziumApi.text(
            this,
            "ID", "id", "animeId", "anime_id", "slug", "animeSlug", "anime_slug",
        ) ?: return null

        val href = if (id.startsWith("http")) id else "$mainUrl/anime/$id"
        val poster = AniziumApi.text(
            source,
            "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        ) ?: AniziumApi.text(
            this,
            "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        )
        val type = (AniziumApi.text(source, "type", "mediaType", "media_type", "contentType", "content_type")
            ?: AniziumApi.text(this, "type", "mediaType", "media_type", "contentType", "content_type")
            ?: "series").lowercase()

        return if (type.contains("movie") || type == "film") {
            newMovieSearchResponse(title, href, TvType.AnimeMovie) { posterUrl = poster }
        } else {
            newAnimeSearchResponse(title, href, TvType.Anime) { posterUrl = poster }
        }
    }

    private suspend fun emitApiSources(
        node: JsonNode,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val root = AniziumApi.unwrap(node)
        var emitted = false

        // Merge every source container. Different qualities can be split across sibling arrays.
        val directSources = collectArrays(
            root,
            "sources", "videos", "video_sources", "videoSources", "streams", "qualities",
        )
        if (emitSourceItems(directSources, "", seenLinks, seenSubtitles, subtitleCallback, callback)) {
            emitted = true
        }

        val fallbackDirect = collectArrays(
            root,
            "items", "links", "playback_sources", "playbackSources", "playback", "files",
        )
        if (emitSourceItems(fallbackDirect, "", seenLinks, seenSubtitles, subtitleCallback, callback)) {
            emitted = true
        }

        val groups = collectArrays(
            root,
            "groups", "sound_groups", "soundGroups", "audio_groups", "audioGroups", "audio", "servers",
        )
        for (group in groups) {
            val groupSound = AniziumApi.text(
                group,
                "sound_group", "soundGroup", "group", "sound", "name", "audio", "language", "lang",
                "audio_type", "audioType", "dub", "dub_type", "dubType",
            ) ?: ""
            val groupedSources = collectArrays(
                group,
                "sources", "items", "links", "videos", "video_sources", "videoSources",
                "streams", "qualities", "playback_sources", "playbackSources",
            )
            if (emitSourceItems(groupedSources, groupSound, seenLinks, seenSubtitles, subtitleCallback, callback)) {
                emitted = true
            }
            if (emitSourceItem(group, groupSound, seenLinks, seenSubtitles, subtitleCallback, callback)) {
                emitted = true
            }
            emitSubtitles(group, groupSound, seenSubtitles, subtitleCallback)
        }

        if (emitSourceItem(root, "", seenLinks, seenSubtitles, subtitleCallback, callback)) {
            emitted = true
        }
        emitSubtitles(root, "", seenSubtitles, subtitleCallback)
        return emitted
    }

    private fun collectArrays(node: JsonNode?, vararg names: String): List<JsonNode> {
        if (node == null) return emptyList()
        val result = mutableListOf<JsonNode>()
        val seen = mutableSetOf<String>()
        for (name in names) {
            for (item in AniziumApi.array(node, name)) {
                val key = item.toString()
                if (seen.add(key)) result.add(item)
            }
        }
        return result
    }

    private suspend fun emitSourceItems(
        items: List<JsonNode>,
        groupSound: String,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false
        for (item in items) {
            if (emitSourceItem(item, groupSound, seenLinks, seenSubtitles, subtitleCallback, callback)) {
                emitted = true
            }
        }
        return emitted
    }

    private suspend fun emitSourceItem(
        item: JsonNode,
        groupSound: String,
        seenLinks: MutableSet<String>,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        var emitted = false

        // Some responses put each quality inside nested streams/qualities instead of one flat source array.
        val nestedSources = collectArrays(
            item,
            "sources", "videos", "video_sources", "videoSources", "streams", "qualities",
            "items", "links", "playback_sources", "playbackSources",
        )
        if (nestedSources.isNotEmpty() &&
            emitSourceItems(nestedSources, groupSound, seenLinks, seenSubtitles, subtitleCallback, callback)
        ) {
            emitted = true
        }

        val link = AniziumApi.text(
            item,
            "source_url", "sourceUrl", "playback_source_url", "playbackSourceUrl",
            "stream_url", "streamUrl", "video_url", "videoUrl",
            "url", "link", "src", "file", "hls", "m3u8",
        )
        if (link?.startsWith("http") == true && seenLinks.add(link)) {
            val sound = AniziumApi.text(
                item,
                "sound_group", "soundGroup", "audio", "group", "sound", "language", "lang",
                "audio_type", "audioType", "dub", "dub_type", "dubType",
            ) ?: groupSound
            val qualityText = AniziumApi.text(
                item,
                "resolution", "quality", "source_quality", "sourceQuality",
                "source_resolution", "sourceResolution", "video_quality", "videoQuality", "height", "label",
            ) ?: ""
            val quality = inferQuality("$qualityText $link")

            callback(newExtractorLink(name, buildLabel(quality, sound), link) {
                type = if (link.contains(".m3u8", true) || link.contains("m3u8", true)) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
                this.quality = quality
                referer = "$mainUrl/"
            })
            emitted = true
        }

        emitSubtitles(item, groupSound, seenSubtitles, subtitleCallback)
        return emitted
    }

    private fun emitSubtitles(
        node: JsonNode,
        fallbackLabel: String,
        seenSubtitles: MutableSet<String>,
        subtitleCallback: (SubtitleFile) -> Unit,
    ) {
        val directUrls = listOfNotNull(
            AniziumApi.text(node, "subtitleUrl", "subtitle_url"),
            AniziumApi.text(node, "captionUrl", "caption_url"),
            AniziumApi.text(node, "trackUrl", "track_url"),
        ).distinct()

        for (directUrl in directUrls) {
            if (!directUrl.startsWith("http") || !seenSubtitles.add(directUrl)) continue
            val label = AniziumApi.text(
                node,
                "subtitle_group", "subtitleGroup", "subtitle_language", "subtitleLanguage",
                "language", "lang", "locale", "language_code", "languageCode", "label", "name", "title",
            ) ?: fallbackLabel.ifBlank { "Türkçe" }
            subtitleCallback(SubtitleFile(label, directUrl))
        }

        val subtitles = collectArrays(
            node,
            "subtitles", "subtitle", "captions", "tracks",
            "subtitle_tracks", "subtitleTracks", "text_tracks", "textTracks",
        )
        for (sub in subtitles) {
            val link = AniziumApi.text(
                sub,
                "subtitleUrl", "subtitle_url", "captionUrl", "caption_url", "trackUrl", "track_url",
                "url", "link", "src", "file", "source_url", "sourceUrl",
            ) ?: continue
            if (!link.startsWith("http") || !seenSubtitles.add(link)) continue
            val label = AniziumApi.text(
                sub,
                "subtitle_group", "subtitleGroup", "group", "subtitle_language", "subtitleLanguage",
                "language", "lang", "locale", "language_code", "languageCode", "label", "name", "title",
            ) ?: fallbackLabel.ifBlank { "Türkçe" }
            subtitleCallback(SubtitleFile(label, link))
        }
    }

    private fun inferQuality(value: String): Int = when {
        Regex("2160|4k", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 2160
        Regex("1440|2k", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 1440
        Regex("1080|fullhd|full hd", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 1080
        Regex("720", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 720
        Regex("480", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 480
        Regex("360", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 360
        else -> 0
    }

    private fun buildLabel(quality: Int, sound: String): String {
        val q = when (quality) {
            2160 -> "4K"
            1440 -> "2K"
            0 -> "Otomatik"
            else -> "${quality}p"
        }
        val normalized = sound.lowercase().replace("-", "").replace("_", "").replace(" ", "")
        val s = when (normalized) {
            "trdub", "trdublaj", "turkishdub" -> "Türkçe Dublaj"
            "endub", "englishdub" -> "İngilizce Dublaj"
            "cndub", "chinesedub" -> "Çince Dublaj"
            "trsub", "traltyazi", "turkishsub" -> "Türkçe Altyazı"
            "original", "japanese", "japonca" -> "Japonca"
            else -> sound.ifBlank { "Kaynak" }
        }
        return "$q ($s)"
    }
}
