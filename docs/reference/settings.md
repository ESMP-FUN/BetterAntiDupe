# Settings

`config.yml` explains every setting right above its line, and opens with a short menu so you can jump to the part you need. This page lists the ones people change most.

After editing, run `/adp reload`. Messages, language, alerts and removal settings apply straight away. For anything else, the reload tells you which of your changes still need a restart.

{% hint style="info" %}
**Searching this page?** Your browser's Ctrl+F cannot see inside closed sections. Use the search bar at the top of the docs (or Ctrl+K) instead.
{% endhint %}

## Covered on their own pages

| Part of `config.yml` | Page |
| -------------------- | ---- |
| `[Part 1]` Alerts in Discord, Telegram, Slack | [Alerts in Discord, Telegram or Slack](../using-it/alerts-outside-the-game.md) |
| `[Part 2]` What happens to a caught duper | [Watch only, or take dupes back](../getting-started/watch-or-remove.md) |
| `[Part 3]` Several servers | [Pick where records are kept](../getting-started/storage.md) |
| `[Blocking]` The dupe machines | [What it blocks and catches](../using-it/what-it-blocks-and-catches.md) |
| `language` | [Language and messages](../using-it/language-and-messages.md) |
| `materials.yml` | [Choosing what gets tracked](../using-it/what-gets-tracked.md) |

## Detection

<details>

<summary><code>detection.sensitivity</code></summary>

**Default:** `50`

How suspicious the plugin is, from `1` (very relaxed) to `100` (very paranoid). Higher means a smaller unexplained amount is enough for an alert.

Getting alerts about players you trust? Try `30`. Want to hear about the smallest thing, and accept more false alarms? Try `70`.

</details>

<details>

<summary><code>hopper_tracking</code></summary>

**Default:** `LOG`

What to do when a hopper, dropper or crafter moves a tracked item by itself. `LOG` records the route, `BLOCK` stops machines moving tracked items at all, `OFF` ignores them. More on [What it blocks and catches](../using-it/what-it-blocks-and-catches.md).

</details>

<details>

<summary><code>block_collect_to_cursor</code></summary>

**Default:** `false`

With `true`, double-clicking an item in a chest no longer gathers every matching tracked item onto your cursor. Players use that constantly and will notice, so only turn it on if you would rather give it up.

</details>

## When players are checked

<details>

<summary><code>ledger.reconciliation.interval_minutes</code></summary>

**Default:** `15`

Check everyone online this often, in minutes. Set `0` to turn the timer off and rely only on the two checks below.

</details>

<details>

<summary><code>ledger.reconciliation.on_pickup</code> and <code>on_inventory_close</code></summary>

**Default:** `true` for both

Check a player when they pick something up off the ground, and when they close a chest, shulker box or other container. The second one catches players who move everything through storage and never pick anything up.

{% hint style="warning" %}
**Upgraded from before 4.3.0?** Your `config.yml` may still say `on_inventory_close: false`. Set it to `true`.
{% endhint %}

</details>

<details>

<summary><code>ledger.reconciliation.cooldown_ms</code> and <code>stagger_ms</code></summary>

**Defaults:** `5000` and `250`

These are in milliseconds, where `1000` is one second.

`cooldown_ms` is the shortest gap between two checks on the same player, so a busy player is not counted over and over. `5000` is five seconds.

`stagger_ms` spreads the timed check out, instead of checking everyone at the same instant. `250` means four players a second.

</details>

## Who saw it happen

<details>

<summary><code>ledger.witness</code></summary>

**Defaults:** `radius: 48`, `verified_threshold: 3`, `flag_suspicious_patterns: true`, `suspicious_solo_ratio: 0.8`

When a player gains something, others within `radius` blocks count as having seen it. With `verified_threshold` or more nearby, it counts as well seen.

With `flag_suspicious_patterns` on, a player whose gains go unseen more than `suspicious_solo_ratio` of the time becomes more suspicious. `0.8` means more than four in five. Turn it off on a server where most people play alone.

</details>

## Hiding the plugin from players

<details>

<summary><code>hide_tag_from_clients</code></summary>

**Default:** `true`

The plugin puts a hidden mark on tracked items saying who owns them. This keeps that mark out of what players' games receive, so a mod that shows hidden item data cannot see it, and a duper testing with two accounts cannot tell which items are marked. Detection works exactly the same either way.

</details>

<details>

<summary>What is the hidden mark, and what is NBT?</summary>

NBT is the extra data Minecraft stores on an item, such as its enchantments or custom name. The plugin adds one small entry to it with the owner's UUID, the permanent id of their account.

</details>

<details>

<summary><code>strip_all_custom_data</code> and <code>strip_whitelist</code></summary>

**Defaults:** `false` and `[]`

The strict version of the setting above: hide **every** plugin's hidden item data from players' games, not just this plugin's mark.

Off by default, because resource packs that change an item's look based on that data, and client mods that sort or price items, will see those items as plain. To keep other plugins working, list their names:

```yaml
strip_whitelist: ["itemsadder", "mmoitems"]
```

</details>

<details>

<summary><code>ownership.namespace</code> and <code>ownership.key</code></summary>

**Defaults:** `antidupepro` and `adp_owner`

The name of the hidden mark. Rename it so a leaked screenshot shows something meaningless instead of a name that points at this plugin.

Renaming is safe. The old name is remembered in `legacy_keys` automatically, items already marked stay tracked, and they pick up the new name as they change hands.

</details>

## Console and statistics

<details>

<summary><code>console_log_level</code></summary>

**Default:** `INFO`

How much the plugin writes to your console: `CRITICAL`, `ERROR`, `WARNING`, `INFO` or `DEBUG`. Each also shows everything more serious than itself. Use `WARNING` for a quiet console, or `DEBUG` when asked for detail while getting help.

</details>

<details>

<summary><code>metrics.enabled</code> and <code>metrics.error_reporting</code></summary>

**Defaults:** `true` for both

Anonymous statistics: which storage you use, which switches are on, your server software and versions, and plain counts of how many dupes were caught, removed and blocked, by item and type.

**Never sent:** addresses, server names, player names, item data, or anything that could identify you or a player.

`error_reporting` sends error details when the plugin goes wrong, with anything resembling a password, token, id or home folder removed first. Set either to `false` to send nothing.

</details>
