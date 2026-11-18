package com.esmpfun.antidupe.setup

/** A rough CPU cost for a setting, measured against a server with a few dozen players. */
enum class CpuImpact(val text: String, val color: String) {
    TINY("tiny", "&f"),
    LITTLE("little", "&a"),
    MEDIUM("medium", "&e"),
    HIGH("high", "&c"),
}

/**
 * One setting in the `/adp setup` tour. Both renderers read the same catalog; the text lives
 * in messages.yml under `setup.steps.<id>.title` and `.desc`.
 */
sealed class SetupStep {
    abstract val id: String
    abstract val configPath: String
    abstract val cpuImpact: CpuImpact?

    val titleKey: String get() = "setup.steps.$id.title"
    val descKey: String get() = "setup.steps.$id.desc"

    /** A true/false setting: Enable, Disable, or skip. */
    class Toggle(
        override val id: String,
        override val configPath: String,
        val default: Boolean,
        override val cpuImpact: CpuImpact? = null,
        /** When set and that plugin is absent, Enable is shown greyed out. */
        val requiresPlugin: String? = null,
    ) : SetupStep()

    /** A pick-one setting. Skip keeps the current value. */
    class Choice(
        override val id: String,
        override val configPath: String,
        val options: List<ChoiceOption>,
        override val cpuImpact: CpuImpact? = null,
        /** Turns the stored value into the "Currently: ..." text. */
        val formatCurrent: ((Any?) -> String)? = null,
    ) : SetupStep()
}

/** One option of a [SetupStep.Choice]; [optionId] is what `/adp setup set <step> <optionId>` names. */
data class ChoiceOption(
    val optionId: String,
    val labelKey: String,
    val value: Any,
)
