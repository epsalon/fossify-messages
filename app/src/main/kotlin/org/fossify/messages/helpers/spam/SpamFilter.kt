package org.fossify.messages.helpers.spam

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.fossify.messages.helpers.Config
import org.fossify.messages.messaging.getSmsManager
import java.security.MessageDigest

/**
 * Decides whether an incoming message is spam, and whether to answer with STOP.
 *
 * Only messages with a Stop2End opt-out directive can be blocked. Everything else always notifies.
 * For a directive message: the local model at P(spam) >= 0.9 decides alone; otherwise the span-02
 * second opinion decides, unless the sender is a contact or no key is set. Any failure notifies.
 */
object SpamFilter {

    private const val TAG = "SpamFilter"
    const val LOCAL_SPAM_CUT = 0.9
    const val LLM_SPAM_CUT = 0.7
    const val STOP_TEXT = "STOP"
    const val STOP_COOLDOWN_MS = 24L * 60 * 60 * 1000

    private val VALID_TARGET = Regex("^\\+?\\d{3,15}$")
    private val FORMATTING = Regex("[\\s\\-().]")

    // Verification or one-time codes: never touched, whatever else the message says
    private val CODE_MESSAGE = Regex(
        "\\b(otp|one[- ]time|verification|passcode|security code|code)\\b.{0,40}?\\b\\d{4,8}\\b",
        RegexOption.IGNORE_CASE
    )

    enum class Verdict { SPAM, NOTIFY }

    data class Result(val verdict: Verdict, val reason: String, val stopSent: Boolean)

    private data class Outcome(
        val verdict: Verdict,
        val reason: String,
        val localSpam: Double? = null,
        val llmSpam: Double? = null,
        val llmWanted: Double? = null
    )

    @Volatile
    private var loaded = false

    @Volatile
    private var classifier: LocalSpamClassifier? = null

    private fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            Redactor.load(context)
            classifier = LocalSpamClassifier.fromAsset(context)
            loaded = true
        }
    }

    /** Blocking; call from a background thread. Never throws: any error notifies the user. */
    fun evaluate(
        context: Context,
        sender: String,
        body: String,
        isContact: Boolean,
        subscriptionId: Int
    ): Result {
        val config = Config.newInstance(context)
        val directive = Stop2EndDetector.detect(body)
        val outcome = try {
            ensureLoaded(context)
            decide(context, config, sender, body, isContact, directive)
        } catch (e: Exception) {
            Log.e(TAG, "Spam decision failed, notifying", e)
            Outcome(Verdict.NOTIFY, "error: ${e.message}")
        }

        var stopSent = false
        if (outcome.verdict == Verdict.SPAM && directive != Stop2EndDetector.Directive.NONE && config.autoStopEnabled) {
            stopSent = sendStop(context, sender, subscriptionId)
        }

        runCatching {
            SpamDatabase.get(context).logDecision(
                SpamDatabase.Decision(
                    created = System.currentTimeMillis(),
                    sender = sender,
                    body = body,
                    directive = directive.name,
                    localSpam = outcome.localSpam,
                    llmSpam = outcome.llmSpam,
                    llmWanted = outcome.llmWanted,
                    verdict = outcome.verdict.name,
                    reason = outcome.reason,
                    stopSent = stopSent
                )
            )
        }.onFailure { Log.e(TAG, "Could not write decision log", it) }

        return Result(outcome.verdict, outcome.reason, stopSent)
    }

    private fun decide(
        context: Context,
        config: Config,
        sender: String,
        body: String,
        isContact: Boolean,
        directive: Stop2EndDetector.Directive
    ): Outcome {
        if (!config.spamFilterEnabled) return Outcome(Verdict.NOTIFY, "filter disabled")
        if (directive == Stop2EndDetector.Directive.NONE) return Outcome(Verdict.NOTIFY, "no opt-out directive")
        if (CODE_MESSAGE.containsMatchIn(body)) return Outcome(Verdict.NOTIFY, "code message")

        val redacted = Redactor.redact(body)
        val localSpam = redacted?.let { classifier?.probabilities(it)?.get("S") }
        if (localSpam != null && localSpam >= LOCAL_SPAM_CUT) {
            return Outcome(Verdict.SPAM, "local model P(spam)=%.2f".format(localSpam), localSpam)
        }

        if (isContact) return Outcome(Verdict.NOTIFY, "contact: no second opinion", localSpam)
        if (config.openRouterApiKey.isBlank()) return Outcome(Verdict.NOTIFY, "no second opinion key", localSpam)
        if (redacted == null) return Outcome(Verdict.NOTIFY, "redaction rules missing: not sent", localSpam)

        val (spam, wanted) = try {
            secondOpinion(context, config.openRouterApiKey, config.openRouterDecisionModel, sender, redacted)
        } catch (e: Exception) {
            Log.w(TAG, "Second opinion failed, notifying: ${e.message}")
            return Outcome(Verdict.NOTIFY, "second opinion failed: ${e.message}", localSpam)
        }
        val verdict = if (spam - wanted >= LLM_SPAM_CUT) Verdict.SPAM else Verdict.NOTIFY
        return Outcome(
            verdict = verdict,
            reason = "second opinion score %+.2f".format(spam - wanted),
            localSpam = localSpam,
            llmSpam = spam,
            llmWanted = wanted
        )
    }

    /** Looks up the answer in the cache first, so a repeated message never costs a second paid call. */
    private fun secondOpinion(
        context: Context,
        apiKey: String,
        model: String,
        sender: String,
        redacted: String
    ): Pair<Double, Double> {
        val payload = OpenRouterDecisions.buildPayload(model, sender, redacted)
        val key = sha256(model + "\n" + payload.toString())
        val db = SpamDatabase.get(context)
        db.cachedAnswer(key)?.let { return it }
        val answer = OpenRouterDecisions.call(apiKey, payload)
        db.storeAnswer(key, answer.first, answer.second)
        return answer
    }

    @Synchronized
    private fun sendStop(context: Context, sender: String, subscriptionId: Int): Boolean {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        // Only real numbers and numeric short codes can receive a reply
        val target = sender.replace(FORMATTING, "").takeIf { VALID_TARGET.matches(it) } ?: return false
        val db = SpamDatabase.get(context)
        val now = System.currentTimeMillis()
        val last = db.lastStopReply(target)
        if (last != null && now - last < STOP_COOLDOWN_MS) return false
        return try {
            getSmsManager(subscriptionId).sendTextMessage(target, null, STOP_TEXT, null, null)
            db.recordStopReply(target, now)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send STOP reply", e)
            false
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
