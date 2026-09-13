# What it blocks and catches

The plugin works two ways. Some dupes it **blocks**, so the extra item never exists. Everything else it **catches**, by noticing a player holds more than they could have.

You do not need to set any of this up. It is all on by default. Open a section to see what it does and when you might turn it off.

## Blocked: the classic dupe machines

These machines copy blocks in the world before any item exists, so there is nothing to count. Instead, the trick each one relies on is simply not allowed. Each has its own switch under `[Blocking]` in `config.yml`.

<details>

<summary><strong>Rail and carpet dupers</strong> (<code>prevent-rail-dupers</code>, <code>prevent-carpet-dupers</code>)</summary>

A piston cannot move a block that has a rail or carpet on it, and a moving slime or honey block cannot drag one off its side.

**You might notice:** contraptions that pop carpets or rails off with pistons stop working, and a slime flying machine will not move while touching a rail or carpet. Both are rare outside dupers.

</details>

<details>

<summary><strong>Sand and gravel dupers</strong> (<code>prevent-gravity-dupers</code>)</summary>

Sand, gravel, concrete powder and dragon eggs cannot fall through a portal. That is what the end portal sand dupers rely on.

Pistons pushing sand are left alone, so flying machines and normal farms keep working.

</details>

<details>

<summary><strong>TNT dupers</strong> (<code>prevent-tnt-dupers</code>)</summary>

Pistons cannot move TNT.

**Turn it off** if your players are allowed to run TNT duper world eaters, as some anarchy and tech servers do.

</details>

<details>

<summary><strong>Ghost chest window dupes</strong> (<code>prevent-container-desync-dupers</code>)</summary>

When a chest, shulker box, donkey or chest boat disappears while someone has it open, their window closes. Without this, a player could keep taking items out of a chest that was already broken and dropped with everything still inside.

Costs nothing. A window that was open for a normal reason just closes.

</details>

<details>

<summary><strong>Restart dupes</strong> (<code>prevent-shutdown-dupers</code>)</summary>

Every open window is closed the moment the server starts shutting down. The server saves players and the world as two separate steps, and an item moved in the gap between them can end up in both places.

This covers a normal stop or restart. After a crash no plugin gets a chance to run, and the balance checks below catch it afterwards.

</details>

## Caught: everything else

<details>

<summary><strong>Carrying more than they earned</strong></summary>

The plugin records every tracked item a player mines, crafts, trades for, picks up or takes out of a container, and every one they place, drop, use or put away. Every so often it counts what they actually carry, including inside shulker boxes and bundles. Carrying more than the record explains is the alert.

A check runs when a player picks something up, when they close a container, and for everyone online every 15 minutes. See [Settings](../reference/settings.md) to change when.

</details>

<details>

<summary><strong>The same dropped item picked up twice</strong></summary>

Every dropped item in the world has its own id. If the exact same one is picked up a second time, something has copied it. This is the most certain alert the plugin raises, and the item is not counted for the player.

The one innocent cause is a server crash at just the wrong moment. See [Troubleshooting](../help/troubleshooting.md).

</details>

<details>

<summary><strong>More drops than the block or frame gave</strong></summary>

When a block is broken or an item frame is knocked down, the plugin knows how much should drop. Picking up more than that nearby raises an alert.

</details>

<details>

<summary><strong>Nobody ever sees them get anything</strong></summary>

When a player gains something, the plugin notes who else was within 48 blocks. Duping usually happens alone, so a player on a busy server whose gains are almost never seen by anyone becomes a little more suspicious. This never raises an alert on its own.

Vanished staff can be left out of this with the `antidupe.witness.exempt` permission.

</details>

<details>

<summary><strong>Gaining things impossibly fast</strong></summary>

Each item can have a limit on how many a player could honestly get in a minute. Going past it adds suspicion but is not an alert on its own. See [Choosing what gets tracked](what-gets-tracked.md).

</details>

<details>

<summary><strong>Items washed through hoppers</strong> (<code>hopper_tracking</code>)</summary>

Dupers sometimes drop duped goods into a chest and let hoppers carry them away, so they look clean when picked up. The plugin writes the route into the item's history so you can follow it.

| Setting | What happens |
| ------- | ------------ |
| `LOG` (default) | The route is recorded. Nothing changes for players. |
| `BLOCK` | Hoppers, droppers and crafters cannot move tracked items at all. This changes how the game plays. |
| `OFF` | Machine-moved items are ignored. For very redstone-heavy servers. |

</details>

<details>

<summary><strong>Someone editing the records</strong></summary>

Every record is sealed to the one before it. If anyone edits the database by hand, even an admin, `/adp ledger verify` shows exactly where.

</details>

## Where it does not look

* **Creative and spectator mode.** Nothing a player does in those modes is recorded.
* **Items other plugins hand out** (shops, kits, crates) are not seen being given. The plugin notices the gap the next time it checks and quietly accepts what the player really has, so this does not cause alerts.
