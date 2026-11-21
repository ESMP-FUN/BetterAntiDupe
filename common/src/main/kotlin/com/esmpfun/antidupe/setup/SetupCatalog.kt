package com.esmpfun.antidupe.setup

import com.esmpfun.antidupe.util.Messages

/**
 * The settings `/adp setup` walks through, in order: language first so the rest of the tour
 * shows in the admin's chosen language, then taking items back (watch-only first, as the
 * take-back switch depends on it) and its limits, then alerts, then the rest. Every one is
 * applied by the same reload as `/adp reload`, so a change works straight away. To add a step,
 * add it here and its `setup.steps.<id>` text in messages.yml; `SetupCatalogPathsTest` checks
 * the path exists.
 */
object SetupCatalog {

    private val severities = listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")

    val steps: List<SetupStep> = listOf(
        SetupStep.Choice(
            "language", "language",
            options = listOf("en", "pt_BR", "es", "de", "ru", "pl").map { ChoiceOption(it.lowercase(), "setup.steps.language.options.${it.lowercase()}", it) },
            formatCurrent = { language(it) },
        ),
        SetupStep.Toggle("watch-only", "shadow_mode", default = true),
        SetupStep.Toggle("take-back", "auto_delete_dupes", default = false),
        SetupStep.Choice(
            "take-back-sure", "enforcement.min_severity",
            options = severities.map { ChoiceOption(it.lowercase(), "setup.steps.take-back-sure.options.${it.lowercase()}", it) },
            formatCurrent = { severity(it, "HIGH", "take-back-sure") },
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
            formatCurrent = { severity(it, "HIGH", "alert-level") },
        ),
        SetupStep.Choice(
            "alert-gap", "notifications.rate_limit_seconds",
            options = listOf(10, 30, 60, 300).map { ChoiceOption("$it", "setup.steps.alert-gap.options.$it", it) },
            formatCurrent = { seconds(it) },
        ),
        SetupStep.Choice(
            "creative", "leaving_creative_mode",
            options = listOf("RECORD", "ALERT").map { ChoiceOption(it.lowercase(), "setup.steps.creative.options.${it.lowercase()}", it) },
            formatCurrent = { creative(it) },
        ),
        SetupStep.Toggle("shutdown", "prevent-shutdown-dupers", default = true),
    )

    fun stepAt(index: Int): SetupStep? = steps.getOrNull(index)
    val count: Int get() = steps.size

    /** Uses the step's own button label, so "Currently" names one of the buttons. */
    private fun severity(value: Any?, default: String, step: String): String {
        val v = (value as? String)?.uppercase() ?: return Messages.msg("setup.ui.not-set")
        val text = if (v in severities) Messages.msg("setup.steps.$step.options.${v.lowercase()}") else v
        return if (v == default) Messages.msg("setup.ui.default-suffix", "value" to text) else text
    }

    private fun limit(value: Any?): String {
        val n = (value as? Number)?.toInt() ?: return Messages.msg("setup.ui.not-set")
        return when {
            n <= 0 -> Messages.msg("setup.ui.default-suffix", "value" to Messages.msg("setup.ui.value-no-limit"))
            n == 64 -> Messages.msg("setup.ui.value-one-stack")
            else -> Messages.msg("setup.ui.value-items", "count" to n)
        }
    }

    private fun seconds(value: Any?): String {
        val s = (value as? Number)?.toInt() ?: return Messages.msg("setup.ui.not-set")
        val text = when {
            s >= 60 && s % 60 == 0 -> {
                val m = s / 60
                if (m == 1) Messages.msg("setup.ui.value-1-minute")
                else Messages.msg("setup.ui.value-minutes", "count" to m)
            }
            else -> Messages.msg("setup.ui.value-seconds", "count" to s)
        }
        return if (s == 30) Messages.msg("setup.ui.default-suffix", "value" to text) else text
    }

    private fun creative(value: Any?): String = when ((value as? String)?.uppercase()) {
        null -> Messages.msg("setup.ui.not-set")
        "RECORD" -> Messages.msg("setup.ui.default-suffix", "value" to Messages.msg("setup.ui.creative-record"))
        else -> Messages.msg("setup.ui.creative-alert")
    }

    private fun language(value: Any?): String = when ((value as? String)?.lowercase()?.replace('-', '_')) {
        "en", null -> "English"
        "pt_br" -> "Português"
        "es" -> "Español"
        "de" -> "Deutsch"
        "ru" -> "Русский"
        "pl" -> "Polski"
        else -> value
    }
}
