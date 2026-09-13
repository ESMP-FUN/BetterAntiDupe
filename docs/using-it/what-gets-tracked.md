# Choosing what gets tracked

The plugin keeps a record of a short list of valuable items. Out of the box that is:

`DIAMOND_BLOCK`, `NETHERITE_INGOT`, `BEACON`, `ENCHANTED_GOLDEN_APPLE`, `SHULKER_BOX`, `ELYTRA`, `NETHER_STAR`

Shulker boxes of **every** colour are always tracked, even if you remove them from the list. They are how duped goods get moved around, so they stay watched.

Everything on this page lives in `plugins/BetterAntiDupe/materials.yml`. Restart the server after editing it: `/adp reload` does not apply this file.

## Add an item

1. Open `materials.yml`.
2. Add the item under `tracked_materials`, using its Material name:

   ```yaml
   tracked_materials:
     - DIAMOND_BLOCK
     - TOTEM_OF_UNDYING
   ```
3. Restart the server.

A name the server does not recognise is skipped, with a warning in the console that names it.

<details>

<summary>How do I find an item's Material name?</summary>

It is the name Minecraft uses in commands, in capitals with underscores. Type `/give @s ` in game and the suggestions show it, for example `minecraft:totem_of_undying`. Drop the `minecraft:` and write it in capitals: `TOTEM_OF_UNDYING`.

</details>

{% hint style="info" %}
**Keep the list to things worth duping.** Tracking common blocks like dirt or cobblestone fills the records fast and tells you nothing useful. You also do not need rails, carpets, TNT or sand here: the machines that dupe those are blocked in `config.yml` instead.
{% endhint %}

## Remove an item

Delete its line from `tracked_materials` and restart. Its old records stay, but nothing new is recorded.

## Fine tuning (optional)

<details>

<summary>How many extras are worth an alert (<code>alert_thresholds</code>)</summary>

How many more of an item a player can carry than their record explains before you hear about it. Valuable items deserve a small number. `default` covers every tracked item you do not name.

```yaml
alert_thresholds:
  ENCHANTED_GOLDEN_APPLE: 1
  ELYTRA: 1
  NETHERITE_INGOT: 2
  DIAMOND_BLOCK: 3
  default: 5
```

With these numbers, one unexplained golden apple is enough for an alert, but a player needs more than a few unexplained diamond blocks. The overall `detection.sensitivity` in `config.yml` makes all of these stricter or more relaxed at once.

</details>

<details>

<summary>How many a player could honestly gain in a minute (<code>tmar_limits</code>)</summary>

The most of an item anyone could realistically get in one minute of normal play. Going past it does not accuse anyone by itself. It is one more reason to look, on top of everything else.

```yaml
tmar_limits:
  DIAMOND_BLOCK: 10
  ENCHANTED_GOLDEN_APPLE: 2
  BEACON: 5
```

Only items on your `tracked_materials` list are counted, so a limit for anything else does nothing. Leave an item out to stop counting its rate.

</details>
