# Watch only, or take dupes back

Out of the box the plugin **only tells you**. It records, it alerts, and it never touches anyone's items. That is called shadow mode, and it is the right place to start.

You can still take duped items back yourself, one player at a time, with `/adp ledger remove <player>`. See [When an alert comes in](../using-it/when-an-alert-comes-in.md#6-take-the-duped-items-back-optional).

Leave it that way for a week or two. Watch the alerts, check a few players with [When an alert comes in](../using-it/when-an-alert-comes-in.md), and once you trust what it finds, come back here.

## Turn on automatic removal

Open `config.yml`, find `[Part 2]`, and change **both** of these:

```yaml
shadow_mode: false
auto_delete_dupes: true
```

Run `/adp reload`, or restart the server.

{% hint style="warning" %}
**Both switches have to agree.** While `shadow_mode` is `true`, nothing is ever removed, whatever `auto_delete_dupes` says. If the two disagree, the console warns you at startup.
{% endhint %}

## What gets taken, and what never does

* **Only the extra.** If a player should have 12 diamond blocks and carries 20, the 8 extra are taken. The 12 stay.
* **Only items the plugin has marked.** Items it never tracked are never touched.
* **Only when it is sure.** Small differences still alert you but remove nothing. See "How sure it has to be" below.
* **Nothing inside a shulker box or bundle is unpacked.** Those items are counted, but if part of the extra is stored that way, the console tells you how much it could not reach and leaves it to you.
* **Everything is written down.** `/adp ledger history <player>` shows what was taken and when.

The player gets a chat message saying what was removed. You can make removal silent, see below.

<details>

<summary>How sure it has to be (<code>enforcement.min_severity</code>)</summary>

**Default:** `HIGH`

Every alert has a level. Removal only happens at this level or above.

| Level      | Means                                                   |
| ---------- | ------------------------------------------------------- |
| `CRITICAL` | Only the signal that has essentially no false alarms     |
| `HIGH`     | Strong evidence. Recommended.                           |
| `MEDIUM`   | A clear extra amount                                    |
| `LOW`      | Anything at all over the expected amount. Not advised.  |

</details>

<details>

<summary>A safety cap on one removal (<code>enforcement.max_items_per_action</code>)</summary>

**Default:** `0` (no cap)

The most items taken in one go. For example `64` means one stack at most, however large the extra is.

</details>

<details>

<summary>Silent removal (<code>enforcement.notify_player</code>)</summary>

**Default:** `true`

With `false`, the player is not told. The removal still shows in your console and in their history.

</details>

<details>

<summary>Ban or punish when you confirm a duper (<code>on_confirm_command</code>)</summary>

**Default:** empty (does nothing)

When you run `/adp ledger confirm <player>`, this console command runs too. `{player}` becomes their name.

```yaml
on_confirm_command: "tempban {player} 7d Item duplication"
```

This only runs when **you** confirm someone. The plugin never bans anyone on its own.

</details>
