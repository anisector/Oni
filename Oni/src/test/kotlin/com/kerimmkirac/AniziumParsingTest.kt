package com.kerimmkirac

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AniziumParsingTest {
    private val mapper = jacksonObjectMapper()
    private val provider = Anizium()

    @Test
    fun `episodes are deduplicated and sorted`() {
        val root = mapper.readTree(
            """
            {
              "seasons": [
                {
                  "number": 1,
                  "episodes": [
                    {"episode": 2, "id": "ep-2", "title": "Second"},
                    {"episode": 1, "id": "ep-1", "title": "First"},
                    {"episode": 1, "id": "ep-1", "title": "First duplicate"}
                  ]
                }
              ]
            }
            """.trimIndent()
        )

        val episodes = provider.buildEpisodes(root, "anime-1", "")

        assertEquals(2, episodes.size)
        assertEquals(listOf(1, 2), episodes.map { it.episode })
        assertEquals(listOf(1, 1), episodes.map { it.season })
    }

    @Test
    fun `pagination respects explicit last page metadata`() {
        val pageTwo = mapper.readTree("""{"pagination":{"currentPage":2,"totalPages":3}}""")
        val pageThree = mapper.readTree("""{"pagination":{"currentPage":3,"totalPages":3}}""")

        assertTrue(provider.hasNextPage(pageTwo, pageTwo, 2))
        assertFalse(provider.hasNextPage(pageThree, pageThree, 3))
    }

    @Test
    fun `quality and audio labels preserve multiple variants`() {
        assertEquals(2160, provider.inferQuality("2160p 4K"))
        assertEquals(1080, provider.inferQuality("Full HD"))
        assertEquals(720, provider.inferQuality("https://cdn.example/video-720.m3u8"))
        assertEquals("4K (Türkçe Dublaj)", provider.buildLabel(2160, "tr_dub"))
        assertEquals("1080p (Japonca)", provider.buildLabel(1080, "Japanese"))
    }

    @Test
    fun `subtitle language aliases are normalized`() {
        val turkish = mapper.readTree("""{"language":"tr-TR"}""")
        val english = mapper.readTree("""{"lang":"en-US"}""")
        val japanese = mapper.readTree("""{"label":"Japanese"}""")

        assertEquals("Türkçe", provider.subtitleLabel(turkish, ""))
        assertEquals("İngilizce", provider.subtitleLabel(english, ""))
        assertEquals("Japonca", provider.subtitleLabel(japanese, ""))
    }

    @Test
    fun `playback headers allow only non-session transport headers`() {
        val source = mapper.readTree(
            """
            {
              "headers": {
                "Referer": "https://x.anizium.co/",
                "Origin": "https://x.anizium.co",
                "User-Agent": "Anizium-Test-UA",
                "Cookie": "session=must-not-forward",
                "Authorization": "Bearer must-not-forward"
              }
            }
            """.trimIndent()
        )

        val safe = provider.safePlaybackHeaders(source)

        assertEquals("https://x.anizium.co", safe["Origin"])
        assertEquals("Anizium-Test-UA", safe["User-Agent"])
        assertFalse(safe.keys.any { it.equals("Cookie", true) })
        assertFalse(safe.keys.any { it.equals("Authorization", true) })
        assertEquals("https://x.anizium.co/", provider.sourceReferer(source, "https://cdn.example/video.m3u8"))
    }

    @Test
    fun `embed and media types are distinguished`() {
        val embed = mapper.readTree("""{"type":"embed"}""")
        val hls = mapper.readTree("""{"format":"hls"}""")
        val dash = mapper.readTree("""{"mime":"application/dash+xml"}""")

        assertTrue(provider.isEmbedSource(embed, "https://x.anizium.co/embed?id=123"))
        assertEquals(ExtractorLinkType.M3U8, provider.detectLinkType(hls, "https://cdn.example/master"))
        assertEquals(ExtractorLinkType.DASH, provider.detectLinkType(dash, "https://cdn.example/manifest"))
    }
}
