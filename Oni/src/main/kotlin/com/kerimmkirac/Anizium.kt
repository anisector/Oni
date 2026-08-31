package com.kerimmkirac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.loadExtractor
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
    private val genreIds = mutableMapOf<String, String>()
    private var genresLoaded = false
    private val pageSignatures = mutableMapOf<String, String>()

    private val legacyGenreIds = mapOf(
        "aksiyon" to "23813",
        "macera" to "43261",
        "komedi" to "47450",
        "drama" to "59624",
        "fantastik" to "62263",
        "romantik" to "87910",
        "bilimkurgu" to "94032",
    )

    override val mainPage = listOf(
        MainPageData("Yeni Bölümler", "/page/last-added-episodes?page=%d"),
        MainPageData("Top 100", "/page/top?platform=favorite&page=%d"),
        MainPageData("Aksiyon", "genre:Aksiyon"),
        MainPageData("Macera", "genre:Macera"),
        MainPageData("Komedi", "genre:Komedi"),
        MainPageData("Drama", "genre:Drama"),
        MainPageData("Fantastik", "genre:Fantastik"),
        MainPageData("Romantik", "genre:Romantik"),
        MainPageData("Bilim Kurgu", "genre:Bilim Kurgu"),
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val path = if (request.data.startsWith("genre:")) {
            val genreName = request.data.substringAfter("genre:")
            val id = resolveGenreId(genreName)
                ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
            "/page/catalog?id=${AniziumApi.encode(id)}&type=genre&page=$page"
        } else {
            request.data.replace("%d", page.toString())
        }

        val node = AniziumApi.getJson(path)
            ?: return newHomePageResponse(request.name, emptyList(), hasNext = false)
        val root = AniziumApi.unwrap(node)
        val items = extractItems(root).mapNotNull { it.asSearchResponse() }.distinctBy { it.url }
        val signature = items.joinToString("|") { it.url }
        val previous = pageSignatures["${request.data}:${page - 1}"]
        if (page > 1 && signature.isNotBlank() && signature == previous) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
        pageSignatures["${request.data}:$page"] = signature

        return newHomePageResponse(
            request.name,
            items,
            hasNext = items.isNotEmpty() && hasNextPage(node, root, page),
        )
    }

    private suspend fun resolveGenreId(name: String): String? {
        val key = normalize(name)
        genreIds[key]?.let { return it }
        if (!genresLoaded) {
            val node = AniziumApi.getJson("/page/genre")
            if (node != null) {
                val root = AniziumApi.unwrap(node)
                val rows = collectArrays(root, "genres", "items", "results", "data", "list")
                    .ifEmpty { AniziumApi.findFirstArray(root) }
                for (row in rows) {
                    val label = AniziumApi.text(row, "name", "title", "genre", "label") ?: continue
                    val id = AniziumApi.text(row, "ID", "id", "genreId", "genre_id", "catalogId", "catalog_id") ?: continue
                    genreIds[normalize(label)] = id
                }
                genresLoaded = genreIds.isNotEmpty()
            }
        }
        return genreIds[key] ?: legacyGenreIds[key]
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = AniziumApi.encode(query)
        val result = linkedMapOf<String, SearchResponse>()
        var previousSignature: String? = null

        for (page in 1..3) {
            val node = AniziumApi.firstJson(
                "/page/search?value=$q&page=$page",
                "/page/search?query=$q&page=$page",
                "/search?value=$q&page=$page",
                "/search?query=$q&page=$page",
            ) ?: break
            val root = AniziumApi.unwrap(node)
            val current = extractItems(root).mapNotNull { it.asSearchResponse() }.distinctBy { it.url }
            val signature = current.joinToString("|") { it.url }
            if (signature.isNotBlank() && signature == previousSignature) break
            current.forEach { result.putIfAbsent(it.url, it) }
            if (current.isEmpty() || !hasNextPage(node, root, page)) break
            previousSignature = signature
        }
        return result.values.toList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val animeId = Regex("[?&]id=([^&]+)").find(url)?.groupValues?.get(1)
            ?: Regex("/anime/([^/?#]+)").find(url)?.groupValues?.get(1)
            ?: url.substringAfterLast('/').substringBefore('?').takeIf { it.isNotBlank() }
            ?: return null
        val encoded = AniziumApi.encode(animeId)
        val node = AniziumApi.firstJson(
            "/anime/get?id=$encoded",
            "/anime/get?anime_id=$encoded",
        ) ?: return null
        val root = AniziumApi.unwrap(node)

        val title = AniziumApi.text(
            root, "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: return null
        val poster = AniziumApi.text(
            root, "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        )
        val plot = AniziumApi.text(root, "overview", "overview_short", "overviewShort", "description", "synopsis", "summary")
        val year = AniziumApi.int(root, "year", "release_year", "releaseYear")
        val type = (AniziumApi.text(
            root, "type", "mediaType", "media_type", "contentType", "content_type", "anime_type", "animeType",
        ) ?: "series").lowercase()
        val tmdb = AniziumApi.text(root, "tmdb_id", "tmdbId", "tmdb") ?: ""
        val episodes = buildEpisodes(root, animeId, tmdb)

        return if (type.contains("movie") || type == "film") {
            val ref = EpisodeRef(animeId, tmdb, 1, 1, null, true)
            newMovieLoadResponse(title, url, TvType.AnimeMovie, mapper.writeValueAsString(ref)) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                posterUrl = poster
                this.plot = plot
                this.year = year
            }
        }
    }

    internal fun buildEpisodes(root: JsonNode, animeId: String, tmdb: String): List<Episode> {
        val out = mutableListOf<Episode>()
        val seen = mutableSetOf<String>()
        val seasons = collectArrays(root, "seasons", "season_list", "seasonList")

        for (seasonNode in seasons) {
            val seasonNo = AniziumApi.int(seasonNode, "number", "season", "seasonNumber", "season_number") ?: 1
            for (ep in episodeNodes(seasonNode)) addEpisode(out, seen, ep, animeId, tmdb, seasonNo)
        }
        if (out.isEmpty()) {
            for (ep in episodeNodes(root)) {
                val seasonNo = AniziumApi.int(ep, "season", "seasonNumber", "season_number") ?: 1
                addEpisode(out, seen, ep, animeId, tmdb, seasonNo)
            }
        }
        return out.sortedWith(compareBy<Episode> { it.season ?: 0 }.thenBy { it.episode ?: 0 })
    }

    private fun episodeNodes(node: JsonNode): List<JsonNode> {
        val direct = collectArrays(node, "episodes", "episode_list", "episodeList")
        if (direct.isNotEmpty()) return direct
        return collectArrays(node, "items", "data", "list").filter {
            AniziumApi.int(it, "episode", "episodeNumber", "episode_number", "number") != null ||
                AniziumApi.text(it, "episodeId", "episode_id") != null
        }
    }

    private fun addEpisode(
        target: MutableList<Episode>,
        seen: MutableSet<String>,
        node: JsonNode,
        animeId: String,
        tmdb: String,
        seasonNo: Int,
    ) {
        val epNo = AniziumApi.int(node, "number", "episode", "episodeNumber", "episode_number") ?: 0
        val epId = AniziumApi.text(node, "ID", "id", "episodeId", "episode_id")
        val key = "$seasonNo:$epNo:${epId.orEmpty()}"
        if (!seen.add(key)) return
        val title = AniziumApi.text(node, "name", "title", "episodeTitle", "episode_name") ?: "Bölüm $epNo"
        val ref = EpisodeRef(animeId, tmdb, seasonNo, epNo, epId, false)
        target.add(newEpisode(mapper.writeValueAsString(ref)) {
            name = title
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
            val encoded = AniziumApi.encode(id)
            val paths = listOf(
                "/anime/source?id=$encoded&season=${ref.season}&episode=${ref.episode}",
                "/anime/source?anime_id=$encoded&season=${ref.season}&episode=${ref.episode}",
                "/anime/bulk-source?id=$encoded&season=${ref.season}&episode=${ref.episode}",
                "/anime/bulk-source?anime_id=$encoded&season=${ref.season}&episode=${ref.episode}",
            )
            for (path in paths) {
                val node = AniziumApi.getJson(path) ?: continue
                if (emitApiSources(node, seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true
            }
        }
        return emitted
    }

    private fun extractItems(root: JsonNode): List<JsonNode> {
        val direct = collectArrays(root, "items", "results", "animes", "anime", "episodes", "data", "list", "content")
        if (direct.isNotEmpty()) return direct
        val page = root.get("page")
        val nested = collectArrays(page, "data", "items", "results", "animes", "anime", "episodes", "list", "content")
        if (nested.isNotEmpty()) return nested
        return AniziumApi.findFirstArray(root)
    }

    private fun JsonNode.asSearchResponse(): SearchResponse? {
        val source = listOf("anime", "show", "series", "item").asSequence()
            .mapNotNull { get(it) }.firstOrNull { it.isObject } ?: this
        val title = AniziumApi.text(
            source, "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: AniziumApi.text(
            this, "name", "title", "animeName", "anime_name", "original_name", "originalName", "name_jp", "nameJp",
        ) ?: return null
        val id = AniziumApi.text(source, "ID", "id", "animeId", "anime_id", "slug", "animeSlug", "anime_slug")
            ?: AniziumApi.text(this, "ID", "id", "animeId", "anime_id", "slug", "animeSlug", "anime_slug")
            ?: return null
        val href = if (id.startsWith("http")) id else "$mainUrl/anime/$id"
        val poster = AniziumApi.text(
            source, "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        ) ?: AniziumApi.text(
            this, "poster", "posterUrl", "poster_url", "image", "cover", "coverUrl", "cover_url",
            "mobile_poster_link", "mobilePosterLink", "anime_poster", "animePoster",
        )
        val type = (AniziumApi.text(source, "type", "mediaType", "media_type", "contentType", "content_type", "anime_type")
            ?: AniziumApi.text(this, "type", "mediaType", "media_type", "contentType", "content_type", "anime_type")
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

        val direct = collectArrays(root, "sources", "videos", "video_sources", "videoSources", "streams", "qualities") +
            collectArrays(root, "items", "links", "playback_sources", "playbackSources", "playback", "files")
        if (emitSourceItems(direct, "", seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true

        val groups = collectArrays(root, "groups", "sound_groups", "soundGroups", "audio_groups", "audioGroups", "audio", "servers")
        for (group in groups) {
            val sound = sourceSound(group, "")
            val items = collectArrays(
                group, "sources", "items", "links", "videos", "video_sources", "videoSources",
                "streams", "qualities", "playback_sources", "playbackSources",
            )
            if (emitSourceItems(items, sound, seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true
            if (emitSourceItem(group, sound, seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true
            emitSubtitles(group, sound, seenSubtitles, subtitleCallback)
        }
        if (emitSourceItem(root, "", seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true
        emitSubtitles(root, "", seenSubtitles, subtitleCallback)
        return emitted
    }

    private fun collectArrays(node: JsonNode?, vararg names: String): List<JsonNode> {
        if (node == null) return emptyList()
        val result = mutableListOf<JsonNode>()
        val seen = mutableSetOf<String>()
        for (name in names) {
            for (item in AniziumApi.array(node, name)) if (seen.add(item.toString())) result.add(item)
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
        for (item in items) if (emitSourceItem(item, groupSound, seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true
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
        val sound = sourceSound(item, groupSound)
        val nested = collectArrays(
            item, "sources", "videos", "video_sources", "videoSources", "streams", "qualities",
            "items", "links", "playback_sources", "playbackSources",
        )
        if (nested.isNotEmpty() && emitSourceItems(nested, sound, seenLinks, seenSubtitles, subtitleCallback, callback)) emitted = true

        val link = AniziumApi.text(
            item, "source_url", "sourceUrl", "playback_source_url", "playbackSourceUrl",
            "stream_url", "streamUrl", "video_url", "videoUrl", "url", "link", "src", "file", "hls", "m3u8",
        )
        if (link?.startsWith("http") == true) {
            val key = "${link.trim()}|${normalize(sound)}"
            if (!seenLinks.contains(key)) {
                val qualityText = AniziumApi.text(
                    item, "resolution", "quality", "source_quality", "sourceQuality", "source_resolution",
                    "sourceResolution", "video_quality", "videoQuality", "height", "label",
                ) ?: ""
                val quality = inferQuality("$qualityText $link")
                val referer = sourceReferer(item, link)

                if (isEmbedSource(item, link)) {
                    var extractedAny = false
                    loadExtractor(link, referer, subtitleCallback) { extracted ->
                        val extractedKey = "${extracted.url}|${normalize(sound)}"
                        if (seenLinks.add(extractedKey)) {
                            callback(extracted)
                            extractedAny = true
                        }
                    }
                    if (extractedAny) {
                        seenLinks.add(key)
                        emitted = true
                    }
                } else if (seenLinks.add(key)) {
                    callback(newExtractorLink(name, buildLabel(quality, sound), link, detectLinkType(item, link)) {
                        this.quality = quality
                        this.referer = referer
                        this.headers = safePlaybackHeaders(item)
                    })
                    emitted = true
                }
            }
        }
        emitSubtitles(item, sound, seenSubtitles, subtitleCallback)
        return emitted
    }

    private fun sourceSound(node: JsonNode, fallback: String): String =
        AniziumApi.text(
            node, "sound_group", "soundGroup", "audio", "group", "sound", "language", "lang",
            "audio_type", "audioType", "dub", "dub_type", "dubType",
        ) ?: fallback

    internal fun isEmbedSource(item: JsonNode, link: String): Boolean {
        val hint = AniziumApi.text(item, "type", "kind", "source_type", "sourceType", "player_type", "playerType")
            ?.lowercase().orEmpty()
        val lower = link.lowercase()
        return hint.contains("embed") || hint.contains("iframe") || hint.contains("player") ||
            lower.contains("/embed/") || lower.contains("/embed?") || lower.contains("/player/") || lower.contains("/player?")
    }

    internal fun detectLinkType(item: JsonNode, link: String): ExtractorLinkType {
        val hint = AniziumApi.text(item, "mime", "mime_type", "mimeType", "format", "type", "source_type", "sourceType")
            ?.lowercase().orEmpty()
        val lower = link.lowercase()
        return when {
            lower.contains(".m3u8") || hint.contains("m3u8") || hint.contains("mpegurl") || hint == "hls" -> ExtractorLinkType.M3U8
            lower.contains(".mpd") || hint.contains("dash") || hint.contains("mpd") -> ExtractorLinkType.DASH
            else -> ExtractorLinkType.VIDEO
        }
    }

    internal fun sourceReferer(item: JsonNode, link: String): String {
        val headers = AniziumApi.stringMap(item, "headers", "http_headers", "httpHeaders")
        val explicit = AniziumApi.text(item, "referer", "referrer", "source_referer", "sourceReferer")
            ?: headers.entries.firstOrNull { it.key.equals("referer", true) || it.key.equals("referrer", true) }?.value
        if (!explicit.isNullOrBlank()) return explicit
        if (link.contains("anizium.co", true) || link.contains("anizium.online", true)) {
            Regex("^(https?://[^/]+)", RegexOption.IGNORE_CASE).find(link)?.groupValues?.getOrNull(1)?.let { return "$it/" }
        }
        return "$mainUrl/"
    }

    internal fun safePlaybackHeaders(item: JsonNode): Map<String, String> {
        val raw = AniziumApi.stringMap(item, "headers", "http_headers", "httpHeaders")
        val safe = linkedMapOf<String, String>()
        for ((key, value) in raw) {
            if (value.isBlank()) continue
            when (key.trim().lowercase()) {
                "origin" -> safe["Origin"] = value
                "user-agent", "useragent" -> safe["User-Agent"] = value
            }
        }
        AniziumApi.text(item, "origin", "source_origin", "sourceOrigin")
            ?.takeIf { it.isNotBlank() }?.let { safe.putIfAbsent("Origin", it) }
        AniziumApi.text(item, "user_agent", "userAgent", "source_user_agent", "sourceUserAgent")
            ?.takeIf { it.isNotBlank() }?.let { safe.putIfAbsent("User-Agent", it) }
        return safe
    }

    private fun emitSubtitles(
        node: JsonNode,
        fallbackLabel: String,
        seen: MutableSet<String>,
        callback: (SubtitleFile) -> Unit,
    ) {
        val direct = listOfNotNull(
            AniziumApi.text(node, "subtitleUrl", "subtitle_url"),
            AniziumApi.text(node, "captionUrl", "caption_url"),
            AniziumApi.text(node, "trackUrl", "track_url"),
        ).distinct()
        val directLabel = subtitleLabel(node, fallbackLabel)
        for (url in direct) emitSubtitle(directLabel, url, seen, callback)

        val dedicated = collectArrays(
            node, "subtitles", "subtitle", "captions", "subtitle_tracks", "subtitleTracks", "text_tracks", "textTracks",
        )
        for (sub in dedicated) emitSubtitleNode(sub, fallbackLabel, seen, callback)

        for (track in collectArrays(node, "tracks")) {
            val type = AniziumApi.text(track, "type", "kind", "track_type", "trackType", "category")?.lowercase().orEmpty()
            val specific = AniziumApi.text(track, "subtitleUrl", "subtitle_url", "captionUrl", "caption_url") != null
            if (specific || type.contains("subtitle") || type.contains("caption") || type == "text") {
                emitSubtitleNode(track, fallbackLabel, seen, callback)
            }
        }
    }

    private fun emitSubtitleNode(node: JsonNode, fallback: String, seen: MutableSet<String>, callback: (SubtitleFile) -> Unit) {
        val url = AniziumApi.text(
            node, "subtitleUrl", "subtitle_url", "captionUrl", "caption_url", "trackUrl", "track_url",
            "url", "link", "src", "file", "source_url", "sourceUrl",
        ) ?: return
        emitSubtitle(subtitleLabel(node, fallback), url, seen, callback)
    }

    internal fun subtitleLabel(node: JsonNode, fallback: String): String {
        val raw = AniziumApi.text(
            node, "subtitle_group", "subtitleGroup", "group", "subtitle_language", "subtitleLanguage",
            "language", "lang", "locale", "language_code", "languageCode", "label", "name", "title",
        ) ?: fallback
        return when (normalize(raw)) {
            "tr", "trtr", "turkish", "turkce", "turkcealtyazi" -> "Türkçe"
            "en", "enus", "engb", "english", "ingilizce" -> "İngilizce"
            "ja", "jp", "jajp", "japanese", "japonca" -> "Japonca"
            "de", "dede", "german", "almanca" -> "Almanca"
            "fr", "frfr", "french", "fransizca" -> "Fransızca"
            "es", "eses", "spanish", "ispanyolca" -> "İspanyolca"
            "pt", "ptbr", "portuguese", "portekizce" -> "Portekizce"
            "ar", "arar", "arabic", "arapca" -> "Arapça"
            else -> raw.ifBlank { "Türkçe" }
        }
    }

    private fun emitSubtitle(label: String, url: String, seen: MutableSet<String>, callback: (SubtitleFile) -> Unit) {
        if (!url.startsWith("http")) return
        if (!seen.add("${url.trim()}|${normalize(label)}")) return
        callback(SubtitleFile(label, url))
    }

    internal fun hasNextPage(node: JsonNode, root: JsonNode, page: Int): Boolean {
        val candidates = mutableListOf(node, root)
        for (key in listOf("pagination", "meta", "page", "paging")) {
            node.get(key)?.let { candidates.add(it) }
            root.get(key)?.let { candidates.add(it) }
        }
        for (c in candidates) {
            AniziumApi.bool(c, "hasNext", "has_next", "hasMore", "has_more")?.let { return it }
            val current = AniziumApi.int(c, "currentPage", "current_page", "page") ?: page
            val last = AniziumApi.int(c, "lastPage", "last_page", "totalPages", "total_pages", "pageCount", "page_count")
            if (last != null) return current < last
            val next = AniziumApi.text(c, "nextPage", "next_page", "next")
            if (next != null) return next.trim().lowercase() !in setOf("", "0", "null", "none", "false")
        }
        return true
    }

    internal fun inferQuality(value: String): Int = when {
        Regex("2160|4k", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 2160
        Regex("1440|2k", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 1440
        Regex("1080|fullhd|full hd", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 1080
        Regex("720", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 720
        Regex("480", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 480
        Regex("360", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 360
        Regex("240", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 240
        Regex("144", RegexOption.IGNORE_CASE).containsMatchIn(value) -> 144
        else -> 0
    }

    internal fun buildLabel(quality: Int, sound: String): String {
        val q = when (quality) { 2160 -> "4K"; 1440 -> "2K"; 0 -> "Otomatik"; else -> "${quality}p" }
        val s = when (normalize(sound)) {
            "trdub", "trdublaj", "turkishdub", "turkcedublaj" -> "Türkçe Dublaj"
            "endub", "englishdub", "ingilizcedublaj" -> "İngilizce Dublaj"
            "cndub", "chinesedub", "cincedublaj" -> "Çince Dublaj"
            "trsub", "traltyazi", "turkishsub", "turkcealtyazi" -> "Türkçe Altyazı"
            "original", "japanese", "japonca", "ja", "jp" -> "Japonca"
            "tr", "turkish", "turkce" -> "Türkçe"
            "en", "english", "ingilizce" -> "İngilizce"
            else -> sound.ifBlank { "Kaynak" }
        }
        return "$q ($s)"
    }

    private fun normalize(value: String): String = value.lowercase()
        .replace("ı", "i").replace("ğ", "g").replace("ü", "u").replace("ş", "s").replace("ö", "o").replace("ç", "c")
        .replace(Regex("[^a-z0-9]"), "")
}
