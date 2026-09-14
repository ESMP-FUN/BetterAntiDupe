# When an alert comes in

An alert in chat looks like this:

```
[DUPE] Steve (BALANCE_DISCREPANCY) DIAMOND_BLOCK: Has 20 but ledger shows 12 (excess: 8)
```

Steve is carrying 20 diamond blocks, but the plugin only saw them gain 12. The 8 extra are what you are looking into. Here is how to go from that line to a decision, in about five minutes.

If you can use the `/adp ledger` commands, the alert ends with two buttons: **[History]** and **[Stash]**. They run steps 3 and 4 below for that player in one click.

<details>

<summary>What is <code>DIAMOND_BLOCK</code>?</summary>

That is the item's Material name, the name Minecraft itself uses in commands like `/give`. It is always in capitals with underscores, for example `ENCHANTED_GOLDEN_APPLE`.

</details>

## 1. See who is worth a look

```
/adp ledger suspects
```

```
Current Suspects (3)
  R4gnar95 - 19 violations (ENCHANTED_BOOK: +60)
  Arnold_158 - 8 violations (NETHERITE_INGOT: +50)
  facurolo - 7 violations (DIAMOND: +69)
```

The worst are at the top. `19 violations` is how many times R4gnar95 was caught carrying more than they should. `ENCHANTED_BOOK: +60` is the item with the most extras across all of those, added up. That is usually what they are duping.

Players who logged off stay on this list, and their names tab-complete in the commands below.

## 2. Check them now

If they are online:

```
/adp ledger reconcile R4gnar95
```

This counts what they are carrying right now, including inside shulker boxes and bundles, and compares it to their record. `No discrepancies found` means they are clean at this moment. `DUPE DETECTED!` lists each item with how many they have and how many they should have.

## 3. See when it happened

```
/adp ledger history R4gnar95
```

Their last 15 records, newest first. A `+` is something gained, a `-` something lost. `[3W]` at the end means three other players were nearby to see it.

## 4. Find the stash

```
/adp ledger stash R4gnar95
```

Their last 20 times putting tracked items into a chest, barrel, shulker box, ender chest, item frame or similar:

```
Recent stashes by R4gnar95 (newest first, click coords to TP)
2026-05-31 14:22:05 16xENCHANTED_BOOK -> CHEST @ [world 102, 64, -200]
2026-05-31 14:21:48 8xDIAMOND_BLOCK -> BARREL @ [world 102, 65, -200]
```

**Click the green coordinates** to teleport straight there, even in another world.

## 5. Decide

* **It is a real duper:**

  ```
  /adp ledger confirm R4gnar95
  ```

  From now on the plugin reacts to far smaller amounts from this player, and that stays until you clear them. If you set a punishment command, it runs now. See [Watch only, or take dupes back](../getting-started/watch-or-remove.md).
* **It is a false alarm:**

  ```
  /adp ledger clear R4gnar95
  ```

  Resets their suspicion and takes them off the list. Even without this, the extra suspicion from an automatic alert halves after each full day with no new alert, so an old false alarm fades on its own.

Unless you turned on automatic removal, nothing has been taken yet.

## 6. Take the duped items back (optional)

If they are online:

```
/adp ledger remove R4gnar95
```

This lists what they carry beyond their record, and nothing is touched yet. Click **[Remove]** to take back only those extras. It counts again at the moment you click, so it never takes more than is extra right then.

Items stored inside a shulker box or bundle are left alone, and you are told how many. Every removal shows in their history. This works in shadow mode too, because it is you deciding, not the plugin.

<details>

<summary>Two more commands, for a closer look</summary>

* `/adp ledger witness <player>` shows how often other players were nearby when they gained things. Someone who is **never** seen on a busy server is worth a second look, because duping tends to happen alone.
* `/adp ledger trust <player>` shows a score out of 100, built up over time from how often their gains were seen by others. Higher is more trustworthy.

Neither is proof on its own. They help you decide.

</details>

## Other alerts you may see

Most alerts are the "Has 20 but ledger shows 12" kind above. A few others read differently.

<details>

<summary><strong>"Chunk-load / drop-race dupe" (the same item picked up twice)</strong></summary>

The exact same dropped item was picked up a second time, so it was copied. This is the most certain alert there is. The one innocent cause is a server crash, and the alert says so when the server did not shut down cleanly shortly before. Follow steps 2 to 5 as usual.

</details>

<details>

<summary><strong>"carried without explanation, then stored away and written off"</strong></summary>

The player carried extra items that carry their own mark, then stored them so their record dropped below zero and was written off. That is how small dupes get hidden. Check their history (step 3) and stash (step 4) around the time of the alert.

</details>

<details>

<summary><strong>"belonging to ... came out of storage beyond what was ever put in"</strong></summary>

More of one player's items came out of chests, frames, pots or the ground than that player ever put there, so copies were made in storage. The alert names whose items they were, who took the last of them, and where.

The player who took them may simply have been handed them by a duper, so this alert adds no suspicion and removes nothing. Go to the location in the alert, and check `/adp ledger history` for both names. See [Troubleshooting](../help/troubleshooting.md) if a crash might be involved.

</details>

<details>

<summary><strong>"Left creative mode carrying ... more ... than their record showed"</strong></summary>

You set `leaving_creative_mode: ALERT`, and someone switched out of creative mode carrying more than their record showed. Items can be made in creative, so this is not proof of a dupe. It adds no suspicion and removes nothing. Check who gave them creative mode, and their history (step 3).

</details>

{% hint style="info" %}
**Staff who should see alerts but not use commands** need only `antidupe.alerts`. See [Commands & permissions](../reference/commands-and-permissions.md).
{% endhint %}
