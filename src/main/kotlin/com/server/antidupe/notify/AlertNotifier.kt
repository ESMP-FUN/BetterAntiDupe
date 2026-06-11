package com.server.antidupe.notify

import com.server.antidupe.ledger.DupeAlert
import com.server.antidupe.ledger.Severity
import com.server.antidupe.util.Messages
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.bukkit.configuration.ConfigurationSection
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.logging.Logger

/**
 * Pushes dupe alerts to external services (Discord, Telegram, Slack, or any
 * generic JSON webhook). Configured under `notifications:` in config.yml.
 *
 * Design constraints:
 *  - Fire-and-forget: delivery runs async on the IO dispatcher and can never
 *    block the server thread or the alert pipeline.
 *  - Storm-proof: alerts are rate-limited per (player, type, material) so a
 *    burst of identical detections sends one notification, not hundreds.
 *  - Quiet failure: an unreachable webhook logs a warning at most once per
 *    minute per target instead of spamming the console.
 *  - Text reuses the messages.yml translations with colour codes stripped,
 *    so notifications follow the server's language.
 */
class AlertNotifier(
    config: ConfigurationSection?,
    private val scope: CoroutineScope,
    private val logger: Logger
) {

    private val minSeverity: Severity = config?.getString("min_severity", "HIGH")
        ?.uppercase()?.let { runCatching { Severity.valueOf(it) }.getOrNull() } ?: Severity.HIGH
    private val rateLimitMs: Long = (config?.getLong("rate_limit_seconds", 30L) ?: 30L) * 1000L

    private val discordUrl = config.enabledUrl("discord", "webhook_url")
    private val slackUrl = config.enabledUrl("slack", "webhook_url")
    private val genericUrl = config.enabledUrl("generic", "url")
    private val telegramToken: String?
    private val telegramChatId: String?

    init {
        val tg = config?.getConfigurationSection("telegram")
        val tgEnabled = tg?.getBoolean("enabled", false) == true
        telegramToken = if (tgEnabled) tg?.getString("bot_token")?.takeIf { it.isNotBlank() } else null
        telegramChatId = if (tgEnabled) tg?.getString("chat_id")?.takeIf { it.isNotBlank() } else null

        val targets = listOfNotNull(
            discordUrl?.let { "Discord" },
            telegramToken?.let { "Telegram" },
            slackUrl?.let { "Slack" },
            genericUrl?.let { "generic webhook" }
        )
        if (targets.isNotEmpty()) {
            logger.info("[Notify] Alert notifications enabled: ${targets.joinToString(", ")} (min severity $minSeverity)")
        }
    }

    private fun ConfigurationSection?.enabledUrl(section: String, key: String): String? {
        val s = this?.getConfigurationSection(section) ?: return null
        if (!s.getBoolean("enabled", false)) return null
        return s.getString(key)?.takeIf { it.isNotBlank() }
    }

    private val anyEnabled =
        discordUrl != null || slackUrl != null || genericUrl != null ||
        (telegramToken != null && telegramChatId != null)

    private val httpClient: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()
    }

    /** (player|type|material) -> last send time, for the burst rate limit. */
    private val lastSent = ConcurrentHashMap<String, Long>()

    /** target name -> last failure log time (warn at most once per minute per target). */
    private val lastFailureLog = ConcurrentHashMap<String, Long>()

    fun handle(alert: DupeAlert) {
        if (!anyEnabled) return
        if (alert.severity.ordinal < minSeverity.ordinal) return

        // Burst control: one notification per (player, type, material) per window.
        val key = "${alert.player}|${alert.type}|${alert.material}"
        val now = System.currentTimeMillis()
        var allowed = false
        lastSent.compute(key) { _, prev ->
            if (prev == null || now - prev >= rateLimitMs) { allowed = true; now } else prev
        }
        if (!allowed) return
        if (lastSent.size > 1000) lastSent.entries.removeIf { now - it.value >= rateLimitMs }

        val text = plainText(alert)
        scope.launch {
            discordUrl?.let { post("Discord", it, discordPayload(alert, text)) }
            slackUrl?.let { post("Slack", it, JSONObject().put("text", text).toString()) }
            if (telegramToken != null && telegramChatId != null) {
                post("Telegram", "https://api.telegram.org/bot$telegramToken/sendMessage",
                    JSONObject().put("chat_id", telegramChatId).put("text", text).toString())
            }
            genericUrl?.let { post("generic webhook", it, genericPayload(alert)) }
        }
    }

    /** Human-readable single line, using the server's messages.yml language, colours stripped. */
    private fun plainText(alert: DupeAlert): String {
        val details = if (alert.messageKey.isNotEmpty())
            Messages.msg(alert.messageKey, alert.placeholders) else alert.details
        return stripColors("[DUPE] ${alert.playerName} (${alert.type.name}) ${alert.material.name}: $details [${alert.severity.name}]")
    }

    private fun stripColors(s: String): String = s.replace(COLOR_CODES, "")

    private fun discordPayload(alert: DupeAlert, text: String): String {
        val color = when (alert.severity) {
            Severity.CRITICAL -> 0xE74C3C
            Severity.HIGH -> 0xE67E22
            Severity.MEDIUM -> 0xF1C40F
            Severity.LOW -> 0x95A5A6
        }
        val embed = JSONObject()
            .put("title", "AntiDupePro alert")
            .put("description", text)
            .put("color", color)
        return JSONObject().put("embeds", JSONArray().put(embed)).toString()
    }

    /** Full machine-readable alert for custom integrations (n8n, Zapier, home-grown bots). */
    private fun genericPayload(alert: DupeAlert): String = JSONObject()
        .put("plugin", "AntiDupePro")
        .put("type", alert.type.name)
        .put("severity", alert.severity.name)
        .put("player", alert.playerName)
        .put("playerUuid", alert.player.toString())
        .put("material", alert.material.name)
        .put("details", alert.details)
        .put("timestamp", alert.timestamp)
        .toString()

    private suspend fun post(target: String, url: String, body: String) = withContext(Dispatchers.IO) {
        try {
            val request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build()
            val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
            if (response.statusCode() !in 200..299) {
                warnThrottled(target, "HTTP ${response.statusCode()}")
            }
        } catch (e: Exception) {
            warnThrottled(target, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun warnThrottled(target: String, reason: String) {
        val now = System.currentTimeMillis()
        var log = false
        lastFailureLog.compute(target) { _, prev ->
            if (prev == null || now - prev >= 60_000L) { log = true; now } else prev
        }
        if (log) logger.warning("[Notify] $target notification failed: $reason")
    }

    private companion object {
        private val COLOR_CODES = Regex("[§&][0-9a-fk-orx]", RegexOption.IGNORE_CASE)
    }
}
