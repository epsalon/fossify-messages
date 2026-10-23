package org.fossify.messages.helpers.spam

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Checks an API key against OpenRouter's account endpoint. It reads account data and calls no model, so it costs nothing. */
object OpenRouterAccount {

    private const val ENDPOINT = "https://openrouter.ai/api/v1/auth/key"

    /** [remaining] is the dollars left on a limited key, or null when the key has no limit. */
    data class KeyInfo(val label: String, val remaining: Double?)

    /** Throws [IOException] when the key is rejected or the request fails. */
    fun verify(apiKey: String): KeyInfo {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 10_000
            setRequestProperty("Authorization", "Bearer $apiKey")
        }
        try {
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val data = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).getJSONObject("data")
            val remaining = data.optDouble("limit_remaining", Double.NaN).takeIf { !it.isNaN() }
            return KeyInfo(label = data.optString("label", "key"), remaining = remaining)
        } finally {
            connection.disconnect()
        }
    }
}
