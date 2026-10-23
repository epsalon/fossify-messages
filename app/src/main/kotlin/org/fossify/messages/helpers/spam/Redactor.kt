package org.fossify.messages.helpers.spam

import android.content.Context
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Removes numbers, amounts, codes, URLs and the user's own names before text leaves the device or
 * reaches the local model. The rules come from `assets/spam_model/redaction.json`, which is kept out of git
 * because it contains personal names. Without that file nothing is redacted, and nothing is sent remotely.
 */
object Redactor {

    private class Rule(val pattern: Pattern, val replacement: String)

    @Volatile
    private var rules: List<Rule>? = null

    fun load(context: Context) {
        rules = runCatching {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            val list = JSONObject(json).getJSONArray("rules")
            (0 until list.length()).map { i ->
                val rule = list.getJSONArray(i)
                // Android's regex engine rejects Pattern.UNICODE_CHARACTER_CLASS, so the flags stop there
                var flags = 0
                if (rule.getString(2).contains("i")) flags = flags or Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE
                Rule(Pattern.compile(rule.getString(0), flags), rule.getString(1))
            }
        }.getOrNull()
    }

    fun isReady(): Boolean = rules != null

    /** Returns the redacted text, or null when the rules are not loaded (callers must not send the text then). */
    fun redact(text: String): String? = rules?.fold(text) { acc, rule ->
        rule.pattern.matcher(acc).replaceAll(rule.replacement)
    }

    private const val ASSET = "spam_model/redaction.json"
}
