# FAQ

<details>

<summary><strong>Will it slow my server down?</strong></summary>

No noticeable amount. Saving records and checking players happens in the background, away from the game itself. The timed check is spread out, a few players a second, so even a full server does not feel it.

</details>

<details>

<summary><strong>Does it stop items from stacking?</strong></summary>

No. Every tracked item from the same owner carries the same mark, so items stack exactly as they normally would.

</details>

<details>

<summary><strong>What about items that existed before I installed it?</strong></summary>

Nothing to do. The first time a player joins after installing, what they are carrying becomes their starting point. Items in chests join the record the first time someone takes them out. Nobody is flagged for owning things from before.

</details>

<details>

<summary><strong>Does it work with Geyser and ViaVersion?</strong></summary>

Yes. Bedrock players through Geyser, and players on other client versions through ViaVersion, are tracked like everyone else.

</details>

<details>

<summary><strong>My shop or crate plugin gives out tracked items. Will that cause alerts?</strong></summary>

No. The plugin does not see the item being given, but the next check notices the player has items it never recorded and quietly accepts them as theirs.

</details>

<details>

<summary><strong>Can a staff member cheat by editing the records?</strong></summary>

Not without it showing. Every record is sealed to the one before it, so editing the database by hand breaks the seal, and `/adp ledger verify` shows exactly where.

</details>

<details>

<summary><strong>Does it ever ban anyone?</strong></summary>

No. It alerts you, and only takes items back if you turn that on. If you want a punishment when **you** confirm a duper, see `on_confirm_command` on [Watch only, or take dupes back](../getting-started/watch-or-remove.md).

</details>

<details>

<summary><strong>I used a 2.x version. Anything to clean up?</strong></summary>

Optionally. Older versions stored extra per-item data that is no longer used. The `isotopes` table in your SQLite file, or the `iso:*` keys in Redis, can be deleted whenever you want the space back.

</details>

<details>

<summary><strong>Where do I report a bug?</strong></summary>

On [GitHub](https://github.com/ESMP-FUN/BetterAntiDupe/issues). Include your server software and version, the plugin version, which storage you use, and the console lines around the problem.

</details>
