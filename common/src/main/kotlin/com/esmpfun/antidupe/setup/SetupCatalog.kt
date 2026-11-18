package com.esmpfun.antidupe.setup

/**
 * The settings `/adp setup` walks through, in order: taking items back (watch-only first, as
 * the take-back switch depends on it) and its limits, then alerts, then the rest. Every one is
 * applied by the same reload as `/adp reload`, so a change works straight away. To add a step,
 * add it here and its `setup.steps.<id>` text in messages.yml; `SetupCatalogPathsTest` checks
 * the path exists.
 */
object SetupCatalog {

    private val severities = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")

    val steps: List<SetupStep> = listOf(
        SetupStep.Toggle("watch-only", "shadow_mode", default = true),
        SetupStep.Toggle("take-back", "auto_delete_dupes", default = false),
        SetupStep.Choice(
            "take-back-sure", "enforcement.min_severity",
            options = severities.map { ChoiceOption(it.lowercase(), "setup.steps.take-back-sure.options.${it.lowercase()}", it) },
            formatCurrent = { severity(it, "HIGH") },
        ),
        SetupStep.Choice(
            "take-back-limit", "enforcement.max_items_per_action",
            options = listOf(0, 16, 64, 256).map { ChoiceOption("$it", "setup.steps.take-back-limit.options.$it", it) },
            formatCurrent = { limit(it) },
        ),
        SetupStep.Toggle("tell-player", "enforcement.notify_player", default = true),

        SetupStep.Choice(
            "alert-level", "notifications.min_severity",
            options = severities.reversed().map { ChoiceOption(it.lowercase(), "setup.steps.alert-level.options.${it.lowercase()}", it) },
            formatCurrent = { severity(it, "HIGH") },
        ),
        SetupStep.Choice(
            "alert-gap", "notifications.rate_limit_seconds",
            options = listOf(10, 30, 60, 300).map { ChoiceOption("$it", "setup.steps.alert-gap.options.$it", it) },
            formatCurrent = { seconds(it) },
        ),
        SetupStep.Choice(
            "creative", "leaving_creative_mode",
            options = listOf("RECORD", "ALERT").map { ChoiceOption(it.lowercase(), "setup.steps.creative.options.${it.lowercase()}", it) },
            formatCurrent = { (it as? String)?.uppercase()?.let { v -> if (v == "RECORD") "Only note it down (default)" else "Also alert staff" } ?: "not set" },
        ),
        SetupStep.Toggle("shutdown", "prevent-shutdown-dupers", default = true),
        SetupStep.Choice(
            "language", "language",
            options = listOf("en", "pt_BR", "es", "de", "ru", "pl").map { ChoiceOption(it.lowercase(), "setup.steps.language.options.${it.lowercase()}", it) },
            formatCurrent = { language(it) },
        ),
    )

    fun stepAt(index: Int): SetupStep? = steps.getOrNull(index)
    val count: Int get() = steps.size

    private fun severity(value: Any?, default: String): String {
        val v = (value as? String)?.uppercase() ?: return "not set"
        val text = when (v) {
            "CRITICAL" -> "Only when it is certain"
            "HIGH" -> "Strong evidence"
            "MEDIUM" -> "A clear surplus"
            "LOW" -> "Anything at all"
            else -> v
        }
        return if (v == default) "$text (default)" else text
    }

    private fun limit(value: Any?): String {
        val n = (value as? Number)?.toInt() ?: return "not set"
        return when {
            n <= 0 -> "No limit (default)"
            n == 64 -> "One stack (64 items)"
            else -> "$n items"
        }
    }

    private fun seconds(value: Any?): String {
        val s = (value as? Number)?.toInt() ?: return "not set"
        val text = if (s >= 60 && s % 60 == 0) (if (s == 60) "1 minute" else "${s / 60} minutes") else "$s seconds"
        return if (s == 30) "$text (default)" else text
    }

    private fun language(value: Any?): String = when ((value as? String)?.lowercase()?.replace('-', '_')) {
        "en", null -> "English"
        "pt_br" -> "Portuguese (Brazil)"
        "es" -> "Spanish"
        "de" -> "German"
        "ru" -> "Russian"
        "pl" -> "Polish"
        else -> value
    }
}
