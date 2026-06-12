package com.server.antidupe.util

import org.bukkit.configuration.file.FileConfiguration
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.plugin.java.JavaPlugin
import java.io.File
import java.io.InputStreamReader

/**
 * Translatable display strings, loaded from `plugins/AntiDupePro/messages.yml`.
 *
 * Design rules:
 *  - Every key missing from the server's file falls back to the built-in English
 *    default shipped inside the jar, so plugin updates can add new messages without
 *    breaking servers running an older or translated messages.yml.
 *  - Values use `&` colour codes and named `{placeholder}` tokens. Named (not
 *    positional) so translators can reorder words freely.
 *  - Console log lines and ledger metadata notes are intentionally NOT routed
 *    through here: logs stay English for supportability, and notes strings such
 *    as `CHAIN_RESET:` are machine-parsed data, not display text.
 */
object Messages {

    private var custom: FileConfiguration = YamlConfiguration()
    private var langDefaults: FileConfiguration? = null
    private var defaults: FileConfiguration = YamlConfiguration()

    /**
     * Three-layer lookup, highest priority first:
     *   1. the server's own messages.yml (admin edits always win),
     *   2. the bundled translation picked by `language:` in config.yml
     *      (messages_<code>.yml inside the jar; `en` means none),
     *   3. the bundled English defaults.
     * A key missing from one layer falls through to the next, so partial
     * translations and old messages.yml files can never break anything.
     */
    fun init(plugin: JavaPlugin, language: String = "en") {
        val file = File(plugin.dataFolder, "messages.yml")
        if (!file.exists()) plugin.saveResource("messages.yml", false)
        custom = YamlConfiguration.loadConfiguration(file)
        defaults = loadResource(plugin, "messages.yml") ?: YamlConfiguration()

        val code = language.trim().lowercase().replace('-', '_')
        langDefaults = if (code.isEmpty() || code == "en") null else {
            val res = loadResource(plugin, "messages_$code.yml")
            if (res == null) plugin.logger.warning(
                "[Messages] No bundled translation 'messages_$code.yml' — falling back to English. " +
                "Available: ${BUNDLED_LANGUAGES.joinToString(", ")}")
            res
        }
    }

    private fun loadResource(plugin: JavaPlugin, name: String): FileConfiguration? =
        plugin.getResource(name)?.let { YamlConfiguration.loadConfiguration(InputStreamReader(it, Charsets.UTF_8)) }

    private val BUNDLED_LANGUAGES = listOf("en", "pt_br", "es", "de", "ru", "pl")

    /** Look up a message, substitute `{name}` placeholders, translate `&` colours. */
    fun msg(key: String, vararg args: Pair<String, Any?>): String =
        format(raw(key), args.asIterable())

    fun msg(key: String, args: Map<String, String>): String =
        format(raw(key), args.map { it.key to it.value })

    /** A list-valued message (multi-line help blocks). Each line is formatted independently. */
    fun list(key: String, vararg args: Pair<String, Any?>): List<String> {
        val lines = when {
            custom.contains(key) -> custom.getStringList(key)
            langDefaults?.contains(key) == true -> langDefaults!!.getStringList(key)
            else -> defaults.getStringList(key)
        }
        return lines.map { format(it, args.asIterable()) }
    }

    private fun raw(key: String): String =
        custom.getString(key) ?: langDefaults?.getString(key) ?: defaults.getString(key) ?: key

    private fun format(template: String, args: Iterable<Pair<String, Any?>>): String {
        var out = template
        for ((k, v) in args) out = out.replace("{$k}", v.toString())
        return colorize(out)
    }

    /**
     * `&` -> `§` colour translation. Hand-rolled instead of the deprecated
     * ChatColor.translateAlternateColorCodes; behaviour is identical.
     */
    private fun colorize(s: String): String {
        val chars = s.toCharArray()
        for (i in 0 until chars.size - 1) {
            if (chars[i] == '&' && chars[i + 1] in "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx") {
                chars[i] = '§'
                chars[i + 1] = chars[i + 1].lowercaseChar()
            }
        }
        return String(chars)
    }
}
