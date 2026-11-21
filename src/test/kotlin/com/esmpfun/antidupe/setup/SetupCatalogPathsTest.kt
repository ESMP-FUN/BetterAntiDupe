package com.esmpfun.antidupe.setup

import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Every setting the setup tour offers must exist in config.yml and have its text in messages.yml. */
class SetupCatalogPathsTest {

    private fun bundled(name: String): YamlConfiguration {
        val stream = javaClass.classLoader.getResourceAsStream(name)
        assertNotNull(stream, "$name should be on the classpath as a plugin resource")
        return YamlConfiguration.loadConfiguration(InputStreamReader(stream, Charsets.UTF_8))
    }

    @Test
    fun `every setup step points at a setting in config yml`() {
        val config = bundled("config.yml")
        val paths = SetupCatalog.steps.map { it.configPath }
        assertTrue(paths.size in 8..18, "the tour should offer 8 to 18 settings, not ${paths.size}")
        paths.forEach { path ->
            assertTrue(config.contains(path), "setup offers '$path', which config.yml does not have")
        }
        assertTrue(config.contains("setup.reminder.enabled"), "config.yml should have setup.reminder.enabled")
    }

    @Test
    fun `every toggle default matches config yml`() {
        val config = bundled("config.yml")
        SetupCatalog.steps.filterIsInstance<SetupStep.Toggle>().forEach { step ->
            assertTrue(config.getBoolean(step.configPath) == step.default, "'${step.configPath}' ships as ${config.get(step.configPath)}, the tour says ${step.default}")
        }
    }

    @Test
    fun `language is the first setup step`() {
        assertEquals("language", SetupCatalog.steps.first().id)
    }

    @Test
    fun `every setup step has its text in messages yml`() {
        val messages = bundled("messages.yml")
        val uiKeys = listOf(
            "dialog-title", "chat-header", "complete-title", "currently", "on", "off",
            "turn-on", "turn-off", "turn-on-hover", "turn-off-hover", "use-hover",
            "back", "back-hover", "skip", "skip-hover", "pause", "pause-hover",
            "stop", "stop-hover", "done", "cpu-use", "requires-plugin-button",
            "reminder-hover", "not-set", "default-suffix", "value-no-limit",
            "value-one-stack", "value-items", "value-1-minute", "value-minutes",
            "value-seconds", "severity-critical", "severity-high", "severity-medium",
            "severity-low", "creative-record", "creative-alert",
        ).map { "setup.ui.$it" }
        val strings = SetupCatalog.steps.flatMap { step ->
            listOf(step.titleKey) + ((step as? SetupStep.Choice)?.options?.map { it.labelKey } ?: emptyList())
        } + listOf(
            "setup.reminder", "setup.reminder-button", "setup.reminder-resume", "setup.reminder-resume-button",
            "setup.paused", "setup.stopped", "setup.usage", "setup.player-only", "setup.invalid-step",
            "setup.missing-option", "setup.requires-plugin", "commands.help.setup-line",
        ) + uiKeys
        strings.forEach { key -> assertTrue(messages.isString(key), "messages.yml has no text for '$key'") }
        val lists = SetupCatalog.steps.map { it.descKey } + listOf("setup.intro", "setup.complete")
        lists.forEach { key -> assertTrue(messages.getStringList(key).isNotEmpty(), "messages.yml has no lines for '$key'") }
    }

    @Test
    fun `every bundled translation covers all setup keys`() {
        val english = bundled("messages.yml")
        val setupKeys = english.getKeys(true).filter { it.startsWith("setup.") && !english.isConfigurationSection(it) }
        assertTrue(setupKeys.isNotEmpty())
        val translations = listOf("messages_de.yml", "messages_es.yml", "messages_pl.yml", "messages_pt_br.yml", "messages_ru.yml")
        for (file in translations) {
            val translated = bundled(file)
            for (key in setupKeys) {
                if (english.isList(key)) {
                    assertTrue(translated.getStringList(key).isNotEmpty(), "$file is missing list '$key'")
                } else {
                    assertTrue(translated.isString(key), "$file is missing string '$key'")
                }
            }
        }
    }

    @Test
    fun `changing every setup setting keeps config yml's comments`() {
        val config = bundled("config.yml")
        val before = commentsOf(javaClass.classLoader.getResource("config.yml")!!.readText())
        SetupCatalog.steps.forEach { step ->
            when (step) {
                is SetupStep.Toggle -> config.set(step.configPath, !step.default)
                is SetupStep.Choice -> config.set(step.configPath, step.options.last().value)
            }
        }
        val after = commentsOf(config.saveToString())
        assertTrue(before.isNotEmpty())
        assertTrue(after.containsAll(before), "comments lost: ${before - after.toSet()}")
    }

    private fun commentsOf(yaml: String): List<String> =
        yaml.lines().mapNotNull { line -> line.indexOf('#').takeIf { it >= 0 }?.let { line.substring(it).trim() } }
}
