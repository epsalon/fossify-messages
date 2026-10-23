package org.fossify.messages.helpers.spam

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/** A decision model from OpenRouter's public catalog, priced in dollars per million input tokens. */
data class DecisionModel(val id: String, val name: String, val inputPerMillion: Double) {
    val priceLabel: String
        get() = if (inputPerMillion == 0.0) "free" else String.format(Locale.US, "$%.3f per M input tokens", inputPerMillion)
}

/** Lists the decision models. The catalog is public, so no key is needed and nothing is charged. */
object OpenRouterCatalog {

    // "all" so that decision models, which return scores instead of text, are included
    private const val ENDPOINT = "https://openrouter.ai/api/v1/models?output_modalities=all"

    fun decisionModels(): List<DecisionModel> {
        val connection = URL(ENDPOINT).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5_000
            connection.readTimeout = 10_000
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return parse(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    /** Keeps models that take text and return only decision scores, with a fixed price. Sorted by id. */
    fun parse(json: String): List<DecisionModel> {
        val data = JSONObject(json).optJSONArray("data") ?: return emptyList()
        val models = ArrayList<DecisionModel>()
        for (i in 0 until data.length()) {
            val model = data.getJSONObject(i)
            val id = model.optString("id").takeIf { it.isNotEmpty() } ?: continue
            val architecture = model.optJSONObject("architecture") ?: continue
            val outputs = architecture.optJSONArray("output_modalities") ?: continue
            if (outputs.length() != 1 || outputs.optString(0) != "decisions") continue
            val inputs = architecture.optJSONArray("input_modalities") ?: continue
            if ((0 until inputs.length()).none { inputs.optString(it) == "text" }) continue
            val pricing = model.optJSONObject("pricing") ?: continue
            val input = pricing.optString("prompt").toDoubleOrNull() ?: continue
            if (pricing.optString("completion").toDoubleOrNull() == null || input < 0) continue
            models += DecisionModel(id, model.optString("name", id), input * 1_000_000)
        }
        return models.sortedBy { it.id }
    }
}
