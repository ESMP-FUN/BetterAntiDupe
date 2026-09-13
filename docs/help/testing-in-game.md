# Testing in-game

Every way to see the plugin working with your own eyes. Each test takes a minute or two. Do them in any order.

{% hint style="warning" %}
**Test in survival mode.** Nothing a player does in creative or spectator is recorded, so a test in creative shows nothing at all. Operators are tracked like everyone else.
{% endhint %}

## Is it running?

1. Look in the console after startup for `=== BetterAntiDupe enabled successfully ===`.
2. In game, run `/adp ledger status`. It should answer with `Chain of Custody Status` and a suspect count.

`Chain of Custody is not initialized` means it did not start properly. The console says why, and [Troubleshooting](troubleshooting.md) covers the usual causes.

## Are items being tracked?

1. In creative, place a diamond block. Switch to survival (`/gamemode survival`).
2. Mine it with an iron pickaxe or better, and pick it up.
3. Run `/adp ledger history <your name>`. The newest line shows `+1` and `DIAMOND_BLOCK`.
4. Run `/adp ledger balance <your name>`. It shows `DIAMOND_BLOCK: 1`.

## Does a player check come back clean?

1. Do the test above first, so you are carrying a tracked item.
2. Run `/adp ledger reconcile <your name>`.
3. It should say `No discrepancies found - player balances verified`.

Says `Reconciliation skipped: Cooldown active`? You were checked a few seconds ago. Wait five seconds and run it again.

## Can you find a stash?

1. Carrying a diamond block, put it into a chest.
2. Walk away, then run `/adp ledger stash <your name>`.
3. Your chest is listed with its coordinates in green. **Click them** and you are teleported back to it.

## Are the records untouched?

Run `/adp ledger verify`. It should say `[OK] Chain integrity verified` with the number of records checked.

If it reports a failure instead, see [Troubleshooting](troubleshooting.md).

## Are the dupe machines blocked?

These change the world, so use a test world or a spot nobody will miss. Each blocked attempt also writes a `[DuperPrevention]` line in the console.

<details>

<summary>Rail and carpet dupers</summary>

1. Place a piston facing sideways, with any solid block in front of it.
2. Put a rail or a carpet on top of that block.
3. Power the piston with a lever.

The piston does not move. Take the rail or carpet away and it pushes normally.

</details>

<details>

<summary>TNT dupers</summary>

1. Place a piston with a TNT block in front of it.
2. Power the piston with a lever.

The piston does not push the TNT.

</details>

<details>

<summary>Sand and gravel dupers</summary>

1. Build a nether portal and light it.
2. Drop sand or gravel so it falls into the portal.

It does not travel through. It lands instead.

</details>

<details>

<summary>Ghost chest windows (needs a second player)</summary>

1. Open a chest and keep the window open.
2. Have a friend, or your second account, break that chest.

Your window closes the moment the chest breaks.

</details>

## Does removing by hand work?

1. Do the [tracking test](#are-items-being-tracked) first, so you carry a tracked item you really earned.
2. Run `/adp ledger remove <your name>`.
3. It should say you are not carrying anything extra, and nothing is taken.

There is no safe way to fake a real dupe for this test. When a real one comes in, the command lists the extras and shows a **[Remove]** button.

## Do your staff see the right things?

1. Give a helper only `antidupe.alerts`.
2. Have them run `/adp ledger suspects`. They are told they do not have permission.
3. They still receive `[DUPE]` alerts in chat when one fires, without the **[History]** and **[Stash]** buttons, because those need `antidupe.ledger`.

## Are Discord, Telegram or Slack alerts arriving?

1. Run `/adp test alert`.
2. A test alert appears in your chat, marked as a test.
3. Below it, each app you turned on says **delivered**, or **failed** with the reason.
4. Check the channel. The test message is there.

The test ignores `min_severity` and the quiet time between repeats, so it always goes out. Nobody is suspected and nothing is recorded.

Says no apps are turned on? `enabled: true` is missing, or you have not run `/adp reload` since editing. See [Troubleshooting](troubleshooting.md).
