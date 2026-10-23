package org.fossify.messages.helpers.spam

/**
 * Detects commercial opt-out directives ("Reply STOP to end", "STOP2END", ...).
 * Ported from junkboy's Stop2EndDetector: stricter than a bare stop/end match, so ordinary text
 * such as "the bus stop is closed" never qualifies.
 */
object Stop2EndDetector {

    private const val VERBS = "reply|respond|text|txt|send"
    private const val KEYWORDS = "stop|end"
    private const val ACTIONS = "end|cancel|quit|unsubscribe"
    private const val QUOTE = "[\"'”’]?"

    // "Reply STOP", optionally followed by a short tail such as "to opt out"
    private val VERB_FORM =
        "(?:$VERBS)\\s+[\"'“‘]?(?:$KEYWORDS)$QUOTE(?:\\s*(?:to|2)\\s+[a-z]+(?:\\s+[a-z]+){0,2})?"

    // "STOP2END", "STOP to unsubscribe"
    private val BARE_FORM = "(?:$KEYWORDS)\\s*(?:2|to)\\s*(?:$ACTIONS)"

    // Only closing punctuation and whitespace may follow the directive
    private const val TRAILER = "[\\s.!?\"'”’)]*$"

    // Closes the message: a spam signal
    private val AT_END = Regex("\\b(?:$VERB_FORM|$BARE_FORM)$TRAILER", RegexOption.IGNORE_CASE)

    // Anywhere in the message: not a spam signal by itself
    private val ANYWHERE = Regex("\\b$BARE_FORM\\b", RegexOption.IGNORE_CASE)

    enum class Directive { NONE, ANYWHERE, AT_END }

    fun detect(body: String): Directive = when {
        AT_END.containsMatchIn(body) -> Directive.AT_END
        ANYWHERE.containsMatchIn(body) -> Directive.ANYWHERE
        else -> Directive.NONE
    }
}
