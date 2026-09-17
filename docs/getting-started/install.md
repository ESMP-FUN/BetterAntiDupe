# Install

This page gets BetterAntiDupe running on one server. It takes about two minutes, and when you are done your server is already protected.

Running several servers that players move between? Do this on each one, then read [Pick where records are kept](storage.md).

## 1. Pick the right download

* Minecraft **1.21.x**: `BetterAntiDupe-4.5.0.jar`
* Minecraft **26.0 to 26.2**: `BetterAntiDupe-4.5.0-mc26.jar`
* Minecraft **26.3**: `BetterAntiDupe-4.5.0-mc263.jar`

## 2. Put it in your plugins folder

Stop the server, drop the jar into `plugins/`, and start it again.

## 3. Check it started

Look in your console for these two lines:

```
[BetterAntiDupe] === BetterAntiDupe v4.5.0 ===
[BetterAntiDupe] === BetterAntiDupe enabled successfully ===
```

If you see `Failed to initialize BetterAntiDupe` instead, the line after it says why. [Troubleshooting](../help/troubleshooting.md) covers the usual causes.

{% hint style="success" %}
**That's the whole setup.** Valuable items are now tracked, the dupe machines are blocked, and admins get an alert in chat when something does not add up. Nothing is ever taken from a player until you decide it should be.
{% endhint %}

Want to see it working for yourself? [Testing in-game](../help/testing-in-game.md) walks you through it.

## What it created

Inside `plugins/BetterAntiDupe/`:

| File            | What it is for                                                        |
| --------------- | --------------------------------------------------------------------- |
| `config.yml`    | Everything the plugin does. It opens with a short menu, so you can jump to the part you need. |
| `materials.yml` | Which items are watched, and how many of each is too many. See [Choosing what gets tracked](../using-it/what-gets-tracked.md). |
| `messages.yml`  | Every message players and admins see. See [Language and messages](../using-it/language-and-messages.md). |
| `ledger.db`     | The records themselves. Leave it alone, and back it up with the rest of your server. |

## Updating

Stop the server, swap the jar, start the server. Your settings and records are kept, and settings that were renamed in a newer version keep working without you editing anything.

<details>

<summary>Update from inside the game instead</summary>

With `antidupe.admin`, run:

```
/adp update check
/adp update download
```

The new version is used from the next restart. If it gives you trouble, `/adp update restore` puts the previous one back.

</details>

## Next

* **Want duped items taken back automatically?** [Watch only, or take dupes back](watch-or-remove.md)
* **Want alerts on your phone?** [Alerts in Discord, Telegram or Slack](../using-it/alerts-outside-the-game.md)
* **Staff who should see alerts?** [Commands & permissions](../reference/commands-and-permissions.md)

Changed a setting later? `/adp reload` applies most of them without a restart.
