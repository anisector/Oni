package com.kerimmkirac

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.app
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.TextStyle
import java.util.Locale
import kotlin.random.Random

object AniziumApi {
    private val mapper = jacksonObjectMapper()

    const val WEB = "https://anizium.co"
    const val WEB_APP = "https://web.anizium.online"
    const val ONLINE = "https://api.anizium.online"
    const val API = "https://api.anizium.co"
    const val LEGACY = "https://x.anizium.co"

    private const val CF_TOKEN_KEY = "hlxjl1c2w281ax473rt1ofgrvhyjvi"
    private const val OFFICIAL_BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    private data class RequestProfile(val device: String, val origin: String)

    private val apiBases = listOf(ONLINE, API)
    private val officialProfiles = listOf(
        RequestProfile("tv_app", WEB),
        RequestProfile("android", WEB_APP),
        RequestProfile("browser", WEB_APP),
    )

    @Volatile
    private var preferredBase: String? = null

    @Volatile
    private var preferredProfile: RequestProfile? = null

    private val baseHeaders = mapOf(
        "Accept" to "application/json, text/javascript, */*; q=0.01",
        "Content-Type" to "application/json",
        "User-Agent" to OFFICIAL_BROWSER_UA,
        "user-profile" to "null",
        "user-session" to "null",
        "language" to "tr",
        "site" to "main",
    )

    fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    fun cfControl(nowMillis: Long = System.currentTimeMillis()): String {
        val zone = ZoneId.of("Europe/Istanbul")
        val day = Instant.ofEpochMilli(nowMillis).atZone(zone).dayOfWeek
            .getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            .lowercase(Locale.ROOT)
        val combinedKey = "${CF_TOKEN_KEY}_$day"
        val randomKey = buildString(6) {
            repeat(6) {
                append("abcdefghijklmnopqrstuvwxyz0123456789"[Random.nextInt(36)])
            }
        }
        val json = mapper.writeValueAsString(mapOf(randomKey to nowMillis))
        return xorHex(json, combinedKey)
    }

    private fun xorHex(text: String, key: String): String {
        val tb = text.toByteArray(Charsets.UTF_8)
        val kb = key.toByteArray(Charsets.UTF_8)
        return tb.indices.joinToString("") { i ->
            "%02x".format((tb[i].toInt() xor kb[i % kb.size].toInt()) and 0xff)
        }
    }

    private fun headers(
        cf: Boolean = true,
        profile: RequestProfile,
        extra: Map<String, String> = emptyMap(),
    ): Map<String, String> = buildMap {
        putAll(baseHeaders)
        put("Origin", profile.origin)
        put("Referer", "${profile.origin}/")
        put("device", profile.device)
        put("device_type", profile.device)
        if (cf) put("Cf-Control", cfControl())
        putAll(extra)
    }

    private fun orderedBases(): List<String> = buildList {
        preferredBase?.let { add(it) }
        for (base in apiBases) if (!contains(base)) add(base)
    }

    private fun orderedProfiles(): List<RequestProfile> = buildList {
        preferredProfile?.let { add(it) }
        for (profile in officialProfiles) if (!contains(profile)) add(profile)
    }

    suspend fun getJson(path: String): JsonNode? {
        if (path.startsWith("http")) {
            for (profile in orderedProfiles()) {
                requestJson(path, profile, cf = true)?.let { return it }
            }
            val fallbackProfile = preferredProfile ?: officialProfiles.first()
            return requestJson(path, fallbackProfile, cf = false)
        }

        for (base in orderedBases()) {
            val url = "$base/${path.trimStart('/')}"
            for (profile in orderedProfiles()) {
                val result = requestJson(url, profile, cf = true)
                if (result != null) {
                    preferredBase = base
                    preferredProfile = profile
                    return result
                }
            }

            // Public endpoints that do not require Cf-Control get one conservative retry per API host.
            val fallbackProfile = preferredProfile ?: officialProfiles.first()
            val fallback = requestJson(url, fallbackProfile, cf = false)
            if (fallback != null) {
                preferredBase = base
                preferredProfile = fallbackProfile
                return fallback
            }
        }
        return null
    }

    private suspend fun requestJson(url: String, profile: RequestProfile, cf: Boolean): JsonNode? {
        return try {
            val response = app.get(url, headers = headers(cf = cf, profile = profile))
            if (!response.isSuccessful) return null
            runCatching { response.parsed<JsonNode>() }.getOrNull()
        } catch (_: Throwable) {
            null
        }
    }

    suspend fun firstJson(vararg paths: String): JsonNode? {
        for (path in paths) {
            val result = getJson(path)
            if (result != null) return result
        }
        return null
    }

    fun unwrap(node: JsonNode): JsonNode {
        var cur = node
        repeat(5) {
            val data = cur.get("data")
            val result = cur.get("result")
            val payload = cur.get("payload")
            val response = cur.get("response")
            cur = when {
                data?.isObject == true || data?.isArray == true -> data
                result?.isObject == true || result?.isArray == true -> result
                payload?.isObject == true || payload?.isArray == true -> payload
                response?.isObject == true || response?.isArray == true -> response
                else -> return cur
            }
        }
        return cur
    }

    fun text(node: JsonNode?, vararg names: String): String? {
        if (node == null) return null
        for (name in names) {
            val v = node.get(name) ?: continue
            if (v.isTextual && v.asText().isNotBlank()) return v.asText()
            if (v.isNumber) return v.asText()
        }
        return null
    }

    fun int(node: JsonNode?, vararg names: String): Int? =
        text(node, *names)?.let { Regex("-?\\d+").find(it)?.value?.toIntOrNull() }

    fun bool(node: JsonNode?, vararg names: String): Boolean? {
        if (node == null) return null
        for (name in names) {
            val v = node.get(name) ?: continue
            if (v.isBoolean) return v.asBoolean()
            if (v.isNumber) return v.asInt() != 0
            if (v.isTextual) {
                when (v.asText().trim().lowercase()) {
                    "true", "1", "yes" -> return true
                    "false", "0", "no", "null", "none" -> return false
                }
            }
        }
        return null
    }

    fun array(node: JsonNode?, vararg names: String): List<JsonNode> {
        if (node == null) return emptyList()
        for (name in names) {
            val v = node.get(name)
            if (v?.isArray == true) return v.toList()
        }
        return emptyList()
    }

    fun stringMap(node: JsonNode?, vararg names: String): Map<String, String> {
        if (node == null) return emptyMap()
        for (name in names) {
            val value = node.get(name) ?: continue
            if (!value.isObject) continue
            val result = linkedMapOf<String, String>()
            val fields = value.fields()
            while (fields.hasNext()) {
                val entry = fields.next()
                val fieldValue = entry.value
                if (fieldValue.isValueNode && !fieldValue.isNull) {
                    result[entry.key] = fieldValue.asText()
                }
            }
            if (result.isNotEmpty()) return result
        }
        return emptyMap()
    }

    fun findFirstArray(node: JsonNode): List<JsonNode> {
        if (node.isArray) return node.toList()
        if (!node.isContainerNode) return emptyList()
        val it = node.elements()
        while (it.hasNext()) {
            val found = findFirstArray(it.next())
            if (found.isNotEmpty() && found.any { child -> child.isObject }) return found
        }
        return emptyList()
    }
}
