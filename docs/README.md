# User Guide

BetterAntiDupe is a Paper, Folia and Spigot plugin that stops item duplication
on Minecraft servers running **1.21.x** (tested through 1.21.11) and **26.x** -
one codebase, two downloads (plain jar for 1.21.x, `-mc26` jar for 26.x). It
tags valuable items with the owner's UUID and writes every acquisition and loss
to a tamper-evident ledger, so when a player's actual inventory diverges from
what their ledger says they should hold, the plugin alerts.

This guide is written for server owners and admins. You do not need to
understand how the detection works to use the plugin - just install it, pick a
storage backend, and the defaults will do the right thing.

## 1. What it does

Whenever a player mines, crafts, picks up, or otherwise obtains a tracked item
(diamonds, netherite, shulkers, elytras, etc.), BetterAntiDupe tags the item
with the player's UUID and records the event in an append-only ledger. Every
loss event - placing a block, dropping an item, putting something into a chest
or frame - is recorded too. If the player's actual inventory diverges from what
the ledger sums to, the plugin alerts.

Detection runs through the **Chain of Custody** ledger. Every entry contains a
SHA-256 hash linking it to the previous entry, so the chain is tamper-evident -
anyone editing the database directly breaks the chain, and `/adp ledger verify`
reports exactly where. Nearby players are recorded as witnesses, so dupe
exploits that bypass normal events leave behind a telltale "no witnesses ever
saw this item appear" pattern that `/adp ledger witness` surfaces.

Items stack normally (vanilla behaviour preserved) because tracking attaches to
the owner UUID, not to per-item identifiers.

## 2. Quick start (5 minutes)

1. Download the latest `BetterAntiDupe-X.Y.Z.jar` (1.21.x) or
   `BetterAntiDupe-X.Y.Z-mc26.jar` (26.x).
2. Drop it into your server's `plugins/` folder.
3. Start the server once. The plugin generates
   `plugins/BetterAntiDupe/config.yml` and a SQLite database file, then loads
   itself.
4. That's it. Your server now tracks the default set of valuable items.

To confirm it's working, mine a diamond block, then run
`/adp ledger balance <your-name>`. You should see `DIAMOND_BLOCK: +1` in the
output.

## 3. Installation

### Requirements

| Item | Requirement |
|---|---|
| Server software | Paper, Folia, Spigot, or a Paper-compatible fork (Purpur, Pufferfish, etc.) |
| Minecraft version | 1.21.0 - 1.21.11 (plain jar), 26.x (`-mc26` jar) |
| Java | 21 or newer for 1.21.x, 25 or newer for 26.x |
| External services | None required (SQLite is built in). Redis is optional. |

### Steps

1. Stop your server.
2. Place `BetterAntiDupe-X.Y.Z.jar` (1.21.x) or `BetterAntiDupe-X.Y.Z-mc26.jar`
   (26.x) in `plugins/`.
3. Start the server. On first boot you will see something like this in the
   console:

   ```
   [BetterAntiDupe] === BetterAntiDupe v3.3.2 ===
   [BetterAntiDupe] Initializing Chain of Custody...
   [BetterAntiDupe] ✓ Scheduler initialized (Bukkit mode)
   [BetterAntiDupe] ✓ Configuration loaded
   [BetterAntiDupe] [Ledger] Connected to SQLite at ledger.db
   [BetterAntiDupe] ✓ Chain of Custody initialized
   [BetterAntiDupe]   Tracking 7 materials, 5 TMAR limits
   [BetterAntiDupe] === BetterAntiDupe enabled successfully ===
   ```

4. Open `plugins/BetterAntiDupe/config.yml` and adjust anything you want (see
   the [configuration reference](#5-configuration-reference)).
5. Reload the plugin with `/reload confirm` or restart the server.

### Upgrading from an older version

Stop the server, replace the jar, start the server. Your existing `config.yml`
is preserved. If new config keys are introduced in a release, the plugin uses
sensible defaults for them - check the changelog to see if you want to add the
new keys explicitly.

## 4. Choosing a storage backend

BetterAntiDupe can store its tracking data in one of three places. Choose the
one that fits your setup:

| Backend | When to use | Pros | Cons |
|---|---|---|---|
| **SQLite** _(default)_ | Single-server setups, almost everyone. | No external service. Data survives restarts. Zero configuration. | Not shared between servers in a network. |
| **Redis** | Multi-server networks where item tracking must span servers. | Fast. Shared state across a network. Battle-tested. | You have to run and maintain a Redis instance. |
| **Memory** | Testing and development only. | Instant, zero setup. | **Data is lost on restart.** Do not use in production. |

Set your choice in `config.yml`:

```yaml
storage:
  backend: SQLITE    # or REDIS, or MEMORY
  sqlite_file: "storage.db"
  sqlite_ledger_file: "ledger.db"
```

If you pick `REDIS`, fill in the `redis:` section further down in the config
(host, port, password, database).

## 5. Configuration reference

Configuration is split across two files in `plugins/BetterAntiDupe/`:

- `config.yml` - plugin behaviour: storage backend, modes, ledger settings.
- `materials.yml` - the lists of tracked items, rate limits, and alert
  thresholds. Kept separate so this file can grow long without cluttering the
  main config.
- `messages.yml` - every in-game message (since 3.3.3). Translate or restyle
  freely; see below.

Existing installs that already have `tracked_materials`, `tmar_limits` or
`ledger.alert_thresholds` defined in `config.yml` are migrated automatically on
first start of this version - the values move into `materials.yml` and are
removed from `config.yml`. You don't need to do anything.

### config.yml

The file itself is the reference. It opens with a short menu of the routes
through it, and every setting is explained above the line it belongs to, so
this guide does not repeat it line by line: a copy here only drifts out of
date, and a stale copy is worse than none.

The chapters, in the order they appear:

| Chapter | What lives there |
|---|---|
| `[Part 1]` | Sending alerts to Discord, Telegram, Slack or your own webhook |
| `[Part 2]` | What happens when a duper is caught: shadow mode, automatic removal, the command to run on confirming |
| `[Part 3]` | Storage, and sharing one record across several servers with Redis |
| `[Blocking]` | Toggles for the classic dupe machines (rail, carpet, TNT, gravity, phantom windows, restart) |
| `[Detection]` | Sensitivity, automated-transfer tracking, double-click gather |
| `[Look]` | Hiding the ownership mark from players' clients, and renaming it |
| `[Advanced]` | Witness and balance-check tuning, console verbosity, anonymous statistics |

The settings people ask about most:

| Setting | Default | Meaning |
|---|---|---|
| `shadow_mode` | `true` | Watch and record only. Vetoes removal entirely while on. |
| `auto_delete_dupes` | `false` | Take the surplus back. Needs `shadow_mode: false` as well. |
| `enforcement.min_severity` | `HIGH` | How sure the plugin has to be before removing anything. |
| `detection.sensitivity` | `50` | 1 is very relaxed, 100 very paranoid. |
| `hopper_tracking` | `LOG` | `OFF`, `LOG` or `BLOCK` for machine-moved items. |
| `ledger.reconciliation.interval_minutes` | `15` | How often everyone online is checked. `0` turns the timer off. |
| `storage.backend` | `SQLITE` | `SQLITE`, `REDIS` or `MEMORY`. |
| `console_log_level` | `INFO` | `CRITICAL`, `ERROR`, `WARNING`, `INFO` or `DEBUG`. |
| `metrics.enabled` | `true` | Anonymous statistics (config snapshot plus running detection/removal/blocked-contraption counts, broken down by check type, severity and item). `false` sends nothing. |
| `metrics.error_reporting` | `true` | Send redacted stack traces when the plugin errors. `false` keeps them to yourself. |

Upgrading from 4.2.0 or earlier keeps working without you editing anything.
Two settings moved to where a reader would look for them, and the old
positions are still honoured: the confirm command is now `on_confirm_command`
at the top level rather than under `detection`, and the Redis database number
is `redis.database` rather than `ledger.redis_database`. Two settings that
never did anything were removed, `storage.sqlite_file` and the unused Redis
timeout, the latter now being read for real.

### materials.yml

```yaml
# Items that get tracked. Add or remove freely; use vanilla Material names.
# Shulker boxes of every color are always tracked regardless of this list.
tracked_materials:
  - DIAMOND_BLOCK
  - NETHERITE_INGOT
  - BEACON
  - ENCHANTED_GOLDEN_APPLE
  - SHULKER_BOX
  - ELYTRA
  - NETHER_STAR

# Maximum tracked items per minute. Players exceeding these are flagged
# as suspicious (Theoretical Max Acquisition Rate).
tmar_limits:
  ANCIENT_DEBRIS: 20
  DIAMOND_BLOCK: 10
  ENCHANTED_GOLDEN_APPLE: 2
  NETHERITE_INGOT: 15
  BEACON: 5

# How many extra items a player can hold above their ledger balance
# before an alert fires (v2 ledger only). Higher-value items have
# stricter thresholds. 'default' applies to materials not listed here.
alert_thresholds:
  ENCHANTED_GOLDEN_APPLE: 1
  BEACON: 1
  NETHER_STAR: 1
  ELYTRA: 1
  NETHERITE_INGOT: 2
  DIAMOND_BLOCK: 3
  default: 5
```

### Webhook notifications & translation

Two features have their own short guide -
[Notifications & Translation](notifications-and-translation.md):

- **Alerts outside the game (3.3.4+)** - push dupe alerts to Discord, Telegram,
  Slack, or any custom webhook. Step-by-step setup per service, all off by
  default, configured in the `notifications` section of `config.yml`.
- **Translating the plugin (3.3.3+)** - every in-game message lives in
  `messages.yml`; edit any line, deleted lines fall back to English. Since
  3.3.5, five translations are built in - set `language: pt_BR` / `es` / `de` /
  `ru` / `pl` in config.yml.

## 6. Features

### 6.1 How detection works

Every tracked item entering a player's inventory gets the player's UUID written
to its NBT. Every gain (mine, craft, pickup, container take, workstation
output, ...) and every loss (place, drop, container put, consume, ...) is appended
to a per-server ledger, with each entry cryptographically linked to the
previous one. Reconciliation walks the player's inventory - recursively,
including inside held shulkers and bundles - and compares the total to the
ledger sum. A surplus is a dupe.

### 6.2 What it catches

Most of what follows is _detection_ - the ledger notices a discrepancy after the
fact. The first entry is different: those exploits are **blocked outright**, so
no duped item is ever created.

- **Duper prevention (blocked, not detected).** The classic contraptions are
  stopped at the mechanic level, each with its own config toggle, all on by
  default:
  - _Rail and carpet dupers_ - piston movement that would dislodge an attached
    rail or carpet is cancelled, including the carpet-on-piston-arm variant and
    the slime-block variant that drags the rail or carpet off the side or
    underside of a moving slime block.
  - _TNT dupers_ - pistons can't move TNT blocks. Turn this off if your server
    allows TNT-duper world eaters.
  - _Gravity dupers_ - falling blocks (sand, gravel, concrete powder, dragon
    egg...) can't travel through portals, closing the end-portal sand duper
    family. Pistons pushing sand are deliberately unaffected.
  - _Phantom-GUI container dupes_ - an open container GUI is force-closed when
    its container goes away (shulker or chest broken or blown up, donkey or
    chest-boat chunk unloading). Without this the phantom takes are recorded as
    legitimate ledger credits, so the dupe would be invisible to reconciliation.
  - _Restart dupers_ - every open inventory is closed when the server begins
    shutting down. Player data and world data are written as separate steps, so
    an item moved in the window between them is saved on one side but not the
    other and exists twice on the next boot. This needs no contraption, just a
    well-timed click during a restart. Covers a clean stop; after a crash
    nothing plugin-side runs, and balance reconciliation is the backstop.
- **Balance reconciliation.** The plugin counts how many of each tracked
  material the player has in their inventory and compares it to the ledger
  total. If a player has 20 diamond blocks but the ledger only shows them ever
  gaining 12, that's a dupe. A check runs when a player picks something up off
  the ground, when they close a container, and for everyone online on a timer
  (every 15 minutes by default). All three are configurable under
  `ledger.reconciliation`, and the timer alone guarantees nobody is missed
  however they play.
- **Proof of Witness.** When a player mines, crafts, or picks up something, the
  plugin records nearby players as witnesses. Players whose actions are
  _never_ witnessed on a populated server are statistically suspicious - that's
  exactly the pattern a dupe exploit produces.
- **Tamper detection.** Each ledger entry contains a SHA-256 hash linking it to
  the previous entry. Anyone editing the ledger directly (e.g. by SQL) breaks
  the chain, and `/adp ledger verify` reports exactly where. The hash covers the
  audit details as well as the transaction, so the witness list, trust level,
  container location and notes cannot be rewritten without the break showing up.
  Entries written before 4.3.0 are checked under the older rules they were
  created with, so upgrading does not invalidate a database you already have.
- **Trust scores.** Players build a long-term trust score based on how often
  their actions are independently corroborated by witnesses.
- **Item-frame dupe detection.** Every frame break registers exactly one
  expected drop. Any surplus pickup in the same area within 60 seconds fires a
  high-severity alert - closes the piston-into-frame and chunk-race frame dupe
  families.
- **Per-entity pickup history.** Every dropped item entity is recorded the
  first time it's picked up. If the same physical entity ever gets picked up
  again - which has no innocent explanation outside of a server crash
  recovery - the second pickup is flagged CRITICAL and not credited. Closes
  chunk-load entity dupes, drop-pickup race dupes, and proxy-network race
  dupes.
- **Workstation and storage coverage.** Smithing tables, anvils, looms,
  stonecutters, cartography tables, grindstones, furnaces, smokers, blast
  furnaces, lecterns, ender chests, decorated pots, horse/donkey/llama chests,
  chest boats and chest minecarts all track item movement correctly. Inputs and
  outputs reconcile: since 4.3.0 what a station consumes is measured and taken
  off the books, so repeatedly renaming an item on an anvil no longer quietly
  builds up spare room in a player's balance. Since 3.3.2 this includes
  **double chests**, **villager trades** and **enchanting a book**.
- **Crafting balances (4.3.0).** The ingredients a recipe eats are taken off the
  ledger, not just the result added to it. Turning nine diamonds into a block and
  back, or dyeing a shulker box between colours over and over, used to add a
  little headroom on every cycle while the player's inventory never changed.
- **Accurate chest accounting (3.3.2).** Moving items with shift-clicks, number
  keys, offhand swaps, double-click gathering or drag-moves is recorded by what
  _actually_ moved - not by what the click "should" have moved. Fewer false
  alarms, no loopholes.
- **Bundle content scanning.** Items stored inside bundles are inspected just
  like shulker contents, so duped items can't be laundered through bundles or
  the shulker-dye crafting recipe vector.
- **Deep container reconciliation.** The balance check recursively descends
  into shulker boxes, barrels, chests-stored-as-items and bundles at every
  nesting level. A player carrying a duped shulker full of diamonds used to
  show a clean balance against an empty main inventory - the deep scan now
  counts the contents and surfaces the discrepancy.
- **Hopper laundering.** When a hopper, dropper or crafter moves a tracked item
  on its own, the route is written into that item's history, so goods fed
  through a chest network to look clean can still be traced back. Set
  `hopper_tracking: BLOCK` to stop machines moving tracked items at all, or
  `OFF` on a redstone-heavy server where you would rather not pay for it.
- **Acquisition rate abuse (TMAR).** If a player suddenly starts gaining far
  more diamonds-per-minute than is physically possible, the plugin flags it.

### 6.3 Shadow mode and alerts

By default, the plugin runs in _shadow mode_: it watches and records, but never
touches anyone's items. When something suspicious happens:

- Admins online get an in-game alert in chat.
- The event is logged to the server console.
- If the player is flagged, the next chest they open is logged too - letting
  you find their stash for manual review.

If you'd rather have items taken back automatically, two settings have to agree:
set `shadow_mode: false` **and** `auto_delete_dupes: true`. Shadow mode is a
hard veto: with it on, nothing is ever removed no matter what else is set, and
the console says so at startup if the two disagree.

Removal is deliberately narrow:

- Only the **surplus** is taken: the amount beyond what the plugin can account
  for, never a player's whole stack.
- Only items carrying the plugin's own ownership tag are eligible, so items it
  never tracked are never touched.
- It only acts at `enforcement.min_severity` (default `HIGH`) and above, so a
  small unexplained difference alerts without anything being removed.
- `enforcement.max_items_per_action` caps a single removal as a safety net.
- Items inside a shulker box or bundle are counted but **not** unpacked and
  deleted. If part of a surplus is stored that way, the console says how much
  could not be reached and leaves it for you to handle by hand.

Every removal is written to the player's ledger history, so `/adp ledger history
<player>` shows what was taken and why.

## 7. Commands

All commands live under `/antidupe` (aliases: `/adp`, `/betterantidupe`).

Two top-level commands: `/adp help` (no permission) and `/adp ledger ...`
(requires `antidupe.ledger`). Everything else is a ledger subcommand.

### Ledger commands

| Command | What it does |
|---|---|
| `/adp ledger status` | Show overall ledger state: chain tip, recent hash, current suspect count. |
| `/adp ledger balance <player>` | Show the player's expected balances for each tracked material. |
| `/adp ledger history <player>` | Show the player's most recent 15 ledger entries. |
| `/adp ledger witness <player>` | Show the player's witness statistics and any suspicious patterns. |
| `/adp ledger suspects` | List all currently flagged players, ranked by violation count. |
| `/adp ledger stash <player>` | Show where the player stashed tracked items, with clickable coordinates that teleport you to each stash (added in 3.2.0). |
| `/adp ledger reconcile <player>` | Force a balance check for an online player right now. |
| `/adp ledger trust <player>` | Show the player's accumulated trust score. |
| `/adp ledger confirm <player>` | Verdict: confirm a real duper. Pins their suspicion high (future hits trip on far less) and runs `detection.on_confirm_command` if set. |
| `/adp ledger clear <player>` | Verdict: mark a false positive. Resets the player's suspicion and removes them from the suspect list. |
| `/adp ledger verify` | Verify the hash chains. Tells you if anything has been tampered with. |

### Reading the suspects list

`/adp ledger suspects` output looks like this:

```
Current Suspects (3)
(material: +N = cumulative excess of that material across all violations)
  R4gnar95 - 19 violations (ENCHANTED_BOOK: +60)
  Arnold_158 - 8 violations (RAISER_ARMOR_TRIM_SMITHING_TEMPLATE: +50)
  facurolo - 7 violations (DIAMOND: +69)
Use /adp ledger stash <player> to see where they stashed items.
```

The number on the right of each line - `+60`, `+50`, `+69` - is the
**cumulative excess** of the named material across that suspect's violations.
Concretely:

- `R4gnar95 - 19 violations (ENCHANTED_BOOK: +60)` means the reconciliation
  engine has flagged R4gnar95 on 19 separate occasions, and across all of
  those events they were carrying a total of 60 more enchanted books than
  their ledger said they should hold.
- The material shown is the one with the highest cumulative excess, so it's
  the "what they're duping" at a glance.

### Finding the stash

Once you spot a suspect, run `/adp ledger stash <player>` to see where they put
the items. The output lists their last 20 stash events - items placed into
chests, shulkers, barrels, ender chests, lecterns, decorated pots,
horse/donkey/llama chests, chest boats and item frames - newest first:

```
Recent stashes by R4gnar95 (newest first, click coords to TP)
2026-05-31 14:22:05 16xENCHANTED_BOOK -> CHEST @ [overworld 102, 64, -200]
2026-05-31 14:21:48 8xDIAMOND_BLOCK -> BARREL @ [overworld 102, 65, -200]
2026-05-31 14:15:11 1xELYTRA -> DECORATED_POT @ [the_end 0, 60, 0]
2026-05-31 14:10:33 32xNETHERITE_INGOT -> ENDER_CHEST @ [overworld 50, 70, 12]
```

The bracketed coordinates are clickable in chat - a single click runs
`/execute in <world> run tp @s x y z`, teleporting you directly to the stash,
even across worlds. Hover for a confirmation tooltip.

## 8. Permissions

| Permission | Grants | Default |
|---|---|---|
| `antidupe.alerts` | Receives in-game dupe alerts (no command access) | op |
| `antidupe.ledger` | All `/adp ledger ...` commands | op |
| `antidupe.admin` | Includes both `antidupe.alerts` and `antidupe.ledger` | op |
| `antidupe.witness.exempt` | Holder is never counted as a nearby witness (e.g. vanished staff) | false |
| `antidupe.tag.view` | See the ownership tag in your own client even with `hide_tag_from_clients` on (applies on next login) | false |

If you're using a permissions plugin (LuckPerms, etc.), you can split duties:
give a moderator just `antidupe.alerts` to see dupe alerts without any command
access, or `antidupe.ledger` for the investigation commands. `antidupe.admin`
grants both at once.

## 9. Common scenarios

### "I want to test that it works."

1. Mine a diamond block legitimately.
2. Run `/adp ledger balance <your-name>`. You should see `DIAMOND_BLOCK: +1`.
3. Run `/adp ledger history <your-name>`. The latest entry should be
   `PICKUP +1 DIAMOND_BLOCK` with a `MINE` source attribution in the notes.
4. Run `/adp ledger verify`. The chain should report integrity verified across
   all entries.

### "A player thinks I unfairly took their item."

1. Run `/adp ledger reconcile <player>` while they're online. If their actual
   inventory matches their ledger balance, the items are legit and the
   reconciler reports no discrepancies.
2. If reconcile flags a surplus, run `/adp ledger history <player>` to see when
   the suspicious gains entered the ledger.
3. For the specific stash, `/adp ledger stash <player>` lists their recent
   container puts with clickable teleport coords.

### "I want to track a new item."

1. Open `materials.yml`.
2. Add the item's Material name to the `tracked_materials` list (e.g.
   `- TOTEM_OF_UNDYING`).
3. Optionally add it under `tmar_limits` with a per-minute cap and under
   `alert_thresholds` with a discrepancy ceiling.
4. Restart or reload the server.

### "I want to stop tracking shulkers."

You can't fully - shulker scanning is hardcoded because they're the primary
tool for shipping duped goods. You can remove `SHULKER_BOX` from
`tracked_materials` (which stops tagging the shulker box _item itself_ with
ownership), but their contents will still be scanned during reconciliation.

### "My network has multiple servers. How do I share tracking?"

Set `storage.backend: REDIS` on every server and point them at the same Redis
instance. Items keep their owner UUID via NBT as they travel through proxies
(BungeeCord / Velocity), and the shared ledger lets reconciliation on the
destination server see the original acquisition history. A dupe created on one
server is caught when its recipient is reconciled on another.

## 10. Troubleshooting

### "The plugin failed to start."

Check the console for the line beginning with
`Failed to initialize BetterAntiDupe:`. Common causes:

- **Redis is selected but the server can't reach it.** Either start your Redis
  server or switch `storage.backend` to `SQLITE`.
- **Wrong Java version.** The plugin requires Java 21+.
- **Old Minecraft version.** The plugin requires Paper API 1.21+.

### "I'm getting false-positive dupe alerts."

As of 3.3.0 the detection model is designed to make these rare. If you're still
seeing them:

- **Update to 3.3.2 first.** It fixed several causes of false alarms: worn
  armor/elytras counted twice, double chests not being tracked, shift-clicks
  into nearly-full chests, and an alert flood that happened when another plugin
  blocked an item pickup.
- **Turn sensitivity down.** `detection.sensitivity` (1-100, default 50) is
  the master dial. Lower it toward 1 for a more forgiving server; raise it
  toward 100 only if you want to catch the smallest discrepancies and accept
  more noise.
- **Clear the player.** Run `/adp ledger clear <player>` on a confirmed false
  positive. This resets their suspicion so they aren't re-flagged on small
  wobbles. Conversely, `/adp ledger confirm <player>` on a real duper makes
  future hits trip on far less.
- **Custom plugins / shops giving items.** Plugins that grant items via direct
  API (shop purchases, kit/reward plugins) bypass the events the ledger
  watches. The plugin self-heals this automatically - a balance that goes
  negative is recognised as a tracking gap and re-baselined to the player's
  real inventory rather than flagged. For zero-noise integration, plugin
  authors can call
  `ChainOfCustody.recordSystemGrant(player, material, amount, source)`.
- **Old items from before install.** No owner UUID, no ledger history. They
  become tracked the first time the player interacts with them; a never-seen
  player's inventory is baselined on first join. No manual action needed.

Note that acquisition-rate bursts (raid farms, fast vault looting) and solo
unwitnessed play no longer trigger alerts on their own - they only nudge a
player's suspicion, which decays on its own when nothing else is wrong.

### "I got a CRITICAL chunk-load dupe alert but the player swears they didn't cheat."

This alert fires when the same item entity is picked up twice. The usual cause
is a real dupe exploit, but there's one rare innocent case: if your server
crashed between the player picking the item up and the next chunk save, the
chunk reverts on restart and the player legitimately picks up the same entity
again. This produces a false-positive CRITICAL alert.

(Before 3.3.2 this alert could also fire hundreds of times in a row when
another plugin blocked a pickup - that was a bug, not a dupe, and it's fixed.
Each item can now only trigger this alert once.)

How to tell them apart:

- Check the console for a recent crash or abnormal shutdown around the alert
  timestamp. If yes, it's almost certainly the false positive.
- Use `/adp ledger reconcile <player>` to compare their actual inventory
  against ledger. A false positive will reconcile cleanly within a few
  minutes. A real dupe will keep showing excess.
- Clear the player's suspect status with `/adp ledger` once you're satisfied.

### "The chain integrity check failed."

If `/adp ledger verify` reports a broken chain, that means someone (or
something) modified the ledger database directly. Stop the server, back up the
database file, and investigate. The plugin won't refuse to run, but
reconciliation results from after the break point can't be trusted until you
decide what to do.

## 11. FAQ

### Does this affect server performance?

Tracking work happens off the main thread. The only main-thread work is reading
and writing item NBT, which is very cheap. On a busy server with hundreds of
tracked transactions per second, the plugin uses well under 1% of CPU.

### Does it work with mods like Geyser / ViaVersion?

Yes. The plugin operates entirely at the Paper API level, so any item that's a
real ItemStack in Paper is trackable.

### What happens to items that already existed before I installed it?

They have no owner UUID in their NBT and no ledger history. The plugin treats
them as legitimate (it fails open). The first time the holder picks one up or
moves it through a tracked event, it gets tagged with the owner UUID and joins
the ledger from that point onward - no manual action is needed.

### Can a sneaky admin tamper with the ledger?

Editing entries in the SQLite or Redis database directly will break the hash
chain, and `/adp ledger verify` will report exactly where the tampering started
and which entry was the last valid one.

### I used an older version. What changed?

BetterAntiDupe originally shipped two detection systems side by side: a
"Digital Isotope" system that wrote a unique signature into every tracked
item's NBT, and the Chain of Custody ledger. As of 3.0.0 the isotope system is
removed entirely. The reason is straightforward: per-item NBT broke vanilla
stacking - no two diamonds ever combined into a single slot - and the ledger
now catches everything the isotope system used to catch, plus several families
it never could (item frames, entity inventories, workstations, chunk-load
entity dupes, drop-pickup races, ...).

Existing 2.x installs upgrading to 3.x: the plugin no longer reads the old
isotope data. The `isotopes` table in your SQLite file or the `iso:*` keys in
Redis are unused and can be deleted whenever you want to reclaim the space.

### Where do I report bugs?

Open an issue on the project's
[GitHub repository](https://github.com/ESMP-FUN/BetterAntiDupe/issues). Include
your server version, plugin version, the `storage.backend` you're using, and
the relevant console log lines (search for `BetterAntiDupe` or `[DUPE]`).

---

_Last updated for BetterAntiDupe 4.2.0 - Minecraft 1.21.x (Paper, Folia,
Spigot) and 26.x (Paper). One codebase, two downloads: the plain jar for
1.21.x, the `-mc26` jar for 26.x._
