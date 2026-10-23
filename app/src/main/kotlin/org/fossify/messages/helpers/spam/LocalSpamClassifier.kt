package org.fossify.messages.helpers.spam

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Logistic regression over TF-IDF features, exported from scikit-learn (`assets/spam_model/model.json`).
 * It reproduces the training pipeline: lowercase, word and bigram tokens, sublinear tf, idf weights,
 * L2 normalization, then a softmax over the class scores. Input must already be redacted.
 */
class LocalSpamClassifier private constructor(
    private val classes: List<String>,
    private val vocab: Map<String, Int>,
    private val idf: DoubleArray,
    private val coef: Array<DoubleArray>,
    private val intercept: DoubleArray
) {

    fun probabilities(redacted: String): Map<String, Double> {
        val words = ArrayList<String>()
        val matcher = TOKEN.matcher(redacted.lowercase(Locale.ROOT))
        while (matcher.find()) words.add(matcher.group())

        val counts = HashMap<Int, Int>()
        for (term in words + words.zipWithNext { a, b -> "$a $b" }) {
            val index = vocab[term] ?: continue
            counts[index] = (counts[index] ?: 0) + 1
        }
        val weighted = counts.map { (index, count) -> index to (1 + ln(count.toDouble())) * idf[index] }
        val norm = sqrt(weighted.sumOf { it.second * it.second })

        val scores = DoubleArray(classes.size) { c ->
            var score = intercept[c]
            if (norm > 0) for ((index, value) in weighted) score += coef[c][index] * (value / norm)
            score
        }
        val top = scores.max()
        val exps = scores.map { exp(it - top) }
        val total = exps.sum()
        return classes.indices.associate { classes[it] to exps[it] / total }
    }

    companion object {
        // Same token pattern as the training run (\b[\w<>']+\b with Unicode classes). Android's regex
        // engine rejects Pattern.UNICODE_CHARACTER_CLASS, so the Unicode letter, number and mark classes are spelled out.
        private val TOKEN: Pattern = Pattern.compile("\\b[\\p{L}\\p{N}\\p{M}_<>']+\\b")
        private const val ASSET = "spam_model/model.json"

        fun fromAsset(context: Context): LocalSpamClassifier? = runCatching {
            val json = JSONObject(context.assets.open(ASSET).bufferedReader().use { it.readText() })
            val vocabJson = json.getJSONObject("vocab")
            val vocab = vocabJson.keys().asSequence().associateWith { vocabJson.getInt(it) }
            val coefJson: JSONArray = json.getJSONArray("coef")
            LocalSpamClassifier(
                classes = json.getJSONArray("classes").let { a -> (0 until a.length()).map { a.getString(it) } },
                vocab = vocab,
                idf = json.getJSONArray("idf").toDoubleArray(),
                coef = Array(coefJson.length()) { coefJson.getJSONArray(it).toDoubleArray() },
                intercept = json.getJSONArray("intercept").toDoubleArray()
            )
        }.getOrNull()

        private fun JSONArray.toDoubleArray() = DoubleArray(length()) { getDouble(it) }
    }
}
