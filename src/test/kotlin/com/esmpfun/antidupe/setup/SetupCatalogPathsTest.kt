package com.esmpfun.antidupe.setup

import org.bukkit.configuration.file.YamlConfiguration
import java.io.InputStreamReader
import kotlin.test.Test
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
    fun `every setup step has its text in messages yml`() {
        val messages = bundled("messages.yml")
        val strings = SetupCatalog.steps.flatMap { step ->
            listOf(step.titleKey) + ((step as? SetupStep.Choice)?.options?.map { it.labelKey } ?: emptyList())
        } + listOf(
            "setup.reminder", "setup.reminder-button", "setup.reminder-resume", "setup.reminder-resume-button",
            "setup.paused", "setup.stopped", "setup.usage", "setup.player-only", "setup.invalid-step",
            "setup.missing-option", "commands.help.setup-line",
        )
        strings.forEach { key -> assertTrue(messages.isString(key), "messages.yml has no text for '$key'") }
        val lists = SetupCatalog.steps.map { it.descKey } + listOf("setup.intro", "setup.complete")
        lists.forEach { key -> assertTrue(messages.getStringList(key).isNotEmpty(), "messages.yml has no lines for '$key'") }
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
