# Troubleshooting

Find the problem that matches yours and open it for the fix. Most things can be checked in a minute with [Testing in-game](testing-in-game.md).

{% hint style="info" %}
**Searching this page?** Your browser's Ctrl+F cannot see inside closed sections. Use the search bar at the top of the docs (or Ctrl+K) instead.
{% endhint %}

## Starting up

<details>

<summary><strong>The console says "Failed to initialize BetterAntiDupe"</strong></summary>

The lines right after it say why. The usual causes:

* **Redis cannot be reached.** You set `storage.backend: REDIS` but the server cannot connect. Start Redis, fix the `host`, `port` or `password`, or switch back to `SQLITE`. See [Pick where records are kept](../getting-started/storage.md).
* **A mistake in `config.yml`.** Usually indentation: every line inside a section must start with the same number of spaces as the line above it, and never with a tab.

</details>

<details>

<summary><strong>The plugin does not load at all, and the console mentions <code>UnsupportedClassVersionError</code></strong></summary>

**The likely cause:** Java is too old, or you have the wrong download.

* Minecraft 1.21.x needs Java 21 and the plain `BetterAntiDupe-4.3.0.jar`.
* Minecraft 26.x needs Java 25 and `BetterAntiDupe-4.3.0-mc26.jar`.

</details>

<details>

<summary><strong>A warning says an item name was not recognised</strong></summary>

A name in `materials.yml` is misspelled, or the item does not exist in your Minecraft version. The warning names it. Fix or remove that line and restart. See [Choosing what gets tracked](../using-it/what-gets-tracked.md).

</details>

## Alerts

<details>

<summary><strong>I get alerts about players I trust</strong></summary>

**Fix one or more of these:**

1. **Clear the player** with `/adp ledger clear <player>`. Their suspicion resets, so small differences stop tripping alerts for them.
2. **Relax the whole server.** Lower `detection.sensitivity` in `config.yml` from `50` to `30`, and restart.
3. **Relax one item.** Raise that item's number under `alert_thresholds` in `materials.yml`. See [Choosing what gets tracked](../using-it/what-gets-tracked.md).

Items from shop, kit or crate plugins do not cause alerts, and neither do items players owned before you installed the plugin. If you suspect either, [report it](https://github.com/ESMP-FUN/BetterAntiDupe/issues).

</details>

<details>

<summary><strong>A CRITICAL alert says an item was picked up twice, but the player swears they did not cheat</strong></summary>

**The likely cause:** either a real dupe, or a server crash at just the wrong moment.

If the server crashed after the player picked the item up but before the world saved, the world rolls back on restart, the item lies on the ground again, and they pick up the very same item a second time.

For 30 minutes after a server start that follows a crash, these alerts say so themselves: "the server did not shut down cleanly shortly before this".

**To tell them apart:**

1. If the alert carries that note, it is very likely the crash.
2. Run `/adp ledger reconcile <player>` a few minutes later. An innocent player comes back clean. A real duper keeps showing extras.
3. Once you are satisfied, run `/adp ledger clear <player>`.

</details>

<details>

<summary><strong>An alert says someone's items "came out of storage beyond what was ever put in"</strong></summary>

**What it means:** more of one player's items came back out of chests, frames or the ground than that player ever put there. The alert names whose items they were, who took the last of them, and where.

**The likely causes:**

* **A dupe inside a container.** Go to the location in the alert and look at who has been using that chest. `/adp ledger history <player>` for both names shows the puts and takes.
* **A server crash.** A crash can roll a chest back to an earlier state while the plugin already saw items leave it. If the server did not shut down cleanly shortly before, the alert says so.

The player who took the items may simply have been given them by a duper, so the alert adds no suspicion to them and nothing is removed.

</details>

<details>

<summary><strong>Nothing is being recorded for me</strong></summary>

**The likely cause:** you are in creative or spectator mode. Nothing done in those modes is recorded. Switch to survival and try [the tracking test](testing-in-game.md#are-items-being-tracked).

Also check the item is on your `tracked_materials` list.

</details>

<details>

<summary><strong>My staff do not see alerts</strong></summary>

They need `antidupe.alerts` (or `antidupe.admin`). Operators have it automatically. See [Commands & permissions](../reference/commands-and-permissions.md).

</details>

<details>

<summary><strong>Nothing arrives in Discord, Telegram or Slack</strong></summary>

Check these in order:

1. Run `/adp test alert`. It says for each app whether it was delivered, or why it failed.
2. It says no apps are turned on? Set `enabled: true` for that app and run `/adp reload`.
3. It failed? Check the URL or token is pasted in full, inside the quote marks. `HTTP 401`, `403` or `404` means the address or token is wrong.
4. The test arrives but real alerts do not? By default only `HIGH` and `CRITICAL` alerts are sent, so a quiet channel may simply mean nothing serious happened.

</details>

## Commands

<details>

<summary><strong>"Reconciliation skipped: Cooldown active"</strong></summary>

That player was checked a few seconds ago. Wait five seconds and run it again.

</details>

<details>

<summary><strong>"Player must be online for reconciliation"</strong></summary>

`reconcile` counts what a player is carrying, so they have to be online. For a player who logged off, use `history`, `stash` or `balance` instead.

</details>

<details>

<summary><strong><code>/adp ledger verify</code> reports a failure</strong></summary>

**What it means:** someone or something edited the records directly, outside the plugin.

**What to do:**

1. Stop the server and make a copy of `plugins/BetterAntiDupe/ledger.db` (or your Redis data).
2. The failure message names where the break starts and the last record that was fine. Anything recorded after the break cannot be trusted.
3. Think about who has access to the database or server files.

The plugin keeps running either way.

</details>

<details>

<summary><strong>I changed a setting and nothing happened</strong></summary>

1. Run `/adp reload` after editing. If what you changed needs a restart, it tells you which settings. `materials.yml` always needs a restart.
2. Check the setting sits inside its section. `enforcement.min_severity` means `min_severity:` indented under `enforcement:`, not a line reading `enforcement.min_severity:`.
3. For removal, both `shadow_mode: false` **and** `auto_delete_dupes: true` are needed. See [Watch only, or take dupes back](../getting-started/watch-or-remove.md).

</details>

Still stuck? Open an issue on [GitHub](https://github.com/ESMP-FUN/BetterAntiDupe/issues) with your server software and version, the plugin version, and the console lines around the problem.
