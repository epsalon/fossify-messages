package org.fossify.messages.helpers.spam

import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Second opinion from an OpenRouter decision model (alpha Decisions API). The model answers two yes/no
 * questions with a probability each; the verdict uses the difference between the two.
 * Questions and wording match the holdout and tagging runs, so the thresholds carry over.
 */
object OpenRouterDecisions {

    const val DEFAULT_MODEL = "respan/span-02"
    private const val ENDPOINT = "https://openrouter.ai/api/alpha/decisions"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val READ_TIMEOUT_MS = 5_000

    private val SHORT_CODE = Regex("\\d{1,6}")

    private fun question(instructions: String, whenTrue: String, whenFalse: String) = JSONObject()
        .put("type", "noul")
        .put("instructions", instructions)
        .put(
            "criteria",
            JSONObject().put("true", whenTrue).put("false", whenFalse)
        )

    fun senderKind(sender: String): String = when {
        SHORT_CODE.matches(sender) -> "short code"
        sender.startsWith("+") -> "phone number"
        else -> "name or alphanumeric id"
    }

    /** The request body. It carries the sender kind and the redacted text, never the raw number. */
    fun buildPayload(model: String, sender: String, redacted: String): JSONObject = JSONObject()
        .put("model", model)
        .put(
            "questions",
            JSONObject()
                .put(
                    "is_spam",
                    question(
                        "A text message was received by one person from a sender they have never texted. " +
                            "Is it unsolicited bulk messaging?",
                        "The sender is trying to get something from the person: money, votes, signatures, " +
                            "survey answers, a purchase, a click on a promotion, or a job. Includes political " +
                            "and advocacy campaigns, fundraising, petitions, polls, marketing, recruiting and scams.",
                        "The message is not an attempt to get money, votes, signatures, survey answers, " +
                            "purchases or clicks from the person."
                    )
                )
                .put(
                    "is_wanted",
                    question(
                        "A text message was received by one person from a sender they have never texted. " +
                            "Is it a message about something the person set up or did?",
                        "An account, service or transaction message: welcome or sign-up confirmation, login or " +
                            "verification code, appointment or health care, bank or card notice, delivery, " +
                            "receipt, order update, waitlist or reservation. Wanted even if it mentions STOP, " +
                            "HELP or message and data rates.",
                        "The message is not about an account, service, appointment, delivery or transaction " +
                            "the person set up or did."
                    )
                )
        )
        .put("state", "Sender: ${senderKind(sender)}\nMessage: $redacted")

    /** Returns (spam score, wanted score). */
    fun parseScores(json: String): Pair<Double, Double> {
        val answers = JSONObject(json).optJSONObject("answers")
            ?: throw IOException("Reply had no answers")
        val spam = answers.optJSONObject("is_spam")?.optDouble("noul", Double.NaN)
        val wanted = answers.optJSONObject("is_wanted")?.optDouble("noul", Double.NaN)
        if (spam == null || wanted == null || spam.isNaN() || wanted.isNaN()) {
            throw IOException("Reply had no scores")
        }
        return spam to wanted
    }

    fun call(apiKey: String, payload: JSONObject): Pair<Double, Double> {
        val connection = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }
        try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return parseScores(body)
        } finally {
            connection.disconnect()
        }
    }
}
